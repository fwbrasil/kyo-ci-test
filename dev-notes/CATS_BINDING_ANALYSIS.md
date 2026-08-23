# Allowing cats-effect bindings outside the repo — validated scope

Issue #1778 removes all Typelevel integrations from kyo (maintainer banned from
Typelevel repos). PR #1779 executed it. cats-effect may be maintained **outside**
the kyo repo. This doc scopes the **in-repo-only** work that lets an external
cats-effect binding be authored, published, validated, and consumed with the
existing kyo-compat tooling. Every claim below is checked against the tree at HEAD
(`34b1255fd0`) or against the git history of the removal; evidence is cited.

## Locked scope

- **In (primary):** kyo-compat plugin support for externally-published backend
  coordinates, so a cats-effect `ce` binding can be built, published, and consumed
  entirely from its own repo.
- **In (secondary):** make the cross-binding conformance suite
  (`kyo-compat/test/**`, `kyo-compat/test-streams/**`) consumable by an external
  repo; document in the kyo-compat README how to **use** and how to **set up** an
  external binding; fix the two stale `ce` references left by the removal.
- **Out, permanently:** the `kyo-cats` `Cats.get`/`Cats.run` bridge (removed in
  `b017e0414f`, stays removed); any cats-effect source in the kyo tree.

## Background: what kyo-compat is (and what a "binding" is)

`kyo-compat` lets a library be written once against the `kyo.compat.*` surface
(`CIO`, `CFiber`, `CPromise`, `CChannel`, `CStream`, atomics, latches, meters,
locals) and shipped to multiple runtimes. A **binding** is one runtime's
implementation of that whole surface. Bindings ship today for kyo, zio, future,
ox, twitter-future. `CIO[+A]` is an **opaque type alias** whose definition is
per-binding (`A < (Abort[Throwable] & Async)` on kyo, `zio.ZIO[Any,Throwable,A]`
on zio, etc. — kyo-compat/README.md:44-52). An sbt plugin (`kyo-compat-plugin`)
generates one project-matrix row per (backend × platform × scalaVersion) from a
single `.compatLibrary(...)` call.

A cats-effect (`ce`) binding was one of six; it was removed in `ffdd279e1e`
("remove the cats-effect (ce) backend binding"). Its source is preserved at
`ffdd279e1e^:kyo-compat/bindings/ce/**`.

## Validated finding 1 — the ce binding is fully self-contained

Every ce source file (`ffdd279e1e^:kyo-compat/bindings/ce/**`: `CIO.scala`,
`CStream.scala`, all `C*.scala`, `FromCompletionStage.scala`) is `package
kyo.compat` and imports **only** `cats.effect.*` / `cats.syntax.*` / `fs2` /
Scala stdlib. A grep for `import kyo.` (excluding `kyo.compat`) across the whole
ce tree returns nothing. Its build deps were exactly `org.typelevel:cats-effect`
and `co.fs2:fs2-core` (`ffdd279e1e^:build.sbt`, `kyo-compat-ce` entry).

Consequence: the binding **source** needs nothing from the kyo tree and can live
wholesale in an external repo. No cats-effect code returns to kyo.

## Validated finding 2 — one line is the only in-repo blocker

The plugin injects each generated row's dependency on its backend artifact with a
**hardcoded** org, artifact-name pattern, and single shared version
(`kyo-compat/plugin/src/main/scala/io/getkyo/compat/CompatLibrary.scala:196-203`):

```scala
libraryDependencies ++= {
    val isBound = metaOf(matrixId).exists(_.bindings.contains(backend.name))
    if (isBound) Seq.empty
    else Seq(
        "io.getkyo" %%% s"kyo-compat-${backend.name}" %
            CompatPlugin.autoImport.compatKyoVersion.value
    )
}
```

`CompatBackendAxis` (CompatLibrary.scala:249-261) carries no coordinate data —
only `name / idSuffix / directorySuffix / supportedPlatforms`. A user can already
construct one (`CompatBackendAxis(...)` companion `apply` is public; the type is
re-exported in `autoImport`, CompatPlugin.scala:48) and pass it to
`.compatLibrary(...)`, but the generated row resolves to
`io.getkyo:kyo-compat-<name>:<compatKyoVersion>` — an artifact an external
maintainer cannot publish (no rights to `io.getkyo`, own version cadence).

`bindLocally` (CompatPlugin.scala:111) only rewires a backend to a **local**
`ProjectReference`; there is no path to an externally-published artifact.

