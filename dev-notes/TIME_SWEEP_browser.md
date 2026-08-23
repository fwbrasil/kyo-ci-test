# Time / threshold-based-assertion sweep: kyo-browser, kyo-ui

Scope: every `*.scala` under `kyo-browser/**/src/test/` and `kyo-ui/**/src/test/` (all platform trees: shared, jvm, js, js-wasm, native), including `demo/` sources. Analysis only, no edits made.

Method: broad grep across scope for `elapsed`, `timed(`, `.toMillis`, `.toNanos`, `nanoTime`, `currentTimeMillis`, `assert(...millis|seconds...)`, `under \d`, `within`, `budget`, `Async.sleep`, `Thread.sleep`, `Clock.`, `Duration`/`.millis`/`.seconds`/`.hour` literals, `Retry[...]`/`Schedule.fixed`. Every hit's surrounding code was read and the actual assertion classified. kyo-ui returned zero anti-pattern hits; every timing construct there is retry/animation CONFIG or a pure function. All findings below are in kyo-browser, concentrated in `BrowserSettlementTest.scala`.

## Findings (anti-pattern), ranked most-flaky-risk first

### 1. `kyo-browser/shared/src/test/scala/kyo/BrowserSettlementTest.scala:1081`
Test: `"mutationQuiescenceWindow(10ms) lets 30ms-spaced mutations resolve in the first window"`
```scala
assert(
    elapsedMs >= 50L && elapsedMs <= 320L,
    s"mutationQuiescenceWindow(10ms) should resolve after the first mutation's window expires (10ms vs 30ms gap), expected [50, 320]ms but got ${elapsedMs}ms"
)
```
**This is the canonical example named in the brief** (flaked at 602ms on a loaded runner). Pass/fail depends on real wall-clock elapsed time of a live Chrome click+settle round trip falling inside a 270ms-wide band; CDP round-trip latency, GC pauses, or CI contention push the real value outside the band with no functional regression.
**Deterministic replacement**: assert the DOM state at the moment `click` returns instead of elapsed time. With the fixture's 30/80/110/140/170ms-spaced mutations and a 10ms quiescence window, settlement should resolve after only the FIRST mutation ('a') lands, not the last ('e'). Read `Browser.text(Selector.id("root"))` after the click and assert `finalText == "a"` — this directly proves "resolved after the first window, not all five," which is the actual behavioral claim, without touching a clock. (The sibling 500ms-window test right below it already does exactly this: `assert(finalText == "e", ...)`.)

### 2. `kyo-browser/shared/src/test/scala/kyo/BrowserSettlementTest.scala:1156`
Test: `"mutationSettlementTimeout(500ms) shortens the never-quiesce timeout"`
```scala
assert(
    elapsedMs >= 400L && elapsedMs <= 1500L,
    s"mutationSettlementTimeout(500ms) should timeout in [400, 1500]ms (foil: default 2s) but got ${elapsedMs}ms"
)
```
Real Chrome click + 5ms-interval DOM churn + 500ms-configured settlement timeout, measured against a 1100ms-wide wall-clock band. CI scheduling jitter (same class of event that produced the 602ms flake above) can push real elapsed past 1500ms.
**Deterministic replacement**: the test's actual claim is "the 500ms override was honored, not the 2s default." Expose (or add) a hook reporting which `mutationSettlementTimeout` value the settlement loop actually used when it aborted, and assert on that value directly. Absent that hook, the `Result.Failure(_: BrowserAssertionTimedOutException)` shape plus a poll-count/tick-count proxy (see #10-13 below) is a better signal than a wall-clock band.

