# Advice on the open issues at f7cbfde9eb

Held-out reading of `issues.md`, the sources it names, and the pins. Nothing was run. Where a
recommendation would be stronger with a run, the run is named at the end as a next step.

## Summary

Piece P should come out, not be extended. The kernel's own law says `suspendWith(E, i)(f)` is
`suspend(E, i).map(f)` fused for speed, so nothing composed after `f` is fused with the delivery
of the answer; the "step fused with the delivery" for a join is the crossing's park, and the fold
`f` is the first step of the resumed slice, after both polls. A walk that applies `f` runs user
code after the interrupt outside every region, which is the thing the base's walk law forbids, and
it cannot do so correctly anyway: every fiber spawned through `IOTask.apply` answers its join with
a `Snapshot` node (the contextual isolate is always composed), that node reaches the continuation
raw because `Result.Success` is transparent, the boundary's one-argument `cont(r)` nests it
through the lift, and the walk's two-argument apply at `Any` cannot, so the walk misreads data as
an unsettled input and stops. The `ScopeInterruptTest` pin passes only because a plain `Promise`
holds a plain `Int`, a shape no real fiber produces. The delivery of an answer belongs to the
evaluator on resumption, the scheduler's abandonment only links, and the walk only collects
regions and offers a settled value to an `Ensure` standing first. The pool's defect is then a
consumer defect with a known shape, already used at `Connection.scala:386`: the owner registers
the release against the handoff, the take promise, in its own step before it waits, so no window
exists on any platform. For Issue 2 the body's own ending should stand: a value or a typed abort
reached in the slice the interrupt landed on completes the promise, the interrupt owns only a
remainder and the throw a blocked carrier's interrupt produces; `Fiber.interrupt`'s `true` means
"this call stopped the fiber", and `getResult` says how it ended. For Issue 4, two stops aimed at
one thread that name different slices combine into a wildcard, which is honored wherever either
would be, costs at most one spurious park, and needs no cross-thread read. Issues 5 and 6 are
environment work with a concrete approach each. Three things `issues.md` did not name are listed
under "Missed": the walk applies an `Ensure` unguarded so a throwing registration escapes
`abandon` and leaves the promise pending forever; the walk's delivery would run `Fiber.use`'s and
`Async.use`'s user function after the interrupt; and the boundary's correctness for a
computation-valued answer rests on the lift firing at `cont(r)`, which the Batch diagnosis called
an accident and which deserves an explicit spelling.

## Corrections to issues.md, and what it missed

### Corrections

1. The walk does not reach the fold. `issues.md` says the walk applies the join's continuation,
   the fold yields the `Snapshot`, and `ensureMap` builds `Effect.defer(Snapshot, Ensure, rest)`.
   The suspension the remainder stands at is the re-raised one from `IOTask.boundary` line 149,
   whose fused transform is `r => cont(r)` where `cont` is the crossing. `Eval.release` line 707
   applies it with the two-argument `apply(v: A < S2, cont)` at `Any`, where the lift cannot fire
   (`Any < Any` is `Any`), so the `Snapshot` arrives as a `Pending` and takes the suspension's
   `Pending` arm at `ArrowEffect.scala:110`: `Effect.defer(kyo, this, cont2)`. The walk then
   descends into that deferral's value, meets the `Snapshot`, and returns. The fold, the crossing
   and the `Ensure` are never reached. Same outcome, but the mechanism matters: it is the missing
   lift, not the fold, that stops the walk, and even with the lift the fold's output is a
   computation the walk cannot evaluate (see Issue 1).