**Validated that this is the *only* closed-world coordinate assumption.** A grep
of the plugin for backend enumeration / `io.getkyo` shows every other mechanism is
already backend-agnostic:
- empty-intersection check (CompatLibrary.scala:98-107) — generic over `meta.backends`.
- `aggregate` (CompatPlugin.scala:147) — uses `m.projectRefs`, generic.
- `.get(axis)` (CompatPlugin.scala:170) — generic; the only lookups tied to
  specific backends are the `.future/.kyo/.zio/.ox/.twitterFuture` convenience
  accessors (CompatPlugin.scala:155-167), which an external backend simply does
  not use (it uses `.get(axis)`).
- `bindLocally`, `bindAllLocally`, per-platform settings — all generic.

So the enabler is small and localized: enrich the axis, read it at line 196-203.

## Validated finding 3 — the conformance suite must ship as source

The cross-binding suite is **20 scalatest files**: 18 in
`kyo-compat/test/shared/src/test/scala`, 1 in `kyo-compat/test/jvm/...`, 1 in
`kyo-compat/test-streams/shared/...`. Base class `CompatTest extends
AsyncFreeSpec, NonImplicitAssertions`
(`kyo-compat/test/shared/.../CompatTest.scala`). Imports across the suite are only
`kyo.compat.*`, scalatest, and stdlib. Needs `-Xmax-inlines:1024`. `CStreamTest`
carries 3 unconditional `pending` tests (kyo-binding limitations, README:435-451).

Because `CIO` is an **opaque alias resolved per binding at compile time**, these
tests can only be compiled *together with a specific binding on the classpath*.
They cannot be pre-compiled and published as classes; they must be distributed as
**sources** and compiled in the consumer's build against the consumer's binding.

In-repo they are wired as `Test / unmanagedSourceDirectories` pointing at absolute
`kyo-compat/test/...` paths, into each binding's crossProject
(build.sbt:2342-2356 for future, 2371-2387 for kyo, etc.). The `kyo-compat-tests`
project (build.sbt:2495) and `kyo-schema-tests` (build.sbt:790) are both
`publish / skip := true` in-build projects. **There is no existing mechanism for
an external repo to consume these test sources.** That mechanism is the secondary
workstream.

## Workstream 1 — plugin external-coordinate enabler

Give `CompatBackendAxis` optional coordinate overrides; defaults keep the five
built-ins byte-identical.

```scala
final case class CompatBackendAxis(
    name: String,
    idSuffix: String,
    directorySuffix: String,
    supportedPlatforms: Set[String],
    organization: String = "io.getkyo",
    artifactName: Option[String] = None,   // default => s"kyo-compat-$name"
    version: Option[String] = None         // default => compatKyoVersion.value
) extends VirtualAxis.WeakAxis { /* equality stays by `name` */ }
```

`addRows` reads `backend.organization`, `backend.artifactName.getOrElse(s"kyo-compat-${backend.name}")`,
`backend.version.getOrElse(compatKyoVersion.value)`. (`organization %%% artifact %
version` type-checks: `%%%` is the `String` extension already imported at
CompatLibrary.scala:3.) A `CompatBackendAxis.external(...)` factory can make intent
clearer than default args — a naming decision, not a semantic one.

Validation: a new scripted test `external-backend` (mirroring `bind-locally`'s
`ext.get(row / libraryDependencies)` assertions) declares an axis with a custom
org/artifact/version, then asserts the generated row's `libraryDependencies`
contains `<custom-org>:<custom-artifact>:<custom-version>` and **not**
`io.getkyo:kyo-compat-<name>`; plus an assertion that a built-in axis in the same
build still resolves to `io.getkyo:kyo-compat-<name>:<compatKyoVersion>`.

Notes surfaced by validation:
- Equality is by `name` (CompatLibrary.scala:256), and `.compatLibrary` dedups via
  `.distinct` (CompatPlugin.scala:80): an external author must pick a `name` not
  colliding with a built-in. Document it.
- Adding fields to the public case class changes its constructor/`apply`/`copy`
  signature (source-compatible via defaults, **binary-incompatible**). The plugin
  is a build-time dependency (always recompiled), so this is acceptable, but if
  `kyo-compat-plugin` is under MiMa it needs an exclusion. **Checkpoint.**

## Workstream 2 — conformance suite consumable by an external repo

The suite must reach the external build as **sources** compiled against the
external binding, then run under scalatest. Three designs; correctness/maintenance
differ.

