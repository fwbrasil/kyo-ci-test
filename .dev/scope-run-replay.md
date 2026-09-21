# `Scope.run` under a handler that resumes more than once

Status: proposal, nothing implemented. Read-only analysis, 2026-09-21.

## The defect

Two `pendingUntilFixed` leaves in `ScopeTest`, "under a handler that replays". Correct code fails:

```scala
Choice.run {
    Scope.run {
        Choice.eval(1, 2).map(n => Scope.ensure(release(n)).andThen(n))
    }
}
// expected: Chunk(1, 2), both releases run, each once, after both branches
// actual:   branch 2 is refused with Closed
```

## Cause

`Scope.run` closes its scope in two places:

```scala
ContextEffect.handle(Tag[Scope], ...)(v)
    .handle(Abort.run[Any])
    .map { result =>                                   // (A) runs at the end of EVERY branch
        finalizer.close(result.error).andThen(finalizer.await).andThen(Abort.get(result))
    }
    .handle(Sync.ensure(finalizer.close))              // (B) a Bracket region: runs once
```

`Choice.run` sits outside `Scope.run` and replays the continuation that contains (A). Branch 1 reaches (A) and
closes the scope. Branch 2 registers on a closed scope and is refused.

(B) is already right. It is built on the kernel's `Bracket`, whose contract under a replaying handler is pinned by
passing kernel tests: `BracketTest` "a Choice-shaped clause runs every branch against the live resource" runs three
branches against one resource and releases once, after the last.

## Why (A) cannot simply be deleted

(A) does two things (B) cannot:

1. It waits for the scope's async finalizers (`finalizer.await`). That is the backpressure on a NORMAL exit. A
   Bracket release takes no effects, so (B) can start the drain but not wait for it.
2. It closes with the real error. The comment in `Scope.run` says (B)'s ending carries nothing on a normal return
   and an abandonment panic for a typed abort.

## Proposed shape

Close only from the region's release. Wait after the region, and only when the scope is already closed.

```scala
Sync.Unsafe.defer {
    val finalizer = Finalizer.Unsafe.init(closeParallelism)
    val outcome   = AtomicRef.Unsafe.init[Maybe[Error[Any]]](Absent)     // per run, written by the branch that ends
    ContextEffect.handle(Tag[Scope], ...)(v)
        .handle(Abort.run[Any])
        .map { result => outcome.set(result.error); result }             // record, do not close
        .handle(Sync.ensure(finalizer.close(outcome.get())))             // the ONLY close, once per extent
        .map { result =>
            // after the region: wait only if the release has already closed the scope
            finalizer.awaitIfClosed.andThen(Abort.get(result))
        }
}
```

- One run of the extent (every ordinary use): the region ends, its release fires right there and closes the scope,
  the step after the region finds it closed and waits. Same behavior as today, backpressure on normal exit kept.
- A replayed branch: the region is a resumed remainder, so its release belongs to the handler's end and has not
  fired. The step after the region finds the scope open and does not wait. After the last branch the handler ends,
  the release fires once, the scope closes and drains detached.
- Abnormal exit and abandonment: the release closes, nobody waits. Unchanged ("no backpressure on failure").

The pending leaves already expect the detached drain under replay: both wait for the releases with
`assertEventually`.

`awaitIfClosed` is new on `Finalizer`: `await` when the scope is closed, `()` otherwise.

## Not verified, to check before building on it

1. That the release of a region at its clean end runs before the step after the region. `Eval.loop` reads that
   way (`hc.complete`, then `drainCleanOwn(stack.takeReleases(top))`, then the pop and the continuation), not run.
2. That for a resumed remainder the release is NOT owed at the branch's clean end (`stack.owesAny` false there),
   which is what makes the "scope still open" check tell the two cases apart. Read, not run.
3. The error a typed abort hands the close. The `outcome` slot above is there for it; a replayed extent has one
   slot and several branches, so the last branch to end wins. Whether that is the right error for the finalizers
   of a replayed scope is a semantic choice.
4. `Choice.run` uses `ArrowEffect.handle`, the kernel tests use `handleCont`. The leaves report `Closed` from the
   scope's finalizer, not from the bracket, so the bracket side appears to hold; not confirmed by a run.

## Proof plan

The two pending leaves are the reproduction. With the change in, they report "now passes" and the markers come
off. Then full `kyo-coreJVM/test` and `kyo-coreJS/test`, since every `Scope.run` in the tree goes through this.