2. The `ScopeInterruptTest` pin is not evidence for any fiber. `issues.md` notes it joins a plain
   `Promise`. The stronger statement: every task built by `IOTask.apply` answers its join with a
   `Pending`. `Fiber.internal.initUnscoped` uses `isolate.crossing`, which is
   `Contextual.andThen(this)` (`Isolate.scala:219`), and `Contextual.restore` (`Isolate.scala:291`)
   always yields a `SnapshotWith`. `Result.succeed` stores it raw (`Result.scala:48,179`). Only
   `IOTask.detached` produces a raw value. So the fused delivery piece P implements is exercised by
   no `Fiber.get`, and the JS SQL leaf is the general case, not an edge.

3. Issue 4's window is narrower than "the slice runs until it parks or ends on its own", and the
   loss is different. A stale `Stop` is cleared by the owner's next `Safepoint.get()`
   (`Safepoint.scala:122-130`: not honored, so `slots.set(h, s.thread)`), which every fused step on
   a settled value calls (`map`, `Arrow.apply`, `Effect.deferInline`). What is lost is every
   request the stale stop answered `true` in the meantime: `IOTask.interrupt` line 214 and
   `stopSlice` line 180 issue one `Safepoint.stop` each and never retry. The interrupt is delayed
   until the slice ends by budget, by a later preemption tick, or by its own park; the preemption
   is skipped once. A real defect, bounded rather than unbounded.

4. The `Result.Error` arm of `run` (`IOTask.scala:359-364`) drops a typed abort as well as a value:
   the abort arm of the boundary also goes through `finish`. Issue 2 covers both endings.

### Missed

