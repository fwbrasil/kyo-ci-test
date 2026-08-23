# Audit: `sleep` usage across the kyo codebase

Swept every module (main + test, all platforms) for `Thread.sleep`, `Async.sleep`, `Clock.sleep`, `Async.delay`, `LockSupport.park*`, scheduler `Sleep(...)`, and `CIO.sleep`/`ZIO.sleep` in the compat/zio suites. Classified by six parallel per-module auditors against one rubric, then spot-verified the top findings against source (kyo-core `SignalTest`, kyo-data `MpscUnsafeQueueTest`, kyo-mcp `McpResourceSubscribeTest`, kyo-flow `FlowApiTest` all confirmed exact).

## Totals

**~572 call sites — 445 legitimate, 74 ANTI-PATTERN, 56 BORDERLINE.**

| Group | sites | legit | anti | borderline |
|---|---|---|---|---|
| kyo-core | 177 | 133 | 28 | 16 |
| http/mcp/jsonrpc/caliban/reactive-streams | 76 | 48 | 12 | 16 |
| scheduler/data/test/ffi/compat/compiler/bench | 140 | 120 | 8 | 12 |
| stm/flow/combinators/actor/direct/zio | 68 | 48 | 14 | 6 |
| net/aeron/pod | 71 | 65 | 4 | 5 |
| browser/ui | 40 | 31 | 8 | 1 |