### 3. `kyo-browser/shared/src/test/scala/kyo/BrowserVerifyReadTest.scala:106`
Test: `"settle reads its bound from configLocal and not a hardcoded constant"`
```scala
assert(
    elapsedA < elapsedB,
    s"expected override A ($elapsedA) to finish before override B ($elapsedB)"
)
```
Pass/fail depends on comparing two REAL wall-clock durations from two sequential live-Chrome runs (retry schedules bounded at 200ms vs 600ms). Not a fixed magic number, but still wall-clock as the correctness signal: a GC pause or scheduler hiccup during run A (or a lucky fast pass during run B) can invert the comparison even though the configured bounds differ 3x.
**Deterministic replacement**: don't time two live runs against each other. Instrument `SettleRead.settle` (or add a test-only counter) to report how many retry polls it performed before aborting, and assert `pollsA < pollsB` — a count driven mechanically by `maxDuration / tickInterval` for each override, with no wall-clock measurement at all. Alternatively expose the effective bound each run actually consulted via a test hook and assert those two values equal the two configured overrides directly.

### 4. `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendIntegrationTest.scala:246-254`
Test: `"CdpBackend.closeNow returns in less than 100ms"`
```scala
start   <- Clock.now
_       <- backend.closeNow
elapsed <- Clock.now.map(_ - start)
yield assert(elapsed < 1.second, s"closeNow took $elapsed (expected < 1s)")
```
This is the **sole assertion** in the test. It's a real wall-clock ceiling on process/socket teardown; CI thread starvation or OS scheduler delay in closing sockets can push `closeNow` past 1s with nothing functionally wrong. Also note the test's own name promises "100ms" while the code enforces "1s" — a sign the ceiling was already loosened once for flakiness and is still wall-clock-based.
**Deterministic replacement**: mirror the sibling test directly above it (`"CdpBackend.close(gracePeriod = 1.second) returns within the grace period"`, lines 226-244), which after closing asserts that a subsequent `CdpBackend.getTargets(backend)` raises `BrowserConnectionLostException` — assert that state instead of timing. If "does not hang forever" must still be guaranteed, wrap the call in `Async.timeout` as a generous safety net (failure mode: the test itself times out, not a silently-wrong pass/fail) rather than asserting on the measured duration.

### 5. `kyo-browser/shared/src/test/scala/kyo/internal/BrowserSnapshotConfigLocalTest.scala:56-59`
Test: `"restoreSnapshot consults Browser.configLocal.loadSchedule (does not hardcode the default)"`
```scala
val elapsed = end - start
assert(
    elapsed < 2.seconds,
    s"expected restoreSnapshot to finish within 2s under configured 50ms loadSchedule cap, " +
        s"but took $elapsed; result=$res"
)
```
This is the **only** assertion in the test, and `res` is captured but never inspected. Wall-clock elapsed time is used as the sole proof that the 50ms configured cap (rather than the 5s hardcoded default) was actually consulted. Real CDP round-trip overhead under CI load can push elapsed past 2s with the cap correctly honored; conversely a regression that silently falls back to the 5s default could still finish under 2s on a fast round-trip, masking the bug entirely.
**Deterministic replacement**: assert on `res` and/or an instrumented poll count instead of timing. Have `restoreSnapshot`'s failure path (or a debug hook) report which `loadSchedule`/cap it actually used, and assert it equals the configured 50ms one; or count `waitForLoad` polls performed (bounded by `configuredCap / tickInterval`, structurally different for a 50ms cap vs. a 5s default) and assert on that count.

### 6. `kyo-browser/shared/src/test/scala/kyo/BrowserDownloadTest.scala:483-499`
Test: `"recordDownloads captures every DownloadEvent emitted during the body in arrival order"`
```scala
Browser.click(Browser.Selector.id("c")).andThen(
    // Bounded settle for the internal event drain (see the note above the
    // test: recordDownloads exposes no in-body completion event).
    Async.sleep(2.seconds)
)
...
assert(
    names.containsSlice(expected) || names.toSet == expected.toSet,
    s"expected arrival order ${expected.mkString("(", ", ", ")")} in $names"
)
```
This is exactly the "fixed sleep to wait long enough, then assert state settled" race called out in the brief. The comment even documents the design gap: "there is no in-body event to await for 'all three WillBegin drained'." If drain of the 3 `WillBegin` events takes longer than 2s under CI load, the assertion fails even though nothing is broken — a `WillBegin` simply hasn't arrived yet.
**Deterministic replacement**: gate on a real completion signal instead of a fixed delay. The same file already has a `collectEvents` helper (lines 42-52) that completes a `Promise` when a terminal `Progress(state == "completed")` event arrives — add an analogous deterministic wait keyed on "3 `WillBegin` events recorded" (e.g. an `AtomicRef`-driven `Promise` completed once the count reaches 3) and await that instead of `Async.sleep(2.seconds)`.