- **A (recommended) — bundle the suite in the plugin jar + a `.compatConformance`
  helper.** The plugin is already the artifact every consumer depends on. Its build
  copies `kyo-compat/test/**` + `test-streams/**` into `Compile/resources` (via
  `resourceGenerators`); a new `.compatConformance` matrix helper, per generated
  Test scope, extracts the platform-appropriate `.scala` resources into `Test /
  sourceManaged`, adds `"org.scalatest" %%% "scalatest"` and `-Xmax-inlines:1024`.
  Self-service, versioned with the plugin (no separate version to sync, no drift).
  Most machinery. Feasibility validated: sbt plugins can carry and classloader-read
  resources (the plugin already reads its own `Implementation-Version`), and
  extract-to-`sourceManaged` is a standard task pattern.

- **B — publish a separate `kyo-compat-testkit` sources artifact + the same helper.**
  Cleaner separation (test sources out of the plugin jar) at the cost of a new
  published module versioned in lockstep with the bindings.

- **C — document vendoring.** External repo copies the 20 files and wires them as
  `unmanagedSourceDirectories`. Near-zero in-repo machinery; drifts from upstream.

Recommendation: **A**. It is the only self-service option with no version-sync and
no drift, matching "the external binding runs the *same* conformance suite." B is
the fallback if bundling test sources into a plugin jar is judged inappropriate.
This is the one architecture decision that needs the user's ruling before build.

Note: the 3 `pending` CStream tests are kyo-binding limitations; on a ce binding
they remain `pending` (not failures), same as every non-kyo binding today.

## Workstream 3 — README (use + setup) and stale-ref cleanup

kyo-compat/README.md, "How to publish a kyo-compat library" section, gains:

1. **Using an external binding** (consumer side): declare the external
   `CompatBackendAxis` with its coordinates (or a provided axis val the binding
   ships), pass it to `.compatLibrary(...)`, pick it at deploy time. Include the
   `.get(axis)` accessor note (external backends have no named accessor).
2. **Setting up an external binding** (author side): implement the full
   `kyo.compat.*` surface for the runtime in `package kyo.compat`; depend only on
   that runtime; cross-publish artifacts with the platform suffixes `%%%` expects
   (`_sjs1`, `_native0.5`); run the conformance suite (Workstream 2) to prove
   surface conformance; choose a unique backend `name`.

Stale-ref cleanup left by `ffdd279e1e`:
- kyo-compat/README.md:438 still lists `ce` among live bindings in the streams
  "known divergences" note — reword (the divergence is the kyo binding's, framed
  against "the other bindings", not an enumerated `ce`).
- kyo-compat/test/shared/.../FiberTest.scala:147 comment mentions "CE's IORuntime
  error reporter" — generalize the comment (it is a cross-binding test).

## Cross-cutting checkpoints (validated as real, must be handled)

- **MiMa** on `kyo-compat-plugin` for the `CompatBackendAxis` field additions.
- **`%%%` platform suffixes**: external binding must be cross-published for every
  platform its axis claims in `supportedPlatforms`; document.
- **scalatest version** the conformance helper injects must match the suite's
  expectations (`scalaTestVersion` in build.sbt).
- **doctest**: kyo-compat README is doctest-verified via `kyo-compat-kyo`
  (build.sbt:2389-2392); new fenced snippets must carry correct doctest directives
  (`doctest:expect=skipped` for build.sbt snippets, as the existing publish section
  does).

## Phased plan (proposed)

1. **Enabler** — axis coordinate fields + `addRows` read + `external-backend`
   scripted test. Self-contained; validates finding 2. Commit.
2. **Conformance consumability** — per the design the user picks (A/B/C). Includes
   a scripted test that compiles+runs the bundled suite against the Future binding
   in a scripted build. Commit.
3. **README + stale refs** — use/setup docs + the two cleanups; `sbt
   kyo-compatKyoJVM/doctest` green. Commit.

Each phase compiles, its tests pass (`sbt kyo-compat-plugin/scripted`,
`kyo-compatKyoJVM/doctest`), before the next.

## Progress — all three phases DONE, validated

- **Phase 1 (enabler) — committed `35c103a34f`.**
  `CompatBackendAxis` gained `organization` / `artifactName` / `version`
  (defaults preserve the five built-ins); `addRows` reads them;
  `CompatBackendAxis.external(...)` + companion re-export in `autoImport`.
  Validation: new `external-backend` scripted test (external axis resolves to
  `io.github.example:kyo-compat-ce:9.9.9-EXTERNAL`, built-in Kyo still
  `io.getkyo:kyo-compat-kyo:<compatKyoVersion>`); no regression in `bind-locally`.

