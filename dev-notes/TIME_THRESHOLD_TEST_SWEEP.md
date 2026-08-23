# Time / threshold based test sweep (consolidated inventory)

Goal (user): **improve the test suites so pass/fail does not depend on measured wall-clock time or numeric time thresholds.** This is the re-run of the earlier sweep, which had major holes; it is the durable fix for the CI flakes that keep breaking the 3-green streak one-test-per-run.

## What counts as an anti-pattern

An assertion whose pass/fail depends on **measured real elapsed time vs a numeric threshold/band**, or a **fixed sleep then assume-settled** race. Ranked by shape:

- **Two-sided band / bare ceiling** (`elapsed <= N`, `elapsed in [a,b]`): a loaded runner only pushes elapsed up, straight through the ceiling → **spurious FAIL** (breaks CI). Highest priority.
- **Fixed-sleep-then-assert** (`Async.sleep(N); assert(state)`): the sleep is a guess that a real side effect has landed; too-tight margins **fail**, absence-checks **falsely pass**.
- **Lower-bound-only** (`elapsed >= N`): hardware-monotone, load pushes it further into passing → **no flake**, but still a wall-clock assertion and often redundant next to a state assertion in the same `yield`.
- **Perf/linearity budget** (`render < 30000ms`): machine-variance class; not a per-run flake but a latent one on a slow/loaded runner.

**NOT anti-patterns** (deliberately cleared, do not touch): virtual clock (`Clock.withTimeControl`/`tc.advance`); `Duration`/`Schedule` asserted by equality (config-as-data); `Async.timeout(N)` as a deadlock/safety ceiling with the assertion on the typed outcome; bounded **poll-until-condition** loops (`awaitCondition`/`assertEventually`) that re-check real state and assert on the settled outcome; `nanoTime()` used only for unique names; protocol-carried ms fields asserted by equality; `timed(...)` used diagnostically (value in the failure message only).

---

## Priority tiers

### Tier 1 — CAN SPURIOUSLY FAIL under load (breaks CI). Fix first.