### 7. `kyo-browser/shared/src/test/scala/kyo/BrowserSettlementTest.scala:942`
Test: `"Settle.NetworkIdle waits for chatty fetches to quiesce (3-fetch positive case)"`
```scala
assert(
    elapsedMs >= 200L && elapsedMs <= 5000L,
    s"Settle.NetworkIdle must wait past the 3-fetch burst (>= 200ms) and within idle window envelope (<= 5000ms) but got ${elapsedMs}ms"
)
```
Real-server 3-fetch timing (100/200/300ms) plus a 500ms configured idle window, measured against a wall-clock band. The 25x margin on the ceiling makes this lower-risk than #1/#2, but it is still real-clock-gated pass/fail and can in principle flake under severe CI/Chrome-process contention.
**Deterministic replacement**: instrument the page to record each fetch's arrival (e.g. increment a `window.__pingCount` or push timestamps into an array) and read it via `Browser.eval` once `goto` resolves; assert `pingCount == 3`, proving the call didn't return on the `Load` event alone, without deriving correctness from measured Duration.

### 8. `kyo-browser/shared/src/test/scala/kyo/BrowserSettlementTest.scala:1026`
Test: `"Settle.Load waits for slow <img> subresource to load before returning"`
```scala
assert(
    elapsedMs >= 400L && elapsedMs <= 8000L,
    s"Settle.Load must wait for the slow <img> subresource (>= 400ms) but got ${elapsedMs}ms"
)
```
Same category as #7: real server-delayed image (500ms) measured against a wall-clock band; wide (20x) margin keeps practical risk low but the mechanism is still wall-clock-gated.
**Deterministic replacement**: have the fixture set a JS-side flag in the image's `onload` handler (or read `document.querySelector('img').complete`) and assert that flag is `true` at the moment `goto` returns, proving `Settle.Load` waited for the subresource without needing elapsed time.

### 9. `kyo-browser/shared/src/test/scala/kyo/BrowserScreencastTest.scala:263-290`
Test: `"screenshotFrames aborts on the duration cap with reached and limit both in milliseconds"`
```scala
assert(ex.reached > ex.limit, s"expected reached (elapsed ms) to exceed the 300ms limit but got ${ex.reached}")
assert(ex.reached < 10000, s"expected reached (elapsed ms) under the spin window but got ${ex.reached}")
```
`ex.reached` is a real wall-clock elapsed-ms value computed by the production code (`Browser.scala:3328`) and surfaced via the exception. `reached > limit` is guaranteed by construction (the code only poisons after crossing the cap), so that half is not a flake risk. `reached < 10000` is a genuine "elapsed under N" ceiling, but with a 33x margin over the 300ms cap and an 800ms spin window, so practical flake risk is low. Included for completeness since it is literally a measured-Duration-vs-numeric-bound assertion.
**Deterministic replacement**: if the intent is only "the cap fired promptly, not after minutes," prefer bounding the whole test body with an outer `Async.timeout` (failure mode: explicit test timeout) rather than asserting the measured value against a numeric ceiling.

### 10-13. Floor-only `elapsedMs >= N` assertions in `BrowserSettlementTest.scala` (low practical risk, still flagged)
These four assertions only check a lower bound, so CI slowness cannot cause a false failure (load only makes elapsed bigger). They are flagged because they are still literally "measured Duration compared to a numeric bound," and each one sits alongside a deterministic check that already anchors the test's correctness claim, making the wall-clock floor redundant belt-and-suspenders rather than load-bearing:

