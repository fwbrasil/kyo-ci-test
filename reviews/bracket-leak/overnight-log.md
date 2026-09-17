# Overnight work log — bracket-leak fix

Isolated worktree: `bracket-leak-explore` (branch `bracket-leak-explore`, off `778f630155` = the tip of the
completed live review in `effervescent-painting-backus`).

## The fix (committed 6d87653b91)

Contract: a bracket owes a release only if its acquire **completes**; an acquire that **stops** (interrupt) or
**fails** owes nothing.

- **kernel** `Eval.release`: removed the stranded-`Ensure` recovery (`settledResource`/`firstStep`/`ensuringInCont`/
  `ensuring`/`leftmost` + the Park-case block). It manufactured a release for a stopped acquire (contract violation)
  and corrupted #1735. Completed acquires still release via region teardown; the walk still runs nothing.
- **kernel test** `BracketTest`: asserts owns-nothing deterministically for an acquire interrupted before it
  finishes (platform-independent, drives `Eval.release` directly). **core test** `ScopeInterruptTest`: asserts the
  real-runtime bracket releases only what it took (`rel == 0` on the JVM where the stop lands before the bracket
  takes the value; `rel == acq` on JS and Native where the acquire reaches it).
- **aeron** `Topic`: the add's token-guard also owns the produced publication/subscription — on Done the token
  becomes the resource, and an abnormal exit after Done closes it (spanning to the clean hand-off); outer
  registration via `ensureMap`. `AeronTransportTest`'s two leak-on-interrupt tests pass.

Validated locally: kernel 1747 pass, core 0 failed, aeron 34 pass.

## Overnight task list (from the user)

1. prepare for the live review
2. run more module tests locally
3. dispatch CI for the branch
4. cleanup prose in source files
5. run benchmarks (incl. compilation times); investigate benches where kyo is worse than other libs

## Morning summary (all five tasks)

1. **Prepare live review** — done. `derivation.md`, `review.md` (5-file edit walk, per-hunk justifications),
   `fix.diff`. Apply into `effervescent-painting-backus` (at 778f630155).
2. **More module tests** — done, all green: kyo-data, kyo-prelude, kyo-combinators, kyo-stm, kyo-actor (JVM), 0 fails.
3. **Dispatch CI** — done: fwbrasil/kyo-ci-test run 35168861512 (full: JVM+JS+Native on linux-x64+arm64). Was still
   running at last check (kyo's full matrix is ~1h+); see the run page / `scripts/ci-logs.sh run 35168861512` for the
   verdict.
4. **Prose cleanup** — done: Eval.release Defer-comment tightened + dead Safepoint save/restore removed (committed
   bfba740693); other files' added comments checked (no dashes, load-bearing). Re-validated green (kernel 1747 /
   core 0-failed / aeron 34).
