# Does the kyo-compat external-binding change satisfy sage's needs?

**Verdict: SATISFIES-WITH-GAPS.** The delivered mechanism (external backend
coordinates + bundled conformance suite + docs) is the correct, sufficient
*tooling* for sage to declare, consume, and validate an externally-published
cats-effect (`ce`) binding, and it matches sage's exact build shape. It does
NOT, by itself, let sage build its `ce` cell on a post-RC5 kyo, because it ships
no `ce` binding artifact: sage must still author/vendor/publish that binding
(its source now lives only in kyo git history). That residual is a direct,
intended consequence of issue #1778 (cats-effect removed from kyo), not a defect
in this change.

---

## 1. What sage is, and its relationship to this work

**Sage is a from-scratch Redis & Valkey client for Scala 3, built on kyo-compat.**
Evidence:

- `sage/README.md:6-8`: "a **Redis & Valkey client for Scala 3** ... First-class
  ZIO, **Cats Effect**, Kyo, Ox, and Pekko artifacts, each with its ecosystem's
  native types and no wrapper visible."
- `sage/build.sbt:124-125`: the `sage-client` module is "**Runtime written once
  against kyo-compat, cross-published per backend.**"
- `sage/project/plugins.sbt:1`: `addSbtPlugin("io.getkyo" % "kyo-compat-plugin" % "1.0.0-RC5")`.
- `sage/build.sbt:153-154,187-188,202-203,233-234`: every product module
  (`client`, `integrationTests`, `examples`, `benchmarks`) is built with
  `.compatLibrary(KyoLib)(...)(scala3Next)` and
  `.compatLibrary(ZioLib, CeLib, OxLib, PekkoLib)(VirtualAxis.jvm)(scala3)`.
- Sage's own history nails the tie: PR **#28** "Scaffolding: sbt modules,
  **kyo-compat wiring**, CI", PR **#33/#5** "All four Backend artifacts", PR
  **#134** "Add a Pekko/Future backend", and PR **#157** "**Update
  kyo-compat-ce, kyo-compat-future, ... to 1.0.0-RC5**" (Scala Steward bumping the
  *published* `io.getkyo:kyo-compat-ce` artifact sage consumes today).

**Sage ships a first-class cats-effect backend as a published artifact.** The
tree carries `sage-client/ce/`, `integration-tests/ce/`, `benchmarks/ce/`,
`examples/ce/`; `sage-client/ce/src/main/scala/sage/backend/SageClient.scala`
exposes `type SageClient = Client[IO, String]` (cats-effect `IO`) and fs2
streams. The published coordinate is `com.github.ghostdogpr:sage-client-ce_3`.

**How sage gets `ce` today, and why this change matters.** `CeLib` is *never
defined* anywhere in sage (confirmed: no `val CeLib`/`CeLib =` in any `.sbt`/
`.scala`, including `project/`). It resolves from `CompatPlugin.autoImport` at
plugin `1.0.0-RC5`, where `ce` was still one of the built-in backends and
`io.getkyo:kyo-compat-ce` was published. In the **current** kyo tree the built-in
`ce` is gone: it is absent from `CompatPlugin.autoImport` (only `FutureLib`,
`KyoLib`, `ZioLib`, `OxLib`, `TwitterFutureLib` remain,
`CompatPlugin.scala:58-67`), absent from `CompatBackendAxis.builtinNames`
(`{future, kyo, zio, ox, twitter-future}`, `CompatLibrary.scala:316-317`), and
no `ce` path is tracked in `kyo-compat/`. So the moment sage upgrades past RC5,
`CeLib` vanishes from autoImport and `io.getkyo:kyo-compat-ce` stops being
published for that version. **This change is precisely the migration path sage
needs to keep its cats-effect backend alive.** Sage is the archetypal, and
apparently the motivating, consumer.

---

## 2. Requirement-by-requirement mapping

### R1 - Declare and consume an externally-published `ce` binding. MET.