- **`BrowserSettlementTest.scala:1108`** — `"mutationQuiescenceWindow(500ms) waits for all 30ms-spaced mutations to quiesce"`: `assert(elapsedMs >= 700L, ...)`, redundant with the immediately-preceding `assert(finalText == "e", ...)` which already proves the window waited for the last mutation.
- **`BrowserSettlementTest.scala:406`** — `"mutation settlement raises assertion timeout on pages that never quiesce"`: `assert(elapsedMs >= 1500, ...)`, redundant with the typed-exception match plus `assert(tickCount > 0, ...)`.
- **`BrowserSettlementTest.scala:337`** — `"settlement observes mutations across the whole document body and raises on continuous chatter"`: `assert(elapsedMs >= 400L, ...)`, redundant with the typed-exception match plus `assert(ticks >= 5, ...)`.
- **`BrowserSettlementTest.scala:538`** — `"Browser.withConfig(retrySchedule maxDuration 100ms) with never-matching waitForText fails within ~100ms"`: `assert(elapsedMs >= 50, ...)`, redundant with the typed-exception match alone (the sibling test "Nested withConfig uses innermost value" in the same file proves the same shape of claim with zero elapsed check).

**Deterministic replacement for all four**: drop the elapsed-floor assertion; the adjacent deterministic check already proves the behavior. If "didn't return prematurely" specifically needs a guard (catching a regression where the timeout fires before doing real work), replace the floor with an instrumented retry/poll COUNT assertion (e.g. "at least N polls occurred") rather than a wall-clock reading — that preserves the intent deterministically.

## Reviewed & cleared (legitimate)