**Main-source (production): no violation.** All 25 main-source `Thread.sleep`/`park` sites are low-level runtime substrate (the scheduler's `Sleep` primitive and its `InternalClock`/`BlockingMonitor`/`SelfCheck`/`top` loops, the ffi guard-drain and the Scala-Native leak-detector daemon, a bench server, CLI tools) on dedicated carrier/daemon threads. The "never block a thread" rule governs effect code; none of these are effect code.

## The one shape

Almost every finding is the same anti-pattern: **start async work (fork a fiber / send a notification / write to a channel / launch an OS process), sleep a fixed duration hoping it finished, then assert.** On a loaded CI runner the work outruns the sleep. It fails three ways, in descending severity:

- **HANG** — a missed wakeup means a downstream `take(n)`/`get` never completes, so the leaf runs to the *suite* timeout (minutes) instead of failing cleanly.
- **RED** — a positive assertion (`counter == 1`, `elapsed >= 900ms`) reads too early and fails a correct build.
- **GREEN (vacuous)** — a negative/weak assertion passes *without ever exercising the behavior*; a broken build stays green.

## Findings by severity

### Tier 1 — HANG to suite timeout (fix first)

- **kyo-core `SignalTest` (17 sites)** — `:224,226,228,230`, `:360,362`, `:516,518,520,522`, `:754`, `:783,785`, `:797,799,801,803`. Fork a `streamChanges`/`streamCurrent` consumer, `Async.sleep(50-100ms)`, then `set`; if the fiber has not subscribed the `set` is missed and `take(n)` hangs forever. **Fix:** `assertEventually(ref.waiters.map(_ == 1))` before each `set` — the exact idiom is already used ~30× in this file (`:196,:405,:504`).
- **kyo-core `ExchangeTest` (4 sites)** — `:518,:530,:680,:1459`. `Async.sleep(10ms)` waiting for the reader fiber to observe stream-end/error, then send a request that otherwise parks forever. **Fix:** `ex.awaitDone` — used correctly 20 lines below at `:537`.
- **kyo-core `ChannelTest` (2 sites)** — `:1893,:1908`. Fork two `put`/`take` fibers, sleep 10ms, then assert `pendingPuts == 2`. **Fix:** `assertEventually(c.pendingPuts.map(_ == 2))` (idiom at `:110`).
- **kyo-stm `STMStressTest:215`** — waiter fiber that must retry before the writer publishes; 50ms guess. **Fix:** `assertEventually(nestedRetries.get.map(_ >= 1))` before `r1.set(42)`.

### Tier 2 — RED flake under load

- **kyo-mcp (7 sites)** — `McpResourceSubscribeTest:41,74`, `McpLoggingSetLevelTypedTest:36,67`, `McpClientReverseHandlerTest:88,124,159`. Fire a notification, sleep 30-50ms, assert a cross-fiber counter (`>= 1` / `== 1`); the 30ms ones even have a `fail("... was never invoked")` branch. **Highest-leverage fix in the audit:** all 7 take the identical transform — a `Fiber.Promise` completed inside the notification handler, awaited in place of the sleep. Idiom already at `McpServerTest:20`.
- **kyo-browser (8 sites)** — `BrowserDownloadTest:487` (2s), `CdpBackendLifecycleTest:152`, `CdpBackendLifecycleJvmTest:235,208`, `CdpBackendDecoderTest:98,168,204,244`. Every one violates the module's own rule (`kyo-browser/CONTRIBUTING.md:1124`: "gate on barriers, never sleeps"); `BrowserDownloadTest:487` even has scaladoc describing the correct Promise gate directly above the sleep that doesn't implement it. Fixes are per-site (Promise from a download/console/route handler); details in the browser sub-report.
- **kyo-core `ProcessTest` (4 sites)** — `:384,447,473,498`. 200ms wait for the OS to reap a killed process, then `kill -0` must fail. **Fix:** bounded `kill -0` retry to a generous deadline (poll idiom at `AsyncPlatformSpecificTest:70`).
- **kyo-compat `FiberTest` (5 sites)** — `:114,135,155,218,264`. Sleep then read an `Atomic*` set by a completion callback, across six backends. **Fix:** the `CPromise` handoff already in the file at `:162`. (`:155` also asserts nothing real — `ctr == 1` holds regardless.)
- **kyo-zio `ZStreamsTest:259,262`**, **kyo-scheduler-finagle `KyoFinagleSchedulerServiceTest:72`**, **kyo-scheduler `WorkerTest:983`**, **kyo-core `AsyncPlatformSpecificTest:67`**, **kyo-caliban `ResolversTest:1882,1658`**, **kyo-http `HttpWebSocketTest:1465`** (the deterministic `observed` promise is already in scope), **kyo-reactive-streams `PublisherToSubscriberTest:198,203`** (a self-defeating "safety net" that interrupts the subscribers directly, so it can't tell "propagation works" from "never happened"), **kyo-pod `ContainerItTest:3545`** (800ms vs 600ms of in-container wall clock), **kyo-net `PosixTransportAcceptEmfileTest:154`** (rate bound computed from the nominal sleep, not measured elapsed).

### Tier 3 — GREEN / vacuous (broken coverage)

- **kyo-data `MpscUnsafeQueueTest:61` — a genuinely broken test.** The latch is released *after* the 1s sleep (`start.await` → `sleep(1000)` → `countDown` → `stop`), so the concurrency window is ~zero and the per-producer FIFO assertion iterates a near-empty queue and passes unconditionally. 17 sibling leaves order it correctly (e.g. `:121-124`). **Fix:** move `countDown` before the sleep and assert `consumed` is non-empty.
- **kyo-flow `FlowApiTest` (10 sites)** — `:154,179,195,206,240,256,270,283,300,317`. `POST executions` → fixed 200-500ms sleep → one API call → assert. Confirmed dead time: `createExecution` persists before returning, so every assertion holds the instant the POST returns; 4.4s/run buys nothing. **Fix:** delete all ten; where worker progress matters, use the file's `awaitStatus` poll (`:60`). The sibling `FlowEngineTest` runs under `Clock.withTimeControl` with zero real sleeps.
- **kyo-actor `ActorTest:813`** — `Async.sleep(100ms)` sitting directly on top of its own fix (`actor.fiber.getResult` is the very next line). Delete.
- Plus 56 BORDERLINE (negative/vacuous assertions, redundant settle windows already subsumed by a nearby barrier, STM waiter-parking coverage sleeps). Not flake-causing, but many are safe deletions that also cut suite runtime.

## High-leverage clusters (one transform each)

| Cluster | Sites | Transform | Idiom already at |
|---|---|---|---|
| kyo-core `SignalTest` | 17 | `assertEventually(ref.waiters.map(_ == 1))` before `set` | `SignalTest:196` |
| kyo-flow `FlowApiTest` | 10 | delete (dead time) / `awaitStatus` | `FlowApiTest:60` |
| kyo-mcp notification tests | 7 | `Fiber.Promise` in the handler | `McpServerTest:20` |
| kyo-compat `FiberTest` | 5 | `CPromise` completed in the callback | `FiberTest:162` |
| kyo-core `ExchangeTest` | 4 | `ex.awaitDone` | `ExchangeTest:537` |
| kyo-core `ProcessTest` | 4 | bounded `kill -0` poll | `AsyncPlatformSpecificTest:70` |

## Recommended fix order

1. **kyo-core `SignalTest` (17)** — biggest single win; hang-risk; idiom already in the file.
2. **kyo-data `MpscUnsafeQueueTest:61`** — an actually broken (vacuous) test; one-line reorder.
3. **kyo-core `ExchangeTest` (4) + `ChannelTest` (2)** — hang-risk; `ex.awaitDone`/`assertEventually`.
4. **kyo-mcp (7)** — red flake with a `fail(...)` branch primed; one transform.
5. **kyo-flow `FlowApiTest` (10)** — delete dead time (also -4.4s/run).
6. **kyo-browser (8)** — module violates its own written rule; several hard-fail.
7. Remainder: `ProcessTest` 4, `FiberTest` 5, `ZStreamsTest` 2, `STMStressTest:215`, finagle/`WorkerTest`/`AsyncPlatformSpecific`, caliban 2, `HttpWebSocketTest`, reactive-streams 2, pod 3, net 1.
8. Borderline (56) — opportunistic; many are safe deletions.

## Hygiene notes (not sleep findings)

- `kyo-browser/CONTRIBUTING.md:1155,1160` cite `CdpClientLifecycleJvmTest.scala`, a since-deleted file — its two worked examples of the barrier rule now point at nothing, which is likely why the pattern drifted.
- Six dead sleep-primitive files with zero callers repo-wide: `kyo-test/runner/{jvm,native,js-wasm}/.../TestSleep.scala` and `kyo-test/api/{jvm,native,js-wasm}/.../ApiTestSleep.scala` — deletion candidates.
- `kyo-core `StreamCoreExtensionsTest:177`` — a `randomSleep` val that is never referenced (dead code).
