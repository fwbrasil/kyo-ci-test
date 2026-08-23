# Resolution of the two wall-clock bracket assertions

The de-flaking directive is categorical: no time-based and no threshold-based assertions, not
even a lower bound. Fable's re-check found the reviewed chunk had two assertions left that read the
wall clock. Both are now resolved, each by the applicable path (convert where a deterministic
substitute exists, remove with a report where it does not).

## 1. InternalClock tick, `InternalClockTest."currentMillis"` — CONVERTED

Was: five iterations of `systemBefore = System.currentTimeMillis()` ... await two ticks ...
`systemAfter = System.currentTimeMillis()`, asserting the published tick fell inside
`[systemBefore, systemAfter]`. A wall-clock read on both sides.

Now: `InternalClock` takes an injectable time source `now: () => Long` (default
`() => System.currentTimeMillis()`, so production is unchanged). The test constructs the clock with
an `AtomicLong`-backed source, then asserts `currentMillis()` publishes the exact value the source
is set to, and republishes the new value after the source is moved forward.

- No real time enters the assertion; the comparison is exact equality against a value the test
  chose, not a bracket against the system clock.
- Coverage is preserved and sharpened: the loose interval becomes an exact publish, and moving the
  source proves the update loop keeps resampling rather than latching its first reading (the
  monotonic property, stated as an exact target).
- Production change: one function-call indirection per update (once per millisecond), negligible.

## 2. `CIO.now`, `TimeTest."now returns a wall-clock Instant bracketed by System.currentTimeMillis"` — REMOVED with this report

Was: `before = System.currentTimeMillis()` ... `CIO.now` ... `after = System.currentTimeMillis()`,
asserting `CIO.now.toEpochMilli` fell inside `[before, after]`.

No deterministic substitute exists. `CIO.now` is a thin per-backend delegation to the backend's
current-instant source: `java.time.Instant.now()` for the future and twitter-future bindings, and
the effect system's real-time clock for the zio, kyo, and ox bindings. The compat test harness
(`CompatTest`) runs a `CIO` bounded by a timeout; it has no controllable clock, and for the
bindings that read `java.time.Instant.now()` directly there is nothing to control short of mocking
the JVM clock. The property under test, that the value sits on the wall-clock epoch, can only be
checked against a real wall-clock reading, which the directive forbids.

- Dropped coverage: that `CIO.now` returns a value on the wall-clock epoch. This would have caught
  a binding that returned a wrong-epoch or constant instant (for example monotonic nanoseconds
  mistaken for epoch millis). The risk is low: each binding is a one-line delegation to a
  well-known clock source.
- Retained deterministically: `"now returns without error"` still exercises `CIO.now` on every
  backend and pins its type to `java.time.Instant`. The surviving test carries an in-file note
  recording that the epoch value is out of scope and why.

## Result

Zero wall-clock and zero threshold assertions remain in the reviewed de-threshold chunk. The
InternalClock contract is now tested more precisely than before; the only net coverage loss is the
`CIO.now` epoch check, which has no clock-free form.
