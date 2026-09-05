# kyo-core: `Queue.close` versus an in-flight `offer`

Groundwork for analysis. Every claim below was read from the source at `origin/main` = `8b8e463a86`, not
taken from the handoff. Line numbers are against that commit.

## The defect, verified

`Queue.scala:596` contains a bare spin, and it is the ONLY such loop in the file:

```scala
val initial = _drain()
while activeOffers.get() > 0 do ()
initial ++ _drain()
```

Its own comment (`:588-595`) states why: an in-flight offer that read `state = Open` before the closer's CAS
may still commit and run race-repair, and a re-insert landing after `_drain()` exits would strand the item.

The race-repair is a producer-side poll, `Queue.scala:747-753`:

```scala
def offer(v: A)(using AllowUnsafe) =
    offerOp(
        q.offer(v),
        q.poll() match
            case Maybe.Present(polled) =>
                val isOurs = polled.asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef]
                if !isOurs then discard(q.offer(polled))
                !isOurs
            case _ => true
    )
```

On a single-consumer backing structure that `q.poll()` makes the producer a second consumer beside the
closer's `_drain()`. When the drain empties the slot the producer is polling for, the poll does not return,
and the closer is spinning on `activeOffers` waiting for exactly that producer. Both are stuck, and the spin
has no safepoint so it is immune to interruption.

## The protocol, in full

`offerOp` (`:662-680`):

```scala
discard(activeOffers.incrementAndGet())      // BEFORE the state read, deliberately
try
    offerClosed.getOrElse {
        val result = f                        // the enqueue
        if result && closed() then Result(raceRepair) else Result(result)
    }
finally discard(activeOffers.decrementAndGet())
```

`closed()` is `state.get().isInstanceOf[State.FullyClosed]` (`:618-619`), so the repair fires only on
FullyClosed, not HalfOpen.

State ADT (`:558-562`): `Open`, `HalfOpen(p: Promise.Unsafe[Boolean, Any], r)`, `FullyClosed(r)`.

`close()` (`:571-598`) escalates Open -> FullyClosed, or HalfOpen -> FullyClosed completing the await-empty
promise `false`, then drains, spins, re-drains.

`handleHalfOpen()` (`:682-687`): when HalfOpen and empty, CAS to FullyClosed and complete the promise `true`.

## Blast radius

`Scope`'s finalizer queue is single-consumer (`Scope.scala:157-159`):

```scala
val queue = Queue.Unbounded.Unsafe.init[...](Access.MultiProducerSingleConsumer)
```

`Scope.ensure` is an offer on it (`:162-171`), `Scope.run`'s close is `queue.close()` (`:176`). Every MPSC
`Channel` is exposed for the same reason. Roughly 66 non-test files touch a `.close` on a channel-like
value, so the surface is wide even if most call sites are mechanical.

## The obstacle the handoff does not mention, and it is the load-bearing one

The proposed design makes `close` return a fiber so the closer can await a handoff. **`Scope.run` cannot
hold an async close on one of its two close paths.**

`Scope.run` (`Scope.scala:129-142`) closes the finalizer twice, on different paths:

```scala
.handle(
    Sync.ensure(finalizer.close),      // :134  the panic / interrupt path
    Abort.run[Any]
).map { result =>
    finalizer.close(result.error)      // :138  the normal path
        .andThen(finalizer.await)
```

`Sync.ensure` is typed `f: => Any < (Sync & Abort[Throwable])` (`Sync.scala:60`, `:108`) and is implemented
as `Safepoint.ensure(ex => Sync.Unsafe.evalOrThrow(f(ex)))` (`:111`). It is Sync-only and evaluates or
throws synchronously; it cannot hold an `Async` computation. `Scope.run` already returns `A < (Async & S)`,
so the `:138` site could take an async close, but the `:134` site could not.

So "make close async" is not a uniform change: the panic path needs a close that completes without
awaiting anything, while the normal path can await. Any design has to say what the panic path does with a
late-committing offer's element.

## Precedent that lowers the cost of the contract change

`close` and `closeAwaitEmpty` are already asymmetric in exactly the proposed direction
(`Channel.scala:218`, `:229`, `:409-410`):

```scala
def close(using Frame): Maybe[Seq[A]] < Sync
def closeAwaitEmpty(using Frame): Boolean < Async
def close()(using Frame, AllowUnsafe): Maybe[Seq[A]]
def closeAwaitEmpty()(using Frame, AllowUnsafe): Fiber.Unsafe[Boolean, Any]
```

A fiber-returning unsafe close and an `Async` safe close would make `close` match its own sibling rather
than introduce a new shape.

## Why the previous attempt was rejected

Branch `kyo-core-close-drain`, commit `3b26625be0`, fetched and inspected:

