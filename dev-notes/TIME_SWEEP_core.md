# Time / Threshold-Based-Assertion Sweep — Core Effects Modules

Scope: `kyo-core`, `kyo-prelude`, `kyo-data`, `kyo-kernel`, `kyo-stm`, `kyo-parse`, `kyo-config`,
`kyo-combinators`, `kyo-direct`, `kyo-offheap` — every `*.scala` under `**/src/test/`.

Method: grepped all scope modules for `elapsed`, `timed(`, `.toMillis`, `.toNanos`, `nanoTime`,
`currentTimeMillis`, `assert(.*(millis|seconds))`, `under \d`, `within`, `budget`, `Async.sleep`,
`Thread.sleep` (429 raw hits), then read the surrounding code for every hit to classify.

Analysis only. No files were edited, no tests were run.

---

## ANTI-PATTERN FINDINGS (ranked, most-flaky-risk first)

### 1. `kyo-core/.../ClockTest.scala` — "Sleep" and "TimeShift" sections use real wall-clock ceilings

The suite's `Stopwatch`/`Deadline`/`Integration` sections correctly use `Clock.withTimeControl`
(the good, deterministic pattern). But the **`"Sleep"`** and **`"TimeShift"`** sections, plus the
real (non-controlled) branches of `"repeatAtInterval"`, `"repeatWithDelay"`, and `"Monotonic Time"`,
measure real `Clock.now`/`clock.sleep` against fixed millisecond ceilings:

- `kyo-core/shared/src/test/scala/kyo/ClockTest.scala:265` — `"sleep for specified duration"`:
  `assert(elapsed >= 3.millis && elapsed < 100.millis)` after a real `clock.sleep(5.millis)`.
- `ClockTest.scala:280` — `"multiple sequential sleeps"`: `assert(end >= 8.millis)` (floor only, lower risk).
- `ClockTest.scala:290` — `"sleep with zero duration"`: `assert(elapsed < 10.millis)` (pure ceiling).
- `ClockTest.scala:300` — `"concurrency"`: 100 concurrent `clock.sleep(5.millis)` fibers,
  `assert(elapsed >= 3.millis && elapsed < 100.millis)` — most contention-prone of the group.
- `ClockTest.scala:319-320` — `"speed up time"`: `assert(elapsedWall >= 25.millis && elapsedWall < 400.millis)`.
  The load-bearing comment above it **already documents CI flakiness under contention**:
  > "under CI contention the gap can be large... Sleeping long enough keeps the wall sleep well above the gap."
- `ClockTest.scala:331` — `"slow down time"`: `assert(elapsedWall >= 18.millis && elapsedWall < 200.millis)`.
- `ClockTest.scala:386,406,435,474` — `"repeatAtInterval"`/`"repeatWithDelay"` real-clock variants:
  `assert(avgInterval >= 4.millis && avgInterval < 100.millis)` (averaged over ~8 samples, so more
  robust than a single-shot check, but still a real 20x-margin ceiling).
- `ClockTest.scala:507-508` — `"nowMonotonic"`: `assert(time2 - time1 >= 4.millis)` and `< 40.millis`.
- `ClockTest.scala:528-529` — `"with time shift"`: `assert(time2 - time1 >= 4.millis && < 500.millis)`.

