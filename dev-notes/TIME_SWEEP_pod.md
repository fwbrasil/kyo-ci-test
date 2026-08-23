# Time / Threshold-Based-Assertion Sweep — kyo-pod

Scope: every `*.scala` under `kyo-pod/**/src/test/` (all platforms). Files scanned (21):
`shared/src/test/scala/kyo/*` (BasePodTest, ContainerItTest, ContainerOrchestrationItTest,
ContainerPredefItTest, ContainerPredefTest, ContainerRuntimeBase, ContainerRuntimeJvmLike,
ContainerTest, ContainerRuntime x3 jvm/js-wasm/native, PodNodeBuiltins),
`shared/src/test/scala/kyo/internal/*` (FrameAssemblerTest, HttpContainerBackendTest,
LineAssemblerTest, ShellBackendTest), `shared/src/test/scala/demo/*` (CodeSandbox,
IntegrationTestScaffold, LogAggregator, PrometheusExporter, ServiceMesh),
`shared/src/test/scala/dev/KyoPodDevRunner.scala`.

Method: grepped all test files for `elapsed`, `timed(`, `.toMillis`, `.toNanos`, `nanoTime`,
`currentTimeMillis`, `assert(.*(millis|seconds))`, `under N`, `within`, `budget`, `deadline`,
`Async.sleep`, `Thread.sleep`, `clock.sleep`, `eventually`, `poll`, `settle`, plus every `.millis`/
`.seconds`/`Duration(` literal, then OPENED and READ the surrounding test for every hit to classify.

Analysis only. No files were edited, no tests were run.

The reference shape to hunt for — "JS ContainerOrchestrationItTest 'init completes under 2s' (2.1s
under load)" — is a wall-clock **ceiling** assertion on an integration op's duration. That exact test
has since been converted/removed (no residual `init complet`/`< 2000`/`completes under` hit remains
in the tree). The findings below are its surviving siblings, ranked by false-fail-under-load risk.

---

## ANTI-PATTERN FINDINGS (ranked, most-flaky-risk first)

### 1. `ContainerItTest.scala:2731-2735` — `assert(elapsedMs < 500)` on a daemon round-trip (exec on stopped container)

```scala
val elapsedMs = t1.toJava.toEpochMilli - t0.toJava.toEpochMilli
// The Retry wraps exec with Schedule.fixed(100.millis).take(2), meaning
// 3 total attempts with 100ms between each. For a deterministic failure
// like exec-on-stopped-container, this wastes 200ms+ on pointless retries.
// Without retries it should fail in < 500ms (Podman's SSH-based daemon adds latency).
assert(
    elapsedMs < 500,
    s"exec on stopped container took ${elapsedMs}ms — " +
        "retries are wasting time on a deterministic NotFound/AlreadyStopped failure"
)
```

The exact reference shape: a wall-clock **ceiling** on an integration op that crosses to a container
daemon. The test's own comment already flags that "Podman's SSH-based daemon adds latency" — i.e. the
baseline round-trip cost is already significant and variable. On a loaded or arm64-QEMU-emulated
runner (this repo runs those), a single exec attempt plus its daemon/SSH round-trip can plausibly
exceed 500ms even with zero wasted retries, producing a false failure that has nothing to do with the
retry behavior the test means to verify.

**Why flaky**: 500ms is a tight ceiling on a network/SSH round-trip whose latency the comment itself
admits is host-dependent. This is the direct sibling of the "init completes under 2s (2.1s under
load)" flake that already bit this repo.

**Replacement**: assert the property directly instead of its wall-clock proxy. The test wants "no
pointless retries on a deterministic failure" — count attempts, don't time them. Wrap `exec` so the
backend's attempt count is observable (an `AtomicInt` bumped per attempt, as
`ContainerOrchestrationItTest.scala:475-482`'s `attempts.get()` health-check test already does) and
assert `attempts == 1`. That detects the exact regression (retries firing on a deterministic failure)
with zero dependency on machine speed.

### 2. `ContainerItTest.scala:2041-2045` — `assert(firstMs < 3000)` build-streaming ceiling on a 4s build

```scala
val firstMs = result.getOrElse(fail("Expected at least one build progress event"))
// Build takes 4+ seconds (two `sleep 2` steps). If streaming, the first event
// arrives well under 3s even with daemon overhead. If events are buffered until
// the build completes, the first event would arrive at 4s+.
assert(
    firstMs < 3000,
    s"First build event took ${firstMs}ms — expected < 3000ms for a 4s build. " +
        "Events are likely buffered until build completes (not streaming)"
)
```