```
kyo-core/shared/src/main/scala/kyo/Queue.scala     | 125 +++---
kyo-core/shared/src/main/scala/kyo/Scope.scala     |  21 +-
kyo-core/shared/src/test/scala/kyo/QueueTest.scala | 229 +++++++++++
kyo-core/shared/src/test/scala/kyo/ScopeTest.scala |  66 ++++
.../scala/kyo/test/runner/internal/LeakCheck.scala |   2 +-
```

It deleted the producer-side repair (right) but replaced the wait with the same bare spin. The owner
rejected it on that basis. Note main ALREADY has that spin at `:596`, so the bar is not "do not add a
spin", it is "leave none behind, including main's". The ~295 lines of new tests are the salvageable part.

## Constraints on any fix

- No busy-wait and no blocking anywhere. kyo-core does not park or spin a thread. The wait must become a
  suspension or cease to exist.
- A single-consumer backing structure must have exactly one consumer at a time. The producer cannot poll;
  the closer cannot drain while an offer may still commit.
- Reproduce first, in kyo-core's own suites, then run kyo-core on JVM, JS, Native and Wasm.
- The `Scope.run` panic path (`Sync.ensure`, Sync-only) must keep working.
- Do not change the unsafe `close` contract without an owner decision, because the synchronous
  `Maybe[Seq[A]]` return is precisely why the spin exists.

## Questions for analysis

1. Is there a design that removes the wait entirely rather than relocating it, and does it need the
   contract change at all? The handoff proposes a handoff-to-last-offerer with a promise in the closed
   state. Is there an alternative where no party ever needs to wait, for instance one where an offer that
   discovers the queue closed never commits in the first place, making the repair unnecessary?
2. What does the `Scope.run` panic path do under each candidate? That path cannot await. Is dropping a
   late element acceptable there, and if so why is it acceptable there but not on the normal path?
3. Does the sliding queue need separate treatment? A sliding offer's drop of the oldest element is a
   consume performed by the producer, so it is a second consumer even with no close in play.
4. Is `handleHalfOpen` subject to the same race, and does it need the same handoff?
5. Can the fix be staged so the spin is removed first, without the contract change, even if some late
   element is lost, and the completeness restored second? Or is that an unacceptable intermediate?

---

# Design analysis (fable-queueclose), with the claims I re-verified

## The result that decides everything

A `close` that returns the COMPLETE backlog SYNCHRONOUSLY cannot exist without a wait.

Producer P increments `activeOffers`, reads `state = Open`, and is preempted before `q.offer(v)` commits.
Closer C escalates the state and must return now. Whatever C returns cannot contain `v`, because `v` is not
in the ring yet. Either the return is incomplete or C waits. No protocol escapes it.

The "offer that never commits once closing" alternative also fails, and its failure is instructive: the
commit is inside a foreign structure (JCTools on JVM), so it cannot be made atomic with the external state
read. The commit-then-revoke variant (per-element CAS cell, drain claims, late offerer revokes) dies on the
half-committed slot: a JCTools array-queue offer claims the producer index and THEN stores the element, so a
drain seeing the claimed index but not the element must either wait for the store or stop early and strand
everything behind the gap. Un-arbitratable without waiting, and it would cost an allocation per offer.

So the wait cannot cease to exist. What CAN cease to exist is any party BUSY-waiting.

## Design D: last-one-out drains, single-shot claim

State ADT gains one case: `Open | HalfOpen(p, r) | Draining(backlog: Promise.Unsafe[...], r) | FullyClosed(r)`.

`offerOp` deletes the `raceRepair` argument and the `result && closed()` post-check entirely. New shape:

    inc activeOffers
    try  offerClosed.getOrElse(Result(f))
    finally
      val n = dec activeOffers
      if n == 0 then helpComplete()

`close()` escalates to `Draining(p)`, then reads `activeOffers` ONCE:
- `0`: attempt the claim CAS `Draining -> FullyClosed`; winner drains and completes `p` synchronously. This
  is the only path on JS and Wasm (single-threaded, `offerOp` has no suspension point), and on JVM/Native it
  is every call that does not land inside a nanosecond commit window.
- `>0`: touch nothing, return `p`. The last offer to decrement to zero claims, drains, completes `p`.

INVARIANT: the ring is consumed after close only by the winner of the single `Draining -> FullyClosed` CAS,
attempted only by a thread that observed `activeOffers == 0` after the state left `Open`. Every offer that
can still commit incremented BEFORE reading the state and decrements only AFTER its commit, so seq_cst
Dekker on `(state, activeOffers)` gives: either the closer sees the in-flight offer (and does not drain), or
the offerer sees `Draining` (and helps). Exactly one claimer emerges; the CAS makes it at most one.

