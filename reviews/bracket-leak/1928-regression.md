# #1928 drain-hang: a real regression in the bracket-leak fix

## Status

`ScopeTest` "finalizers lost under interrupt (#1928) › an interrupt racing the close does not stop the drain"
**hangs (STUCK 1m -> 2m -> TIMEOUT)** on CI's linux-x64 AND linux-arm64 **JS** targets at the bracket-leak fix
HEAD. It is a real regression, not pre-existing flakiness or an environment quirk.

## Evidence it is a regression (not pre-existing)

- **Base passes on the same env.** CI run 34987201283 (branch `worktree-effervescent-painting-backus`, SHA
  `4ed38c8ac9`) ran ScopeTest on **linux-x64 JS** and reported `ScopeTest: 74 passed, 0 failed`, with #1928 itself
  `[PASS] ... an interrupt racing the close does not stop the drain (209ms)`. (That job's overall failure was an
  unrelated kyo-data JS-env crash, not ScopeTest.)
- **HEAD hangs on the same env.** CI run 35168861512 (HEAD) on linux-x64 JS and linux-arm64 JS both show the leaf
  STUCK to TIMEOUT (limit 2m). `ci-mon` shows availMB ~6900, load < 1: not resource exhaustion, a genuine
  liveness stall on the single-threaded JS event loop. JS hang diagnostics carry no thread dump.
- **The only non-test source delta base->HEAD is `Eval.scala` (kernel) and `Topic.scala` (aeron).** `Topic.scala`
  is unrelated to ScopeTest. So the cause is the `Eval.release` change.

## Not reproducible locally

- darwin arm64 (host node v24): ScopeTest passes, #1928 in 95ms.
- native arm64 linux container (`build.sh --env podman-ci --arch arm`, CI caps): ScopeTest 73/0, #1928 in 7.0s.
- emulated x86 (`--arch x86`): `qemu-x86_64-static` SIGSEGVs during setup, unusable on this machine.
- CI node is '24', same major as local: not a node-major difference.

So the losing interleaving only arises under CI's scheduler timing; fast local environments win the race. It is a
**deterministic finalizer loss** on CI (permanent hang, not a slow pass), so a logic path is reachable there that
the local interleavings avoid.

## Mechanism analysis (why the drain never ends)

`Scope.run` wraps the whole computation in `Sync.ensure(finalizer.close)` as the abandonment backstop; the drain
runs on a separate unscoped fiber behind an uninterruptible promise, and `finalizer.await` is `promise.get`. When
the outer fiber is interrupted, `IOTask.abandon` runs `Eval.release(remainder, ..., Tag[Async.Join])` on the
just-interrupted fiber's **stopped Safepoint**. For the drain to run, that walk must reach and fire the
`Sync.ensure(finalizer.close)` region's release (which `Sync.Unsafe.evalOrThrow`s `finalizer.close`, scheduling
the drain). If it does not fire, the drain never starts and `finalizer.await` waits forever.

- `evalOrThrow` runs with `armed=false` (`Eval.apply(v, armed=false)`), so it does **not** short-circuit on a
  stopped Safepoint. The finalizer runs to completion whenever its release closure is **called**. So the failure
  is about the walk **collecting** the region, not about the eval being cut short.
- `Cell.complete()` sets `ended` only; it does not set the AtomicBoolean, so a completed region's `run` still
  fires (CAS false->true succeeds). A completed-then-abandoned region still releases.

### The two removals in the bracket-leak fix

1. `6d87653b91`: removed the stranded-`Ensure` recovery (`ensuring` / `ensuringInCont` / ...). This handles only
   `Arrow.Ensure` in a settled value's continuation, i.e. the `Bracket.apply` (acquire) shape. #1928 contains no
   `Arrow.Ensure`: `Sync.ensure` is `Bracket.ensuringWith` = a `HandleContext` region-from-start, and the trailing
   `.map { finalizer.close ... }` links are `Arrow.Transform`, which `leftmost` never treats as an `Ensure`. So
   the recovery could not have been reaching #1928's finalizer. **Removing it should not affect #1928.**