Discriminates "streaming" from "buffered" by whether the first build-progress event arrives before
3s. The margin between the two states is only 1s (streaming ≈ early, buffered ≈ 4s+), and daemon
image-layer setup before the first progress event, plus emulation overhead, can push a genuinely
streaming build's first event past 3s under CI load — a false "not streaming" failure.

**Why flaky**: a wall-clock ceiling whose safety margin (3s vs 4s build) is a single second on a path
dominated by variable daemon overhead the comment already acknowledges.

**Replacement**: prove streaming structurally, not by first-event latency. Capture the timestamps of
*all* progress events and assert the span between first and last is a meaningful fraction of the build
(events spread across the build ⇒ streaming; all clustered at the end ⇒ buffered), or assert a
progress event is observed *before* the build's completion event is delivered. Both compare events to
each other rather than to a fixed wall-clock number.

### 3. `ContainerItTest.scala:3556-3572` — `Async.sleep(800.millis)` then assert all four log lines present and ordered

```scala
Container.init(config).map { c =>
    Async.sleep(800.millis).andThen {
        c.logs(stdout = true, stderr = true).map { entries =>
            val contents = entries.map(_.content).toSeq
            val o1Idx    = contents.indexOf("o1")
            ...
            assert(o1Idx >= 0, s"Expected 'o1' in logs, got: $contents")
            assert(e1Idx >= 0, ...); assert(o2Idx >= 0, ...); assert(e2Idx >= 0, ...)
            assert(o1Idx < e1Idx && e1Idx < o2Idx && o2Idx < e2Idx, ...)
```

The container script emits `o1; sleep 0.2; e1; sleep 0.2; o2; sleep 0.2; e2` — the last line `e2`
lands at ~0.6s of container wall time, then must flush through the daemon's log buffer before the
`c.logs` read at 800ms. Textbook "fixed sleep, then assert state settled": if `e2` (or its flush)
hasn't landed by 800ms under load, `assert(e2Idx >= 0)` fails for a timing reason, not an ordering
bug. False-fail direction (a positive settle-check on presence), the risky kind.

**Why flaky**: 800ms is a hand-picked single number that must be simultaneously long enough for the
slowest runner and is only ~200ms past the last emission; the flush latency it must also cover is
exactly what CI load inflates.

**Replacement**: poll until all four lines are present, bounded by the test budget, then assert
ordering on that settled snapshot — the suite's own idiom, `Retry[AssertionError](Schedule.fixed(
50.millis).take(40)) { c.logs(...).map(entries => if all-four-present then entries else throw
AssertionError) }` (see `ContainerItTest.scala:2698` and `:1249`). Ordering is a property of the
captured content, not of when you happened to read it.

### 4. `ContainerItTest.scala:1796-1800` — pull-vs-ensure relative-timing threshold

```scala
case Result.Success(_) =>
    // Pull succeeded — verify it actually contacted the registry
    assert(
        pullMs > ensureMs * 2 || pullMs > 500,
        s"pull (${pullMs}ms) should be slower than ensure (${ensureMs}ms) — " +
            "pull appears to skip registry contact when image exists locally"
    )
