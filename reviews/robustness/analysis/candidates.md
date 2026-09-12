# The author's candidate designs for issues.md

Not for the held-out advisor. Written before the advice arrives, so the two can be compared.

## Issue 1

The delivery has to run in the evaluator: only the evaluator can evaluate a `Snapshot`, and only
under the reinstalled regions does a throw in the fused step unwind through them. Three polls stand
between the answer and the step fused with it:

- `Eval.partial` consumes a pending stop at entry and returns `v` unevaluated;
- the `ContHandler` arm polls after the clause's answer, and the crossing's answer is a `Park`;
- the `Defer` arm polls before unfolding, and the crossing parks `Effect.defer(v, kc, resume)`.

Candidate: the abandonment resumes the remainder in the evaluator with the stop already pending
(a new `Eval` entry that arms the stop instead of consuming it), then `Eval.release` walks what
parked. For the fused step to run under that stop, the evaluator must not treat a delivery as a
step boundary: a `Park` a clause answers with is entered rather than parked on, and a deferral
whose head arrow is an `Ensure` is unfolded rather than parked at (its value's own deferrals still
poll). The crossing keeps its parked deferral, so throws still unwind through the crossed regions.
With that, `Eval.release`'s reporting overload, the reporter's `Maybe[O[C]]` answer and the
`delivered` flag go away; the boundary's join arm links and delivers as it always has.

Rejected on the way: applying `kc` eagerly in `crossing` (a throw in the step would escape the
crossed regions, pinned by `BracketTest`); a flag on the boundary's `Present` arm (a volatile read
on every ready join).

## Issue 2

A body that ended with a value completes with it; nothing else can release what the value carries.
`finish` completes whenever the promise is pending; the throw arm keeps the interrupt as the ending
while an interrupt is taken, for the blocked carrier's `InterruptedException`. `Fiber.interrupt`'s
Boolean becomes "the interrupt was taken"; `getResult` and `interruptAwait` say how the fiber ended.
The `FiberTest` pin flips to "a body that ends in the slice its interrupt landed on completes with
its value".

## Issue 3

Register the return in the child's own fused step: `takeSlot` wraps `Scope.acquireRelease(take)(offer)`
inside the timeout, so the registration lands in the parent's scope through the context, in the step
the permit arrives in, on whichever fiber runs it. When the parent's scope has already closed, the
closed-scope path runs the return detached (with the existing warning). Alternative considered: the
parent registers, before the spawn, an `onComplete` on the child that returns a permit the child ever
produces; needs the child's handle, which `Async.timeout` does not expose.

## Issue 4

A slot holds a chain of stops rather than one: a request prepends, the owner honors any element
addressed to its slice or a wildcard, `consumeStopped` and `endSlice` clear the chain. No request is
ever answered by a stop that is not honored, and the owner-supersede rule becomes unnecessary.

## Issue 5

Bisect by suite order on Native (the crash needs the whole suite), then by leaf within the last
suite before the crash; read the Native `BlockingMonitor` thread-state probes for the signal 6.

## Issue 6

Run scalafmt directly over `kyo-config/native` and `kyo-stats-registry/native` to name the file.
