# Test plan for finalizers: the leaves to add, by file

Each row is one leaf. "Stop" says where the stop or interrupt lands. "Now" says what the leaf does on
the current tip: red means it reproduces a hole, green means it guards a behavior the fix must keep,
new means it is not written yet. Names follow each suite's style. Leaves marked "harness" need the
manual scheduler from section 6 to be deterministic; everything else is deterministic with what the
suites have today.

## 1. The ledger, one helper for every finalizer leaf

`kyo-kernel/shared/src/test/scala/kyo/kernel/internal/Ledger.scala` (a test helper, package
`kyo.kernel.internal`, so `EvalTest`, `BracketTest` and `SafepointTest` share it; core gets a copy
under `kyo-core/shared/src/test/scala/kyo/` since the kernel suite is not a dependency of core's):

```scala
final class Ledger:
    def acquire(name: String): String        // records, returns the name so it can be the value
    def release(name: String): Unit          // records; a second release of one name is a failure
    def balanced: Boolean                    // every acquired name released exactly once, nothing else
    def acquired: List[String]
    def released: List[String]
```

Every leaf below asserts `ledger.balanced` at its end, plus one assertion naming the value the
release saw where that matters. The existing `var released = Maybe.empty[Int]` and `AtomicInt`
counters in the release and interrupt leaves are replaced by it in the same change, so the old pins
and the new ones share one oracle.

## 2. `EvalTest`, block "the step fused with an answer"

The shape under test is always: a clause answers an operation, the operation's continuation
registers a release with `ensureMap` in its first arrow, and a stop is pending somewhere. The
assertion is always: the registration ran if and only if the value exists, and the ledger balances
after `Eval.release` of what parked.

| leaf | operation issued | answer | stop | now |
|---|---|---|---|---|
| a stop pending as a crossing answer arrives parks after the step fused with it | under a `Bracket` | settled | in the clause, before `cont(21)` | red |
| an answer carrying a computation is produced and delivered before a pending stop parks | at the top | `Got(reading(21))` | in the clause | red |
| a crossing answer carrying a computation is produced under the crossed regions | under a context region binding `Env` to 7 | `Got(ContextEffect.suspend(Tag[Env], 0))` | in the clause | new, red; the release must see 7, not 0 |
| a throw in the step fused with a crossing answer unwinds through the crossed regions | under a `Bracket` | settled, the `ensureMap` throws | in the clause | new, red |
| a settled answer at the top runs its fused step before a pending stop parks | at the top | settled | in the clause | new, green |
| a masked context read's answer runs its fused step before a pending stop parks | under a masking region | the region's answer | in the clause of the masking region | new, red |
| a loop clause's answer runs its fused step before a pending stop parks | under a `Bracket`, answered by `handleLoop` | settled | in the loop clause | new, likely red |
| a stop requested inside the fused step parks at the next poll, after it | under a `Bracket` | settled | inside the `ensureMap`, after registering | new, green |
| a stop requested in the acquire's last step still registers the acquire's value | under a `Bracket` | settled | inside the deferral producing the value | green (the #1820 family) |
| a stop before the acquire's last step registers nothing | under a `Bracket` | none, the deferral never runs | in the map before the acquire | green ("an acquire the park stopped in front of") |

Each row is one leaf rather than one loop so a failure names its cell. The four columns are the
axes; a fifth, the continuation after the registration (a `map`, a deferral, another operation, the
region's end), is covered by varying it across the rows rather than multiplying them: the first row
uses a deferral, the second a `map`, the third another operation, the fourth the region's end.

## 3. `EvalTest`, block "release", abandonment of a parked remainder

The remainder is produced by `Eval.partial` with the stop placed as in section 2, so the parked shape
is the real one (a `Park` carrying entries, the re-raised operation, the crossing's continuation),
not a hand-built node.

| leaf | remainder stands at | what the release is told | now |
|---|---|---|---|
| delivers an answer carrying a computation to the release waiting on the value it produces | an operation | the answer, a `Got` carrying a context read | red |
| a park at a crossing operation whose answer arrived registers what the answer produced | an operation under a `Bracket`, parked by a stop in the clause | the answer | new, red |
| a park at a crossing operation whose answer arrived and whose fused step throws releases the crossed regions | as above, the `ensureMap` throws | the answer | new, red |
| a park at a map runs nothing and releases what stood above it | a `map` under a `Bracket` | nothing | green ("an acquire the park stopped in front of") |
| a park at an operation whose answer has not arrived links it and releases what stood above it | an operation | no answer | green (the reporter pins) |
| a park whose remainder is a deferral over a settled value with an `Ensure` head runs the `Ensure` | `Effect.defer(settled, ensure)` | nothing | green (#1820) |

The second column's shape is what `IOTask.abandon` hands over. Whatever the design for delivery
lands, these rows are its acceptance: if abandonment becomes a resumption with the stop pending,
"what the release is told" becomes the promise's state and the rows stay as they are.

## 4. `BracketTest`

| leaf | now |
|---|---|
| a bracket installed by the step fused with a crossing answer is released on abandonment under a stop | new, red |
| a bracket installed by the step fused with a crossing answer is released when the resumed slice ends normally | new, green |
| a bracket failing after a crossing still releases before the recovery | green |
| a park after a crossing resume still owes the bracket | green |

The first two are the `Bracket` twins of section 2's first row: the registration is a region
installed by the fused step, and the ledger must balance both when the parked remainder is released
and when it is resumed to the end.

## 5. `SafepointTest` (jvm-native)

| leaf | now |
|---|---|
| a stop from another thread lands while a stale one is pending | red |
| two stops from two threads for the running slice both count once | new; depends on the design (a chain or a clear), written with it |
| the owner's poll clears a stale stop so a later request lands | new; same |
| a stop for the running slice supersedes a stale one left by a departed slice | green |
| a late stop from another thread does not displace the running slice's own | green |

## 6. Core: the manual scheduler and `IOTask`'s transition table

`kyo-core/shared/src/test/scala/kyo/scheduler/IOTaskTest.scala` is the 1:1 file for `IOTask.scala`
and does not exist. It gets a scheduler harness first: a `Scheduler` with a queue and `step()`,
installed for a leaf through the same seam `Scheduler.get` reads, so `Fiber.initUnscoped` enqueues
and the leaf decides the order. `kyo-scheduler`'s `TestExecutors` and `TestTask` are the model; the
missing piece is the install seam, which is a small change in `kyo-scheduler` with its own pin.

With the harness, the status word's transitions become leaves. The first column is the state the
interrupt finds the task in, the second what the body does next.

| leaf | interrupt over | the body | now |
|---|---|---|---|
| an interrupt over an idle task before its first run releases nothing and completes with the interrupt | `Idle`, never run | a `Scope.acquireRelease` at its head | harness; today raced 100 times by "rapid interrupt after init (#1458)" |
| an interrupt over an idle task between slices releases the remainder | `Idle`, parked once | parked at a map by preemption | harness |
| a body ending with its value in the slice its interrupt landed on completes with the value | `Thread` | returns 42 | red |
| a body ending with a failure in the slice its interrupt landed on completes with the failure | `Thread` | `Abort.fail` | new; depends on Issue 2's ruling |
| a body throwing in the slice its interrupt landed on completes with the interrupt | `Thread` | throws | green ("cooperative interruption") |
| a body parking in the slice its interrupt landed on is released at the park and completes with the interrupt | `Thread` | self-interrupts, then `promise.get` | new, expected green |
| a task interrupted while parked has the answer that arrived delivered before it is released | the parked promise, then complete | `Scope.acquireRelease(promise.get)` | green with a promise (strand pin), red with a fiber |
| a task interrupted while parked on a promise that never completes is released | the parked promise | `Async.never` | green ("uninterruptible promise") |
| a task completed by another party between slices releases its remainder | none, `abandon(Absent)` | parked, then `unsafe.complete` from outside | new |
| a second interrupt is refused while the first is being released | `Result.Error` | anything | green ("a second interrupt is refused") |
| interruptAwait returns after the release, with the value when the body won | `Thread` | returns 42 | new; depends on Issue 2's ruling |

## 7. `ScopeInterruptTest`: cross-fiber ownership

| leaf | shape | window | now |
|---|---|---|---|
| a resource a joined fiber produced is released when the acquiring fiber is abandoned before it resumed | `Scope.acquireRelease(inner.get)` | owner abandoned after the child completed | red |
| a permit a child takes after its owner was abandoned is returned | `Scope.acquireRelease(inner.get)`, child takes in its wakeup's step | owner abandoned before the child produced | red on the branch, green in the primary tree by the budget's stepping |
| the producing fiber's registration returns the permit when the owner is abandoned after the value | `Async.timeout(Scope.acquireRelease(take)(offer))` | after | new; the supported shape, expected green once Issue 1 is fixed |
| the producing fiber's registration returns the permit when the owner's scope closed first | same | before, the child then takes on its own slice | new; exercises the closed-scope detached run |
| the producing fiber's registration returns the permit when the take is delivered on the child's own abandonment | same | the take's promise completes between the cascade and the child's release | new; harness for the ordering |

The last three are the specification the pool, aeron and net are rewritten against; their own pins
(`SqlClientInterruptTest`, `AeronTransportTest` "an interrupt on a completed add", the net descriptor
leaf) stay as the integration layer.

## 8. `flags.sh`

One line: `Scope.acquireRelease(` whose acquire contains `Async.timeout` or `.get` on a fiber is
flagged `ownership`, verdict required. It catches the unsound shape at review time; it is not a
test.

## 9. Verification chain for a finalizer change

Run before a commit claims the change verified, in this order, all local:

1. `kyo-kernelJVM/testOnly kyo.kernel.internal.EvalTest kyo.kernel.BracketTest kyo.kernel.internal.SafepointTest`
2. `kyo-kernelJS/testOnly` the same two shared suites
3. `kyo-coreJVM/testOnly kyo.FiberTest kyo.ScopeInterruptTest kyo.ScopeTest kyo.SyncTest kyo.scheduler.IOTaskTest`
4. `kyo-coreJS/testOnly` the same
5. `kyo-sql-postgresJS/testOnly kyo.SqlClientInterruptTest` and the JVM container suites
6. `kyo-aeronJVM/testOnly kyo.AeronTransportTest`

JS is in the chain because its scheduler's fixed order is the deterministic oracle; the JVM legs are
the ones the harness makes deterministic.

## Order of work

1. The ledger, and the existing release, bracket and interrupt leaves rewritten over it (green stays
   green).
2. Sections 2, 3 and 4: the kernel cells. Nine new leaves, seven red today.
3. Section 5 with the stop design.
4. The scheduler install seam and `IOTaskTest` (section 6).
5. Section 7's three ownership leaves, then the consumers rewritten to the supported shape.
6. The flag line and the chain in the kernel skill.