Sage would replace the (now-absent) autoImport `CeLib` with:

```scala
val CeLib = CompatBackendAxis.external(
    name = "ce", idSuffix = "Ce", directorySuffix = "-ce",
    supportedPlatforms = Set("jvm"),
    organization = "<sage-or-3rd-party-org>", artifactName = "kyo-compat-ce", version = "<v>")
```

- The `external(...)` factory exists with exactly these seven parameters
  (`CompatLibrary.scala:337-361`) and is reachable in a `build.sbt` via the new
  `val CompatBackendAxis` autoImport term re-export (`CompatPlugin.scala:52-54`)
  — the same import sage already uses (`build.sbt:1`).
- The generated `ce` row pulls `organization %%% artifactName % version` through
  the **same one code path** the built-ins use (`CompatLibrary.scala:206-213`);
  only the org/name/version values differ. External and built-in resolution are
  not parallel implementations, which de-risks correctness.
- Directly validated by the `external-backend` scripted test, which is sage's
  scenario verbatim: an `external("ce","Ce","-ce",Set("jvm"),"io.github.example",
  "kyo-compat-ce","9.9.9-EXTERNAL")` in a matrix with built-in `KyoLib`, asserting
  the `ce` row pulls `io.github.example:kyo-compat-ce:9.9.9-EXTERNAL` and **not**
  `io.getkyo:kyo-compat-ce`, while `KyoLib` still pulls `io.getkyo:kyo-compat-kyo`
  (`external-backend/build.sbt:13-85`).
- `"ce"` is not in `builtinNames`, so the `external(...)` collision guard passes
  (`CompatLibrary.scala:346-351`).

**Identity preservation (important, MET):** the external axis lets sage keep
`idSuffix="Ce"` and `directorySuffix="-ce"`, so the generated project ids
(`clientCe`, `integrationTestsCe`, `benchmarksCe`, `examplesCe`) and published
module name (`sage-client-ce`) are byte-for-byte what sage's build already
depends on — its command aliases (`build.sbt:60-72`) and `publish/skip`
predicate (`build.sbt:134`) keep resolving unchanged. Sources stay at
`sage-client/ce/...` because the source root is `<base>/<name>/<platform>`
(`CompatLibrary.scala:142-146`, `name="ce"`).

### R2 - Platforms sage targets. MET.

Every sage `.compatLibrary(...)` call requests `VirtualAxis.jvm` only; the ce
cell builds on Scala LTS `3.3.8` (`build.sbt:4,154`). The external `ce` axis
needs `supportedPlatforms=Set("jvm")` and one JVM publish (`kyo-compat-ce_3`).
`%%%` resolves `_3` across all Scala 3.x, so a single artifact covers `3.3.8`.
No JS/Native obligation. The empty-intersection check passes for a JVM request
(`CompatLibrary.scala:108-117`).

### R3 - `kyo.compat.*` surface areas sage actually uses. MET (and narrow).

Sage's source names only two compat types: **`CIO`** (486 occurrences repo-wide,
320 in `sage-client`) and **`CStream`** (in one paging helper per backend). It
names *none* of `CFiber / CPromise / CChannel / CAtomic* / CLatch / CMeter /
CLocal / CChunk` (explicit per-token grep: all zero). All concurrency is
expressed through `CIO` combinators or dropped to the native runtime at the
per-backend lowering seam via `.lower`:

- `sage-client/ce/.../SageClient.scala:65,68,211,216-218`:
  `CStream.unfold(...).flatMap(...).lower : fs2.Stream[IO,A]`, `CIO.lift(io)`,
  and `c.lower : IO[A]` bridging `Client[CIO,String]` ⇄ `Client[IO,String]`.

Every symbol sage uses is in the documented surface and is exercised by the
bundled conformance suite (`CIO`, `CStream`, lift/lower). Because sage compiled
against RC5's built-in `ce`, whatever it uses is in that surface, and a binding
that passes conformance reproduces it. Surface coverage is not a risk for sage.

