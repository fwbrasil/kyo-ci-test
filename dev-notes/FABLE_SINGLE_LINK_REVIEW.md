# Fable review: single Native link (compile upfront, link in the run phase)

Verdict: **REVISE**. The single-link direction is correct, strictly faster, and eliminates the
relink; adopt it. Two parts of the proposal as written are wrong and must be corrected before
implementation: (1) removing `NATIVE_HEAVY` and raising the shared run driver toward 12G is
unsafe, because two fork-heavy module families (podman containers and Chrome) run AFTER
kyo-schema-tests in the sequential run order; (2) the speed framing rests on a false premise:
there is no upfront cross-module link parallelism today to lose. The corrected design below
preserves the single-link property for every module including the heavy one. No fallback to
post-test-cleanup is needed.

## 1. Soundness: does it eliminate the relink? YES, with one required addition

The in-session first link cannot be spuriously re-triggered:

- `testKyo` joins per-module tasks with `; ` and runs them through one `Command.process`
  (`project/TestKyo.scala:145-147`, `229-231`), so `X/test` evaluates `X/Test/nativeLink`
  exactly once. No later `Y/test` depends on X's TEST link (inter-module dependencies are on
  `Compile` products, the NIR classpath), so the task is never re-evaluated in the session.
- The Scala-version churn that triggers #2514 happens only AFTER the full 3.8.4 pass
  (`project/TestKyo.scala:89-107`: `runForScala(state1, targetScala)` completes before the
  `++2.13`/`++2.12` folds). The 2.13-pass Native links (kyo-scheduler, kyo-scheduler-zio,
  kyo-stats-registry; `build.sbt:620,646,1412`) are distinct `scala-2.13` crossTargets, first
  links, not relinks, and they already happen in today's test phase identically.
- The `#1822` prune hook is compatible as-is: `Test / nativeLink ~=` prunes the work dir but the
  binary sits one level up, outside it (`build.sbt:3204-3232`), so the in-session link, prune,
  then test sequence is safe, and peak disk stays bounded to one un-pruned work dir.

Residual #2514 exposure, all pre-existing or handled:

- **The crash-retry loop** (`scripts/ci-test.sh:431-464`) re-invokes `sbt testKyo ... Native` as
  a NEW process on rc=2; that retry pays cross-session relinks. Same shape and rarity as today;
  not a regression.
- **The relink guard** (`build.yml:121-124`) links kyo-data in separate processes before the run
  phase; the run phase's kyo-data link may miss the skip and relink once. One cheap cycle,
  exists today.
- **The NATIVE_HEAVY isolated step**: the proposal's open question 1 is answered below; without
  the exclusion mechanism it WOULD reintroduce a cross-session relink for the heaviest module.
  With it, zero cross-session reuse remains anywhere in the steady-state row.

One operational hole to close: links now run inside the watchdogged, stale-timeout process
(`STALE_TIMEOUT=600`, `scripts/ci-test.sh:269`). Scala Native prints per-phase lines only as
each phase completes; the longest measured cycle is 372s total (NATIVE_LINK_SLOWDOWN_PROPOSAL,
run 31841875109), so silent gaps stay under 600s, but on qemu-emulated or degraded runners a
long optimize could trip the watchdog and burn a full retry. Keep 600s but verify the longest
silent gap in the A/B; raise for Native if it approaches the limit.

## 2. Memory: the crux. The shared driver must NOT be raised; keep the heavy module isolated

The proposal's suggestion to raise the run driver toward 12G and drop `NATIVE_HEAVY` is
**unsafe**, from the module ordering alone. `runAll` sorts alphabetically
(`project/TestKyo.scala:143`), and after `kyo-schema-tests` come, in the same sequential driver:

- `kyo-sql-tests`: container-driven suites (`SqlSharedContainers`) live in shared tests
  (`kyo-sql-tests/shared/src/test/scala/kyo/internal/SqlSharedContainers.scala`), so the Native
  binary starts real Postgres AND MySQL podman containers (`build.sbt:963-994`, service
  providers for both backends on Native).
- `kyo-ui`: a Native cross-project whose tests drive real Chrome via `kyo-browser % Test`
  (`build.sbt:2886-2900`).