- **`BrowserConfigTest.scala`** (all): every `assert(cfg.xxx == N.millis/seconds)` is checking a config VALUE (default or overridden `SessionConfig`/`LaunchConfig` field), never a measured runtime duration.
- **`BrowserAssertionStabilityTest.scala`**: default `stabilityWindow` config assertions, plus retry-exhaustion tests gated on `Result.Failure(_: BrowserAssertionTimedOutException)` match, not elapsed time. The "budget"/"stability window" language in comments is descriptive, not a literal wall-clock assertion.
- **`internal/NavigationWatcherTest.scala:147-158`**: `NavigationWatcher.loadScheduleTimeout` is a pure function over a `Schedule` value; `out == 5.seconds` / `out2 == 2.seconds` are pure-computation equality checks, no real clock involved.
- **`BrowserCookieTest.scala:255-284`** (`deltaMillis < 1000`): round-trip fidelity check (does Chrome's cookie store preserve a set `expires` Instant through encoding-rounding), not a test-speed assertion. `deltaMillis` compares two configured/persisted timestamps, not measured test execution time.
- **`BrowserDownloadTest.scala`** `assertNeverLands` helper (10 samples x 50ms inverse-poll, used at lines 123, 162, 449): genuine multi-sample state poll that fails immediately on any sampled state change (file appearing); not a single "wait then check" race.
- **`internal/BrowserLauncherTest.scala:250-284`** (`killOrphans`): bounded-retry `Loop` (5 attempts x 50ms) asserting the final boolean process-alive STATE, not elapsed time.
- **`jvm/internal/BrowserLauncherPlatformTest.scala`** / **`jvm/internal/BrowserLauncherCleanupJvmTest.scala`**: `waitFor(30s, ...)` / `waitUntil(timeoutMs, stepMs)` poll helpers used as TIMEOUT SAFETY bounds around deterministic process-state/PID-alive checks; assertions are on the resulting boolean, not the elapsed time.
- **`internal/CdpBackendLifecycleTest.scala`** / **`jvm/internal/CdpBackendLifecycleJvmTest.scala`**: dialog-drainer liveness assertions gated on explicit close/interrupt completion (comment at line 311: "interrupt to settle (no fixed sleep that can race under CI load)"), not fixed sleeps.
- **`internal/MutationSettlementTest.scala`**: explicitly self-documented design discipline — comments at lines 139, 319, 370, 484 state "not on elapsed timing" / "no elapsed floor" / "No wall-clock assertion." All assertions are on DOM/state (`__kyoMutCount`, text content, typed exception shape).
- **`internal/ActionabilityTest.scala`**, **`internal/StabilitySamplerTest.scala`**: pure decode/state-machine assertions, no real clock.
- **`BrowserViewportTest.scala` / `BrowserEmulationTest.scala` / `BrowserScreencastTest.scala`** interruption tests (`Async.sleep(30.seconds)`): used only as a "block interruptibly forever" fixture inside a fiber the test explicitly interrupts; the actual assertions afterward are bounded-retry `Retry[...](Schedule.fixed(20.millis).take(150))` polls on deterministic `AtomicRef` state.
- **`BrowserHistoryTest.scala:407`** (`Async.sleep(1.hour)`), **`internal/BrowserSnapshotConfigLocalTest.scala:84`** (`Async.sleep(60.seconds)`), **`internal/NavigationWatcherTest.scala:439`** (`Async.sleep(500.millis)`), **`BrowserSettlementTest.scala:1015`** (`Async.sleep(500.millis)`): all server-side fixture delays used as CONFIG/INPUT to the system under test (simulate a slow or never-completing subresource), with assertions on resulting page STATE ("reload returns before the slow tail completes", "img eventually loads"), never on elapsed time.
- **`ChartTransitionTest.scala` / `ChartMorphTest.scala` / `ChartLowerTest.scala`** (`.animate(_.ease(300.millis))`): the Duration is a config value serialized into a static SVG `<animate dur="...">` string and checked via string-content assertions; `Tol = 1.0e-4` is floating-point tolerance for chart geometry, unrelated to time.
- **`internal/ReactiveUITeardownTest.scala`** "every leaf waiters == 0 after Scope closes": a stability-sample poll (5 consecutive zero-samples at 10ms spacing) bounded by the suite's 60s per-test timeout as a safety net; the final assertion (`w1 == 0 && w2 == 0 && w3 == 0`) is on deterministic waiter-count state. (This test is currently `.ignore`d, but the pattern itself is legitimate.)
- **`ChatScenarioItTest.scala` / `AnchorTest.scala` / `TodoScenarioItTest.scala` / `RealisticInteractionItTest.scala`**: `Retry[...](Schedule.fixed(300.millis).take(5))` wrapping `Browser.assertText(...)` — bounded-count retry with the assertion on resulting text/state, not on elapsed time.
- **kyo-ui broadly**: a comprehensive grep across the entire `kyo-ui/**/src/test` tree for `elapsed`, `timed(`, `Duration`, `.millis`/`.seconds`/`.hour`, `Async.sleep`, `Thread.sleep`, `Clock.`, `nanoTime`, `currentTimeMillis` turned up no anti-pattern hits outside the items above; `ReactiveTest.scala`, `ReactiveUITest.scala`, `ReactiveScenarioItTest.scala`, `RealtimeScenarioItTest.scala`, `CrossComponentItTest.scala`, `UIServerWsTest.scala`, and every other kyo-ui test file contain zero timing-threshold constructs.
- **All `demo/*.scala` files** (`BrowserDemo`, `LiveDashboardDemo`, `FlamegraphDemo`, `SnakeDemo`, `DashboardDemo`, `ChartReactiveScalesDemo`, etc.): `Async.sleep` used only for animation/demo cadence; none contain `assert(` at all (verified directly), so none can affect a test PASS/FAIL.

## Note (not an anti-pattern, documentation drift)
`BrowserIsolateTest.scala:1153` — the comment above `"withPopup with a schedule clause bounds the wait time"` claims "we assert the abort fires in [300, 1500) ms," but the actual code below it only matches on `Result.Failure(_: BrowserProtocolErrorException) => succeed` with no elapsed-time assertion at all. The wall-clock check this comment describes appears to have already been removed from the code; the comment is stale and should be updated to stop describing behavior the test no longer checks.
