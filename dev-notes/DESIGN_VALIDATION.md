# Design-doc fact-check: EXTERNAL_CATS_INTEGRATION_DESIGN.md

**Verdict: ACCURATE-WITH-FIXES.** Every load-bearing factual and technical claim in
the document checks out against primary sources (repo tree at `90f79b86a4^`, current
`kyo-core`/`kyo-scheduler`/`kyo-compat-plugin` sources, the RC5 tag, and sage's public
repo). No claim is wrong. The fixes are polish: one incomplete build recipe (MINOR),
two nits. The doc is structurally and stylistically publication-ready (no em/en dashes,
no AI tells). The three code fixes in the 4th commit are correct and safe.

---

## Fact-check table

| # | Claim | Verdict | Primary source |
|---|-------|---------|----------------|
| 1 | PR #1779 / `90f79b86a4` removed exactly the three integration modules; benchmarks kept cats-effect; all three exist at `90f79b86a4^` | CONFIRMED | `git show 90f79b86a4 --name-status`: deletes `kyo-cats/**`, `kyo-compat/bindings/ce/**`, `kyo-scheduler-cats/**`. Current `build.sbt:32` `catsVersion="3.7.0"`, `:2936` cats-effect only under kyo-bench; no `kyo-cats`/`kyo-compat-ce`/`kyo-scheduler-cats` refs remain. All three trees present at `90f79b86a4^` (`git ls-tree`) |
| 2 | Integration A signatures match; bridge uses ONLY public kyo API (no `private[kyo]`) | CONFIRMED | `90f79b86a4^:kyo-cats/shared/src/main/scala/kyo/Cats.scala`. Every referenced symbol is public in current `kyo-core`: `Fiber.initUnscoped` (`Fiber.scala:165`), `Promise.Unsafe.init` (`Fiber.scala:635`), `Promise.Unsafe.safe` (`:658/:683`), `Promise.Unsafe.complete` (`:646/:671`), `Fiber.Unsafe.onComplete` (`:448`), `.onInterrupt` (`:449`), `.interrupt` (`:451`), `Sync.Unsafe.defer` (`Sync.scala:136`), `Sync.Unsafe.evalOrThrow` (`:163`). Only the internal `lower` helper is `private[kyo]`, and the bridge never calls it |
| 3 | Integration B: `ce` depends only on cats-effect+fs2 (no kyo dep), JVM+JS, Scala 3; `CIO` opaque = `cats.effect.IO`; built-in axis was `("ce","Ce","-ce",...)` | CONFIRMED | Old `build.sbt:2278-2296`: `kyo-compat-ce = crossProject(JSPlatform, JVMPlatform)`, no `.dependsOn`, `cats-effect % catsVersion` + `fs2-core % "3.13.0"`, `crossScalaVersions := List(scala3LTSVersion)`. `CIO.scala:16` `opaque type CIO[+A] = IO[A]`; `lift`/`lower` identity (`:21,:78`). Built-in axis literal `CompatBackendAxis("ce","Ce","-ce",Set("jvm","js","native"))` (removal diff of `CompatPlugin.scala`). See note [N1] on the axis-vs-binding platform mismatch |
| 4 | Integration C: `kyo-scheduler-cats` depends on kyo-scheduler + cats-effect, JVM only, Scala 2.13 AND 3; runtime/app as described; impl is `Scheduler.get.asExecutionContext`; that call is public | CONFIRMED | Old `build.sbt:574-585`: `crossProject(JVMPlatform)`, `.dependsOn(kyo-scheduler)`, `cats-effect`, `crossScalaVersions := List(scala3LTSVersion, scala213Version)` (= 3.3.8 + 2.13.18). `KyoSchedulerIORuntime`/`KyoSchedulerIOApp` match doc. `Scheduler.get.asExecutionContext` public in current `kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Scheduler.scala:262` + `val get` `:574` |
| 5 | Plugin change: `external(name,idSuffix,directorySuffix,supportedPlatforms,organization,artifactName,version)`; `.compatConformance()`; shared coordinate path; snippets API-accurate (`.get`, `bindLocally`) | CONFIRMED | `CompatLibrary.scala:337-361` (7 params, exact order); collision guard `:346-351`. `compatConformance(scalatestVersion="3.2.20")` `CompatPlugin.scala:138-141`. One resolution path `CompatLibrary.scala:206-213`. `get(b): Option[CompatBackendProjects]` `:191-195` (see [F1]); `bindLocally(b, local)` `:116-119`. `conformanceSettings` adds scalatest + `-Xmax-inlines:1024` + extract into `Test/sourceManaged` (`:265-275`) |
| 6 | Versions: cats-effect `3.7.0`, fs2 `3.13.0` | CONFIRMED | Old `build.sbt:16` `catsVersion="3.7.0"`; `:2289`/`:2814` `fs2-core % "3.13.0"` |
| 7 | sage is a live consumer pinned to the release where `ce` was built in | CONFIRMED & DEFENSIBLE | sage is a **public** repo (github.com/ghostdogpr/sage), "Redis & Valkey client for Scala 3" with first-class Cats Effect + Kyo artifacts; `project/plugins.sbt` pins `io.getkyo % kyo-compat-plugin % 1.0.0-RC5`. At tag `v1.0.0-RC5`, `CompatPlugin.scala:59-60` had built-in `CeLib = CompatBackendAxis("ce","Ce","-ce",Set("jvm","js","native"))`, so `ce` was built in and its suffixes were exactly `Ce`/`-ce`. This closes SAGE_VALIDATION's one open assumption |