```

Infers "pull contacted the registry" from pull being either >2x ensure or >500ms
(`ensureMs`/`pullMs` are `Clock.now` deltas at 1787/1792). Both disjuncts are load-sensitive: if the
registry response is cached/fast and `ensure` happens to run slow under load, `pullMs > ensureMs*2`
can be false while `pullMs < 500` is also false → false failure, even though pull did contact the
registry. Relative timing between two operations on a shared, contended machine is inherently noisy.

**Why flaky**: the assertion encodes "registry contact" as "slower than a local check," a proxy that
breaks whenever the two operations' real durations happen to converge under load.

**Replacement**: assert registry contact by observing the contact, not its cost. The `Failure(
ContainerImageMissingException)` branch already correctly proves contact by outcome (pull attempted
and failed to reach the registry). For the success branch, assert on a pull-progress event / layer
event that only a real registry pull produces, or drop the timing disjunct entirely and let the
missing-image branch carry the "contacts the registry" proof.

### 5. `ContainerOrchestrationItTest.scala:499-507` — dual wall-clock bound on stopTimeout-honoring cleanup

```scala
val elapsedMs = t1.toJava.toEpochMilli - t0.toJava.toEpochMilli
assert(
    elapsedMs >= 800,
    s"Expected cleanup to wait ~stopTimeout (1s) when stopSignal is Present, took ${elapsedMs}ms — " +
        "the kill path is not honoring stopTimeout"
)
assert(
    elapsedMs < 20000,
    s"Cleanup took too long (${elapsedMs}ms); expected timeout then force-remove"
)
```

Container traps SIGUSR1 with `sleep 3; exit 0` and `stopTimeout=1s`, so cleanup should send the
signal, wait ~1s, then force-remove. The `>= 800` floor is safe-direction (CI slowness only inflates
it; it fails only if stopTimeout is genuinely skipped, a real bug), and the `< 20000` ceiling is
generous, so practical false-fail risk is low. But it is still a genuine wall-clock dependency for a
property that has a non-timing witness.

**Why flaky (low)**: only the `< 20000` ceiling can false-fail, and only on a pathologically slow
runner; the floor is a correctness check, not a flake source.

**Replacement**: prove "kill path honored stopTimeout then force-removed" by observing the sequence of
lifecycle events (signal sent → grace period → force-remove) rather than the total elapsed. If the
backend exposes the stop path's decision (e.g. it force-removed after the timeout vs the container
exited on its own), assert on that outcome. If no such seam exists, the floor is acceptable as-is;
the ceiling should be replaced by wrapping the whole cleanup in `Async.timeout(...)` as a hang net
that asserts on completion, not on a magic 20000.

### 6. `ContainerItTest.scala:155-162` — `assert(elapsed >= 1000)` meter-concurrency floor via `currentTimeMillis`

```scala
// HTTP backend wraps exec in meter.run, so with meter=2 and
// 6 execs sleeping 0.5s, it takes >= 3 * 0.5s = 1.5s
val start = java.lang.System.currentTimeMillis()
Kyo.foreach((1 to 6).toSeq) { _ => Fiber.init(c.exec("sleep", "0.5")) }
    .map(fibers => Kyo.foreach(fibers)(_.get)).map { results =>
        val elapsed = java.lang.System.currentTimeMillis() - start
        assert(results.forall(_.isSuccess))
        assert(elapsed >= 1000, s"Expected >= 1000ms with meter=2, took ${elapsed}ms")
    }