| # | File:line | Test | Shape | Deterministic replacement |
|---|-----------|------|-------|---------------------------|
| 1 | `kyo-browser BrowserSettlementTest.scala:1080` | mutationQuiescenceWindow(10ms) resolves in first window | band `[50,320]ms` (observed 602ms) | **DONE (this session):** assert `#root` text `!= "e"` (released before final mutation); foil already asserts `== "e"`. |
| 2 | `kyo-browser BrowserSettlementTest.scala:1155` | mutationSettlementTimeout(500ms) shortens timeout | band `[400,1500]ms` | assert the raised `BrowserAssertionTimedOutException` / `SettlementResult.Timeout` carried **budget == 500** (not 2000). |
| 3 | `kyo-browser BrowserSnapshotConfigLocalTest.scala:56` | restoreSnapshot consults configLocal.loadSchedule | `elapsed < 2s` | run under two schedules with different retry counts; assert probe counts differ (`window.__probeCount`), or assert typed failure under an exhausting schedule. |
| 4 | `kyo-browser BrowserDownloadTest.scala:490` | recordDownloads captures every event in order | `Async.sleep(2s)` then assert list | complete a `Promise` when 3 distinct `WillBegin` guids arrive; replace sleep with `done.get`; then assert `names == expected` (ordered; drop the `toSet` disjunct). |
| 5 | `kyo-browser CdpBackendIntegrationTest.scala:254` | closeNow returns in < 100ms | `elapsed < 1s` (name says 100ms) | assert the close EFFECT: subsequent `getTargets` raises `BrowserConnectionLostException`; delete the elapsed read (promptness covered by leaf timeout). |
| 6 | `kyo-browser BrowserVerifyReadTest.scala:105` | settle reads bound from configLocal | `elapsedA < elapsedB` | count probes (`window.__reads`) instead of timing; assert `readsUnderA < readsUnderB`. |
| 7 | `kyo-browser BrowserSettlementTest.scala:941` | NetworkIdle waits for 3-fetch burst | band `[200,5000]ms` | fixture records completions (`window.__done`); after `goto` assert `__done === 3`. |
| 8 | `kyo-browser BrowserSettlementTest.scala:1025` | Settle.Load waits for slow `<img>` | band `[400,8000]ms` | assert `document.images[0].complete && naturalWidth>0` at return. |
| 9 | `kyo-browser BrowserScreencastTest.scala:281` | screenshotFrames duration cap unit | `ex.reached < 10000` (contradicts its comment) | unit-anchor: `ex.limit == 300 && ex.reached > ex.limit`; fix the comment. |
| 10 | `kyo-browser BrowserLauncherPlatformTest.scala:166` (jvm) | shutdown hook kills sub-JVM proc | 1s poll cap in FAILING direction | raise cap to a real backstop (30s; loop exits early on success) or block on `ProcessHandle.onExit().get(30,SECONDS)`. |
| 11 | `kyo-net PosixTransportAcceptEmfileTest.scala:154` (jvm-native) | no spin on acceptNow EMFILE (bounded retry) | `Async.sleep(250ms)` then `count <= 8` | signal a `Promise`/latch each time `acceptResourceBackoff` re-arms; await a fixed count of re-arm events with `Async.timeout` safety; assert the event count, not a wall-clock window. |
| 12 | `kyo-http HttpClientTest.scala:1296` | exponential backoff | `delay2 >= delay1` on real inter-request gaps | assert the computed retry delay the executor requested, or drive the retry clock via `Clock.withTimeControl`. |
| 13 | `kyo-http UnsafeServerDispatchTest.scala` (×17, esp. `:1583` 0.4x margin, `:1613` 0.75x) | idle-timeout / keep-alive / WS-cleanup family | `config.idleTimeout(N)` + `Async.sleep(guess)` then assert state | **PRODUCTION SEAM:** inject a controllable `Clock` into `UnsafeServerDispatch`'s idle-timer path and drive with `tc.advance`, or expose a completion `Promise`/`Latch` set when the idle fiber fires; await it instead of sleeping. |
| 14 | `kyo-sql-tests SqlEndToEndTest.scala:656` | close / closeNow latency | `elapsed < 5s / < 1s` (real Postgres) | instrument the close path to expose which branch it took (graceful-wait vs immediate) via a flag; assert the branch. |
| 15 | `kyo-pod ContainerOrchestrationItTest.scala:485` | scope cleanup waits stopTimeout | band `[800,20000]ms` | assert the container's reported exit signal (137 = SIGKILL after trap-delayed SIGTERM), not elapsed. |
| 16 | `kyo-pod ContainerItTest.scala:145` | Meter limits concurrency | `elapsed >= 1000ms` | track peak concurrent in-flight via `AtomicInt`; assert peak `<=` limit. |
| 17 | `kyo-pod ContainerItTest.scala:~2726` | exec on stopped container: no retries | `elapsedMs < 500` | count actual exec attempts via a hook; assert `== 1`. |
| 18 | `kyo-pod ContainerItTest.scala:~1295` | delivers entries incrementally | `totalMs > 1000` | assert distinct flush events / ordering relative to a causal signal, not absolute duration. |
| 19 | `kyo-pod ContainerItTest.scala:~1778` | imagePull contacts registry | `pullMs > ensureMs*2 || pullMs > 500` | spy/count actual registry HTTP calls of `pull` vs `ensure`. |
| 20 | `kyo-pod ContainerItTest.scala:~2000` | streams build progress incrementally | `firstMs < 3000` | assert first event content is `step1` while `step2` has not yet arrived (causal ordering). |
| 21 | `kyo-compat TimeTest.scala:200` | concurrent sleeps ~max not sum | `deltaMs < 250` | peak-concurrency `AtomicInteger` canary (sibling `RaceZipTest`/`ForeachTest` already do this); assert `peak == 2`. |

### Tier 2 — fixed-sleep-then-assert; mostly weak coverage (false-PASS), some fail-flake.

