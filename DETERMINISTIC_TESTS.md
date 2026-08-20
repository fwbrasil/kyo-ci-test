# Deterministic Tests

A test must pass or fail on the code's behavior, never on how fast the machine ran. Any test whose
outcome depends on real wall-clock time is a defect: it flakes on a loaded CI runner, a fast laptop, or
a slow emulator. This is not a style preference; a timing-dependent assertion is a broken test.

## The rule

**No test may depend on the real clock.** Concretely:

- No assertion on measured real elapsed time (`System.currentTimeMillis`/`nanoTime`, `Clock.now`/
  `nowMonotonic` deltas) against a threshold.
- No `Thread.sleep` to "give something time", then asserting it happened.
- No real-time delay or timeout used as the pass condition.

Coordinate with **barriers**, not time. Control durations with **virtual time**. If a test genuinely
cannot avoid the real clock, that is a **deviation**: it must be justified in a comment and reported to
the maintainer, never left silently.

## Virtual time: `Clock.withTimeControl`

Under `Clock.withTimeControl`, the clock advances only when the test tells it to, so sleeps, delays,
timeouts, schedules, and stopwatches become exact and deterministic. Sleeps are fine here; a real-time
sleep outside time control is not.

Drive a sleeping effect by forking it, forking an advancer, and joining:

```scala
Clock.withTimeControl { control =>
  for
    fiber    <- Fiber.initUnscoped(<the effect that sleeps/retries/schedules>)
    advancer <- Fiber.initUnscoped(Loop.forever(control.advance(1.milli)))
    result   <- fiber.get          // completes only once virtual time reaches its deadlines
    _        <- advancer.interrupt
  yield assert(<deterministic property>)
}
```

Assert exact durations with a stopwatch or `Clock.now`, both driven by the same control:

```scala
Clock.withTimeControl { control =>
  for
    stopwatch <- Clock.stopwatch
    _         <- control.advance(5.seconds)
    elapsed   <- stopwatch.elapsed
  yield assert(elapsed == 5.seconds)   // exact, never `>= 5.seconds`
}
```

`TimeControl` gives `set(instant)`, `advance(duration)`, and, for periodic tasks where you need exactly
one tick per interval, `awaitPendingSleepers(n)` (advance only after `n` sleepers are registered, so the
tick count is exact rather than a function of fiber interleaving).

## Barriers instead of sleeps

To make one fiber wait for another to reach a point, use a coordination primitive, never a sleep:

- `Latch` (one-shot), `Barrier` (reusable/phased), `Channel`/`Fiber.get` (rendezvous), `AtomicInt` +
  a latch for "wait until N participants arrived".
- A negative property ("X must NOT have happened yet") is proven by a barrier the other fiber would have
  had to pass, not by sleeping and checking.

## Converting the common shapes

| Flaky shape | Deterministic replacement |
|---|---|
| `assert((currentTimeMillis - start) >= d)` | run under `withTimeControl`; assert on `calls`/state, and/or `stopwatch.elapsed == d` |
| `Thread.sleep(n); assert(done)` | release a `Latch`/`Channel` from the other fiber and `await` it |
| `assert(elapsed < budget)` (op returns fast) | assert the terminal event/state that proves it returned, not the elapsed |
| retry/schedule/backoff timing | `withTimeControl` + advancer fiber; assert attempt count and exact virtual elapsed |
| "settle" sleep before reading | wait on the settle's own completion signal (a promise/latch/event) |

## Legitimately not the real clock (fine, keep)

- Sleeps, delays, and timeouts **under `withTimeControl`** (user-confirmed: fine).
- `Duration` arithmetic (`5.millis.toMillis == 5`).
- A generous ceiling (a large timeout or `Schedule.fixed(1.hour)`) used **only as a deadlock/hang canary**,
  where the assertion reads a state or event and the ceiling exists so a hang trips the suite timeout
  rather than being asserted on. See the browser suite's canary pattern.

## Deviations

Some tests exercise a real seam that virtual time cannot cover: the platform clock itself, a real OS
socket/kernel poller, a spawned subprocess, raw threads below the effect system. `withTimeControl` does not
reach these.

**"No virtual-time seam" is not a license to keep a timing assertion.** The pass/fail condition must still
not depend on real time. What makes a real-clock test deterministic is *what it asserts*, not whether it
uses a clock:

- **Assert a state, event, structure, or monotonicity**, never measured elapsed. "The connect completed",
  "the peer-closed flag is set", "entries arrived in order", "count == N", "b >= a". A slow or fast runner
  cannot flip any of these.
- **A timeout is legitimate only as a hang-canary ceiling**: a genuine defect hangs and trips the suite
  timeout. It is never the pass condition. `awaitCondition(5.seconds)(state)` then `assert(state)` is fine;
  `assert(elapsed < 5.seconds)` is not.
- If you truly need a magnitude bound (a real perf/skew check with no state proxy), make it
  **catastrophic-only** (so wide only a genuine defect trips it) and **label + report** it as a deviation.

So a deviation is: (1) **validated**, a real reason virtual time cannot cover it, written at the site as
`// deviation: <reason>`; (2) **reported** to the maintainer; (3) asserting state/structure, or at worst a
catastrophic-only bound, never a runner-flippable threshold.

Converting a real-I/O test off a threshold usually means: replace `assert(elapsed < budget)` with the
terminal state/event it produces; replace a `Thread.sleep`-to-open-a-window with a fixed operation count or
a latch; and let the suite timeout be the only hang guard.

## Checklist before adding a timing-touching test

- [ ] Does the assertion depend on real elapsed time? If yes, move it under `withTimeControl`.
- [ ] Is there a `Thread.sleep` or bare `Async.sleep` used to coordinate? Replace with a barrier.
- [ ] Is a duration/timeout the pass condition? Assert the state/event it produces instead.
- [ ] If the real clock is unavoidable, is the deviation commented and reported?
