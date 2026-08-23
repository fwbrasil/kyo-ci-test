# Time / threshold-based-assertion sweep — application modules

Analysis only. Scope: kyo-http, kyo-sql, kyo-sql-tests, kyo-sql-postgres, kyo-sql-mysql, kyo-pod,
kyo-jsonrpc, kyo-jsonrpc-http, kyo-aeron, kyo-ffi, kyo-tasty, kyo-actor, kyo-flow, kyo-mcp, kyo-caliban,
kyo-zio, kyo-zio-test, kyo-schema(+json/bson/ion/msgpack/protobuf/yaml/tests), kyo-compat, kyo-doctest,
kyo-slack, kyo-i18n, kyo-markdown, kyo-reactive-streams, kyo-lsp, kyo-ai, kyo-case-app, kyo-website,
kyo-stats-otlp, kyo-stats-machine. (kyo-ffi-it does not exist as a separate module; its content lives at
`kyo-ffi/it/`, which is covered.)

Method: grepped every `**/src/test/**/*.scala` in scope (1143 files) for `elapsed`, `timed(`, `.toMillis`,
`.toNanos`, `nanoTime`, `currentTimeMillis`, `assert(...millis|seconds...)`, `under \d`, `within`, `budget`,
`Async.sleep`, `Thread.sleep`, `deltaMs`/`durationMs`/`elapsedMs`, then read each hit in context (15-90
lines) to classify. `within`/`budget` are heavily false-positive in this codebase (scope language, SQLSTATE
naming, LLM token budgets, poll-attempt counts) — every one was individually opened and confirmed before
being cleared, not assumed.

Note on process: two parallel sub-agent dispatches for part of this scope failed to start (nested forking
is unavailable from this worker); the module coverage below was produced directly, first-hand, file by file.

---

## ANTI-PATTERN findings (ranked, most flaky-risk first)

### 1. `kyo-pod/shared/src/test/scala/kyo/ContainerOrchestrationItTest.scala:2036-2045` — kyo-pod — `"streaming build progress events arrive incrementally, not buffered until completion"` (test name approximate; leaf builds a 4+s image via two `sleep 2` steps)
```scala
assert(
    firstMs < 3000,
    s"First build event took ${firstMs}ms — expected < 3000ms for a 4s build. " +
        "Events are likely buffered until build completes (not streaming)"
)
```
Why wall-clock: absolute upper bound on the time-to-first-event from a real `docker/podman build`, exactly the same shape as the already-fixed "init completes under 2s" flake (task history: JS `ContainerOrchestrationItTest` failed once at 2.1s under load). Daemon/build overhead varies a lot under CI load; 3000ms vs. an intended "well under 4000ms" gives thin margin.
Replacement: assert a **relative** ordering instead of an absolute ceiling — e.g. record both `firstMs` and the total build completion time `totalMs`, and assert `firstMs < totalMs / 2` (or `firstMs` strictly precedes the second `sleep 2` step, if the build emits a distinguishable per-step marker). That keeps the signal (streaming vs. buffered) without pinning an absolute constant.