M1. The walk applies an `Ensure` unguarded. `Eval.release`'s `ensuring` (line 651-654) calls
`step(Nested.unnest(v))`, user code, with no `try`. `releaseCollected` guards `released`, but this
site is not guarded. `Scope.Finalizer.ensureUnsafe` throws `Closed` by contract on a closed scope
(`Scope.scala:289`), so a registration reached by a walk after the owning scope closed throws out
of `Eval.release`, out of `abandon` (`IOTask.scala:416`) before `interruption.foreach(settleInterrupt)`
on line 434, and out of `run` (the `catch` on line 320 covers only `Eval.partial`). The promise
then stays pending forever, the cascade never fires, the regions collected before the throw are
never released, and the worker gets the exception. Piece P makes this reachable across fibers (a
child's take delivered on its own abandonment registering on a parent scope that already closed).
Whatever Issue 1 decides, `ensuring` needs the guard `released` has: catch a non-fatal throwable,
attach it to `ex` as suppressed, continue.

M2. The walk's delivery runs user code. `Async.use(v)(f)` and `Fiber.use(f)` put the user's `f`
inside the join's fused transform (`Async.scala:812-813`, `Fiber.scala:275-276`). Piece P applies
that transform in the walk, after the interrupt, outside every region. That is the case the base's
walk law was written to forbid ("a step of the caller's code after the interrupt acquires what
nothing will release", `Eval.scala:606-611`), and no pin covers it because the three delivery pins
use `askWith` with a pure body.

M3. The boundary's handling of a computation-valued answer is correct only through the lift.
`cont(r)` at `IOTask.scala:133` resolves to `Arrow.apply(v: A)` (line 63), which passes a raw
`Result` into an `A < S2` position, and the lift nests a runtime `Pending`. The crossing then takes
its settled path and the restore runs under the reinstalled regions, which is what a `Var` restore
needs (`Var.isolate.update`'s `Var.setWith` must find the parent's region). Commit 5985139c59
called exactly this resolution "delivered as data by accident of erasure" when Batch depended on
it, and Batch was changed to nest explicitly. The boundary should say what it means:
`cont(Nested.nest[Result[Nothing, C], Any](r))` with a representation-assertion comment, and a
pin in `IsolateTest` or `FiberTest` that a fiber's restore performing a `Var` write under a
crossing lands on the parent's region. Without the explicit spelling, the next overload change or
`Result` change silently moves every fiber's restore to the clause's level, where its effects are
unhandled.

M4. Under piece P, `Eval.release`'s reporter runs `input(this)`, then `promise.poll()`, then
`removeInterrupt`; the base's link-only reporter is what `abandon`'s doc still describes ("the
release runs no step of the remainder"). The doc and the code disagree on the tip.

## Issue 1: where an answer that already arrived is delivered

### The equation

`ArrowEffect.suspendWith` documents itself as the fusion of `suspend(...).map(f)`
(`ArrowEffect.scala:82-85`). So, in the public combinators:

    suspendWith(E, i)(f).ensureMap(g)  =  suspend(E, i).map(f).ensureMap(g)

Two consequences follow, and both are already the kernel's law elsewhere:

- `f` is a step. A stop may land before it. The walk must not run it (`EvalTest` "an acquire the
  park stopped in front of is neither run nor released", "does not run a deferral to reach the
  operation behind it").
- `g` is fused with `f`'s output, not with the delivery of the answer. `Arrow.ensure`'s contract
  (`Arrow.scala:183-190`) is "applies as the value arrives"; the value `g` waits on is what `f`
  produces, and for a join `f` is `_.fold(...)`, whose output for every real fiber is a `Snapshot`
  computation. `g` is one whole evaluation away from the answer.

So "the step fused with the delivery" of a join answer is nothing but the delivery itself. At the
top of the stack the clause's `cont(r)` runs `f` and `g` inside one call before the arm's poll
(`Eval.scala:120`); that is the fast path being ahead of the law, which is allowed. Under a
crossing the delivery is `Park(Effect.defer(r, kc, resume), entries)` (`PendingInternal.scala:91-96`),
`kc` is the first step of the resumed slice, and the arm's poll on the clause's answer and the
`Defer` arm's poll (`Eval.scala:77`) both precede it. That is required, not incidental:
`BracketTest` "a bracket failing after a crossing still releases before the recovery" is true only
because `kc` runs under the reinstalled regions, and a stop at a step boundary is the general law.

Therefore `Scope.acquireRelease(fiber.get)(release)`, which is
`Sync.defer(suspendWith(Join)(fold)).ensureMap(register)`, never had the atomicity
`Scope.acquireRelease` wants, at top or under a crossing, and no walk machinery can give it one:
the answer lives in a promise that outlives the fiber, the registration is at least one step and
one evaluation away, and the value in `r` is a copy of what the promise still holds. Ownership of
a value that already exists elsewhere is the promise's, until the owner registers against the
promise.

### Where it lives

| piece | belongs to | does |
|---|---|---|
| delivery of a completed promise's answer | the evaluator, on resumption, through the boundary clause's `cont(r)` | the lift, the crossing, the reinstall, `kc`, the `Ensure` that follows, under the regions |
| abandonment | the scheduler, `IOTask.abandon` | links the awaited promise so the cascade reaches it, releases, completes with the interrupt |
| the walk, `Eval.release` | the kernel | collects regions, offers a settled value to an `Ensure` standing first (#1820), runs nothing else |

### The change

Revert piece P's kernel and scheduler halves; replace its pool half with the register-first shape
of Issue 3.

Files and methods:

- `kyo-kernel/.../internal/Eval.scala`: `release` (the reporting overload's `f` returns to
  `[C] => I[C] => Unit`; the private overload's `f: Any => Unit`), `collect` loses the `delivered`
  parameter and the `case _ if delivered` arm, the `SuspendArrow` arm returns to
  `effectTag.foreach(t => if t <:< kyo.tag.erased then f(kyo.input))`. The scaladoc on the
  reporting overload returns to the base's two sentences. Separately, M1: `ensuring` guards
  `step(...)`.
- `kyo-core/.../scheduler/IOTask.scala`: `abandon`'s reporter returns to
  `[C] => input => discard(input(this))`. M3: the boundary's `cont(r)` nests explicitly.
- `kyo-sql/.../SqlConnectionPool.scala`: `takeSlot`, `withSlot`, `leaseScoped` take the shape in
  Issue 3.
- `reviews/robustness/derivation.md`: the piece P section is withdrawn with the reason above.

Pins flipped or removed, with the reason:

| pin | action | reason |
|---|---|---|
| `EvalTest` "delivers an answer the reporter already holds to the release waiting on it" | remove | pins a delivery the law does not give: `ask.ensureMap(g)` has `kc = id`, so the base's `ensuring` already finds `g`; the reporter's answer adds nothing the resumption would not do differently |
| `EvalTest` "delivers through the operation's own continuation, the one step fused with the delivery" | remove | pins running `askWith`'s `f` in the walk, which is `suspend.map(f)` by the law and must not run |
| `EvalTest` "a delivery runs nothing past the step fused with it" | remove | its premise is the previous pin |
| `EvalTest` "an acquire the park stopped in front of is neither run nor released" | keep, reporter returns to `Unit` | unchanged law |
| `ScopeInterruptTest` "a resource an async acquire produced is released when the acquiring fiber is abandoned before it resumed" | rewrite | the shape it uses, a release registered after a join, is the shape the law says is not atomic; the rewritten pin registers before the join (see Issue 3 pins) and keeps the deterministic last-registered-first ordering |
| `BracketTest` three crossing pins | keep | the crossing is unchanged |
| the #1820 block in `EvalTest` and `ScopeTest` | keep | same-step pairings, the walk's one legitimate application |

Pins needed (deterministic, cross-platform):

- `EvalTest`, release block: "an answer delivered to a fused suspension runs nothing" with
  `askWith { a => ran = true; a }.ensureMap(...)` and a reporter that only records; assert `!ran`
  and no release. It is the negative of the removed pin and states the law.
- `EvalTest`: "a throwing release found standing first is contained" for M1: an `Ensure` that
  throws; assert `Eval.release` returns, the throwable is suppressed on `ex`, and a context region
  collected before it is still released.
- `FiberTest` or `IsolateTest`: "a fiber's restore that writes a Var under a crossing lands on the
  parent's region" for M3: `Var.run(0)(Var.isolate.update.use(Fiber.init(Var.set(1)).map(_.get)))`
  under a `Scope.run` so the join crosses; assert the parent's final state is 1.
- `ScopeInterruptTest`: the rewritten pin, below under Issue 3.

Rejected:

- Threading `delivered` through the walk and spending it on the first settled deferral (piece P):
  runs a step of user code after the interrupt, cannot evaluate a `Snapshot`, bypasses the lift,
  and does not cover a stop landing on the JVM before the crossing's deferral unfolds.
- Nesting the answer in the walk (`kyo.cont(Nested.nest(answer), id)`): fixes the misread but the
  fold's output is still a computation; the walk stops at the `Snapshot` one level later.
- Making the `Defer` arm skip its poll for a deferral built by a crossing: needs a marker or a
  node kind to tell that deferral from any other, which the skill names as the off-path signal,
  and a stop landing over `Thread` between the reinstall and the unfold is exactly the case it
  would have to special-case.
- Running the crossing's `kc` at the clause's level so it is fused with the delivery: breaks
  `BracketTest` "a bracket failing after a crossing still releases before the recovery" and every
  restore that performs an effect the interior regions answer.
- Delivering on abandonment by resuming the fiber for one slice with a stop pre-armed: a resumed
  slice runs steps of the body after the interrupt, the same objection as the first, on the
  evaluator instead of the walk.

### The five traces

Under the change above: the walk runs nothing but an `Ensure` standing first, the boundary
delivers on resumption, the pool registers before it waits.

(a) The JS SQL interleaving, `Snapshot`-valued answer. The query fiber registered
`Scope.acquireRelease(Sync.Unsafe.defer(takeFiber().safe))(drain)` in its own step before the
timeout child existed, so the parent's finalizer owns the take promise. Interleaving as in
`issues.md`: the child takes the permit synchronously (the promise completes with `()`), the
parent's release runs, `abandon` walks `Park(reraised join, [boundary])`: the `Park` arm collects
the `Scope` region and its finalizer; the `SuspendArrow` arm reports the child's join, the reporter
links and returns; `releaseCollected` closes the scope; the drain finds the take promise holding
`Success(())` and offers the permit back on the detached drain fiber; `settleInterrupt` cascades
to the child, whose result, the `Snapshot`, is never applied by anyone. `client.close` finds both
permits within the macrotask queue's next turns. Runs: the walk's collection, the finalizer on
the drain fiber. Parks: nothing; the remainder is dropped.

(b) A throw inside the step after a crossing answer. In the evaluator the throw happens in `kc`
under the reinstalled regions, `guarded` catches it, `recovered` unwinds the bracket and offers the
handler's `recover` arm: the `BracketTest` pin as today. In the walk nothing runs, so nothing
throws; the one user-code site that remains, `ensuring`'s `step`, is guarded by M1.

(c) An interrupt over `Thread` on the JVM as a fiber resumes at a crossing join, before the
crossing's deferral unfolds. The `Defer` arm parks `Defer(Nested(r), kc, resume)` under the crossed
regions and the boundary. `abandon` walks it: regions collected (the parent's `Scope` finalizer
among them), the deferral's value is settled and is offered to `leftmost(kc.chain(resume))`, which
is the fold, not an `Ensure`, so nothing runs; the finalizer registered before the join drains the
promise. `r` was a copy; the promise still held the value; nothing is lost. Runs: the finalizer.
Parks: the remainder, then released.

(d) A fiber abandoned while its join's promise is still pending. The walk reports the operation;
the reporter runs `input(this)`, which links this task to the promise (`Async.scala:834-836`), and
returns. `settleInterrupt` cascades: a child fiber is interrupted and abandons itself; a take
promise becomes an error and the channel keeps its permit (verify the channel contract, Issue 3).
Runs: nothing of the body. Parks: nothing.

(e) A fiber abandoned whose remainder is a park at a `map`. The walk meets `Defer(v, mapStep)`,
`v` settled, offers it to `leftmost`, which is the map's transform, not an `Ensure`, and runs
nothing. As the base.

## Issue 2: a body ending in the slice its interrupt landed on

Position: the body's own ending stands. A value or a typed abort the body reached completes the
promise; the interrupt owns a remainder, and the throw its delivery produces on a blocked carrier.
The other position, the interrupt owns any ending in its slice, is the tip's, pinned by `FiberTest`
"an interrupt taken on the running slice owns the ending".

Defense. `finish` is documented as "the one place an ending of the body completes the promise".
An ending is a fact of the computation: the boundary's done arm ran `restore` and the region is
gone; the abort arm settled the region with the body's own failure. The interrupt's claim is on
what has not run yet, which after an ending is nothing. Dropping the ending drops what it carries,
and a value can be a resource with no other owner: the take's permit in `Async.timeout(ch.take)`
has left the channel and exists only as that value. `main` completed with the value and let the
interrupt's CAS race it, so the drop was one CAS wide; the tip made it a whole slice wide. Taking
the value in every case makes it zero wide, strictly better than `main`. The interrupt's `true`
still means what a caller uses it for: nothing of the body runs past the next safepoint, the
remainder is released, and a second interrupt is refused. Whether the fiber ended by interrupt is
`getResult`'s to say, and it already is: a scoped fiber's release (`Fiber.scala:131-133`) reads
`result.error` and closes the scope clean for a body that finished, which is the right verdict
for a body that finished.

The throw path keeps the interrupt, by equation rather than by exception. A body that cannot poll
(a carrier blocked in `IOPromise.block`) is stopped by the monitor interrupting the thread, and the
`InterruptedException` at `IOPromise.scala:285` is that delivery arriving as a throw. It is an
ending the interrupt produced, not one the body reached, so it belongs to the interrupt. `FiberTest`
"cooperative interruption: onInterrupt fires after carrier is interrupted" needs exactly this:
`onInterrupt` fires only through `flushInterrupt`, which only `settleInterrupt` runs.

Where it lives: `IOTask.finish` completes while `isPending()`, without the `!interrupted` guard.
`run`'s catch (`IOTask.scala:320-336`) keeps a guard for the non-fatal throw with the blocked
carrier as its stated reason: fatal completes regardless as today; non-fatal on an interrupted
slice is dropped in favour of the interrupt; non-fatal otherwise finishes. The `Result.Error` arm
of `run` is unchanged: `abandon` releases and `settleInterrupt` returns `false` when `finish`
already completed. `Fiber.interrupt`'s scaladoc (`Fiber.scala:340-356`) says: `true` when this call
stopped the fiber, no step of its body runs past the next safepoint and its remainder is released;
the result is the interrupt unless the body had already reached its ending in that slice, which
`getResult` reports.

Pins flipped: `FiberTest` "an interrupt taken on the running slice owns the ending" becomes "a
body that reaches its value in the slice its interrupt landed on completes with the value", same
setup, `assert(result == Result.succeed(42))`, plus `second == false` for a second interrupt.

Pins needed:

- `FiberTest`: "a typed abort reached in the interrupted slice is the result": body requests its
  own interrupt then `Abort.fail(e)` in the same step; assert `Failure(e)`.
- `FiberTest`: "a throw on an interrupted slice is the interrupt's ending": body requests its own
  interrupt then throws; assert the result is the interrupt's error and `onInterrupt` fired.
  Cross-platform, no blocking, unlike the carrier pin.
- `AsyncTest` or `ChannelTest`: "a permit taken in the slice the timeout's interrupt landed on
  reaches the parent": a channel with one permit, a body `Sync.defer { self.unsafe.interrupt(); () }`
  fused with the take's step through `takeWith`; assert the parent's `Abort.run(child.get)` holds
  `Success(())` and the permit can be offered back, so the channel's count is restored.

Rejected: keeping the guard for the value and dropping it for the throw (the tip's asymmetry
inverted) has no equation; keeping the tip's rule and fixing the pool alone leaves every
resource-valued body under `Async.timeout` and `Async.race` with a slice-wide drop.

## Issue 3: a resource acquired on one fiber and owned by another

Rule: a value acquired on one fiber and owned by another is owned by neither until the owner has
registered its release, so the owner registers against the handoff, the promise, in its own step,
before it waits. `Connection.scala:386-435` is the precedent: the close is registered before the
connect is launched, and the cell breaks the ordering cycle. The kernel needs nothing new; the
shape is `Scope.acquireRelease` with a synchronous acquire whose value is the promise.

For the pool, `withSlot` and `leaseScoped` become:

    Scope.acquireRelease(Sync.Unsafe.defer(slotCh.unsafe.takeFiber().safe)) { take =>
        Sync.Unsafe.defer {
            val p = take.unsafe
            // a take still pending is withdrawn; a take that completed hands its permit back
            discard(p.interrupt(Result.Panic(Interrupted(frame))))
            p.poll() match
                case Present(Result.Success(())) => discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                case _                           => ()
        }
    }.map(take => Async.timeoutWithError(config.acquireTimeout, ...)(take.get))

`takeFiber` (`Channel.scala:493-498`) enqueues the taker and flushes, so a buffered permit
completes the promise in the same step; the registration and the promise are one step, which is
`Scope.acquireRelease`'s #1820 shape. The timeout bounds only the wait, so the child of
`Async._timeout` never owns the permit; its result is a `Snapshot` nobody needs to apply. The
`Abort.run[Closed]` and the timeout-to-`SqlConnectionAcquireTimeoutException` mapping wrap the
join as today.

The three windows:

- Parent abandoned after the child completed: the take promise holds `Success(())`; the drain
  offers it back. The child's `Snapshot` is dropped unapplied.
- Parent abandoned before the take completed, the take then completing on its own: the drain
  interrupted the take promise first, `Channel`'s flush finds the taker refused, and the permit
  stays in the channel. The child, joining a promise now in error, ends with that error or is
  interrupted by the cascade; either way it holds nothing. Requires the channel contract that a
  value a withdrawn taker refused is kept; pin it (below).
- Child's take delivered on the child's own abandonment: does not exist. The child has no take;
  it joins the parent's promise.

`Scope.Finalizer.ensureUnsafe` on a closed scope runs the finalizer detached, logs a warning and
throws `Closed` (`Scope.scala:277-290`). For the producer-side variant (the child registering on
the forked finalizer in the take's step) that path is hit whenever the parent closed first, which
under interrupt is a routine race: the permit comes back, but every such race logs a warning that
reads as a bug, the throw ends the child with a panic, and by M1 a throw from inside a walk's
`Ensure` escapes `abandon`. Acceptable as a backstop, not as the design. The consumer-side shape
never registers on a closed scope because the registration precedes the wait in the parent's own
open scope.

Pins needed:

- `ScopeInterruptTest` (replacing the piece P pin): "a release registered before a join runs when
  the joining fiber is abandoned after the promise completed": same setup as the current pin,
  `Scope.acquireRelease(Promise.init)(drain)` before `child.get`, `child.onComplete(parent.interrupt)`
  registered after the park so it runs first; assert the drain saw `Success(42)` once.
- `ChannelTest`: "a permit offered to a withdrawn taker stays in the channel": `takeFiber` on an
  empty channel, interrupt the promise, `offer(())`, assert `size == 1` and a later `take` gets it.
- `SqlConnectionPoolTest` or the JS SQL suite: "the acquire-timeout child's permit is returned when
  the parent is interrupted at each interleaving": pool of one permit, a second lease parked on the
  take, interrupt the first holder at the three points above (before the take completes, after it
  completes, after the parent parked); assert the channel's count returns to one. Deterministic on
  JS through the macrotask order; on the JVM through `Promise` callback ordering as the current
  pin does.

Rejected: the producer-side registration (child registers in the take's step on the forked
finalizer), for the closed-scope race above; wrapping the whole take in `Async.timeout` with the
release registered after it (the tip), for Issue 1; a `Fiber`-level "acquire through a join"
combinator, since the existing `Scope.acquireRelease` over a synchronous promise acquire is the
shape and a new surface would name what the guide should teach.

## Issue 4: a fresh stop over a stale one

Rule: two stops aimed at one thread combine into the stop that is honored wherever either would
be. Same slice, the pending one stands. Either a wildcard, the wildcard stands. Different slices,
the requester replaces the pending one with a wildcard, because from another thread the slot
cannot tell which of the two names the running slice, a stop that lands on a slice that was not
its addressee costs that slice one park, and a stop that never lands costs an interrupt or a
preemption.

Where it lives: `Safepoint.stop(thread, slice)`, the `pending: Stop` arm (`Safepoint.scala:230-239`).
The `(thread ne Thread.currentThread())` clause goes; the CAS replaces with
`new Stop(thread, null)` when the slices differ, and with `new Stop(thread, slice)` only when the
pending one is stale and the requester is the owner, which is a special case the wildcard already
covers, so it goes too. One arm, no cross-thread read of `slices`. The owner's own supersede pin
still holds through the wildcard.

Cost: a wildcard left by two stale stops lands on the next slice on that thread. `Eval.partial`
consumes it at entry and returns the input unevaluated, `run` reports `Task.Preempted`, and the
task is rescheduled: one wasted round, rare, and harmless by the safepoint's own contract ("a run
becomes a slice without the computation knowing"). `endSlice` clears only a stop naming the
ending slice, so the wildcard survives to the next entry, which is the intent.

Pins flipped: `SafepointTest` "a late stop from another thread does not displace the running
slice's own" still passes (it asserts `stopped` and one consume), but its name and comment
describe the old mechanism; rename to "a late stop from another thread combines with the running
slice's own into one it honors".

Pins needed, jvm-native:

- "an interrupt's stop from another thread over a stale stop lands on the running slice":
  `beginSlice(slot, slice)`; `stop(owner, departed)` (unhonored); from a second thread
  `stop(owner, slice)`; assert `stopped(slot)`, `consumeStopped(slot)`, then `!consumeStopped`.
- "a preemption over a stale stop is not skipped": same with the second thread requesting
  `stop(owner, otherDeparted)`; assert `stopped(slot)`.
- "two stale stops cost the next slice one park, not an interrupt": two departed slices, then
  `endSlice`, `beginSlice(next)`; assert `consumeStopped` is `true` once and the evaluation that
  follows completes (the shape of "a stop addressed to a departed slice does not short-circuit
  the next evaluation").

Rejected: reading `slices(idx)` from the requester to decide staleness, since `slices` is
owner-written with no fence the requester can rely on and a stale read reproduces the swallow;
retrying `Safepoint.stop` from `IOTask.interrupt` until honored, which spins against a slice that
may not poll; a per-thread queue of stops, a new carrier for a one-word protocol.

## Issue 5: the Native scheduler test binary crashes

Approach only. The crash is in `stackOverflowHandler` with `si_addr=0x0` after `InternalTimerTest`,
and the suites are green alone, so it is cross-suite state, almost certainly a thread one suite
leaves running (timer or blocking-monitor threads) that receives a signal while the runner tears
down, or a thread not created through Scala Native's `Thread` whose stack bounds the guard-page
handler does not know. Steps: run the pair `InternalTimerTest` then `BlockingMonitorTest`, then
the triple with `WorkerTest`, to find the minimal ordering; check that each of those tests stops
what it starts (`InternalTimer`, `BlockingMonitor`, workers) and that their threads are daemon;
run the test binary under `lldb` with the same runner arguments to get a symbolized trace of the
faulting thread; compare the Scala Native version's known handler issues on macOS arm64 (the
signal 6 alone in `BlockingMonitorTest` points at a `Thread.interrupt` of a parked native thread).
The CI Native leg on linux is the discriminator between a platform bug and a leaked thread; if
linux is green, file it against the local toolchain and pin the thread-cleanup fix regardless,
since a leaked thread is a defect on every platform. Not the branch's, but owned.

## Issue 6: scalafmt fails for one Native source

Approach only. Set `scalafmtDetailedError := true` and `scalafmtLogOnEachError := true` in the
build (sbt-scalafmt names the file and the parse position with them), or run the scalafmt CLI
over `git ls-files '*/native/src/**/*.scala'` with `--check`, which names the file. Expect a
dialect parse failure on a Native-only construct (`extern`, `CFuncPtr` lambdas, `@static`) in a
module whose format task runs between kyo-config and kyo-stats-registry in sbt's order; fix with a
`fileOverride` for `native/` in `.scalafmt.conf` or by rewriting the construct. Pin by making the
Native format check part of the local build script so it cannot fail silently again.

## Recommended runs, in order

1. After reverting piece P and guarding `ensuring`: kernel JVM, JS, Native; core JVM and JS.
2. After the pool change: `kyo-sql-postgresJS/testOnly kyo.SqlClientInterruptTest`, then
   `kyo-sql-postgresJVM/test` and `kyo-sql-mysqlJVM/test` against the containers.
3. The M3 pin before the explicit nest, to see it pass on the tip through the lift, then after.
4. The new `SafepointTest` pins on JVM and Native.
5. `AsyncTest` "with isolates" and `FiberTest` after Issue 2, since both read fiber results
   under interrupt.