---

## Findings

**[F1] NIT — `myLib.get(CeLib)` returns an `Option`, not a row.**
Doc line 210: "External backends have no named accessor, so reach their rows with
`myLib.get(CeLib)`." `get(b: CompatBackendAxis): Option[CompatBackendProjects]`
(`CompatPlugin.scala:191`) returns an `Option`; reaching an actual `Project` row is
`myLib.get(CeLib).get.jvm` (or a match). `.get` is the right accessor, but the phrasing
reads as if it yields the row directly. Suggest: "reach their rows via the safe lookup
`myLib.get(CeLib)`, e.g. `myLib.get(CeLib).get.jvm`."

**[F2] MINOR — vendored `ce` binding recipe omits `-Xmax-inlines` (and the Scala pin).**
Doc lines 216-221 define the local `kyoCompatCe` crossProject with only the cats-effect
and fs2 dependencies. The original `kyo-compat-ce` module set `scalacOptions +=
"-Xmax-inlines:1024"` and `crossScalaVersions := List(scala3LTSVersion)` at module level
(old `build.sbt:2286-2289`); the `CIO`/`CStream` sources are heavily inlined. The doc's
`.compatConformance()` adds `-Xmax-inlines:1024` only to the **conformance rows' Test**
scope (`CompatLibrary.scala:268`), not to the standalone binding's own Compile scope. A
maintainer copy-pasting the vendored recipe verbatim may hit a max-inlines error compiling
the binding itself. Fix: add `scalacOptions += "-Xmax-inlines:1024"` (and pin the Scala 3
LTS) to the `kyoCompatCe` settings block. (Unverified by compile: `sbt` was out of bounds
for this pass; flagged from the original module's explicit setting.)

**[F3] NIT — "Each module kept a README at that commit" is loose for B.**
Doc line 272. `kyo-cats/README.md` and `kyo-scheduler-cats/README.md` exist at
`90f79b86a4^`, but the `ce` binding directory has no README of its own; the README lives
at the `kyo-compat/` module root. Reword to "kyo-cats and kyo-scheduler-cats each kept a
README; the ce binding is documented in the kyo-compat README."

**[N1] OBSERVATION (not a defect; a doc strength) — axis-vs-binding platform mismatch, handled correctly.**
The built-in `ce` axis literal declared `supportedPlatforms = Set("jvm","js","native")`,
but the actual `kyo-compat-ce` crossProject built only JVM+JS (no `js/` source dir, an
empty `native/.gitkeep`, `crossProject(JSPlatform, JVMPlatform)`). The doc's external-axis
example correctly uses `Set("jvm","js")` (lines 200-201), matching the binding reality and
avoiding a broken `Ce/Native` row that would try to resolve a nonexistent native artifact.
The doc never claims to preserve the built-in axis's `supportedPlatforms` (only its
`idSuffix`/`directorySuffix`), so this is right. Surfaced only so the team knows the
discrepancy in the removed code is understood and deliberately not carried forward.