Note also that sage pins the backend runtime libs itself (`build.sbt:138-142`:
`cats-effect`, `fs2-core` on the `-ce` cell), so it does not even rely on the
compat binding to supply them transitively.

### R4 - Author/validate the binding against the conformance suite. MET (path exists), with a validation caveat (see G2).

`.compatConformance()` exists (`CompatPlugin.scala:127-139`, default scalatest
`3.2.20`) and wires, per generated row, the plugin-bundled suite into
`Test/sourceManaged` + scalatest + `-Xmax-inlines:1024`
(`CompatLibrary.scala:237-275`, `CompatConformance.scala`). For a JVM `ce` row it
extracts the 18 shared files + `CStreamTest` (streams-shared) +
`FromCompletionStageTest` (jvm-only) — validated by the `conformance` scripted
test (`conformance/build.sbt:62-110`). The README documents the exact authoring
harness sage would use: a local `kyo-compat-ce` crossProject +
`external(...)` + `.bindLocally(CeLib, kyoCompatCe.jvm)` + `.compatConformance()`
(`kyo-compat/README.md:698-731`). This is a complete, self-service path to prove
a vendored ce binding satisfies the same contract the built-ins pass.

### R5 - Publishing / cross-Scala / dependency needs. MET at the tooling level.

Single JVM `_3` publish covers sage's LTS ce cell. `compatKyoVersion` only
governs the *built-in* rows; the external `ce` row carries its own version
(`CompatLibrary.scala:210-211`), so sage's `compatKyoVersion := kyoVersion`
(`build.sbt:41`) does not constrain the ce coordinate. No cross-version friction.

---

## 3. Gaps & risks (severity-ranked)

### G1 - The change ships no `ce` binding artifact; sage must stand one up. HIGH (practical), by-design.

This is necessary-but-not-sufficient. To build `ce` on a post-RC5 kyo, sage must
obtain a published `kyo-compat-ce` for cats-effect. The change provides zero of
that artifact: the ce source exists only in kyo history (`ffdd279e1e^:kyo-compat/
bindings/ce/**`), and sage currently authors no binding (`grep 'package
kyo.compat'` in sage: none). Sage's realistic route is to **vendor** those ~10
files into a new module, publish it under `com.github.ghostdogpr:kyo-compat-ce`,
and consume it via `external(...)` (optionally `bindLocally` for its own
conformance harness). That is a real ownership + maintenance transfer (track
cats-effect/fs2, re-run conformance on kyo-surface changes), and it is the gating
work between "this change merged" and "sage's ce cell green again". Scope note:
this follows inevitably from #1778 removing Typelevel code from kyo; the change
does the maximum kyo can do without re-hosting cats-effect. What would close it:
nothing *in kyo* — someone (most likely ghostdogpr) publishing a maintained
`kyo-compat-ce`. Worth stating to the user because the kyo-side change alone does
not make sage build.

### G2 - Conformance consumability is not exercised end-to-end against a real non-Future binding in-repo. MEDIUM-LOW.

The `conformance` scripted test wires the suite but, by its own comment, does not
compile or run it ("The scripted sandbox has no real binding, so the suite is not
compiled here", `conformance/build.sbt:9-10`). The only real run
(`kyo-compat-futureJVM/test`, per `CATS_BINDING_ANALYSIS.md:248`) compiles the
*canonical* sources from `unmanagedSourceDirectories`, not the copy
*extracted-from-the-plugin-jar*. So the full path "extract from jar → compile →
run against a real binding" is covered only by a logical chain (extracted bytes
== bundled == canonical; canonical is green), never by one executed test. Sage's
own `ceConformance` harness would be the first place that path runs against a
real binding. The chain is sound (byte-identity is checked,
`conformance/build.sbt:93-110`), so risk is modest, but sage should expect to be
the integration proof, and a first-run packaging/extraction surprise is possible.
What would close it: one in-repo test that runs `.compatConformance()`-extracted
sources against the Future binding (extract + compile + run), not just wiring +
byte-identity.