```

Proves the semaphore serializes 6 execs into 3 batches by asserting total wall time `>= 1000ms`
(theoretical min 1500ms). Floor-only and correct-direction: CI slowness only pushes elapsed higher;
it fails only if the meter *didn't* limit concurrency (all 6 ran at once ⇒ ~0.5s), which is the real
bug it targets. Low false-fail risk, but it is a real wall-clock dependency and it uses raw
`currentTimeMillis` (the JS build's monotonic-vs-wall skew that the "init under 2s" flake exposed
lives on exactly this measurement style).

**Why flaky (low)**: floor direction is safe; risk is a missed bug on a very fast machine, not a
spurious red.

**Replacement**: assert the concurrency bound directly — instrument `meter.run` (or a wrapping
counter) to record the peak number of simultaneously-in-flight execs and assert `peak <= 2`. That
proves the semaphore limit exactly, independent of how long `sleep 0.5` actually takes on the host,
and removes the raw wall-clock read.

### 7. `ContainerItTest.scala:3371-3379` — `Async.sleep(2.seconds)` then assert `entries.size <= 1` (logStream on stopped container)

```scala
Container.initUnscoped(config).map { c =>          // command: sh -c "echo done"
    ensureCleanup(c).andThen {
        Async.sleep(2.seconds).andThen {
            Scope.run {
                c.logStream.take(1).run.map { entries =>
                    c.remove(force = true).andThen { assert(entries.size <= 1) }
```

Fixed 2s wait for the `echo done` container to run and exit, then opens `logStream` and asserts it
terminates with at most one entry. The assertion is a **ceiling on count** (`<= 1`), and the real
property under test is that `logStream` terminates on a stopped container; the 2s is a guess that the
container has exited. Since `echo done` exits in milliseconds, 2s is very generous, so false-fail risk
is low — but it is the fixed-sleep-then-assert shape and it pays a flat 2s on every run.

**Why flaky (low)**: the container exits far inside 2s in practice; the ceiling assertion is on
`take(1)` output, not on timing.

**Replacement**: poll for the container's terminal state (`c.state` is `Stopped`/`Exited`) instead of
sleeping a fixed 2s, then open `logStream`. This removes both the flake seam and the flat 2s cost, and
makes "we tested logStream against a genuinely-stopped container" explicit rather than assumed.

### 8 (borderline). `ContainerOrchestrationItTest.scala:404-410` — `Async.sleep(2.seconds)` before reading crashed-container state

```scala
Container.initWith(config) { c =>            // command: sh -c "exit 1"
    // Container exits with code 1 immediately.
    // Wait briefly for the process to exit.
    Async.sleep(2.seconds).andThen { c.state }
}
```

The load-bearing assertion (post-scope, at 415-421) is that the container was removed after scope
cleanup — a non-timing outcome check, correctly done. The 2s sleep only lets the `exit 1` process die
before `c.state` is read mid-body; nothing asserts on the 2s or on `c.state`'s value. Listed as
borderline because it is a fixed-duration wait, but it drives no threshold assertion.

**Replacement**: if the mid-body `c.state` read matters, poll `c.state` until terminal instead of a
flat 2s; otherwise the sleep can be dropped, since the real assertion is the post-cleanup removal
check that does not depend on it.

---

## REVIEWED & CLEARED (good pattern, or not actually time/threshold-based)

- **`ContainerItTest.scala:1249, 2698, 2964`** (`Retry[AssertionError](Schedule.fixed(50.millis)
  .take(40)) { ... throw new AssertionError if not-yet-flushed }`) — the correct poll-until-condition
  idiom for daemon-log races; asserts on content presence, bounded by attempts, never on elapsed time.
  These are the in-repo exemplars the anti-patterns above should be converted to.
- **`ContainerItTest.scala:1221, 1233`** (`assertEventually(c.logs.map(_.exists(...)))`) — poll-until,
  deterministic. Correct pattern.
- **`ContainerItTest.scala:3399-3404`** (`Loop.indexed { i => if s.memory.usage > 0 then Loop.done
  else if i < 50 then Async.sleep(100.millis).andThen(Loop.continue) else Loop.done }`) — a
  poll-until-condition with a bounded give-up valve; the `100.millis` is a poll cadence, not an
  assertion threshold, and the give-up branch deliberately lets the real assertion fail cleanly.
- **`ContainerItTest.scala:1460` (`Async.timeout(10.seconds)`), `:3168` (`Async.timeout(30.seconds)`)**
  — hang-safety nets asserting on `Success`/`Failure` outcome, not on measured duration.
- **`ContainerItTest.scala:975`, `ContainerOrchestrationItTest.scala:375-378, 383-390`**
  (`statsStream(interval).take(N).run` then `assert(stats.size == N)` /
  `assert(readAt.distinct.size == N)`) — the interval is stream-emission config input; assertions are
  on exact count and distinctness of sample timestamps (a structural property that each emission is a
  fresh sample), never on a wall-clock bound.
- **`ContainerOrchestrationItTest.scala:522-538`** (`runOnce ... timeout = 2.seconds` →
  `assert(exitCode == Signaled(15))` + timeout-marker substring) — `timeout` is config input; the
  assertions are on the typed exit-code outcome and the stderr marker, the correct conversion.
- **All `Schedule.fixed(...).take(N)` health-check / retry schedules** (`ContainerItTest.scala:360,
  671, 689, 701, 719, 734, 756, 790, 809, 3031, 3463, 3654, 3676`;
  `ContainerOrchestrationItTest.scala:433, 471`; `ServiceMesh`/`IntegrationTestScaffold` demos) —
  constructed retry/health-check cadence + attempt-cap config, not assertions. The cap is a bounded
  give-up valve.
- **`ContainerTest.scala:246-247, 328-331, 1042`** (`Schedule.fixed(123.millis).take(1)`,
  `ContainerTimeoutException("pull image", 30.seconds)`, `assert(Config("alpine").stopTimeout ==
  3.seconds)`) — pure `Duration` value equality / exception-construction / config defaults; no clock
  or sleep. Grep false positives from the `millis`/`seconds` unit suffixes.
- **`BasePodTest.scala:11, 45, 123, 178` and `ContainerPredefItTest.scala:13`** (`override def
  timeout = 60.seconds`, `HttpClient.withConfig(_.timeout(60.seconds / 5.minutes))`,
  `override def timeout: Duration = 3.minutes`) — test-framework per-test budgets and HTTP client
  request timeouts; infrastructure config, not timing assertions. Comments explicitly frame these as
  budgets sized to the daemon's first-run cost, not thresholds under test.
- **`ContainerItTest.scala:182`** (`Path(s"/tmp/kyo-fake-${currentTimeMillis}.sock")`) — timestamp
  used only to build a unique filename; not measured or bound-checked.
- **`kyo/internal/FrameAssemblerTest.scala`, `LineAssemblerTest.scala`, `HttpContainerBackendTest
  .scala`, `ShellBackendTest.scala`** — pure unit tests; the only "poll" hits are frame-mode
  narration ("a later poll starting with a header-like byte"), referring to stream reads, not
  wall-clock polling; `ShellBackendTest` asserts are on error-message substrings. No sleep, no clock,
  no threshold.
- **`demo/*` (CodeSandbox, IntegrationTestScaffold, LogAggregator, PrometheusExporter, ServiceMesh)
  and `dev/KyoPodDevRunner`** — all `object ... extends KyoApp` demo entry points, not test suites.
  Their `Async.sleep`/`Async.timeout(5.seconds)`/`Schedule.fixed` uses are demo workload/polling
  behavior; the word "assert" appears only in scaladoc describing what a *caller* could assert. No
  test assertions, timing or otherwise.
- **`ContainerPredefTest.scala`, `ContainerRuntimeBase/JvmLike.scala`, `ContainerRuntime.scala` (jvm/
  js-wasm/native), `PodNodeBuiltins.scala`** — no time/threshold hits; runtime-discovery and predef
  config tests only.

---

## Summary

8 flagged sites (all in the two integration suites, `ContainerItTest.scala` and
`ContainerOrchestrationItTest.scala`); the internal unit tests, predef/base tests, and demo apps are
all clean. Anti-pattern vs cleared: **8 anti-patterns, ~20 cleared clusters** (retry/health-check
schedules, timeout safety nets, framework budgets, `Duration` value equality, demos, internal unit
tests). The reference flake's exact sibling is **#1** (`assert(elapsedMs < 500)` on a daemon
round-trip, with a comment already admitting daemon latency is variable) and **#2** (`assert(firstMs
< 3000)` on a 4s build with only a 1s safety margin) — both wall-clock *ceilings* on integration ops,
the same false-fail-under-load shape as "init completes under 2s (2.1s under load)". **#3** (fixed
`sleep(800.millis)` then assert four log lines settled and ordered) is the highest-risk settle-check.
The remaining five are floor-only or count-ceiling checks whose false-fail direction implies a real
bug (low flake-red risk) but which still carry genuine wall-clock dependencies with a non-timing
witness available; the suite already ships the deterministic replacement idiom
(`Retry[AssertionError](Schedule.fixed(50.millis).take(N))` poll-until, `assertEventually`,
`Loop.indexed` poll, attempt-counter assertions).

Ranked one-line list:
1. `ContainerItTest.scala:2731` — `assert(elapsedMs < 500)` on exec-on-stopped daemon round-trip (comment admits Podman SSH latency; the reference shape).
2. `ContainerItTest.scala:2041` — `assert(firstMs < 3000)` build-streaming ceiling, only 1s margin over a 4s build.
3. `ContainerItTest.scala:3557` — `Async.sleep(800.millis)` then assert all four log lines present + ordered (settle-check, e2 lands ~0.6s + flush).
4. `ContainerItTest.scala:1797` — `assert(pullMs > ensureMs * 2 || pullMs > 500)` relative pull-vs-ensure timing.
5. `ContainerOrchestrationItTest.scala:499` — `assert(elapsedMs >= 800)` && `< 20000` dual wall-clock bound on stopTimeout cleanup (floor safe, ceiling generous).
6. `ContainerItTest.scala:161` — `assert(elapsed >= 1000)` meter-concurrency floor via raw `currentTimeMillis` (floor-only, correct-direction).
7. `ContainerItTest.scala:3371` — `Async.sleep(2.seconds)` then `assert(entries.size <= 1)` on logStream-of-stopped-container (generous, low risk).
8. `ContainerOrchestrationItTest.scala:407` — `Async.sleep(2.seconds)` before reading crashed-container state (borderline; drives no threshold assertion).
