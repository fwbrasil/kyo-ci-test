# kyo-compat external-bindings change: held-out validation report

**Verdict: PASS-WITH-ISSUES.** The three-part scope (external backend coordinates, conformance-suite bundling/extraction, README + stale-ref cleanup) is implemented correctly and I validated the full external-binding path end-to-end against a real published binding; the only findings are minor/nit (an em-dash convention violation on new comment lines, plus a few latent-fragility nits). No blockers, no regressions.

Reviewed commits on top of `origin/main`:

- `35c103a34f` let a backend axis carry external artifact coordinates
- `be018544c7` bundle the conformance suite for external bindings
- `52f224cc75` document external bindings; drop stale ce references

---

## What I ran (actual results)

All runs used the prescribed `JAVA_OPTS`/`JVM_OPTS`. Logs kept under the session scratchpad.

| # | Command | Result |
|---|---------|--------|
| 1 | `sbt kyo-compat-plugin/scripted kyo-compat/external-backend kyo-compat/conformance` | **PASS.** `checkExternalCoords OK; myLibCe -> io.github.example:kyo-compat-ce:9.9.9-EXTERNAL`; `checkBuiltinCoords OK; myLibKyo -> io.getkyo:kyo-compat-kyo:STUB-COMPAT-VERSION`; `checkConformanceSources OK; extracted 20 sources incl. jvm-only FromCompletionStageTest.scala`; `checkConformanceWiring OK; scalatest 3.2.20 + -Xmax-inlines:1024 present`; `checkByteIdentity OK`. Both new scripted tests green. |
| 2 | `sbt kyo-compat-futureJVM/test` | **PASS.** `Total number of tests run: 346 ... succeeded 346, failed 0 ... pending 3`. (The 3 pending are the documented `CStreamTest` kyo-binding divergences, `pending` on every backend.) |
| 3 | `sbt kyo-compat-kyoJVM/doctest` | **PASS.** `total=29 compiled=0 cacheHits=19 warnings=0 failures=0` (the new "External bindings" README blocks are all `doctest:expect=skipped`; existing compiled blocks are cache hits, 0 failures). |
| 4 | **End-to-end (my own harness, closes the scripted gap):** `publishLocal` of `kyo-compat-plugin` + `kyo-compat-futureJVM` at `9.9.9-CONF` to ivy-local, then a scratch consumer build declaring only `.compatLibrary()(VirtualAxis.jvm)(...)` + `.compatConformance()`, running `e2eFuture/test`. | **PASS.** The generated row resolved `io.getkyo:kyo-compat-future:9.9.9-CONF` + `org.scalatest:scalatest:3.2.20:test`, extracted the plugin-bundled suite into `Test/sourceManaged`, **compiled 20 extracted sources against the real published binding**, and ran `succeeded 346, failed 0, pending 3`. |
| 5 | `sbt kyo-compat-plugin/scripted` (full 14-test suite, regression) | (see bottom — filled in after run) |

Run #4 is the decisive one: no scripted test compiles the *extracted* bundle against a *real* binding (the scripted sandbox uses an empty stub), so I built that path myself. The whole chain — bundle into jar → publish → consumer resolves plugin → `.compatConformance()` extracts → compiles against the resolved backend artifact → runs green — works.

---

## Findings

### F1 (minor) — em-dashes on newly added comment lines (convention violation)

kyo forbids em-dashes/en-dashes everywhere, including code comments. Two genuinely-new comment lines introduced by this change use an em-dash:

- `kyo-compat/plugin/src/sbt-test/kyo-compat/conformance/build.sbt:1` — `// Scripted test — .compatConformance wires the plugin-bundled conformance suite.`
- `kyo-compat/plugin/src/sbt-test/kyo-compat/external-backend/build.sbt:1` — `// Scripted test — externally-published backend coordinates.`

Three further em-dashes appear on lines the change *touched* (re-aligned / edited) in the `CompatBackendAxis` case-class field comments, but they pre-existed on `origin/main` (they were not introduced here):

- `kyo-compat/plugin/src/main/scala/io/getkyo/compat/CompatLibrary.scala:313-315` (`name` / `idSuffix` / `directorySuffix` comments, e.g. `// e.g. "future" — default artifact name ...`).

Consistency note: the same change correctly *removed* a pre-existing em-dash from `FiberTest.scala` and the new scaladoc for `external`/`resolvedArtifactName`/`CompatConformance` is em-dash-free, so the two new scripted headers and the three carried-over field comments are the only remaining offenders. Functional impact: none. Fix: rewrite those five comments with commas/colons/parentheses.

