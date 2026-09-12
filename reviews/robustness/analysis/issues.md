# Open issues after the fourth walk, at f7cbfde9eb

The facts and the evidence, without the author's proposals. A held-out advisor reads this file, the
sources it names, and nothing else from the session. Every path is relative to the `robustness`
worktree unless stated.

## Where the tree stands

The tip carries piece P (commit a5b7d2a512, then 0beb288861): `Eval.release` gained a reporting
overload whose reporter may answer the operation it reports, and the walk delivers that answer to
the operation's own continuation and runs "the one step fused with the delivery" before continuing
to collect. `IOTask.abandon` hands back a joined promise's result when the promise already holds
one. The pins for it are in `EvalTest` ("delivers an answer the reporter already holds to the
release waiting on it", "delivers through the operation's own continuation, the one step fused with
the delivery", "a delivery runs nothing past the step fused with it") and in `ScopeInterruptTest`
("a resource an async acquire produced is released when the acquiring fiber is abandoned before it
resumed"). All pass on JVM, JS and Native.

Local results on the tip: kernel JVM 1848, JS 1789, Native 1824 green; core JVM 42 suites, core JS
37 suites green; kyo-sql-postgresJVM 82 suites and kyo-sql-mysqlJVM 57 suites green against real
containers. CI run 34719693185 (fwbrasil/kyo-ci-test, branch robustness) is in progress.

## Issue 1: the JS SQL interrupt suite regressed under piece P

`kyo-sql-postgresJS/testOnly kyo.SqlClientInterruptTest`
(`kyo-sql-postgres/shared/src/test/scala/kyo/SqlClientInterruptTest.scala`):

| leaf | before P (walk runs nothing) | on the tip |
|---|---|---|
| interrupting the statement's fiber stops it | 3 ms | 30.0 s |
| an interrupted connect strands no descriptor | pass | TIMEOUT at 2 m |

30 s is `SqlConfig.acquireTimeout`: `client.close` in the leaf's `Scope.ensure` waits for the pool's
permits, and one permit was taken and returned by nobody. The JVM leaves of the same suite pass.

### The mechanism, from a probe run

Scratch prints were added to the walk and to `IOTask.abandon`'s reporter (removed since), and the
"interrupting the statement's fiber stops it" leaf was run on JS. The lines for the query fiber:

```
SCRATCH walk: start Park(Kyo(kyo.Async$package$.Async$.Join, $anonfun.query(SqlClientInterruptTest.scala:51:93)), regions = 1)
SCRATCH abandon: join promise poll=Snapshot(<internal>.<internal>(<internal>:0:0)) interruption=Panic(kyo.Interrupted: Fiber interrupted at SqlClientInterruptTest.scala:54:45 )
SCRATCH walk: stops at snapshot
```

Every other abandonment in the run polled `Absent` and the walk stopped at "join not complete".

What the query fiber stands at: `withSlot` (`kyo-sql/shared/src/main/scala/kyo/internal/client/SqlConnectionPool.scala`,
`withSlot` and `takeSlot`) is `Scope.run { Abort.run(Scope.acquireRelease(takeSlot(...))(offer)) ... }`,
and `takeSlot` is `Async.timeoutWithError(acquireTimeout, ...)(take)`. `Async._timeout`
(`kyo-core/shared/src/main/scala/kyo/Async.scala`) spawns the take on a child fiber through
`Fiber.internal.initUnscoped(v).ensureMap { task => ...; task.get }`, so the query fiber parks on
the child's join.

On JS the scheduler is a FIFO macrotask queue (`kyo-scheduler/js-wasm/.../Scheduler.scala`), which
fixes the interleaving: the test fiber's wakeup is queued when the query fiber releases the latch,
the child is queued when it is spawned, the query fiber parks on the child, the test fiber
interrupts the query fiber (status `Idle`, taken, a release run queued), the child runs, takes the
permit synchronously (the channel has permits), its body ends and its promise completes with the
value, and only then does the query fiber's release run.

The child's result is `A < S2`, the isolate's restore, which is a `Pending.SnapshotWith`
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala`, `restore`): a node that hands the
evaluator's stack to its continuation. The reporter returns `Present(Success(Snapshot))`, the walk
applies the join's own continuation to it outside the evaluator: the fold yields the `Snapshot`, a
`Pending`, and `Scope.acquireRelease`'s `ensureMap` over a `Pending` builds
`Effect.defer(Snapshot, Ensure, rest)`. The walk's `collect` reaches `Pending.Snapshot` and returns:
the registration that returns the permit never runs. A `Snapshot` can only be evaluated with the
stack, that is inside the evaluator.

The `ScopeInterruptTest` pin passes because its acquire joins a plain `Promise`, whose result is
a settled `Int`, never a `Snapshot`.

### The same split exists inside the evaluator

`Pending.Suspend.crossing` (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/PendingInternal.scala`):
a clause's continuation for an operation dispatched from under other regions. Applied to a settled
answer it returns `Park(Effect.defer(v, kc, resume), entries)`: the answer parked as a deferral,
with the operation's own continuation `kc` in front, under the regions to reinstall.

`Eval.loop` (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala`):

- the `Pending.Defer` arm polls `Safepoint.stopped` before unfolding, whatever the deferral holds;
- the `ContHandler` arm polls after the clause's answer ("The stop is honored on the clause's
  answer: one that re-raises the operation would otherwise dispatch straight back here with no
  deferral to park at");
- `Eval.partial` consumes a stop pending at entry and returns `v` unevaluated.

So for a join dispatched from under a region (the fiber boundary is the outermost region, so any
join issued under `Scope.run` or `Abort.run` crosses), a stop pending as the fiber resumes with the
answer parks in front of `kc`: the fold and the `ensureMap` registration do not run. At the top of
the stack the clause applies `kc` itself before the poll. `Arrow.Ensure.apply`
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala`) applies without polling only when its
input is settled; over a `Pending` it builds a deferral, which the `Defer` arm polls before
unfolding.

The abandonment case is therefore the general case: an interrupt landing over `Thread` just as a
fiber resumes at a crossing join, before the crossing's deferral unfolds, parks the delivery, and
the walk then finds `Defer(settled answer, kc, ...)` whose leftmost arrow is the fold, not an
`Ensure`, and offers the value to nobody. The JVM window is narrow; the abandonment path is the
window made deterministic.

`BracketTest` "a bracket failing after a crossing still releases before the recovery" pins that a
throw from the step after a crossing answer unwinds through the crossed regions; the parked
deferral is what makes that true.

## Issue 2: a body ending in the slice its interrupt landed on has its value dropped

`IOTask.finish` (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala`) completes only while
`isPending() && !interrupted`. The slice-end arm reads: "An interrupt landed on this slice. What
the stop left is the remainder, unless the body ran to its end first, in which case the interrupt
still owns the ending." `FiberTest` "an interrupt taken on the running slice owns the ending" pins
it: the body requests its own interrupt and returns 42 in the same step, and the result is
`Interrupted`.

Consequence for a value that is itself a resource: `Async.timeout(ch.take)` hands the permit to the
parent through the child's completion. If the cascade's interrupt lands on the child while its take
completes in that slice, the child ends with the permit as its value and the value is dropped.
The kernel on `main` completed the promise with the value and let the interrupt's CAS race it, so
the drop existed there only inside that CAS window. `Fiber.interrupt` is documented as "Whether the
Fiber was successfully interrupted"; on `main` a `true` implied the result is the interrupt.

The exception path has its own reason for the guard: a blocked carrier's `IOPromise.block` throws
`InterruptedException` once the monitor interrupts the thread, and `FiberTest` "cooperative
interruption: onInterrupt fires after carrier is interrupted" needs the interrupt, not that
exception, to be the result.

## Issue 3: a resource acquired on a child fiber and registered by the parent

`takeSlot` acquires the permit inside `Async.timeoutWithError`'s child and `withSlot` registers its
return in the parent, on receipt, through `Scope.acquireRelease`. Whatever Issue 1 and Issue 2
decide, one window remains: the parent is abandoned while the child's take has not completed; the
parent's regions are released (its scope closes), then the completion's cascade interrupts the
child, and the child's take completes or is delivered on the child's own abandonment. The permit
then exists in the child's promise with nobody to return it. kyo-net's `transport.connect` met the
same problem and registers the close before the launch (`kyo-sql/shared/src/main/scala/kyo/db/Connection.scala`
around line 387).

`Scope.Finalizer.ensureUnsafe` (`kyo-core/shared/src/main/scala/kyo/Scope.scala`) on a closed scope
runs the finalizer detached, logs a warning, and throws `Closed`.

## Issue 4: a fresh stop is swallowed while a stale one is pending

`Safepoint.stop(thread, slice)` (`kyo-kernel/jvm-native/src/main/scala/kyo/kernel/internal/Safepoint.scala`):
when the slot holds a `Stop` for the thread, a request from another thread returns `true` without
replacing it unless the pending one is a wildcard or names the same slice. A pending `Stop` naming
an ended slice (a late delivery: the interrupter read `status == thread`, the slice ended, the next
slice began on the same thread, then the delayed `Safepoint.stop` landed) is not honored by the
running slice, and every later request from another thread, an interrupt's or the coordinator's
preemption through `stopSlice`, is answered by it and lost. The slice runs until it parks or ends on
its own; the interrupt is delayed, the preemption skipped. `Eval.partial` consumes whatever is
pending at entry, so the window opens only for a late delivery landing inside a slice.

The swallow predates the branch; the third walk added only the owner's own supersede (the pin
"a late stop from another thread does not displace the running slice's own" covers the opposite
case, a live stop that a late one must not displace).

## Issue 5: kyo-scheduler's Native test binary crashes on this machine, on main

`kyo-schedulerNative/test` at the merge-base 0addae62ea (origin/main) in a clean worktree:

```
ScalaNative: Unhandled signal 11, si_addr=0x0
	at StackTrace_PrintStackTrace
	at stackOverflowHandler
	at _sigtramp
[error] Error: Total 148, Failed 0, Errors 3, Passed 145, Canceled 1
[error] 	kyo.scheduler.BlockingMonitorTest
[error] 	kyo.scheduler.InternalClockTest
[error] 	kyo.scheduler.WorkerTest
```

The crash follows `InternalTimerTest` in the output. Run alone: `InternalClockTest` 2 green,
`WorkerTest` 51 green, `BlockingMonitorTest` 28 green and 1 error with the process ending on
signal 6 ("Test runner interrupted by fatal signal 6"). The branch changes nothing under
`kyo-scheduler` except the `SchedulerTest` patience rename. CI runs the Native leg on linux; the
run in progress will say whether it reproduces there.

## Issue 6: scalafmt fails for one Native source

`[error] scalafmt: failed for 1 sources` appears between the `kyo-config/native` and
`kyo-stats-registry/native` formatting lines in every Native build on this machine, on the branch and
on the merge-base. The file is not named. Not yet identified.

## What the advisor is asked

1. Issue 1: where does the delivery of an answer that already arrived belong (the walk, the
   evaluator, the scheduler), and what exactly is "the step fused with the delivery" for a
   crossing join, given the crossing's parked deferral and the two polls named above? The answer
   must hold for a `Snapshot`-valued answer, for a throw in the fused step, and for a stop pending
   as a fiber resumes on the JVM, not only for abandonment.
2. Issue 2: should a body that ends with a value in the slice its interrupt landed on complete
   with the value or with the interrupt, and what does `Fiber.interrupt`'s Boolean then mean?
3. Issue 3: where should the pool's permit return be registered so no window orphans it, and is
   there a general shape for a value acquired on one fiber and owned by another?
4. Issue 4: how should `Safepoint.stop` treat a pending stale stop.
5. Issues 5 and 6: an approach, not a fix.
