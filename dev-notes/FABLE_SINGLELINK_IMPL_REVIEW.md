# Fable review: single-link implementation (commit 6d0997d5a0)

Verdict: **REVISE**. The orchestration itself (partition, memory shape, single-link
property, watchdog scoping) is correct and matches the corrected design from my proposal
review. But three concrete flaws stand, two of which directly poison the planned 3-4h
container A/B: (B) `check_log` converts a mid-session link failure, driver OOM, or
mid-link watchdog kill into a GREEN exit whenever at least one earlier module passed;
(C) `scripts/build.sh` does not carry `NATIVE_HEAVY`/`NATIVE_LINK_CPUS` (or
`STALE_TIMEOUT`) into the podman container, so the A/B as planned would run the heavy
optimize inside the 6G aggregate driver, i.e. measure the wrong configuration and
possibly report it green via (B); (A) removing the upfront aggregate link silently drops
all CI compile+link validation for four LTS-pinned native modules that `testKyo` never
selects. Fix B and C before starting the A/B; A needs a decision (fix or an explicit
accepted-loss ruling) before ship.

## 1. PARTITION: exact for everything testKyo selects; the selected set shrank (Finding A)

The `--only`/`--exclude` complement is exact. `runAll` (`project/TestKyo.scala:153`) and
`runDiff` (`project/TestKyo.scala:236`) apply the same predicate
`!exclude.contains(baseName) && (only.isEmpty || only.contains(baseName))`; with the same
csv on both sides, every selected project lands in exactly one session, in both modes:

- 3.8.4 pass: `--only` yields exactly `kyo-schema-testsNative`, `--exclude` the other 51
  (dry-run confirmed).