2. `bfba740693`: removed the tagged-form Safepoint save/restore around the abandonment walk `collect(v, Arrow.id)`.
   Its own comment named the consequence: *"Without it the abandoned regions are not reached and their releases
   are lost (#1735, and #1928's drain never ends)."* This change was validated **JVM-only**; #1928 only fails on
   JS/Native, so that validation structurally could not catch it.

**Leading hypothesis: the save/restore removal (`bfba740693`) is the regression.** By elimination the recovery
removal cannot touch #1928, and the save/restore's own comment named #1928. The open question is the exact
mechanism by which a fresh Safepoint state during the walk is load-bearing for the drain, given the walk is
otherwise structural; the one external call the walk makes is `f(kyo.input)` (the `Async.Join` link registration)
in the `SuspendArrow` arm, whose cascade may need a live state.

## Bisect in flight (definitive)

Two focused JS/linux-x64 CI runs dispatched to localize the cause:

- `bisect-6d876` at `6d87653b91` (recovery removed, save/restore **present**): CI run 35182706125.
- `bisect-778f` at `778f630155` (recovery + save/restore present, the bracket-leak base): CI run 35183328149.

Expected, if the hypothesis holds: 778f **passes**, 6d876 **passes**, HEAD **hangs** -> the save/restore removal
is the cause, and restoring it (a clean revert of `bfba740693`, with the comment corrected to the real mechanism)
is the fix. If 6d876 **hangs**, the recovery removal is implicated and the analysis above is wrong somewhere;
re-derive from that result.

## Fix + validation plan

- Apply the fix the bisect points to (most likely: restore the tagged-form Safepoint save/restore).
- Re-validate #1928 via CI on linux-x64 JS (no local repro is possible).
- Confirm the contract still holds: `BracketTest` owns-nothing and `ScopeInterruptTest` stay green (JVM + JS).
- The `bfba740693` removal was wrong: "now-dead" was argued, not verified, and the JVM-only validation missed a
  JS/Native-only invariant. The lesson is a Safepoint/abandonment change must be validated on JS and Native, and
  a "dead code" claim about the abandonment walk needs a cross-platform run behind it.

## RESOLUTION (confirmed)

Bisect verdict on JS/linux-x64 (the real failing env):

| ref | recovery | save/restore | #1928 |
|-----|----------|--------------|-------|
| `4ed38c8ac9` (pre-release-model base) | absent | absent-era | PASS (209ms) |
| `778f630155` (bracket-leak base) | present | present | **PASS (78ms)** |
| `6d87653b91` (recovery removed) | absent | **present** | **PASS (118ms)** |
| HEAD before the fix | absent | **removed** | **HANG (2m)** |

So the leading hypothesis is confirmed: **the Safepoint save/restore removal (`bfba740693`) is the regression**,
and the stranded-`Ensure` recovery removal is not involved (6d87653b91 passes #1928 without it). The recovery
analysis stands: no `Arrow.Ensure` in #1928's path.

**Fix applied** (commit `d55aa4da9a`): restore the tagged-form Safepoint save/restore around `collect(v, Arrow.id)`
in `Eval.release`, with the comment corrected (the recovery is gone, so it applies no `Ensure`, but the tagged
walk still needs a live state on a just-interrupted fiber's stopped Safepoint). The restored code is byte-identical
to `6d87653b91`, which the bisect proved passes #1928. JVM re-validated green (BracketTest, ScopeTest,
ScopeInterruptTest). JS/Native validation via CI run 35188597180 (JS+Native on linux-x64 and linux-arm64).

The exact micro-mechanism (which sub-step of the walk needs the live state on JS) is not fully traced: the walk's
one non-structural act is `f(kyo.input)` = `task.interrupts(v)`, the "link comes first" step of the abandonment;
the CI bisect is the evidence that a live state there is load-bearing. This is faithful to the original author's
intent, which added the save/restore for exactly #1735 and #1928.

**Process note**: the removal's error was declaring code dead by argument, then validating JVM-only. A change to
the abandonment walk / Safepoint handling must be validated on JS and Native, since the invariant it protects
(#1928's drain) is only observable there.