5. **Kernel benchmarks + deep dive** — done in `bench.md`: kyo-vs-ZIO/cats/Turbolift comparison (21 losing rows,
   competitor almost always Turbolift; 28 rows at/ahead), four deep-dive cases with code + `-prof gc` + async-profiler
   cpu + `-XX:+PrintInlining` + an optimization experiment, and CompileBench compile times. Headline: the widest loss
   is `suspend.map(f)` allocating a `DeferWith` (case 1; ceiling = `askWith`, 2x); the deepest is foreign-handler
   crossings (case 2, snapshot+park per crossing). Two optimization experiments on case 1 both failed conclusively
   (a GADT-existential bound; then `Class too large: kyo/Kyo$` from inlining the fuse at every call site — the
   reviewer's "breaks JIT fusion" prediction, at compile time). Conclusion: the `DeferWith` premium is structural to
   the inline `map`; the fused construction already exists as `askWith`/`suspendWith`. No kernel optimization is
   warranted from this pass, and none is folded into the bracket-leak fix.

The bracket-leak fix HEAD stays `bfba740693` (fix + prose). The perf experiment was reverted; no perf change is folded
into the fix.

## Progress

- [done] task 4 (prose): Eval.scala Defer-case comment tightened; the dead tagged/untagged Safepoint save/restore
  in `release` removed (the walk applies no `Ensure` now, so it needs no Safepoint of its own) → bare
  `collect(v, Arrow.id)`. Other 4 files' added comments checked: no em/en-dashes, load-bearing. Net delta now
  +80/−132. Pending: validate the Safepoint-dance removal (background run bx5ude6zz), then commit.
- [done] task 1 (prepare live review): `derivation.md`, `review.md` (5-file edit walk with per-hunk justifications),
  `fix.diff`. Applies into `effervescent-painting-backus` (at 778f630155) in the morning.
- [queued, sbt-serial after validation] task 2 (more module tests): kyo-prelude, kyo-combinators, kyo-stm,
  kyo-actor, kyo-data, kyo-scheduler — downstream of the kernel change (bracket/scope/interrupt users).
- [queued] task 3 (CI): push `bracket-leak-explore` to `ci-test` (fwbrasil/kyo-ci-test); `gh` is authed as
  fwbrasil. Dispatch via `gh workflow run ci-dispatch.yml --repo fwbrasil/kyo-ci-test --ref bracket-leak-explore`
  (ci.yml only triggers on push to main / PRs, so a workflow_dispatch is the branch path). Monitor with
  scripts/ci-logs.sh / ci-queue.sh.
### Status snapshot (overnight, running)

- Commits: `6d87653b91` (fix), `bfba740693` (dead Safepoint-dance removal + review docs). Branch pushed to
  `fork` (fwbrasil/kyo-ci-test).
- Validation after cleanup: kernel 1747 / core 0-failed / aeron 34 — all green.
- **CI dispatched**: run https://github.com/fwbrasil/kyo-ci-test/actions/runs/35168861512 (ci-dispatch, full,
  JVM+JS+Native on linux-x64+linux-arm64). prep ✓; 6 build jobs in progress.
- Module tests (task 2): running (bavqld4qd) — kyo-data, kyo-prelude, kyo-combinators, kyo-stm, kyo-actor (JVM).
- Benchmark commands (confirmed; cross-lib deps zio/cats/turbolift are Jmh-scoped per build.sbt ~805):
  - `kyo-kernelJVM/Jmh/compile`
  - `kyo-kernelJVM/Jmh/run 'kyo\.kernel\.bench\.(KernelBench|cross\.ZioBench|cross\.CatsEffectBench|cross\.TurboliftBench)' -f1 -wi3 -i3 -rf json -rff <scratch>/kbench.json`
  - `kyo-kernelJVM/Jmh/run 'kyo\.kernel\.bench\.CompileBench' -rf json -rff <scratch>/cbench.json`
  - The fix is a cold-path change (Eval.release / abandonment), off every KernelBench hot row, so no throughput
    regression is expected; the comparison run is for the user's "where is kyo worse than other libs" ask.

### Deep-dive methodology for rows where kyo loses (per the user's "go deep")

For every KernelBench row where kyo is slower than the best of ZIO / cats-effect / Turbolift (outside a ~5% drift
band), climb the evidence ladder to a named mechanism, not just a number:

1. Confirm narrow: re-run that row on all four libs at `-f 3 -wi 5 -i 5` so the gap is stable, not warmup noise.
2. Allocation totals: `-prof gc`, read `gc.alloc.rate.norm` (B/op) for kyo vs the winner. Identical B/op rules
   allocation out and points at path length / code shape; a delta localizes it.
3. Allocation sites: `-prof "async:...;event=alloc"` if async-profiler is present, else infer from the row's code.
4. JIT: `-jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining"`, grep the row's hot methods for
   `callee is too large` / `hot method too big` (method-size is a kernel design property).
5. Name the mechanism and say whether it is addressable in the kernel or structural to the representation.

Then, per the user's "deep dive: code, profiling, optimization experiments, jit inspection" — for each losing
row go the whole way:

6. **Code**: read the kyo path end to end (the bench body -> the kernel machinery it exercises: eval loop,
   node layout, delivery, currency, safepoint) and the competitor's path, so the delta is understood, not guessed.
7. **Profiling**: `-prof gc` (B/op), async-profiler `event=alloc` (sites) and `event=itimer` (cycles) — the
   dylib IS present at `/opt/homebrew/lib/libasyncProfiler.dylib`, so use
   `-prof "async:libPath=/opt/homebrew/lib/libasyncProfiler.dylib;event=alloc"` (and `event=itimer`) to localize
   where the time/allocation goes.
8. **JIT inspection**: `-XX:+PrintInlining` (callee sizes + refusals) and `-XX:+PrintCompilation` on the row's hot
   methods; correlate refused inlines with method bytecode size.
9. **Optimization experiments**: form a hypothesis, make the smallest kernel edit that tests it, re-benchmark that
   one row on `-f 3`, and report the isolated delta (one variable per measurement). Iterate.

**Experiment isolation** (keep the bracket-leak fix's HEAD pristine for the morning live review): perf
experiments are edit -> measure -> `git checkout <file>` to restore. HEAD stays at the fix + reviews docs. Any
experiment that is a real win is saved as a standalone diff under `reviews/bracket-leak/perf/<row>.diff` with its
before/after numbers, flagged as a **separate** candidate change (its own future live review), never folded into
the bracket-leak fix.

This whole investigation is independent of the bracket-leak fix (a cold-path change). Findings -> `bench.md`,
one case per losing row (row, kyo vs winner numbers, mechanism, profiling evidence, experiments tried + deltas,
verdict).

- [queued, the long pole] task 5 (KERNEL benchmarks, NOT kyo-bench): `kyo-kernel/jvm/src/jmh` has
  `KernelBench` (kyo throughput), `cross/{Zio,CatsEffect,Turbolift}Bench` (same bench names for a kyo-vs-lib
  comparison), and `CompileBench` (per-fixture compile time). Plan: `kyo-kernelJVM/Jmh/run` a wide -f1 screen of
  KernelBench + the 3 cross classes to find rows where kyo is worse; then investigate those; run CompileBench for
  compile times. The fix is a cold-path change (Eval.release/abandonment), off every KernelBench hot path, so no
  throughput regression is expected; spot-check to confirm. Results + investigation → `bench.md`.


## CI verification (post-overnight) — two findings

CI run 35168861512 (HEAD, full matrix) came back RED on JS and Native (both x64 and arm64); JVM green. Two
distinct causes, both owned:

1. **ScopeInterruptTest "owns nothing" was JVM-specific** (my change). The `rel == 0` assertion holds only under
   JVM preemption; on JS/Native the acquire reaches the bracket and releases what it took (`rel == acq`). Fixed by
   asserting `rel == (if Platform.isJVM then 0 else acq)`, validated green on JVM and JS locally (14/14 each).
   Commit 6f7512a048. See `derivation.md` / `review.md` section 3.

2. **ScopeTest #1928 drain hangs on JS (x64 and arm64)** — a real regression in the bracket-leak fix, not
   pre-existing. Base `4ed38c8ac9` passed the same leaf on linux-x64 JS in 209ms; HEAD times out (2m). Only the
   `Eval.release` change is in scope. Not reproducible locally (darwin/arm containers pass, qemu-x86 crashes), so
   two focused JS/linux-x64 CI bisect runs are in flight (`6d87653b91`, `778f630155`) to localize recovery-removal
   vs the JVM-only-validated Safepoint save/restore removal (`bfba740693`). Full diagnosis + evidence:
   `reviews/bracket-leak/1928-regression.md`. Commit 758f4127c6. Fix pending the bisect; #1928 is the work, not a
   known issue.

## #1928 RESOLVED — root cause found and fixed

JS/linux-x64 bisect verdict: `6d87653b91` (recovery removed, **save/restore present**) PASSES #1928 (118ms);
`778f630155` (both present) PASSES (78ms); HEAD without the save/restore HANGS. So the **Safepoint save/restore
removal (`bfba740693`) was the regression**, not the recovery removal. The `bfba` "now-dead" claim was argued, not
verified, and validated JVM-only; #1928 is a JS/Native-only invariant.

**Fix** (commit `d55aa4da9a`): restored the tagged-form Safepoint save/restore around the abandonment walk in
`Eval.release`, with the comment corrected (recovery is gone -> no Ensure applied, but the tagged walk still needs
a live state on a stopped Safepoint or #1735/#1928 break). Byte-identical to `6d87653b91`, which the bisect proved
passes #1928. JVM green (BracketTest, ScopeTest, ScopeInterruptTest). JS+Native CI validation: run 35188597180
(linux-x64 + linux-arm64). Review package updated: the fix now KEEPS the save/restore; only the recovery is removed
(commit `6a9c0def3e`). Full arc in `1928-regression.md`.

## CI validation of the fix (run 35188597180, JS+Native x64+arm64)

**#1928 PASSES on all four targets** — the save/restore fix is fully validated:
- x64 JS 97ms, arm64 JS 146ms, x64 Native 18ms, arm64 Native 17ms. ScopeTest 73/0 everywhere.

**ScopeInterruptTest leaf 2 surfaced a second, separate test-only mistake** (not the fix, the assertion): the
stopped-under-`Sync.ensure` acquire's outer-bracket release count is a race, not a per-platform constant:
- JVM: `rel` near 0 · x64 JS: `rel == acq (200)` · arm64 JS: `rel == 199` · x64/arm64 Native: `rel == 0`.
So Native preempts finely like the JVM (`rel == 0`), NOT like JS. Both my `rel == 0` and the follow-up
`rel == (if Platform.isJVM then 0 else acq)` were the same error: a fixed value for a racy, platform-skewed
outcome. Fixed to the race-robust invariant `fin == acq && rel <= acq && acq > 0` (commit c8181edc2c); the
deterministic owns-nothing is guarded in the kernel BracketTest. Re-validation: run 35197655701.

**Pre-existing, unrelated (routed to you)**: the JS jobs also carry a flaky `kyo-dataJS / Test / executeTests`
JS-env crash (`RunTerminatedException` / `NonZeroExitException: exited with code 1`), which also failed the base
run 34987201283 the same way. kyo-data is a foundation module untouched by the bracket-leak fix; this is a
pre-existing flaky JS-env crash to triage separately, not part of this change. It keeps the JS job conclusions at
"failure" even though the bracket-leak target tests pass, so read the per-test results, not the job conclusion.

## FINAL VERDICT — re-validation run 35197655701 (JS+Native x64+arm64)

All three bracket-leak target tests PASS on every target:

| target | #1928 | ScopeTest | ScopeInterruptTest |
|--------|-------|-----------|--------------------|
| x64 JS | PASS 120ms | 73/0 | 14/0 |
| arm64 JS | PASS 166ms | 73/0 | 14/0 |
| x64 Native | PASS 13ms | 73/0 | 14/0 |
| arm64 Native | PASS 17ms | 73/0 | 14/0 |

(JVM was green in the earlier full HEAD run.) The bracket-leak fix is validated.

The only remaining job-level failures are two PRE-EXISTING issues in untouched modules, both confirmed failing
identically at the base run 34987201283:
- `kyo-dataJS / Test / executeTests` — a flaky JS-env crash (RunTerminatedException), on both JS arches.
- `kyo.ffi.it.ItStructPtrTest › struct with opaque field` — a native C-FFI test, on both Native arches.

Neither is caused by or related to the bracket-leak fix (kyo-data and kyo-ffi are untouched; a kernel Eval.release
change cannot affect a C struct read). Routed to you to triage separately; they are not part of this change and
do not block the live review.