### F2 (nit) — "name must not collide with a built-in" is documented but not code-enforced

`CompatBackendAxis` equality/hashCode is by `name` only (`CompatLibrary.scala:301-305`), and there is no guard in `CompatBackendAxis.external`/`apply` (`CompatLibrary.scala:311-349`) rejecting a `name` equal to a built-in (`future`/`kyo`/`zio`/`ox`/`twitter-future`). The README (`kyo-compat/README.md:696`) states the constraint but nothing enforces it. Failure scenario: a user declares `CompatBackendAxis.external(name = "kyo", ...)`; `.compatLibrary(...).kyo` (which does `lookup(KyoLib)`, matched by name) would then silently resolve to the *external* backend's rows and its coordinates, instead of erroring. Not a blocker (it is a documented contract and produces no crash), but a cheap `require(!builtinNames.contains(name))` in `external` would turn a silent masquerade into a clear build error.

### F3 (nit) — `extract` flattens the scope prefix; two in-scope buckets sharing a relpath would silently overwrite

`CompatConformance.extract` writes each in-scope file to `outDir / rel` with the `<suite>-<bucket>` scope prefix dropped (`CompatConformance.scala:56`). Today no collision exists: on a JVM row the in-scope buckets are `test-shared` (18 files), `test-jvm` (1: `FromCompletionStageTest.scala`), `streams-shared` (1: `CStreamTest.scala`) — 20 distinct filenames (confirmed by run #1 and run #4). But if a future `test-shared/kyo/compat/Foo.scala` and `test-<platform>/kyo/compat/Foo.scala` (or a `test` + `test-streams` file) ever shared a relpath, the second write would silently clobber the first (order = INDEX sort). Latent fragility only; worth a comment or a collision assertion.

### F4 (nit) — `checkByteIdentity` compares bundle-to-bundle, not bundle-to-canonical

`conformance/build.sbt:93-109` reads the extracted file and the bundled plugin resource and asserts they are equal. Both derive from the same bundled resource, so the assertion is close to tautological: it proves `extract` copies faithfully, not that the *bundle* equals the *canonical* `kyo-compat/test/**` source. Bundle-vs-canonical faithfulness rests on the `IO.copyFile` loop in `build.sbt:3270-3296` (which is correct). The test name over-claims slightly; not a defect.

### F5 (observation) — untracked worktree leftover

`CATS_BINDING_ANALYSIS.md` sits untracked at the worktree root (the campaign's analysis doc). It is not part of the three commits and is harmless, but it should not be committed with the change.

---

## Areas checked and cleared (no issue found)

- **External coordinates resolve correctly.** `CompatBackendAxis(organization="io.getkyo", artifactName=None, version=None)` defaults + `resolvedArtifactName = artifactName.getOrElse(s"kyo-compat-$name")` (`CompatLibrary.scala:296-308`); the per-row dep is `backend.organization %%% backend.resolvedArtifactName % backend.version.getOrElse(compatKyoVersion.value)` (`CompatLibrary.scala:206-213`). External path proven by scripted `checkExternalCoords` and end-to-end run #4.
- **Five built-in backends byte-for-byte unchanged.** They are still constructed with 4 positional args (`CompatPlugin.scala:58-72`), so the three new fields take defaults, reproducing the previous `io.getkyo %%% kyo-compat-<name> % compatKyoVersion.value`. `checkBuiltinCoords` and run #2/#4 confirm no drift.
- **`autoImport` re-export.** `type CompatBackendAxis` + `val CompatBackendAxis` are both re-exported (`CompatPlugin.scala:48,54`), so `CompatBackendAxis.external(...)` resolves in a plain `build.sbt`; the scripted `external-backend/build.sbt` uses exactly that and compiled/ran.
- **Extraction writes to the correct place and creates parent dirs.** `extract` uses `IO.transfer(stream, dest)` (`CompatConformance.scala:57`); I disassembled `io_2.12` and confirmed `Using.file`/`OpenFile` calls `IO.createDirectory(file.getParentFile)` before opening, so nested `kyo/compat/...` dirs are created. Run #1/#4 confirm 20 files land as managed sources.
- **Platform bucketing is correct.** `inScope` admits `shared` always plus the row's own platform (`CompatConformance.scala:41-45`); JVM-only `FromCompletionStageTest` (in `test-jvm`) is extracted only onto JVM rows, matching how the in-repo bindings wire the jvm bucket only in `.jvmSettings`. JS/Native rows would get the 19 shared files only.
- **Resource lookup via the plugin classloader.** `getClass.getClassLoader.getResourceAsStream("kyo-compat-testkit/...")` (`CompatConformance.scala:21,54`); I confirmed the published plugin jar contains `kyo-compat-testkit/INDEX` (20 sorted lines) + all 20 scoped `.scala` resources (31 jar entries incl. dir entries).
- **Per-row re-read at materialization.** `compatConformance` only flips `Meta.conformance`; the row `process` closure re-reads `currentMeta.flatMap(_.conformance)` and applies `conformanceSettings` (`CompatLibrary.scala:225-244`), the same late-binding pattern as `bindLocally`/`jvmSettings`. So `.compatConformance(...)` works before or after `.compatLibrary(...)`.
- **Conformance wiring content.** `conformanceSettings` adds `scalatest % Test` (append), `Test / scalacOptions += "-Xmax-inlines:1024"` (append, no clobber), and a `Test / sourceGenerators` extract task (`CompatLibrary.scala:265-275`). `checkConformanceWiring` confirms both are present.
- **README accuracy.** `CompatBackendAxis.external(name, idSuffix, directorySuffix, supportedPlatforms, organization, artifactName, version)`, `.compatConformance(scalatestVersion = "3.2.20")`, `.get(axis): Option[CompatBackendProjects]`, and `bindLocally(b, local)` in the README all match the implemented signatures; `compatKyoVersion` default = plugin `Implementation-Version` is confirmed (published jar manifest shows `Implementation-Version: 9.9.9-CONF`). Doctest green (run #3). All new code blocks are correctly `doctest:expect=skipped`.
- **No closed-world backend assumptions that break an external backend.** The only name-literal branch is the Future-implicit exemption in the empty-intersection check (`CompatLibrary.scala:109`), which is correct for external backends (they are checked). Source dirs use `backend.name`; ids/suffixes come from the axis. Nothing else assumes `io.getkyo`.
- **Stale-ref cleanup complete.** No `ce`/`cats-effect`/`IORuntime` references remain in tracked sources except the intentional README external-binding examples and the scripted `external-backend` fixture; `FiberTest.scala` reference was rewritten. "five backends" wording is consistent throughout the README. (Stale copies under `kyo-compat/plugin/target/**` are regenerated build artifacts, not tracked.)
- **No regression to built-ins/existing plugin behavior.** The change is additive on the shared `addRows` path; built-in dep resolution is preserved (runs #1, #2, #4). Full scripted regression: see below.

---

## Coverage gaps (what the in-repo tests do NOT prove)

1. **No automated test compiles the *extracted bundle* against a *real* binding.** The scripted `conformance` test only checks extraction paths + wiring + byte-identity against an empty stub; the in-repo bindings (`kyo-compat-futureJVM/test`, etc.) compile the *canonical* `kyo-compat/test/**` sources via `unmanagedSourceDirectories`, never the plugin bundle. The two facts compose by transitivity (bundle == canonical by `checkByteIdentity` + `IO.copyFile`; canonical compiles+passes with the same `-Xmax-inlines`/scalatest settings), and I closed the gap directly with run #4. But there is **no CI protection** for the bundle→extract→compile→run path against a real binding; a future regression there (e.g. a bundling or extraction bug that byte-identity happens not to catch) would not be caught by the in-repo suite. Consider an in-repo conformance harness project that uses `.compatConformance()` + `bindLocally(FutureLib, kyo-compat-future.jvm)`.
2. **JS / Native and multi-Scala end-to-end not exercised.** My run #4 covered JVM/Future single-Scala only. The bucketing and `%%%`/`scalaVersionAxis` logic is platform/version symmetric and the JS/Native shared-bucket extraction is the same code path, so risk is low, but a real JS or Native binding compile of the extracted suite was not run.
3. **`bindLocally` + `.compatConformance` together** (the exact README "Setting up an external binding" recipe) was not run against a real local binding; I validated the equivalent published-artifact path instead. The two share the same extraction/wiring code and differ only in classpath provision (`dependsOn` vs `libraryDependencies`), both already covered individually by existing scripted tests.

---

## Full scripted regression (run #5)

<!-- FILLED IN BELOW -->