### G3 - Conformance proves the portable surface, not the native-carrier identity sage's lowering seam needs. LOW.

Sage's `ce` code hard-requires `CIO[A] =:= cats.effect.IO[A]` and
`CStream[A]` lowering to `fs2.Stream[IO,A]`, so that `CIO.lift(io)` / `c.lower`
are zero-cost and `Client[CIO,String]` ⇄ `Client[IO,String]` interconvert
(`sage-client/ce/.../SageClient.scala:65,211,216-218`). The conformance suite is
generic over `kyo.compat.*` and cannot assert the native carrier type. An
independently-authored "conformant" ce binding that aliased a different carrier
(e.g. wrapped `IO`) would pass conformance yet fail to compile *in sage*. In
practice the natural ce binding (the removed one) aliases `IO`/`fs2.Stream[IO,_]`
exactly, and sage's own compile is the backstop, so this bites only a
from-scratch third-party binding, and only at sage's compile time. Note it so
whoever authors the binding knows carrier identity, not just conformance, is the
contract.

### G4 - `CStreamTest`'s three `pending` cases under-test a real ce binding. LOW.

Those three are unconditional `pending` (kyo `Stream` limitations kept as the
cross-binding contract, `kyo-compat/README.md:435-451`;
`CATS_BINDING_ANALYSIS.md:99`), so on ce they report `pending`, never exercising
whether fs2 handles take-after-effectful-map (once-per-element) or 10000-deep
flatMap. Sage's actual `CStream` use is a finite pagination `unfold + flatMap +
init` (`SageClient.scala:65`), which those cases do not model, so the gap does
not reach sage. Cosmetic for this consumer.

### G5 (opportunity, not a gap) - The new coordinate fields could retire sage's Pekko hack.

Sage's `PekkoLib = CompatBackendAxis("pekko","Pekko","-pekko",Set("jvm"))`
(4-arg apply, still source-compatible after the field additions) forces the
plugin to inject a nonexistent `io.getkyo:kyo-compat-pekko`, which sage strips
with `stripBogusPekkoCompatDep` (`build.sbt:28-30,270-272`). With the new fields
sage could instead point that axis at the real future artifact
(`artifactName = Some("kyo-compat-future")`), and the plugin would inject a
resolvable dep, deleting the strip hack. Incidental ergonomics win the change
enables; not required.

---

## 4. Confidence & unknowns

- **High confidence** that the mechanism is shaped for sage: the
  `external-backend` scripted test is sage's `ce` migration verbatim, external
  and built-in coordinate resolution share one code path, and the axis preserves
  the exact `idSuffix`/`directorySuffix`/module-id/source-root sage's build
  depends on. Sage's compat surface is narrow (`CIO` + `CStream` + lift/lower),
  fully inside the documented + conformance-tested surface.
- **Assumption (near-certain, unverified against the RC5 artifact):** RC5's
  built-in `ce` axis had `idSuffix="Ce"` / `directorySuffix="-ce"`. Inferred from
  sage's `sage-client/ce/` dir, `clientCe` ids, and `sage-client-ce` publish
  name; I could not fetch the RC5 plugin to read its axis literal directly. If it
  differed, sage would adjust the two suffixes in its own `external(...)` call —
  no change-side impact.
- **Not executed:** I did not run a build. The kyo worktree shares an sbt lock
  (per the brief) and sage's true validation is its own repo. Assessment is from
  reading the kyo diff, the plugin sources, both scripted tests, and the full
  sage tree + its GitHub issue/PR history.
- **Unknown / out of my visibility:** whether ghostdogpr intends to vendor+publish
  `kyo-compat-ce` (G1). No sage issue/PR yet references the removal or an external
  ce binding (searched all issues/PRs; the ce references are all RC5-era
  consumption of the built-in). That decision is the true determinant of whether
  sage's ce cell survives, and it sits outside this change.