- 2.13 pass: `kyo-schema-tests` uses `kyo-settings`' `crossScalaVersions :=
  List(scala3Version)` (`build.sbt:133`, project at `build.sbt:806-828`), so the `--only`
  session's 2.13 pass selects nothing ("no projects found", returns cleanly, exit 0). The
  Native modules that DO cross 2.13 (`kyo-stats-registryNative` `build.sbt:1412`,
  `kyo-configNative` `build.sbt:1428`; nothing else on Native) fall in the `--exclude`
  session and link+run exactly once, in-session, after the `++2.13` switch (first links in
  the scala-2.13 crossTarget, no skip involved). 2.12 pass: plugins are suffix-less
  JVM-only projects, rejected by `matchesPlatform("Native")`; empty on both sessions.
- Diff mode: exclusion/only apply after dependents expansion (`TestKyo.scala:225-240`), so
  the union of the two sessions is exactly `allAffected`; the heavy session no-ops (exit 0
  through `runDiff`'s "no testable affected projects" path) when kyo-schema-tests is
  unaffected; `metaBuildChanged` falls through to `runAll` with the filters intact
  (`TestKyo.scala:195,210`). No module is dropped or double-run.

**Finding A (coverage regression, needs a ruling):** "every native module exactly once"
is only true of the testKyo-SELECTED set, and this commit shrank what the Native row
BUILDS. The old upfront `kyoNative/Test/nativeLink` linked every aggregated module
regardless of `crossScalaVersions`; `testKyo`'s `matchesScala` filter
(`TestKyo.scala:152`) selects only modules whose `crossScalaVersions` contains the pass
version. Four native projects contain neither 3.8.4 nor 2.13 and are in the `kyoNative`
aggregate:

- `kyo-scheduler.native` (native pin `List(scala3LTSVersion)`, `build.sbt:625`)
- `kyo-scheduler-zio.native` (`build.sbt:653`)
- `kyo-compat-future.native` (all-platform pin, `build.sbt:2453`)
- `kyo-compat-zio.native` (`build.sbt:2521`)

Their tests never RAN under testKyo (pre-existing on origin/main, identical pinning
there), but the old flow compiled their test sources and linked their test binaries every
Native row. After this commit they are not compiled, linked, or run anywhere on the row:
the compile phases use the same filter. The scheduler pair keeps main-compile coverage
transitively (kyo-core / kyo-zio depend on them); `kyo-compat-future.native` and
`kyo-compat-zio.native` have no selected native dependent, so their MAIN native sources
also lose all CI compile coverage. No workflow runs an LTS pass (grep of
`.github/workflows` and `scripts/` finds none).

Fix options: (1) teach testKyo an LTS pass (mirrors `findScala2Versions` with
`scala3LTSVersion`; also closes the deeper pre-existing hole that the kyo-compat family
and `kyo-compat-tests` are invisible to CI on every platform); (2) minimal for this
commit: an explicit `sbt` link of those four native test binaries on the Native row
(cheap, reintroduces cross-session links only for them); (3) an explicit accepted-loss
ruling (e.g. compat bindings validated at publish time). This is a user decision; do not
ship the silent version.

## 2. SINGLE-LINK: holds; retry relinks are acceptable

- In steady state nothing cross-session remains: the compile phases never link, the heavy
  module links once in its `--only` session, everything else once in the `--exclude`
  session, and the mandatory exclusion (my review's condition) is honored in both runAll
  and runDiff, so the 6G aggregate can never relink the 9.9G heavy optimize.
- Crash-retry (`run_native_retry`, `scripts/ci-test.sh:423-463`) re-running a whole
  session on rc=2 relinks the pruned modules of the failed attempt: rare, bounded by
  `MAX_RETRIES`, results correct, wall-clock only. Acceptable, same shape as before.
- The relink-guard step still costs the run one cheap kyo-data relink. Acceptable.

## 3. MEMORY: correct shape; the known open risk is exactly what the A/B measures

Matches the safe configuration from my proposal review: heavy at the .jvmopts 12G with
`-XX:ActiveProcessorCount=2` exported inside a subshell (`ci-test.sh:502-507`) so the cap
cannot leak to the aggregate (self-test 8a proves it via the heap log); aggregate at
`-J-Xmx6G` (`ci-test.sh:512`); the sessions are strictly sequential (`|| return $?`, the
subshell waits). kyo-schema-tests forks no podman/chrome; kyo-sql-tests' containers and
kyo-ui's Chrome run under the capped 6G driver exactly as before. The stacking OOM shape
is avoided. Residual, by design: the 6G aggregate now performs ~46 first links instead of
25 relinks before reaching the late fork-heavy modules; late-module heap pressure is the
one measurement that could force 6G to 8G. That is the A/B's job, but see Finding B: an
aggregate driver OOM currently exits GREEN, so fix B first or the A/B's central signal is
unreliable.

## 4. WATCHDOG: compiles are safe; two real exposure points, one blocking (Finding B)

- The compile phases run as plain `sbt` (`ci-test.sh:491-492`), not under
  `run_native_retry`: they cannot be wrongly killed. Correct.
- Per-link silent gaps: phases print only on completion; longest measured full cycle 372s
  < 600s on real runners, and per-phase gaps are smaller. The one genuinely new watchdog
  exposure is the heavy module's whole-program optimize at 2 CPUs, which never ran under
  the watchdog before (old flow: un-watchdogged pre-link, then the skip hit). If it goes
  silent >600s the session is killed, `check_log` returns 2 (no `Tests:` yet), and all 3
  attempts burn on a deterministic timeout. Measure this gap explicitly in the A/B; if it
  approaches 600s, raise `STALE_TIMEOUT` for the heavy invocation (one line, env-scoped).
- qemu caveat for the A/B itself: on an emulated arch everything is several times slower
  and the watchdog WILL trip. Run the A/B `--arch arm` (native on this host), or export a
  large `STALE_TIMEOUT`, which requires the build.sh passthrough from Finding C.

**Finding B (blocking): `check_log` false-greens mid-session link failures and kills.**
`scripts/ci-test.sh:388-416`, unchanged from the old flow, where it was near-safe because
every link was validated upfront. Now the session performs the FIRST link of every module,
and two paths convert a mid-session death into exit 0 once any earlier module printed a
passing `Tests:` line:

1. Non-watchdog nonzero exit (`ci-test.sh:413`): module C's link fails (clang error,
   missing lib, #1821-style missing .ll.o) or the driver is OOM-killed (exit 137, the
   exact failure the A/B probes) after modules A and B passed. sbt aborts the command
   sequence and exits nonzero; the log has passing `Tests:` lines, no failure markers,
   `watchdog_killed=0`, so "`0 test failures: tolerating non-zero exit`" returns 0. Green
   row, C..Z unlinked and unrun.
2. Watchdog branch (`ci-test.sh:404`): the mid-run marker regex knows only
   `compiling N Scala source|Linking native code|<Suite>:`. A kill during the silent
   optimize/codegen phases (which print nothing until done, and "Linking native code" is
   the LAST phase line) leaves no marker after the last `Tests:` line, so it is classified
   "shutdown hang", return 0.

Concrete fix: run the post-last-`Tests:` marker scan on EVERY nonzero exit (not only
`watchdog_killed=1`), with the marker set extended to the full link-phase vocabulary and
the link task-error line:

```
Linking \(|Discovered [0-9]+ classes|Optimizing|Generating intermediate code|Compiling to native code|Produced [0-9]+ files|Linking native code|compiling [0-9]+ Scala source|Test / nativeLink|^\[info\] [A-Z][a-zA-Z]+(Test|Suite):
```

Do NOT add a bare `[error]` marker: the tolerated end-of-run shutdown-crash cases also
produce `[error] ... / Test / test` lines and would flip red; `Test / nativeLink` is the
precise discriminator for a link that failed before printing any phase line. With this
set, all currently-tolerated scenarios (crash/hang after the final pass, errno-104 retry)
keep their behavior, and mid-run link failures/kills go red. Add two self-test cases:
"link failure after a pass exits 1" (`Tests: succeeded...` then
`[error] (kyo-xNative / Test / nativeLink) ...`, exit 1) and "kill mid-link after a pass
exits 1" (`Tests:` then `[info] Optimizing (debug mode)` then sleep).

Known residual, pre-existing and institutionalized ("Native crash after a clean pass
tolerated"): a shutdown crash of a NON-final module aborts the sequence with no
post-`Tests:` markers at all and still greens. Distinguishing it needs a completion
marker; testKyo already prints one on full-run completion (`restoring Scala`,
`TestKyo.scala:120`). Worth verifying in the A/B logs whether it prints after a failed
run and, if reliable, requiring it in the tolerance branch as a follow-up. Not blocking
this change (unchanged exposure), unlike the link windows above (new exposure).

## 5. FAIL-FAST: acceptable, with one asymmetry noted

Compile errors fail fast in the un-watchdogged upfront phases (self-test 7). A heavy
session link error produces no `Tests:` output, so `check_log` returns 2 and the loop
burns all `MAX_RETRIES` full relink attempts (~20-30 min) before going red: red is
reached, the waste is tolerable and not worth a parser. An aggregate link error is
Finding B: currently it does NOT reliably surface at all. With B fixed, a link error
surfaces red at the failing module's slot, which is the acceptable trade the design
intended (the old upfront link took 90+ min anyway, so time-to-red barely moves).

## 6. SELF-TESTS: good coverage of the happy orchestration; three gaps

Cases 6/7/8/8a/8b correctly pin: ordering (compile-main, compile-test, `--only`,
`--exclude`), no `nativeLink` call, fail-fast, the CPU cap reaching exactly the heavy
session (heap-log line 3 of 4), the full-heap heavy invocation (exact-string call 3, no
`-J-Xmx`), and heavy-failure abort before the aggregate. The count gate (25) checks out.
Gaps worth adding:

1. The Finding-B cases above (mid-session link failure, mid-link kill): the untested
   failure mode that matters most now.
2. Heavy-session retry semantics: all nat cases (9-20) run with `NATIVE_HEAVY` unset, so
   nothing proves the `--only` session is watchdogged/retried (e.g. heavy errno-104
   retried then passes; heavy hang with no output exhausts retries, exits 1, and no
   `--exclude` call follows).
3. Minor: no Native `testDiff` case asserting the `--only`/`--exclude` commands omit
   `--all`.

## 7. Other findings

**Finding C (blocking for the A/B): build.sh does not reproduce CI's Native env.**
`scripts/build.sh` forwards `CI=true`, `SBT_TASK_LIMIT=1`, and the driver opts into the
container (`build.sh:281-283`) but has zero plumbing for `NATIVE_HEAVY`,
`NATIVE_LINK_CPUS`, or `STALE_TIMEOUT` (grep: no hits), and only an explicit allowlist of
env vars crosses into podman. So `scripts/build.sh --env podman-ci test Native` runs
ci-test.sh with `NATIVE_HEAVY` empty, taking the else-branch (`ci-test.sh:514`): every
module INCLUDING kyo-schema-tests' whole-program optimize links inside the `-J-Xmx6G`
aggregate driver, with all 4 CPUs. That is not the shipped orchestration, it is the
likely-OOM configuration the design exists to prevent, and per Finding B the OOM could
still exit green. Under the old flow this env gap was survivable (the heavy link happened
in the 12G upfront driver); the new flow makes env parity load-bearing. Fix before the
A/B: have `podman-ci` mode default the CI values for the Native target (mirror
`build.yml:88,94`: `NATIVE_HEAVY=kyo-schema-tests`, `NATIVE_LINK_CPUS=2`) and forward
host-exported `NATIVE_HEAVY`/`NATIVE_LINK_CPUS`/`STALE_TIMEOUT`/`MAX_RETRIES` overrides.

Minor, non-blocking:
- `build.yml:85` says the aggregate driver "has accumulated ~10 prior modules";
  alphabetically kyo-schema-tests sits far deeper (~40 of 52). Comment accuracy only.
- The heavy `--only` session still pays the `++2.13`/`++2.12`/restore switches for empty
  passes (settings reloads, ~1-2 min total). Cosmetic; not worth complicating testKyo.
- `run_arg` yields "" for testDiff producing double-space commands
  (`testKyo --exclude kyo-schema-tests  Native`); sbt's parser handles it, and self-test 4
  pins the analogous JVM string. Fine.

## Bottom line

The design was implemented faithfully: partition exact, single link per selected module,
memory shape safe, watchdog scoping right, self-tests meaningfully assert the
orchestration. REVISE for: (B) harden `check_log` against mid-session link
failure/kill/OOM false-greens (+2 self-tests), required for CI correctness AND for the
A/B's OOM signal to mean anything; (C) give build.sh podman-ci CI-faithful
`NATIVE_HEAVY`/`NATIVE_LINK_CPUS` defaults and env passthrough, required for the A/B to
measure the shipped configuration; (A) decide or fix the dropped compile+link coverage
for `kyo-scheduler.native`, `kyo-scheduler-zio.native`, `kyo-compat-future.native`,
`kyo-compat-zio.native`. Then run the A/B `--arch arm`, watching: aggregate driver heap
through the late modules (the 6G-vs-8G call), the heavy optimize's longest silent gap
against 600s, cycle count ~50, and whether `restoring Scala` prints after a failed run
(for the follow-up completion-marker hardening).