### 2. `kyo-pod/shared/src/test/scala/kyo/ContainerItTest.scala:2723-2735` — kyo-pod — `"exec on stopped container fails fast without wasting time on retries"` (name approximate)
```scala
val elapsedMs = t1.toJava.toEpochMilli - t0.toJava.toEpochMilli
assert(
    elapsedMs < 500,
    s"exec on stopped container took ${elapsedMs}ms — " +
        "retries are wasting time on a deterministic NotFound/AlreadyStopped failure"
)
```
Why wall-clock: uses an absolute 500ms ceiling as a *proxy* for "no retries happened" (comment explains the Retry schedule is `Schedule.fixed(100.millis).take(2)`, i.e. retrying would add 200ms+). A backend HTTP/SSH latency spike (podman's SSH-based daemon, per a neighboring comment) can push a legitimately-single-attempt failure over 500ms.
Replacement: instrument or spy the retry call site to assert the **attempt count directly** (e.g., wrap `Retry` with a counter and assert `attempts == 1`) instead of inferring "no retry" from elapsed time.

### 3. `kyo-pod/shared/src/test/scala/kyo/ContainerItTest.scala:156-162` — kyo-pod — `"Meter bounds container exec concurrency"` (name approximate; 6× `sleep 0.5` execs under `meter=2`)
```scala
val elapsed = java.lang.System.currentTimeMillis() - start
assert(results.forall(_.isSuccess))
assert(elapsed >= 1000, s"Expected >= 1000ms with meter=2, took ${elapsed}ms")
```
Why wall-clock: distinguishes "bounded concurrency" from "unbounded" purely via a total-duration floor. This is the exact anti-pattern the same codebase already fixed elsewhere (see `kyo-compat/.../MeterTest.scala`, which replaced `elapsed < 5s` with a peak-active-count canary, and left a comment explaining why: *"the former `elapsed < 5s` bound verified neither and could not tell 2 from 4"*).
Replacement: record client-side start/end wall-clock timestamps per exec call and compute peak interval overlap (same technique as `kyo-compat/RaceZipTest`/`MeterTest`/`ForeachTest`), asserting `peakConcurrent == 2`, rather than a total-elapsed floor.

### 4. `kyo-compat/test/shared/src/test/scala/kyo/compat/TimeTest.scala:190-202` — kyo-compat — `"concurrent sleeps complete in parallel — total time ~max not sum"`
```scala
// sleep(50ms) + sleep(100ms) in parallel should take ~100ms, not ~150ms.
// Allow 250ms for CI noise.
c.map { deltaMs =>
    assert(deltaMs < 250L, s"concurrent sleeps took ${deltaMs}ms — expected < 250ms (parallel, not sequential)")
}
```
Why wall-clock: the comment itself concedes the fragility ("Allow 250ms for CI noise") — only 100ms of margin between the "parallel" (~100ms) and "sequential" (~150ms) outcomes on a shared CI runner. This is precisely the shape `ForeachTest`/`RaceZipTest`/`MeterTest` in the same file's sibling files were already rewritten away from (see cleared list below) — this one test in `TimeTest.scala` was missed by that cleanup.
Replacement: same peak-active-count canary as the sibling fix — wrap each `CIO.sleep` call with active/peak `AtomicInteger` markers via `CIO.zip`, assert `peak == 2` (both sleeps were concurrently in flight), not a total-elapsed band.

### 5. `kyo-compat/test/shared/src/test/scala/kyo/compat/TimeTest.scala:174-186` — kyo-compat — `"sleep(0) returns immediately within bounded window"`
```scala
c.map { deltaMs =>
    assert(deltaMs < 500L, s"sleep(0) took ${deltaMs}ms — expected < 500ms")
}
```
Why wall-clock: classic "completes under Nms" ceiling on a real scheduler round-trip.
Replacement: assert only that `t2 >= t1` (monotonic, already the pattern used two tests earlier in the same file for `nowMonotonic`), dropping the magnitude bound entirely — the "does not hang" property is separately guaranteed by the harness-level `testTimeout` (60s) in `CompatTest.run`.

### 6. `kyo-compat/test/shared/src/test/scala/kyo/compat/TimeTest.scala:64-76` — kyo-compat — `"sleep delays at least the requested duration (wall-clock)"` / `"delay waits at least the requested duration (wall-clock)"`
```scala
val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
assert(elapsed >= 30L && elapsed < 5_000L, s"elapsed=$elapsed ms")
```
Why wall-clock: measured `nanoTime` band; self-labeled "(wall-clock)". Margins are generous (30ms floor for a 50ms request, 5s ceiling) so flake probability is low, but it is still the flagged shape and duplicated once per backend-neutral shim.
Replacement: harder to fully eliminate since this specifically verifies the compat shim's `sleep`/`delay` actually delays on the real underlying engine (ZIO/cats-effect/Ox) rather than a virtual clock kyo controls. If a lower-risk fix is wanted, widen the floor margin further or fold into a single relative check (e.g., "sleep(100ms) takes measurably longer than sleep(1ms)") rather than an absolute floor/ceiling pair.

### 7. `kyo-compat/test/shared/src/test/scala/kyo/compat/TimeTest.scala:78-92` — kyo-compat — `"FiniteDuration: 500L.millis materializes as FiniteDuration with correct millis"`
```scala
assert(elapsedMs >= 400L && elapsedMs < 30_000L, s"elapsed=$elapsedMs ms (expected >= 400ms)")
```
Why wall-clock: same measured-sleep-duration shape as #6, nested inside what the test name frames as a pure Duration-conversion check (misleading: the assertion is actually a live 500ms sleep). Same replacement approach as #6.

### 8. `kyo-compat/test/shared/src/test/scala/kyo/compat/FiberTest.scala:190-209` — kyo-compat — `"CFiber.get on already-completed fiber returns immediately"`
```scala
assert(deltaMs < 100L, s"fiber.get on already-completed fiber took ${deltaMs}ms, expected < 100ms")
```
Why wall-clock: "under Nms" ceiling on a no-op fiber-get round-trip; 100ms is generous for a genuine no-op but GC pauses / scheduler contention on a loaded CI runner can still spike a single call.
Replacement: assert a **relative** comparison instead — measure the same `.get` latency on a fiber that is *not* yet complete (so it genuinely suspends) and assert the already-completed case is measurably faster (e.g., `<` the other, not `<` an absolute constant), or instrument via a counter proving no suspension/park occurred rather than timing it.

### 9. `kyo-http/shared/src/test/scala/kyo/internal/UnsafeServerDispatchTest.scala:1509-1531` — kyo-http — `"idle connection closed after timeout"`
```scala
val config = defaultConfig.idleTimeout(200.millis)
...
Async.sleep(500.millis).andThen {
    val result = inbound.offer(Span.fromUnsafe("test".getBytes))
    result match
        case Result.Failure(_: Closed) => succeed
        case other                     => assert(false, s"Expected channel to be closed, got: $other")
}
```
Why wall-clock: textbook "sleep long enough, then assert state settled" race — configures a 200ms idle timeout, sleeps 500ms (2.5x margin), then asserts the connection was closed by the idle timer. Under heavy CI load the idle-timer-driven close could plausibly not have completed within the 500ms window.
Replacement: check whether `UnsafeServerDispatch.serve`'s idle timer reads the ambient `Clock` effect; if so, drive the test with `Clock.let(TestClock)` and `tc.advance(200.millis)` (or the direct equivalent) instead of a real sleep, matching the pattern `kyo-flow/FlowEngineTest.scala`'s `"output completes within timeout"` already uses (`withEngine { (engine, store, tc) => ... }`). If the idle-timeout wiring genuinely isn't clock-injectable yet, that plumbing gap is itself worth closing.

### 10. `kyo-tasty/jvm/src/test/scala/kyo/StandardClasspathFidelityTest.scala:60-85` — kyo-tasty — `"cold-init median < 5 seconds on standard classpath"` (name approximate)
```scala
val times  = Chunk(t1, t2, t3).sortBy(_.toMillis)
val median = times(1)
assert(median < 5.seconds, s"Expected cold-init median < 5 seconds on standard classpath; got ${median.toMillis} ms ...")
```
Why wall-clock: real classpath-load timing with a hard ceiling, though median-of-3 is a decent de-noising technique. The file's own header comment acknowledges "jrt:/ cold loads can still be slow on a contended runner" (hence the 3-minute leaf timeout raised separately) — the 5s *median* assertion is a second, tighter bound layered on top of that risk.
Replacement: no virtual-clock equivalent exists for real classpath I/O; if flake is observed, either raise the ceiling with the same contended-runner rationale as the leaf timeout, or drop the absolute bound in favor of a regression-only comparison (e.g., compare against a stored baseline with tolerance) — lower priority than 1-9 given the built-in median smoothing.

### 11. `kyo-markdown/shared/src/test/scala/kyo/MarkdownTest.scala:445-454` and `kyo-website/jvm/src/test/scala/kyo/website/DocsMarkdownTest.scala:658-667` — kyo-markdown / kyo-website — `"large synthetic README/module transpiles and renders in bounded time"` (twin tests, near-identical)
```scala
val elapsed = (java.lang.System.nanoTime() - start) / 1000000L
...
assert(elapsed < 30000L, s"render took ${elapsed}ms (budget 30000ms): not linear")
```
Why wall-clock: absolute 30s ceiling used as a proxy for "the renderer is O(n), not O(n²)" on a ~100-128KB synthetic doc. Comment states intent is catching algorithmic-complexity regressions, not literal wall-clock performance. Margin is very generous (real render is presumably well under 1s), so flake risk is low, but it is structurally the flagged pattern and duplicated verbatim across two modules.
Replacement: a true deterministic complexity check would render at two sizes (e.g., 1x and 4x the synthetic doc) and assert the elapsed-time **ratio** stays sub-quadratic (e.g., `elapsed4x < elapsed1x * 6` for a 4x input) rather than an absolute constant — still wall-clock-based but self-normalizing to the runner's speed. Given the wide existing margin, this is lower priority than 1-9.

### 12. `kyo-sql-tests/shared/src/test/scala/kyo/SqlEndToEndTest.scala:653-676` — kyo-sql-tests — `"close/closeNow on an idle client return promptly, not after the full grace period"` (name approximate)
```scala
_ <- c1.close(30.seconds)
elapsed1 = e1 - t1
_        = assert(elapsed1 < 5.seconds, s"close(30.seconds) on idle client took $elapsed1, expected < 5.seconds")
// close (default 30s) on an idle client must complete in < 5 seconds.
...
elapsed2 = e2 - t2
_        = assert(elapsed2 < 5.seconds, ...)
// closeNow (Duration.Zero) on an idle client must complete in < 1 second.
...
elapsed3 = e3 - t3
_        = assert(elapsed3 < 1.seconds, ...)
```
Why wall-clock: three back-to-back "under N seconds" ceilings against a real, container-backed Postgres client's close path. This module (`kyo-sql-tests`) already has a documented fix for exactly this shape one file over (`SqlConfigUrlOptionsTest.scala`, see cleared list) — these three assertions are the same anti-pattern the sibling file explicitly avoided, in the same module.
Replacement: per `SqlConfigUrlOptionsTest.scala`'s own stated rationale, assert on **evidence the close path took**, not how long it took — e.g. instrument `SqlClient.close`/`closeNow` to expose (even just for tests) which internal path fired (immediate-return-because-idle vs. graceful-drain-then-timeout), and assert on that discriminant instead of a wall-clock ceiling.

### 13. `kyo-sql-tests/shared/src/test/scala/kyo/postgres/CancelIntegrationTest.scala:36-48` and `kyo-sql-tests/shared/src/test/scala/kyo/mysql/MysqlCancelIntegrationTest.scala:222-234` — kyo-sql-tests — `"caller is released on Async.timeout, not on query completion"` (identical shape, twin PG/MySQL tests)
```scala
Abort.run[Timeout](Async.timeout(1.second)(Abort.run[SqlException](client.query(longQuery)))).flatMap {
    case Result.Failure(_: Timeout) =>
        stopwatch.elapsed.map { waited =>
            assert(waited < 20.seconds, s"the caller must be released on the timeout, not on the query, waited $waited")
        }
    ...
```
Why wall-clock: measured elapsed with a numeric ceiling, but the margin is 20x the trigger point (1s timeout vs. 20s ceiling) — effectively a "did not hang indefinitely" sanity net rather than a tight race. Lowest priority of the elapsed-based findings.
Replacement: if tightened at all, assert the caller is released strictly before the long-running query's own natural completion time (a relative comparison) rather than an absolute constant; given the generous existing margin this is optional.

---

## Lower-confidence / accepted-tradeoff note (not counted above, flagged for awareness only)

- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/MaxInFlightTest.scala:367-379` — the file's own comment explicitly documents this as *"the one timing test deliberately kept on real Async.sleep"* because the reset-deadline monitor fiber doesn't observe `Clock.withTimeControl`, with margin analysis (300ms vs. 1s reset window) already reasoned through. Real-time-dependent by design, already justified and documented — not re-flagged as a fresh finding, but noted since it is inherently wall-clock and worth revisiting if `Clock.withTimeControl` propagation into detached fibers is ever fixed.

---

## Reviewed & cleared

Legitimate / not-flaky patterns confirmed by reading each hit in context:

- **`kyo-compat/test/.../{ForeachTest,RaceZipTest,MeterTest}.scala`** — already fixed: peak-active-`AtomicInteger` canaries replaced prior `elapsed < Ns` bounds; each carries an explanatory comment. Good precedent (findings #3-4 above are exactly this fix, not yet applied to their remaining siblings).
- **`kyo-compat/test/.../TimeTest.scala:52-62,101-106`** ("now returns Instant close to system clock") — 5s window sanity check on `CIO.now` vs `System.currentTimeMillis`; margin is 1000x+ the expected delta, essentially a "clock isn't wildly wrong" check, not a race.
- **`kyo-compat/test/.../TimeTest.scala:18-31`** (`nowMonotonic` non-decreasing) — asserts `b >= a` only, no magnitude bound.
- **`kyo-compat/test/jvm/.../FromCompletionStageTest.scala`** — `Thread.sleep(50)` inside a spawned raw `java.lang.Thread` used purely to simulate an async completion callback firing later; the CIO-side assertion suspends deterministically via `fromCompletionStage`'s own callback registration (no race against the sleep duration).
- **`kyo-compat/test/.../{BlockingCedeTest,LiftingTest,LatchTest,CompatTest}.scala`** — `within`/`budget`/timeout hits are all: (a) `CompatTest.testTimeout` = 60s harness-level per-test safety net, (b) `CIO.timeout(...)` wrapping a deterministic Some/None outcome, (c) a 50ms pre-release sleep in `LatchTest` used only as a scheduling nudge before a 300ms-timeout-wrapped deterministic assertion (very generous margin).
- **`kyo-markdown`, `kyo-website`** — all `within` hits are URL-scope language ("links within /v1.0.0/"), not timing.
- **`kyo-doctest/.../{RuntimeExecutorTest,OrchestratorTest}.scala`** — `assert(after == 250.millis)` etc. assert on the **configured** timeout value echoed back by `RuntimeExecutor.Outcome.TimedOut`, not a measured elapsed time; the underlying mechanism (real subprocess timeout enforcement) inherently needs wall-clock but the assertions themselves are deterministic. `DefaultsParserTest`/`ModifierParserTest` assert on parsed config durations (data, not measurement).
- **`kyo-http`** — all `assert(...millis|seconds...)` hits are config-default/data-value equality checks (`HttpClientConfig().timeout == 5.seconds`, SSE `retry` field, etc.), not measured wall-clock. All `within N attempts`/`within 5s` in `kyo-aeron` and bounded-poll helpers across `kyo-http` are deterministic bounded-attempt-count polling with a final deterministic outcome assertion (the dominant, correct idiom in this codebase).
- **`kyo-http/jvm/.../HttpServerResilienceTest.scala`** — `currentTimeMillis`-based `deadline` controls a chaos/stress-test **workload duration** (how long to hammer the server), not a pass/fail threshold; the actual assertions (`bug.get() == 0`, `liveResult == Result.Success("pong")`) are fully deterministic invariant counters.
- **`kyo-http/.../UnsafeServerDispatchTest.scala`** — `"active connection not closed"` sibling test to finding #9 has no sleep-based race (pipelined requests, no idle gap); cleared.
- **`kyo-pod`** — `Retry[AssertionError](Schedule.fixed(50.millis).take(40))` polling patterns (log-flush waits) are deterministic bounded-retry-for-eventual-consistency, the correct idiom; `BasePodTest`'s 60s `HttpClient` timeout is a documented CI-latency safety net; `ContainerOrchestrationItTest`'s `"isHealthy runs the check once"` explicitly documents having already been fixed to count invocations "rather than by wall-clock time" (comment at line 426-428) — the exact fix pattern findings #1-3 recommend, already applied here. `ContainerItTest`'s stopTimeout/cleanup-wait assertion (`elapsedMs >= 800 && < 20000`) is a real timing behavior test (verifying `stopTimeout` config is honored) with no available virtual-clock substitute since it kills a real container process; noted but not counted as a top finding given no better alternative exists. `ContainerItTest`'s `Async.sleep(2.seconds)` before a `logStream.take(1).run` check is a generous-margin setup sleep whose downstream assertion (`entries.size <= 1`) is a loose upper bound that holds regardless of timing.
- **`kyo-aeron`** — every `within N attempts` / `within 5s` hit across `AeronTransportTest`, `TopicTest`, `TopicRoundTripTest`, `TopicInvariantsTest`, `TopicBackpressureReconnectTest`, `TopicUniformInvariantsTest` is a bounded-attempt polling helper (`awaitTrue`, `pollUntil`, `offerUntil`, probe-backoff loops) with a deterministic final assertion. `Async.sleep(1-5ms)` scheduling nudges inside repeated-iteration UAF/close-race stress loops are inherent to reproducing a race condition (per CONTRIBUTING.md's own guidance to drive races deterministically where possible but loop probabilistic ones for reliability) — not the "single-shot sleep-then-assert" anti-pattern.
- **`kyo-sql`, `kyo-sql-tests`, `kyo-sql-postgres`, `kyo-sql-mysql`** — all `assert(...millis|seconds...)` hits outside finding #12/#13 are config-value or exception-carried-Duration equality checks (`SqlClientConfig` defaults, `SqlConnectionCancelTimeoutException(2.seconds)`, `SqlRequestAdvisoryLockException` wait-budget field, MySQL/Postgres `OffsetDateTime`/`Interval` encoders operating on **data** timestamps, not test timing). `SqlConfigUrlOptionsTest.scala` has an explicit doc comment describing having already fixed this exact anti-pattern ("The two timeout leaves assert the duration carried by the exception rather than how long the call took. A wall-clock assertion would be flaky..."). All `Async.sleep` hits in warmup/retry/channel tests are either `Duration.Infinity` (never-respond simulation), bounded backoff-polling (`awaitSslReady`, 30 attempts), or stress/churn-duration config, not pass/fail timing.
- **`kyo-jsonrpc`, `kyo-jsonrpc-http`** — `JsonRpcHandlerTest.scala` has an explicit doc comment describing having already replaced a "wall-clock `elapsed < 900` proxy" with a gate-ordering guarantee. `JsonRpcHttpTransportTest.scala`'s `Async.sleep(500ms)` (server keeps a WS connection open after sending a message) and `Async.sleep(50ms)`-polling-inside-`Async.timeout(2s)` are both generous-margin setup/bounded-poll patterns whose actual client-side assertions are independently bounded by a 5s deterministic timeout.
- **`kyo-tasty`** — `TestClasspathsTest.scala`'s `assert(elapsed1 >= 0, ...)` is trivially true (elapsed time cannot be negative) — not a flakiness risk, just a low-value assertion. `DecoderFidelity5Phase04Test.scala`/`EvictOlderThanTest.scala` `.toMillis`/`.toNanos` hits operate on Duration **data values**, not measurements. All ~20 `within`/`budget` hits across `DifferentialTastyTest`, `ClasspathPureDataTest`, `InvariantsSpec`, `SymbolIndexTest`, `TypeTraversalTest`, etc. are scope/comment language ("within a run", "within the class-file limit") or the file-level "generous per-leaf budget" timeout-safety-net comment in `StandardClasspathFidelityTest.scala`, not timing assertions.
- **`kyo-ffi`** — `GuardCloseStressTest.scala`'s `Thread.sleep(1-5ms)` inside a raw-Thread callback simulates variable-length retained-callback work to create concurrency pressure for a native close/callback race; the actual assertions are on deterministic outcomes (`Clean`/`TimedOut` both accepted, no deadlock, `isClosed == true`), not elapsed time. All `within`/`budget` hits in `GuardRegistryStressTest`, `ScratchReadCStringBoundedTest`, `ScratchBulkNulScanTest`, `ScratchAutoGrowTest`, `JvmBorrowedBufferBoundsTest` are buffer-bounds language, not timing. No `elapsed`/`under N`/`deltaMs` numeric-threshold hits found anywhere in the module.
- **`kyo-flow`** — `FlowEngineTest.scala`'s `"output completes within timeout"` runs under `withEngine { (engine, store, tc) => ... }`, i.e. a controlled `TestClock` (`tc`) — the explicitly legitimate pattern per the sweep brief. Correctly implemented already.
- **`kyo-mcp`** — `McpConfigTest.scala`'s `assert(cfg.handshakeTimeout == 10.seconds)` is a config-default equality check.
- **`kyo-schema-bson`, `kyo-schema-json`, `kyo-schema-tests`** — `BsonTest.scala`'s "millisecond precision" hit is an error-message substring check unrelated to test timing; `SchemaStructureTest.scala`'s `.toNanos` is a `Schema.transform` type-plumbing test on `Duration` values; `JsonTest.scala`'s "within" hits are numeric-precision language ("within 1 ULP"); `SchemaAnnotationTest.scala`'s "within" hit is about class-file size limits; `ProtobufTest.scala`'s "under N" hits are byte-count language ("under 8 bytes remain").
- **`kyo-slack`** — all `assert(...millis|seconds...)` hits are config/exception-field equality checks (`SlackConfig.ackDeadline`, `SlackRateLimitException.retryAfter`, socket-engine `autoPingInterval`).
- **`kyo-caliban`** — `ResolversTest.scala` has an explicit doc comment for `awaitClose`: *"Use instead of `Async.sleep + ws.closeReason` to remove timing dependency from tests"* — already fixed to a bounded-poll-with-timeout pattern, with documented CI-contention margin reasoning. The remaining `Async.sleep(50-200ms)` hits are all post-operation "let cleanup settle" delays followed by assertions that don't depend on the sleep's sufficiency (plain success/no-hung-fiber checks).
- **`kyo-zio`** — `ZStreamsTest.scala`'s `Async.sleep(1.milli)` hits inside stream element mapping are scheduling nudges to force genuine async interleaving; all downstream assertions are on deterministic collected values (sorted list equality, combined result-set equality), not timing.
- **`kyo-ai`** — `LLMStreamTest.scala` and `LLMTest.scala` carry explicit comments describing the *avoided* anti-pattern: *"the leaf cannot flake on a slow runner where delivery alone exceeds a fixed millisecond budget"* / *"The deadline is a budget on the provider's PRODUCTION, not on the consumer's wall-clock"* — deliberately designed around this exact failure mode already. `CompletionTest.scala`'s `assert(ex == AICompletionTimeoutException(..., 30.seconds))` is a config-value equality check. "budget" elsewhere in the module is LLM token budget / iteration budget, unrelated to timing.
- **`kyo-i18n`, `kyo-lsp`, `kyo-case-app`, `kyo-reactive-streams`, `kyo-zio-test`, `kyo-stats-otlp`, `kyo-actor`** — zero hits across the full high-signal grep set (`elapsed`, `timed(`, `.toMillis`, `.toNanos`, `nanoTime`, `currentTimeMillis`, `assert(...millis|seconds...)`, `under N`, `Async.sleep`, `Thread.sleep`, `deltaMs`/`durationMs`/`elapsedMs`). Confirmed clean, not merely un-searched.
- **`kyo-stats-machine`** — `MachineHandlesJvmTest.scala`'s "Thread.sleep" hit is a **string literal** the test greps for as a banned construct in main source files (a hygiene lint, not an actual sleep call). No genuine timing-assertion hits found; the historical Windows 0-alloc flake (task-log reference) already carries its own fix (`AllocationProbe` per-window floor) and shows no remaining wall-clock-threshold assertion in the current tree.