- **Phase 2 (conformance consumability, Option A) — committed `be018544c7`.**
  Plugin `resourceGenerators` bundles `kyo-compat/test` + `test-streams` into the
  plugin jar as resources + `INDEX`; `CompatConformance` extracts per platform;
  `.compatConformance(scalatestVersion = "3.2.20")` wires the suite into each row's
  Test scope (sources + scalatest + `-Xmax-inlines`). Validation: scripted
  `conformance` (20 sources incl. the jvm-only bucket, scalatest + `-Xmax-inlines`
  wiring, byte-identity of an extracted file vs its bundled resource); real-binding
  run `kyo-compat-futureJVM/test` green (20 suites, 346 passed, 0 failed, 3 pending).

- **Phase 3 (README + stale refs) — committed `52f224cc75`.**
  New "External bindings" README section (using + setting up); fixed the streams
  "known divergences" note and the `FiberTest` comment that still named `ce`/`CE`.
  Validation: `kyo-compat-kyoJVM/doctest` green (total=29, failures=0, warnings=0).

- **Cross-cutting checkpoints — cleared.** Full `kyo-compat-plugin/scripted` suite
  green after all phases (141s, exit 0). Plugin `mimaPreviousArtifacts = Set()`, so
  no binary-compat gate applies to the case-class field additions.

Net effect: an external repo can implement a cats-effect binding against the
`kyo.compat.*` surface, publish it under its own coordinates, declare it with
`CompatBackendAxis.external(...)`, consume it via `.compatLibrary(...)`, and validate
it against the same conformance suite the built-in bindings pass via
`.compatConformance()`. No cats-effect code lives in the kyo tree.

## Phase 2 detailed design (Option A) — needs confirm before build

Distribution chosen: **A**, bundle the conformance suite in the plugin jar +
a `.compatConformance` helper.

Suite inventory (canonical, copied verbatim): 18 files in
`kyo-compat/test/shared`, 1 in `kyo-compat/test/jvm`
(`FromCompletionStageTest.scala`), 1 in `kyo-compat/test-streams/shared`
(`CStreamTest.scala`). Depends on scalatest `3.2.20` and `-Xmax-inlines:1024`.

Mechanism:
1. **Bundle as plugin resources (byte-identical by construction).** The
   `kyo-compat-plugin` build gains a `Compile / resourceGenerators` that copies
   the canonical sources into managed resources under `kyo-compat-testkit/`
   (`test/shared/**`, `test/jvm/**`, `test-streams/shared/**`) and writes an
   `INDEX` (relative path + scope tag per entry). Copying verbatim means the
   bundle IS the canonical suite.
2. **Extraction (`CompatConformance` object in the plugin).** Reads `INDEX` from
   the plugin classpath, filters by scope (always `shared`; plus the row's
   platform bucket), writes each source to `(Test / sourceManaged)/kyo-compat-testkit/...`,
   returns `Seq[File]`.
3. **`.compatConformance(scalatestVersion)` helper** on `CompatLibraryOps`: sets a
   flag + scalatest version in `Meta`. The existing per-row `process` closure
   (already re-reads `Meta` at materialization for bindings/platform-extras) then
   appends, when set: `Test / sourceGenerators += <extract for shared + this
   platform>`, `libraryDependencies += "org.scalatest" %%% "scalatest" %
   <ver> % Test`, `Test / scalacOptions += "-Xmax-inlines:1024"`. Platform is in
   scope in `process`, so the right bucket is chosen. Default `scalatestVersion`
   via a new `compatConformanceScalatestVersion` setting (default `3.2.20`).

Validation chain (honest about the scripted constraint that there is no real
binding in that env, only empty resolution stubs):
- **(a) Extraction + wiring — scripted `conformance` (runnable).** Enable
  `.compatConformance` on a Future+JVM matrix; assert the expected `.scala`
  files land in `sourceManaged` (18 shared + the jvm-only
  `FromCompletionStageTest`), scalatest is on `Test` `libraryDependencies`,
  `-Xmax-inlines:1024` is set. Also assert one extracted file is byte-identical
  to its bundled resource.
- **(b) Byte-identity — by construction** (resourceGenerator copies canonical
  verbatim; the scripted content check in (a) confirms extraction is faithful).
- **(c) Suite green against a REAL binding — main build.** Run an in-repo binding
  conformance run (`kyo-compat-tests/test`, i.e. the Future binding on JVM) to
  confirm the canonical sources compile and pass against a real binding.
- **Gap surfaced:** no single scripted test compiles the *extracted* suite
  against a *real* binding, because the scripted env has no real binding. The
  chain closes it: extracted == canonical (a,b), canonical is green against a
  real binding (c), extraction+wiring is correct (a).

Confirm this mechanism + validation chain, then I build Phase 2.