A 12G driver ballooned by the kyo-schema-tests optimize (measured 7.7G RSS clean, 9.9G
accumulated; `scripts/ci-test.sh:273-280`) does not reliably return RSS between modules, and
the containers plus Chrome then stack on top of it: exactly the overcommit `build.yml:71-75`
and `92` document (kernel OOM, exit 143). The proposal's own claim "the 6G driver already does
coupled link+run for the 25 relinking modules" is true but does not extend to the heavy module:
those 25 evidently never included a full kyo-schema-tests optimize (a 9.9G optimize in a 6G-Xmx
driver would have OOMed the row; rows pass because its cross-session skip hits after the fresh
pre-link).

**Concrete safe configuration:**

1. `testKyo --phase compile-main --all Native`, then `--phase compile-test`: separate sbt
   processes, uncapped 12G `.jvmopts` heap, same as JVM/JS/Wasm (`scripts/ci-test.sh:329-348`).
2. **Isolated heavy test**: for each module in `NATIVE_HEAVY`, run
   `sbt '<m>Native/test'` in its own process: full 12G driver, `NATIVE_LINK_CPUS=2` applied
   (12G driver + uncapped clang fleet is the measured kernel-OOM config, `build.yml:87-92`),
   and wrapped in the same watchdog/check_log machinery as the aggregate run (today's pre-link
   is un-watchdogged, but now this step also RUNS tests, and the libunwind shutdown-hang
   tolerance of `check_log` must cover it; refactor the retry loop body into a reusable
   function). Link + test in one session: the single link is preserved for the heavy module.
   kyo-schema-tests forks no podman/chrome, so the 12G driver is safe here.
3. **Aggregate run with exclusion**: `sbt -J-Xmx6G 'testKyo --all --exclude kyo-schema-tests Native'`.
   `testKyo` needs a small `--exclude` flag (a filter in `runAll`/`runDiff`). This exclusion is
   **mandatory, not cosmetic**: without it the aggregate re-runs kyo-schema-tests/test, and a
   #2514 skip-miss means a 9.9G relink inside the 6G driver, a hard OOM.
4. Keep the aggregate run at **6G** initially (evidence: today's 6G driver survives 25 full
   relinks plus every fork; `scripts/ci-test.sh:314-322`). The one open risk is accumulation:
   the new run driver performs ~46 links instead of 25 before the late modules. If the A/B
   shows heap pressure, 8G is the fallback (8G driver + ~7G left for podman/chrome/binaries is
   still safe); 12G is never the answer.
5. `NATIVE_HEAVY` **survives**, redefined from "isolated pre-link" to "isolated test +
   exclusion". `NATIVE_LINK_CPUS` **survives**, scoped to the isolated heavy invocation only;
   the 6G aggregate run keeps 4 CPUs (today's test-phase relinks already run there uncapped and
   pass). `SBT_TASK_LIMIT=1`, `CI_MON_DISK_ABORT_MB`, and the prune hook are untouched.

## 3. Speed: strictly faster; the "parallel upfront" premise is false

There is no cross-module link parallelism today. `SBT_TASK_LIMIT=1` maps to `Tags.limitAll(1)`
(`build.sbt:93-106`, `build.yml:71-76` "one sbt task at a time"), so the upfront aggregate
links its ~48 modules sequentially, each internally capped to 2 visible CPUs by
`NATIVE_LINK_CPUS` (`-XX:ActiveProcessorCount=2`, `scripts/ci-test.sh:294-302`).
`NATIVE_LINK_CPUS` caps within-link parallelism; it never provided 2 concurrent module links.

So the comparison is: today ~84 sequential cycles (59 at 2 CPUs upfront + 25 at 4 CPUs in the
6G run driver) versus single-link ~50 sequential cycles (1 isolated heavy at 2 CPUs + ~46
aggregate-run links at 4 CPUs + 2 relink-guard links), where the ~46 surviving links run with
twice the visible CPUs of their upfront equivalents. Expected saving on the arm64 row: ~34
cycles x ~90s plus the per-link speedup, roughly 50-70 minutes of the ~196. There is nothing to
recover with a "dedicated parallel link phase": in-invocation link parallelism would require
raising `limitAll` for a link window, which reintroduces the exact memory stacking the serial
cap exists to prevent, for a gain the cycle-count cut already dwarfs. Reject that variant.