| # | File:line | Test | Note / replacement |
|---|-----------|------|--------------------|
| 22 | `kyo-net NioIoDriverTest.scala:768` (jvm) | pre-detach arm not spuriously failed | nothing stimulates `p`; drop the 100ms sleep, assert `!p.done()` immediately (or N deterministic yields). |
| 23 | `kyo-net NioIoDriverTest.scala:282` (jvm) + `JsIoDriverTest.scala:83` (js-wasm) | isPeerClosed stays false for live peer | poll a fixed N times via the in-file `awaitCondition` helper instead of one 300ms sleep. |
| 24 | `kyo-net JsTransportTlsTest.scala:335` (js) | stalled TLS not reaped when timeout=Infinity | race vs `sleep(400ms)`. **PRODUCTION SEAM:** JS handshake deadline is a Node timer, not kyo `Clock`; making it fully deterministic needs an injectable clock into the JS transport handshake path. |
| 25 | `kyo-net TransportHandshakeTimeoutTest.scala:185` (shared) | completed handshake not reaped | `sleep(1500ms)` vs 1s deadline; thread `Clock.withTimeControl` through `Connection.init(clock=...)` (proven in `ReadPumpBackpressureTest`) and `tc.advance`. |
| 26 | `kyo-net WritePumpTest.scala:654` (jvm-native) | write Error tears down pump (no resurrection) | `sleep(50ms)` then assert no write. Have the spy complete a "resurrection" Promise on any post-teardown write; race it against the pump fiber terminating. |
| 27 | `kyo-net JsIoDriverTest.scala:111,113` / `TransportBackpressureReclaimTest.scala:43` / `NioTransportTest.scala:708` | backpressure-reclaim setup sleeps | expose a "ReadPump parked on full channel" latch/probe; the final assertions are already correct `awaitCondition` polls. |
| 28 | `kyo-aeron TopicUniformInvariantsTest.scala:206` | ticker keeps ticking during slow connect | `ticks >= 50` (40x headroom, low risk). Prove liveness via a `Latch` the ticker releases, checked not-yet-complete against the connect fiber. |
| 29 | `kyo-caliban ResolversTest.scala:1885` | subscribe id reuse | `sleep(50ms)` gates the real assert; await a cleanup completion signal. |
| 30 | `kyo-caliban ResolversTest.scala:976,1191,1398` | WS cleanup leaves (×3) | `sleep(N)` then vacuous `yield "ok"`; assert on an actual cleanup/fiber-exit signal. |
| 31 | `kyo-compat FiberTest.scala:208` | get on completed fiber returns immediately | `deltaMs < 100`; assert value correctness (+ dispatch counter if "no re-suspension" is the contract). |
| 32 | `kyo-compat TimeTest.scala:184` | sleep(0) returns immediately | `deltaMs < 500`; drop the timing assert (correctness = completes at all). |
| 33 | `kyo-compat MeterTest.scala:148` | second acquire blocks until release | `waitMs >= holdMs-30`; causal marker: flag set before holder releases, assert waiter observed it. |
| — | `kyo-zio ZIOsTest.scala:232` | interrupt racing acquisition orphan | already `.ignore("flaky…")`; the shape stays nondeterministic even after the underlying bug fix — query live fiber count from the runtime, not a 200ms counter snapshot. |
| 34 | `kyo-ffi/it PosixTest.scala:85` | native `time(0)` ≈ JVM clock | `abs(cSeconds - jSeconds) <= 5` — two independently-sampled clocks; can fail on an NTP step / CPU throttle between reads. Bracket: sample `before`/`after` around `posix.time(0)` and assert it falls in `[before-1s, after+1s]` (±1s only for `time()`'s 1s granularity). |

### Tier 3 — lower-bound-only elapsed asserts (NO flake risk; delete or de-clock; several redundant).

- `kyo-browser BrowserSettlementTest.scala:1107` (`>=700`) — redundant with `:1106 finalText=="e"`; delete.
- `kyo-browser BrowserSettlementTest.scala:336` (`>=400`) — redundant with typed-failure + subtree-tick asserts; delete.
- `kyo-browser BrowserSettlementTest.scala:405` (`>=1500`) — redundant with `tickCount>0` + typed failure; delete.
- `kyo-browser BrowserSettlementTest.scala:537` (`>=50`) — adopt the infinite-outer-schedule pattern (sibling `:549`) + typed failure; delete the elapsed read; rename leaf (drops "within ~100ms").
- `kyo-compat TimeTest.scala:68,75,91` and `:62,105` — elapsed lower-bound bands with huge headroom on the cross-backend compat shim; a real fix needs a per-backend virtual clock (`TestControl`/`TestClock`/`TestScheduler`) through `CIO`. Low urgency.
- `kyo-tasty TestClasspathsTest.scala:44` — `assert(elapsed >= 0)` twice — a tautology (dead weight, never fails); delete the elapsed measurement, coverage is already carried by the adjacent `symbols.length > 0` asserts.

### Tier 4 — perf / linearity budgets (machine-variance class).

- `kyo-markdown MarkdownTest.scala:454` (`elapsed < 30000`) and `kyo-website DocsMarkdownTest.scala:667` (same) — assert a countable algorithmic proxy (node visits / parser steps) that scales with input, or render at 1x and 4x and assert the elapsed-ratio stays sub-quadratic (self-normalizing), or move to a perf-only non-gating suite.
- `kyo-tasty StandardClasspathFidelityTest.scala:60` (jvm) — `median-of-3 < 5.seconds` cold classpath init. Median smoothing already de-noises; no virtual-clock substitute for real classpath I/O. Lowest priority: raise the ceiling with the same contended-runner rationale as its 3-min leaf timeout, or switch to a stored-baseline regression comparison.

---

## Design forks that need a direction (production changes)

Per the operating premise, cost is not a factor; these are surfaced because they are genuine forks, not because they are large:

1. **`UnsafeServerDispatch` idle-timer clock (Tier-1 #13, 17 assertions).** Inject a `Clock` into the idle-timeout path and drive with `tc.advance` (cleanest, removes 17 sleeps at once), vs. expose a per-fire `Latch`/`Promise` the tests await. Recommend the injectable clock.
2. **JS transport handshake deadline (Tier-2 #24).** Node-timer today; an injectable clock would make `JsTransportTlsTest` deterministic. Larger reach (JS transport internals).
3. **`ConnectionPool` reads `System.nanoTime()` directly** (no injectable clock), so the reaper tests use bounded poll-until-true rather than a virtual clock. Leaving as bounded polls is legitimate (they assert on settled `discardCount`, not elapsed) unless we want the pool to take an injectable clock.
4. **Perf budgets (Tier-4).** Convert to an algorithmic proxy vs. relocate to a non-gating perf suite. Recommend algorithmic proxy where a clean counter exists, else relocate.

## Cleared clusters (no findings)

kyo-net IoUring batch (12 files), Poller batch (10 files) — all `awaitCondition` bounded polls. kyo-ui entire test tree — zero elapsed-time assertions. Large cleared lists per cluster are in the per-cluster reports (`TIME_SWEEP_browser.md` + agent outputs).

## Coverage (all clusters swept; inventory complete)

Every `*/src/test/**/*.scala` across the repo was swept. Clusters with **zero** findings, confirmed clean by reading each grep hit (not un-searched): kyo-net IoUring + Poller batches; the entire kyo-ui test tree; kyo-ffi, kyo-flow (uses `Clock.withTimeControl`), kyo-actor, kyo-mcp, all kyo-schema-* (json/bson/ion/msgpack/protobuf/yaml/tests); kyo-i18n, kyo-lsp, kyo-case-app, kyo-reactive-streams, kyo-zio-test, kyo-stats-otlp, kyo-stats-machine, kyo-slack, kyo-ai, kyo-doctest, kyo-jsonrpc/-http. Per-cluster detail lives in `TIME_SWEEP_browser.md` and `TIME_SWEEP_apps.md`.

Already-fixed exemplars found in-tree (the target pattern): `kyo-compat` `ForeachTest`/`RaceZipTest`/`MeterTest` (peak-active `AtomicInteger` canary replaced an `elapsed<5s` bound), `kyo-sql-tests SqlConfigUrlOptionsTest` (assert the exception-carried Duration, not elapsed), `kyo-jsonrpc JsonRpcHandlerTest` (gate-ordering `Promise` replaced an `elapsed<900` proxy), `kyo-caliban ResolversTest awaitClose` (bounded poll replaced `sleep+check`), `kyo-ai LLM*Test` (`Clock.withTimeControl`), `kyo-pod ContainerOrchestrationItTest` isHealthy (count invocations, not wall-clock).

## Status

- Tier-1 #1 `BrowserSettlementTest mutationQuiescenceWindow(10ms)`: **converted + JVM-validated (49/49) + committed `e6bc22b824`.** This was the active windows-JS CI-breaker.
- All other findings: pending direction / execution.
