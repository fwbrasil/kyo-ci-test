# Overnight work log — bracket-leak fix

Isolated worktree: `bracket-leak-explore` (branch `bracket-leak-explore`, off `778f630155` = the tip of the
completed live review in `effervescent-painting-backus`).

## The fix (committed 6d87653b91)

Contract: a bracket owes a release only if its acquire **completes**; an acquire that **stops** (interrupt) or
**fails** owes nothing.

- **kernel** `Eval.release`: removed the stranded-`Ensure` recovery (`settledResource`/`firstStep`/`ensuringInCont`/
  `ensuring`/`leftmost` + the Park-case block). It manufactured a release for a stopped acquire (contract violation)
  and corrupted #1735. Completed acquires still release via region teardown; the walk still runs nothing.
- **kernel test** `BracketTest`, **core test** `ScopeInterruptTest`: assert owns-nothing for an acquire interrupted
  before it finishes.
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
- [queued, the long pole] task 5 (KERNEL benchmarks, NOT kyo-bench): `kyo-kernel/jvm/src/jmh` has
  `KernelBench` (kyo throughput), `cross/{Zio,CatsEffect,Turbolift}Bench` (same bench names for a kyo-vs-lib
  comparison), and `CompileBench` (per-fixture compile time). Plan: `kyo-kernelJVM/Jmh/run` a wide -f1 screen of
  KernelBench + the 3 cross classes to find rows where kyo is worse; then investigate those; run CompileBench for
  compile times. The fix is a cold-path change (Eval.release/abandonment), off every KernelBench hot path, so no
  throughput regression is expected; spot-check to confirm. Results + investigation → `bench.md`.