Nobody loops, nobody parks. `while` disappears from Queue.scala.

## The obstacle in my groundwork was answered, not dodged

I recorded that `Scope.run` closes the finalizer on two paths and that `Sync.ensure` (`Sync.scala:60,108-111`)
is Sync-only, so the panic path could not hold an async close. VERIFIED AND RESOLVED: `Finalizer.close`
(`Scope.scala:174-191`) is already asynchronous in its WORK and synchronous only in its INITIATION. It spawns
via `Fiber.initUnscoped` (:189), completes `promise` via `becomeDiscard` (:190), and `await` (:193) parks on
`promise.get`, not on close. So the finalizer never needed to await the close, only to start it: swapping
`queue.close() match {...}` for an `onComplete` callback around the same match keeps the `Unit < Sync`
signature. Both `Scope.run` sites (:134 panic, :138 normal) compile and behave unchanged, and no element is
dropped on either path, so the "why is dropping acceptable on the panic path" question has no referent.

## Two more defects on main, found by this analysis

`handleHalfOpen` (`:682-687`) has the same race family TODAY, twice over. An offer that read `Open` can commit
after the `_isEmpty()` check and CAS, stranding an element while the awaiter is told `true`. And the repair
does not even fire there: `closed()` (`:618-619`) is `FullyClosed`-only, so `HalfOpen` has NO repair at all.
Fix by the same help pattern: `if activeOffers == 0 && _isEmpty then CAS -> FullyClosed`, in that read order,
with the last-exit hook also calling it (otherwise adding the count check alone would let the awaiter hang).

Sliding is a SEPARATE, independent defect: `initSliding`'s offer calls `underlying.poll()` from the producer
(`:502-512`), a second consumer with NO close in play. Fix is the access widening (MPSC -> MPMC, SPSC -> SPMC)
inside `initSliding` only. Separable and independently green; should land first.

## Spin inventory: the bar is three sites, not one

VERIFIED by grep over `kyo-core/shared/src/main`. Exactly three busy-waits exist:

    Queue.scala:596     while activeOffers.get() > 0 do ()     (this fix deletes it)
    Channel.scala:747   while batchInProgress.get() do ()      (poll, consumer waits on a batch transfer)
    Channel.scala:758   while batchInProgress.get() do ()      (drainUpTo, inside a @tailrec loop)

The Channel pair is a different mechanism (batch transfer coordination), untouched by this design, and equally
unbounded under preemption. If the bar is "kyo-core never spins", they need their own fix and must be named in
the plan rather than left as the next `Queue.scala:596`.

## Contract change: forced, and in the precedented shape

Unsafe `close()(using Frame, AllowUnsafe): Fiber.Unsafe[Maybe[Seq[A]], Any]`; safe `close: Maybe[Seq[A]] < Async`
via `.safe.get`. Same values as today (`Absent` = already closed), only the row changes, and `close` ends up
matching its own sibling `closeAwaitEmpty` instead of introducing a new shape.

Two things cut the 66-file surface to a small mechanical set:
- Scope-managed close sites need ZERO edits: `Scope.ensure` finalizers are typed `Async & Abort[Throwable]`
  (`Scope.scala:146`), so `Scope.ensure(Queue.close(queue))` still conforms.
- Add `closeDiscard: Unit < Sync` (naming precedent: `offerDiscard`). Every site that discards the backlog
  keeps a Sync row with a one-line rename, including the `Sync.ensure` sites that CANNOT take Async
  (`Queue.use`, `useSliding`), which is exactly where the row change would otherwise break the build.

## Rejected attempt's ~295 lines of tests: keep essentially all of them

They assert contracts, not the mechanism, and they are the reproducing tests this fix needs. QueueTest
"close versus in-flight offers" reproduces the live MPSC deadlock; QueueTest closeAwaitEmpty race reproduces
the `handleHalfOpen` strand-and-report-true bug that exists on main today; ScopeTest's two leaves are the
Scope-level exactly-once reproducers. Trim only the comments narrating that attempt's own wait mechanism and
its unverifiable empirical claims. Discard nothing structural.

Note design D makes the `acquireRelease` release-on-refusal change strictly SOUNDER than in its own attempt:
refusal is now certain (accepted implies committed and will run; refused provably never entered), so
release-on-refusal cannot double-release. Under main that same edit was ambiguous.

## Pre-existing, unchanged, worth one sentence of scaladoc

A user-side poll in flight when close is called can consume concurrently with the drain (`pollOp` reads state
before `q.poll()`, `:649-654`). True on main today and under design D. It is the user's single-consumer
contract. Scope never polls, so its MPSC queue is unaffected.