**[N2] OBSERVATION — `run` signature cosmetic difference.** Doc line 60 writes
`(using Frame)`; source is `(using frame: Frame)`. Semantically identical; no action.

---

## Quality assessment (against Google engineering-design-doc guidance)

**PASS.** The document maps cleanly onto the standard template and reads as direct
engineering prose.

- **Context and scope / Goals / Non-goals:** all present and sharp. The problem
  (a cross-published cats-effect artifact stops resolving past the removal) is concrete;
  non-goals correctly fence off re-hosting, `io.getkyo` publishing, and governance.
- **Detailed design:** "Building blocks the kyo repo provides" then per-integration recipes
  is a sound decomposition; the vendored/standalone split is a real design axis, not filler.
- **Alternatives considered:** four, each with a stated reason for rejection (opt-in flag,
  republish under io.getkyo, umbrella-vs-per-integration, vendoring-without-plugin). This
  is the section most design docs skimp; here it is substantive.
- **Cross-cutting concerns:** versioning, testing, and the maintenance boundary are exactly
  the right three, and the carrier-identity caveat (conformance proves the portable surface,
  not that `CIO =:= cats.effect.IO`) is a genuine, correctly-stated subtlety.
- **Open questions:** framed as adopter decisions, appropriately deferred.
- **Style:** no em/en dashes (`grep -P "[\x{2014}\x{2013}]"` clean), none of the banned
  marketing adjectives or filler openers. No waffle or hedging passages found. Sentences are
  load-bearing.

Minor structural note: the carrier-identity point is stated three times (Detailed design B,
Worked example preamble, Cross-cutting/Testing). Once in Detailed design plus the
cross-cutting recap would be tighter, but the repetition is defensible for a load-bearing
gotcha and is not a blocker.

---

## Code-fix check (4th commit `22a89c1c69`)

All three fixes are correct and safe; no over-reach.

- **(a) Em-dashes removed from new comments — CORRECT & COMPLETE.** The commit changed the
  three `CompatBackendAxis` field comments from `—` to `;` and two sbt-test headers from
  `—` to `:` (grammatical substitutions sanctioned by the root rule "use commas, colons,
  parentheses"). Verified the branch introduced **zero** lines containing em/en dashes
  (`git diff origin/main..HEAD -- kyo-compat/plugin/src/main/scala/` has no added dash
  lines; CompatLibrary em-dash count dropped 9→6). The em-dashes still in the plugin
  sources are all pre-existing on `origin/main` and outside this change's scope.
- **(b) `external` rejects a built-in-colliding name — CORRECT & SAFE.**
  `require(!builtinNames.contains(name), ...)` with `builtinNames = {future,kyo,zio,ox,
  twitter-future}` (`CompatLibrary.scala:316-317,346-351`). Correctly rejects the five
  current built-ins while **allowing** `name="ce"` (ce is no longer built in) — exactly
  what sage's migration needs. Guard fires at axis construction; no false positives.
- **(c) `CompatConformance.extract` relpath-collision guard — CORRECT & SAFE.** Since the
  destination drops the scope prefix (`dest = outDir / rel`), two in-scope buckets with the
  same relpath would clobber. New code groups in-scope entries by relpath and `sys.error`s
  on any duplicate before writing (`CompatConformance.scala:50-61`). The `collect`→
  `filter`+`map` refactor preserves the non-collision behavior. No collision exists today
  (shared + platform buckets are disjoint); the guard is a forward safety check as claimed.

---

## Constraints honored
Static reading + git only; `sbt` not run in this worktree (shared lock). No file edited
except this report. The one compile-dependent claim ([F2]) is flagged as unverified.