**Why flaky**: every ceiling check (`< 100.millis`, `< 10.millis`, `< 400.millis`, `< 40.millis`) can
be blown by GC pause, scheduler contention, or a loaded CI runner — exactly the failure mode this
repo has already hit repeatedly (see task history: "JS ContainerOrchestrationItTest 'init completes
under 2s' (2.1s under load)", MachineSampler 0-alloc flake, etc.).

**Replacement**: these are testing the *real* sleep/time-shift primitive itself (not a caller's use
of it), so full virtualization isn't available the way it is for `Stopwatch`/`Deadline`. Where
possible, prefer verifying an **outcome** instead of a **duration**: e.g. for `repeatAtInterval`,
assert the fiber delivered N ticks and was interruptible, not that the *average interval* fell in a
band. Where a real-time bound genuinely must stay, widen margins further (the "speed up time" test's
own comment already flags this isn't wide enough) or gate these specific leaves behind the
CI-hardware-aware retry the suite already uses elsewhere (`Loop.repeat` + `assertEventually`).

### 2. `kyo-config/.../DynamicFlagConcurrencyTest.scala:57-68` — real nanoTime ceiling on a throughput claim

```scala
"apply() never blocks — completes in bounded time" in {
    ...
    val start = java.lang.System.nanoTime()
    for (_ <- 0 until 10000) { flag("user1", "enterprise"): Unit }
    val elapsed = java.lang.System.nanoTime() - start
    // 10000 calls should complete in well under 1 second
    assert(elapsed < 1000000000L, s"Took ${elapsed / 1000000}ms for 10000 calls")
}
```

Classic "completes under N ms" pattern on a raw hot loop with no JIT warmup control. On a
contended/emulated CI runner (this repo runs arm64 QEMU-emulated Native/JVM jobs) a 10k-iteration
loop can plausibly exceed 1s even with correct non-blocking behavior.

**Replacement**: replace the wall-clock ceiling with a structural/soundness proof that `apply()`
never blocks: e.g. run it concurrently with a writer thread and assert no `errors` were recorded
(the pattern the sibling tests in this same file already use, e.g. `"concurrent apply() calls"`),
or bound by iteration count / a Latch showing no `Async.sleep`/lock-park ever happens, not measured
duration.

### 3. `kyo-parse/.../ParseTest.scala:1491-1534` — `withinBudget` nanoTime perf-regression guard

```scala
def withinBudget(label: String)(body: ...): Unit =
    val start = java.lang.System.nanoTime()
    body
    val elapsed = (java.lang.System.nanoTime() - start) / 1000000L
    assert(elapsed < 30000L, s"$label took ${elapsed}ms (budget 30000ms): repeat is not linear")
```

Used 3x (`"repeat(any) over a large input is linear"`, `"readWhile over a large input is linear"`,
`"literal in a repeat over a large input is linear"`) to catch an O(n²) regression on a 500,000-char
input. The comment documents a deliberate >30x margin ("finishes in well under a second... 30s
budget"), which meaningfully reduces flake risk versus a tight bound, but it is still a real
wall-clock ceiling and this suite runs on emulated Native/JS CI where 30x margins have been consumed
before elsewhere in this repo.

**Replacement**: instrument the parser (or wrap the `Parse` primitives under test) to count actual
read/backtrack operations and assert `operations <= C * inputLength` for a small constant `C`. This
detects the exact same O(n²) regression with zero dependency on machine speed.

### 4. `kyo-core/.../StreamCoreExtensionsTest.scala:49-81` — freeze-repro watchdog uses fixed sleeps to "settle" a race

```scala
"collectAllHalting self-terminates infinite producers when the merge halts (freeze repro)".onlyJvm in {
    ...
    val t = new Thread(() => { try Thread.sleep(2000) catch ... ; forceStop.set(true) })
    ...
    _ <- Async.foreach(1 to merges, merges)(_ => Stream.collectAllHalting(...).run)
    _ <- Async.sleep(3.seconds)
  yield
    watchdog.interrupt()
    assert(spinning.get() == 0, s"${spinning.get()} of $merges infinite producers were still spinning...")
}
```

A 2s `Thread.sleep` watchdog plus a 3s `Async.sleep` (5s total) is used to "wait long enough" for
producer fibers to observe `forceStop` and settle to `spinning == 0` — textbook instance of the
"fixed sleep, then assert state settled" race pattern, even though the total margin (5s) is generous.

**Replacement**: poll (`assertEventually`-style, already used elsewhere in this same file's sibling
suites) for `spinning.get() == 0` bounded by the per-leaf timeout, rather than a single fixed-duration
sample. This keeps the test as fast as possible on fast machines and as robust as possible on slow
ones, instead of picking one fixed number that has to work for both.

### 5. `kyo-core/.../StreamCoreExtensionsTest.scala:640-649` — `broadcastDynamicWith` fixed 30ms before draining

```scala
"broadcastDynamicWith" in {
    stream.broadcastDynamicWith { streamHub => Kyo.zip(streamHub.subscribe, streamHub.subscribe) }.map:
        case (s1, s2) =>
            // Ensure boundary works
            Async.sleep(30.millis).andThen:
                Kyo.zip(s1.run, s2.run).map:
                    case (c1, c2) => assert(c1 == c2 && c1 == (0 to 10))
}
```

The comment ("Ensure boundary works") signals this sleep exists specifically to let both
subscriptions register before the underlying source starts emitting. If the broadcast source begins
pushing before both `subscribe` calls are fully wired internally, `c1`/`c2` could come back truncated
or mismatched under CI contention.

**Replacement**: if `broadcastDynamicWith`/`subscribe` doesn't already return only once fully
registered, that is itself worth confirming as a real invariant (a subscribe that isn't
synchronously-safe is a latent bug independent of this test). If a genuine warm-up gap exists,
replace the fixed sleep with an explicit readiness signal (e.g. assert on subscriber count via
`assertEventually` before invoking `.run`).

### 6-7. `kyo-core/.../AsyncTest.scala:37-53` — `"sleep"`/`"delay"` measure real elapsed time via `currentTimeMillis`

```scala
"sleep" in {
    for
        start <- Sync.defer(java.lang.System.currentTimeMillis())
        _     <- Async.sleep(10.millis)
        end   <- Sync.defer(java.lang.System.currentTimeMillis())
    yield assert(end - start >= 8)
}
"delay" in {
    ... assert(end - start >= 4)
}
```

Floor-only checks (timers essentially never fire early, so low flake-red risk), but they duplicate
exactly what `ClockTest`'s `Stopwatch` tests already verify **deterministically** via
`Clock.withTimeControl`. Real-clock measurement here is unnecessary and inconsistent with the
project's own established idiom for this exact assertion shape.

**Replacement**: rewrite using `Clock.withTimeControl { control => ... control.advance(10.millis) ...
stopwatch.elapsed }` exactly as `ClockTest.scala:70-79` does, or drop the elapsed check entirely and
assert only that `Async.sleep`/`Async.delay` complete and return the right value (the outcome, not
the timing).

### 8. `kyo-core/.../AsyncTest.scala:172-196` — "interrupting a parent stops all its Async.foreach children"

```scala
for
    _  <- repeat(200)
    p1 <- Sync.defer(progress.get())
    _  <- Async.sleep(50.millis)
    p2 <- Sync.defer(progress.get())
yield assert(p1 == p2)
```

A single fixed 50ms sample window is used to prove a shared counter has *stopped changing* (i.e. all
child fibers actually stopped after parent interruption) — the canonical "sleep long enough, then
assert settled" race check, on a regression test explicitly about an interrupt race.

**Replacement**: sample `progress` at multiple increasing intervals (or poll until two consecutive
samples 10ms apart agree, bounded by the leaf timeout) instead of a single 50ms snapshot — this
converges as fast as the real signal allows on a fast machine and doesn't need a hand-picked margin
for a slow one.

### 9-10. `kyo-core/.../HubTest.scala:84-96, 502-514` — fixed sleep then assert fiber "not done" (backpressure)

```scala
"backpressure when hub is full" in {
    ...
    fiber <- Fiber.initUnscoped(h.put(3))
    _     <- Async.sleep(10.millis)
    done  <- fiber.done
    hFull <- h.full
  yield assert(!done && hFull)
}
"takeExactly" - "blocks until enough elements" in {
    ...
    fiber <- Fiber.initUnscoped(l.takeExactly(4))
    _     <- Async.sleep(10.millis)
    done1 <- fiber.done
    ...
  yield assert(!done1 && done2 && res == Chunk.from(1 to 4))
}
```

Both assert a *negative* (`!done`) after a fixed wait. Low flake-red risk in the direction of a
correct implementation (a genuinely-blocked fiber stays `!done` no matter how long you wait), but
this is a weak test: it cannot distinguish "correctly blocked" from "the fiber simply hasn't been
scheduled yet" within the 10ms window, so it can pass without proving the property it claims. Same
file (`HubTest.scala:352-354`) already documents having removed an equivalent
elapsed-time floor check ("The former elapsed >= 8.millis floor only confirmed the test's own
per-item sleeps ran...") — these two are the same anti-pattern left unconverted.

**Replacement**: convert to the `Async.timeout` + outcome idiom used correctly elsewhere in this repo
(e.g. `SignalTest.scala:397-408`): `Abort.run[Timeout](Async.timeout(shortDuration)(fiber.get)).map(r
=> assert(r.isFailure))` — asserts that the operation itself reports "did not complete", not a
measured elapsed value.

### 11. `kyo-core/.../MeterTest.scala` — 8x non-reentrant-meter deadlock checks via fixed sleep + `!done`

Lines **635, 649, 677, 691, 719, 733, 769, 786** — one instance each for mutex / semaphore / rate
limiter / pipeline, in both `"non-reentrant"` and `"nested forked fiber can't reenter"` variants:

```scala
f      <- Fiber.initUnscoped(meter.run(meter.run(42)))
_      <- Async.sleep(5.millis)
done   <- f.done
_      <- f.interrupt
result <- f.getResult
yield assert(!done && result.isPanic)
```

Same shape as #9-10. Because the property under test is *permanent* deadlock (non-reentrant meters
block forever, not just for 5ms), this is actually low flake-red risk in practice — but it is 8
duplicated instances of exactly the pattern this sweep is hunting, and the same file already knows
the better idiom (`MeterTest.scala:611` uses `assertEventually(meter.tryRun(()).map(_.isEmpty))`).

**Replacement**: `Abort.run(Async.timeout(5.millis)(f.get)).map(r => assert(r.isFailure))` — same
transformation as #9-10, applied 8x.

### 12. `kyo-core/.../MeterTest.scala:581-587` — rate limiter replenish-cap test on real sleep

```scala
"replenish doesn't overflow" in {
    meter     <- Meter.initRateLimiter(5, 5.millis)
    _         <- Async.sleep(32.millis)
    available <- meter.availablePermits
  yield assert(available == 5)
}
```

Real 32ms sleep against a real 5ms replenish period (pattern #4, rate-per-real-time-window). Low
flake risk in practice (the cap invariant should hold regardless of *how much* extra time elapses,
so CI slowness cannot push `available` above 5), but it is a genuine real-clock dependency with no
virtual-time alternative exercised for `Meter` anywhere in this file, unlike `Clock`.

**Replacement**: if `Meter.initRateLimiter` can be driven by an injectable clock, prefer
`Clock.withTimeControl`; otherwise leave as-is but note the asymmetric risk (only flakes if
`available` reads *less* than 5, i.e. a real bug, not CI slowness).

### 13-15. `kyo-core/.../SignalTest.scala` — fixed-interval `streamChanges` sequencing (x3) + 2 absence checks

- `SignalTest.scala:353-365` (`"inside streamChanges produces expected sequence"`),
  `:509-525` (`"interleaved self,other,self,other produces four emits"`),
  `:792-808` (`"combineLatest feeding streamChanges produces interleaved emit sequence"`):
  all three interleave real state changes with fixed `Async.sleep(50-100.millis)` gaps, then assert
  an **exact** captured sequence (`vs == Chunk(10, 11, 12)`) or count (`vs.size >= 4`). If the
  underlying stream-capture machinery takes longer than the fixed gap to observe+emit under CI load,
  two rapid sets could coalesce or reorder and break the exact-sequence assertion.
- `:878-891` (`"does not re-emit on a same-value set"`) and `:893-904` (`"stops after interruption"`):
  fixed sleep then assert *nothing new was observed* — an absence check that risks a false pass
  (not a false fail) on a slow machine, i.e. it can under-detect a real bug rather than flake red.

**Replacement**: the same file already has the right primitives (`pollUntil`, `awaitValue`,
`assertEventually`, used correctly at lines 812-930) — drive these three sequencing tests off
explicit per-step readiness signals (poll until the stream has consumed N values before firing the
next `set`) instead of a fixed millisecond gap.

### 16. `kyo-stm/.../STMTest.scala:1913-1936` — real nanoTime deltas between STM retry attempts

```scala
"STM.run with Schedule.fixed(20.millis) introduces gap between body attempts" in {
    ... stamps.updateAndGet(java.lang.System.nanoTime :: _) ... STM.retry ...
  yield
    val deltas = ...
    val bound = delay * 0.5
    assert(deltas.forall(_ >= bound), s"retry delays should be >= $bound; got $deltas")
}
```

Real-clock floor check (already tuned to a 50% margin, presumably after a prior flake). Timers don't
fire early, so this shouldn't flake red from CI slowness, but it is a real-wall-clock dependency for
something `ScheduleTest.scala` proves the pure, deterministic way elsewhere in the same module
(asserting `Schedule.next(...)` return **values**, never actually sleeping).

**Replacement**: if `STM.run`'s retry scheduler consumes the same `Clock` service, drive it through
`Clock.withTimeControl` and assert the scheduled invocation instants directly, eliminating the real
sleep and the margin entirely.

### 17. `kyo-direct/.../CoreTest.scala:58-70` — `"sleep and timeout"` real Clock.now floor, already skew-tuned

```scala
Async.sleep(50.millis).now
val elapsed = Clock.now.now - start
assert(elapsed >= 40.millis)
```

The load-bearing comment documents this was already hardened against JS wall-clock/monotonic-timer
skew (an 80% floor at 50ms specifically chosen to make the granularity negligible). Same
anti-pattern shape as #6-7, but already defensively tuned. `kyo-direct` doesn't appear to expose
`Clock.withTimeControl` through `direct { }` blocks elsewhere in this file, so full virtualization may
need that support to exist first.

### 18 (minor). `kyo-core/jvm-native/.../IOPromiseBlockingTest.scala:32-50` — fixed 10ms before `interrupt()`

```scala
while !threadStarted do Thread.sleep(10)   // OK: polls a condition
Thread.sleep(10)                            // fixed: "hope the thread reached promise.block() by now"
thread.interrupt()
```

The poll loop for `threadStarted` is fine; the second, unconditional `Thread.sleep(10)` is a fixed
guess that the spawned thread has reached the actual blocking call before `interrupt()` fires. Low
risk (Java's interrupt flag is sticky, so a slightly-early interrupt is usually still observed on
the next blocking call), but it is the pattern.

---

## REVIEWED & CLEARED (good pattern, or not actually time/threshold-based)

- **`ClockTest.scala`** — all `"Stopwatch"`, `"Deadline"`, `"Integration"`, and the
  `Clock.withTimeControl` branches of `"repeatAtInterval"`/`"repeatWithDelay"`/`"Monotonic Time"`/
  `"TimeControl wallClockDelay"` use virtual time (`control.advance`) — the model pattern.
  `"now"`/`"nowWith"`/`"unsafe now"` (`< 1.milli` vs `javaNow()`) are a correctness check of the live
  clock backend, not a performance/race assertion; left out of the ranked findings as very low risk.
- **`ProcessTest.scala`** — `"waitFor(timeout) returns/terminates/enforces..."` and the stdout-drain
  tests use `Async.timeout`/bounded `waitFor` as **config input**, asserting on `Present`/`Absent`
  **outcome**. Comments explicitly say *"no wall-clock ceiling is needed"* — this file is the
  reference example of the correct conversion for #9-12 above.
- **`HubTest.scala:332-356`** (`"backpressure with slow consumers"`) — comment documents an elapsed
  floor check was **already removed** in favor of an outcome assertion (`result == (1 to 10)`).
- **`SignalTest.scala`** — `pollUntil`/`awaitValue`/`assertEventually`-driven tests (lines 812-930,
  340-351, 367-383, 397-423, 425-439, 660-678, 1075-1111) are all the correct deterministic pattern.
- **`ScopeTest.scala`** — all `Async.sleep(...)` calls live *inside* the finalizer/resource action
  itself; the test blocks on the real Kyo effect chain (`Scope.run(...).map(...)`) rather than racing
  an external timer, so ordering is guaranteed by the effect system, not by wall time.
- **`CacheTest.scala:1831-1852`**, **`AsyncTest.scala:865-970`**, **`ScopeTest.scala:244-263`**,
  **`StreamCoreExtensionsTest.scala:336-429`** — `Async.sleep` used only to widen a race window so a
  concurrency-limit or memoization invariant has a chance to be violated if buggy; assertions are on
  counters/sets, never on measured elapsed time. Not the anti-pattern (risk direction is a missed bug,
  not a spurious failure).
- **`STMStressTest.scala`** — every `"...within bounded wall-clock"` / `"...within bounded time"` /
  `"...within bounded retries"` test (lines ~401, 756, 1130, and siblings) wraps the operation in
  `Async.timeout(N.seconds)` purely as a hang-safety net and asserts on the **outcome count**
  (`d == 32`), never on measured duration. Misleading test names, correct implementation.
- **`kyo-data/.../internal/*QueueTest.scala` + `UnsafeQueueBaseTest.scala`** (Mpmc/Mpsc/Spmc, ~14
  `Thread.sleep(200)` sites) — shared `concurrentTest` helper runs a fixed-duration stress window,
  then asserts invariants (no duplicates, no data loss, no thread left alive) that hold regardless of
  how much stress was actually applied. PASS/FAIL never depends on the timing value itself. **Minor
  ancillary note**: `MpscUnsafeQueueTest.scala:61` places its `Thread.sleep(1000)` *before*
  `start.countDown()` rather than after (unlike every sibling file), which appears to leave almost no
  actual concurrent-stress window before `stop.set(true)` fires — a likely copy/paste ordering bug
  that weakens this specific test's coverage. Not itself a time-threshold-assertion anti-pattern, but
  worth a maintainer's second look.
- **`kyo-data/.../DurationTest.scala`, `InstantTest.scala`, `ScheduleTest.scala`, `RecordTest.scala`**
  — all `assert(... == N.seconds)`-shaped hits are pure value equality on `Duration`/`Instant`/
  `Schedule.next(...)` return values; no real clock or sleep involved anywhere. Grep false positives
  from the `millis`/`seconds` unit suffixes.
- **`kyo-config/.../UpdateHistoryTest.scala:44-51`** — brackets a real timestamp between `before`/
  `after` reads taken immediately around the call; deterministic given a monotonic clock (no numeric
  threshold), not the anti-pattern.
- **`kyo-config/.../CloudTopologyTest.scala:178,186`** — `"within [0, 50)"` refers to bucket-percentage
  ranges, not time. Grep false positive.
- **`kyo-combinators/.../AsyncCombinatorsTest.scala`, `ConstructorsTest.scala`** — `Async.sleep` used
  only to produce a value after a delay; assertions are on the returned value, never on timing.
- **`kyo-prelude`, `kyo-kernel`** — no real hits; all grep matches were the English word "within" in
  test names/comments (`"...within Abort"`, `"...within isolate"`, etc.) or in
  `TraceTest.scala:255`'s comment about frame-count bounds, unrelated to wall-clock time.
- **`kyo-stm/.../STMPropertyTest.scala`, `TChunkTest.scala`, `TRefTest.scala`, `TTableTest.scala`,
  `STMTest.scala`** (all other hits) — "within a transaction" test-name phrasing only, or
  `nanoTime`-tagged stamps used purely for count assertions (e.g. `STMTest.scala:2564-2581`), not
  bound-checked.
- **`kyo-core/.../ChannelTest.scala`**, **`kyo-data/.../ChunkTest.scala`** — "within batch"/"within
  each chunk" refer to element ordering, not timing. Grep false positives.
- **`kyo-core/jvm/.../OSSignalTest.scala:16-28`** — `CountDownLatch.await(5, SECONDS)` is a bounded
  wait for a real external event (an OS signal), asserted on the boolean **outcome** of `await`, with
  a comment explicitly framing the 5s bound as "just a safety cap for a real failure," not a
  timing-sensitive threshold.
- **`kyo-core/jvm/.../GateJvmTest.scala:83-119`** — uses a bounded CPU **spin-count** (1,000,000
  iterations) to settle a lost-wakeup race, not a wall-clock duration; different risk profile
  (pure-CPU-bound, not scheduler/GC-pause-sensitive) and not what this sweep targets.
- **`kyo-core/.../RetryTest.scala:53-64`** (`"backoff"`) — measures real `currentTimeMillis` across an
  exponential-backoff retry (`elapsed >= 15`), a floor-only check tied to real `Async.sleep` delays a
  correct implementation always performs (timers can't fire early). Borderline: technically the
  anti-pattern shape, but essentially zero flake-red risk. Listed here rather than in the ranked
  findings because of that; still worth converting to `Clock.withTimeControl` + `Schedule` delay-value
  assertions (as `ScheduleTest.scala` does) for consistency and to drop the real sleep from the suite.

---

## Summary

18 flagged sites (several are multi-instance clusters, e.g. 8x in `MeterTest.scala`, 3x in
`SignalTest.scala`'s `streamChanges` tests). Highest-confidence, highest-flake-risk items are the
`ClockTest.scala` real-clock `"Sleep"`/`"TimeShift"` sections (#1) and the two magic-ceiling
perf/throughput guards (#2, #3) — all three have either an explicit CI-flakiness comment already on
them or a documented history of exactly this failure mode elsewhere in this repo's test suite. The
`MeterTest.scala`/`HubTest.scala` "sleep-then-assert-not-done" cluster (#9-12) is the largest by
instance count (10 occurrences) but lowest flake-red risk since each tests a *permanent* blocked
state; the concrete `Async.timeout` + outcome-assertion replacement is the same one-line
transformation for all of them, and this exact repo already has the reference implementation of it
in `ProcessTest.scala` and `SignalTest.scala`.