Bonus not in the proposal: today's **diff** rows pay the full unconditional aggregate link
(`run_native` links `kyoNative/Test/nativeLink` regardless of action, `scripts/ci-test.sh:419-424`)
even for a one-module PR. Under single-link, diff rows link only affected modules: a large
additional win. Diff rows need one design point: run the isolated heavy step only when
kyo-schema-tests is affected (cleanest: `testKyo --dry-run` prints the project list; have
ci-test.sh consult it). Running it unconditionally is the acceptable simple fallback (still far
cheaper than today's unconditional 48-link upfront).

## 4. Blast radius

- `scripts/ci-test.sh`: rewrite `run_native` (406-467) per the design above; header comment
  (13-19); `NATIVE_HEAVY` doc (273-281) and `NATIVE_LINK_CPUS` doc/plumbing (283-302);
  `run_phase_heap` unchanged. Self-tests: cases 2b, 6, 7, 8, 8a, 8a2, 8b (128-136, 158-205)
  all assert the aggregate-link path and must be rewritten to assert the new shape (three-phase
  split, isolated heavy test before the aggregate run, heavy failure aborts, CPU cap on the
  isolated invocation only, heap cap on the aggregate run only, no `kyoNative/Test/nativeLink`
  call); the `nat()` helper (209-212) branches on the link call and needs adapting; the case
  count gate (251, `TOTAL -eq 25`) changes. The `link` action (28, 425) leaves the CI path;
  keep it as a standalone `kyoNative/Test/nativeLink` escape hatch (only `ci.yml:46` documents
  it) or make it a no-op like the other platforms.
- `project/TestKyo.scala`: add `--exclude` (runAll and runDiff filters, usage scaladoc). Small.
- `.github/workflows/build.yml`: comment updates for `NATIVE_HEAVY` (83-86) and
  `NATIVE_LINK_CPUS` (87-92); values stay; the relink-guard step (121-124) stays, with its
  "the row reuses the binary linked here" comment touched up.
- `scripts/native-relink-selftest.sh`: **keep it, functionally unchanged** (contra the
  proposal's "its premise changes"). It guards the #1821 prune-relink invariant, which stays
  live through the retry path, diff rows, and local flows. Only its header prose needs a touch.
- Fail-fast: compile errors still surface in the upfront compile phases. A LINK error now
  surfaces at the module's slot in the run phase instead of in a dedicated upfront step; the
  row goes red either way with the same clang/linker diagnostic, and since the upfront link
  itself took 90+ minutes, mean time-to-red barely moves. Coverage nuance: at most one module,
  kyo-schema-protobuf, has no test sources (only native module without any), and its code is
  link-validated through kyo-schema-tests' every-format binary regardless. Acceptable.

## 5. Verdict

**REVISE, then implement.** The single-link restructure is the right call: it removes the
#2514 dependence entirely (in-session first link, nothing between modules re-triggers it),
cuts ~84 codegen cycles to ~50, keeps the #1822 prune and its disk bound, and improves diff
rows substantially. The required corrections:

1. Keep `NATIVE_HEAVY` as an isolated, watchdogged `<m>Native/test` (link + run in one 12G
   session, `NATIVE_LINK_CPUS` applied), excluded from the aggregate pass via a new
   `testKyo --exclude`. Do not raise the shared run driver toward 12G: kyo-sql-tests' podman
   containers and kyo-ui's Chrome run after kyo-schema-tests in the sequence, and stacking them
   on a ballooned driver is the documented OOM shape. The exclusion also closes the one hole
   where #2514 would survive.
2. Drop the "remove NATIVE_LINK_CPUS/NATIVE_HEAVY" simplification and the parallel-link
   framing; both rest on the false premise that the upfront link was parallel.
3. Close the operational details: watchdog coverage for the isolated heavy test, STALE_TIMEOUT
   verification in the A/B, diff-row conditionality for the heavy step, and the ci-test.sh
   self-test rewrite in the same change.

The post-test-cleanup fallback (NATIVE_LINK_SLOWDOWN_PROPOSAL.md) is dominated: it keeps ~20GB
retained and still pays redundant reachability for every relink; the corrected single-link
design is both faster and safe. Validate exactly as the proposal plans: podman-ci A/B on both
arches, expecting ~50 cycles, watching the aggregate driver for late-module heap pressure (the
one measurement that could force the 6G to 8G bump) and the longest silent link gap against
the 600s watchdog.
