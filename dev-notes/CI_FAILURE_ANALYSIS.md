# CI Failure Analysis: kyo-compat-external-bindings

Date: 2026-08-14
Branch: `kyo-compat-external-bindings` @ `a818d764c8`
Fork CI: `fwbrasil/kyo-ci-test`, full-matrix run `31841875109` (in progress on HEAD)

## Bottom line

The failures are **not just windows**, and my prior "pre-existing / doesn't fail on main"
labels were unreliable. Corrected, evidence-grounded picture:

- **1 real linux-x64 JVM failure**: `JsonRpcHandlerProgressPolicyTest` concurrency case
  **hangs 2m then times out**. This is on a platform main runs. Prime issue.
- **3 windows ui timing flakes**: `RealisticInteractionItTest`, `AnchorTest`,
  `TodoScenarioItTest` (assertText/actionable races). Same family as the kyo-ui browser
  flakes already fixed (ChatScenario, Iframe, HtmlRenderer).
- **arm64 Native**: still running (~5h historically vs 6h cap). Slowness/timeout risk,
  unverified this run.
- The arm64 **container/fork** failures from earlier runs are **fixed** on HEAD (PidsLimit
  fix); arm64 JVM/JS/Wasm are green in this run.
- The `doctest: validation failed` line is a **red herring** (kyo-doctest self-test fixture).

## Methodology correction (why "pre-existing" was wrong)

Two labels I used repeatedly do not survive scrutiny:

1. **"These tests don't fail on main's recent CI runs."**
   - For the **windows** ui tests this is **vacuous**: `origin/main` excludes windows from
     push/PR runs. Commit `1e1c0f4d65` ("[ci] drop windows-x64 from push/PR runs while
     windows failures are worked", Flavio Brasil, Aug 3) sets `oses='linux-x64 linux-arm64'`.
     It is an ancestor of the merge-base, so main runs **no windows** by default. The branch
     re-enabled windows (`54c2a0313b`, `oses='... windows-x64'`) per the full-matrix mandate,
     surfacing exactly the "windows failures" that commit was deferring. Main cannot show
     these failing; "not seen on main" says nothing.
   - For the **jsonrpc** hang this is **weak**: main runs linux-x64 JVM, but the hang is a
     rare concurrency flake. A handful of green main runs does not clear a 1-in-many flake.

2. **"The test file exists on main."** True but irrelevant: existence of the test says
   nothing about whether the branch's runtime changes affect its code path.

The correct bar (per repo policy) is a clean reproduction on the base commit, which I have
**not** yet done for the jsonrpc hang. Until then its root cause is diagnosed by code, not
proven by repro.

## Branch position

- 102 ahead / 2 behind `origin/main`. Merge-base `2e9bb02d40`.
- The 2 behind are `654618e394 [kyo-data] add Glob` and `ee795b7d39 Bump coursier` — neither
  touches any failing path. Not on latest main, but immaterial to these failures.

## Branch code footprint (main sources changed vs merge-base)

| Module | Files | Nature |
|---|---|---|
| kyo-http | `HttpClientBackend` (+139), `Http1ClientConnection` (+8) | streaming-pool reuse deferral; DecoderState split |
| kyo-net | `IoUringDriver` (+3) | **diagnostic string only** (frame position in a debug dump) |
| kyo-pod | 5 files | PidsLimit fix, fork readiness, error classification |
| kyo-reactive-streams | `StreamSubscriber` (+12) | added `subscribed`/`awaitSubscribed` promise |
| kyo-sql / -mysql / -postgres | 8 files | connection custody / pool |
| kyo-test/api | `AssertMacro` (+76) | compile-time macro |

**Untouched by the branch:** `kyo-core`, `kyo-kernel`, `kyo-prelude`, `kyo-data`,
`kyo-combinators` (the entire core concurrency runtime), and **all of `kyo-jsonrpc` and
`kyo-ui` main source** (only test files changed there, none of them the failing tests).

## Current-HEAD matrix status (run 31841875109)

| Pole | JVM | JS | Wasm | Native |
|---|---|---|---|---|
| linux-arm64 | ✅ | ✅ | ✅ | running |
| linux-x64 | ❌ jsonrpc | ✅ | ✅ | running |
| windows-x64 | ❌ ui | ❌ ui | n/a | n/a |

## The 4 live failures

### F1 — linux-x64 JVM: `JsonRpcHandlerProgressPolicyTest` (PRIME)

- Case: `"enforceMonotonic=true concurrent: the larger value is always emitted; a smaller
  value never follows it".times(100)` — **hung, 2m TIMEOUT** (1 of 15 cases).
- What it does: handler runs `Async.zip` of **10 concurrent** out-of-order `ctx.progress(v)`
  emissions, then returns `TaskResp`; caller awaits `pending.result`. Runs 100×.
- Path: `ctx.progress` → `ProgressEngine.buildProgressSink` sink →
  `monoMutex.run { monoRef.get; ...; emit() }` where `emit()` does
  `writerChannel.put(WriterMsg.SendEnvelope(env))` **inside the mutex**
  (`ProgressEngine.scala:135`, `:118`). All of this is kyo-jsonrpc + kyo-core, **unchanged
  by the branch**. kyo-jsonrpc depends only on kyo-prelude/core/schema-json/kyo-net; of
  those only kyo-net changed, and only the benign io_uring diagnostic string.
- **Deadlock hypothesis (needs confirmation):** the per-invocation `monoMutex` serializes
  the 10 concurrent sinks; each holds the mutex across `writerChannel.put`. If the writer
  channel is bounded and its drain fiber stalls or is slow, a `put` blocks **while holding
  the mutex**, the other sinks block on the mutex, `Async.zip` never completes, the handler
  never returns, and `pending.result` hangs to the suite timeout. A compare-and-emit that
  holds a mutex across a bounded-channel `put` is a classic hold-and-block deadlock shape.
- **Verdict:** real concurrency bug (or genuinely flaky test) in **unchanged kyo-jsonrpc
  code**. Not caused by a branch code change on its path. Still mine to fix to reach green;
  a fix would also protect main (main runs this test).
- **Open:** not yet reproduced locally; deadlock-vs-flake and exact stall point unproven.

### F2 — windows JVM: `RealisticInteractionItTest`

- Case: `"fill then fill replaces value"` → `BrowserAssertionTimedOutException` at
  `assertText(id("v"), "sig:hello")` right after `fill(id("i"), "hello")`
  (`RealisticInteractionItTest.scala:109`). 1 of 52 cases.
- Signature: controlled-input `value` signal lagging the JS fill — the exact pattern already
  diagnosed and fixed for `ChatScenarioItTest`. **Not a hang.**

### F3 — windows JS: `AnchorTest`

- Case: `"anchor onFocus fires"` → `BrowserAssertionTimedOutException: assertText expected
  true got false`. Focus → signal → DOM text not settled in time. 1 of 12 cases.

### F4 — windows JS: `TodoScenarioItTest`

- Case: `"edit item inline click saves"` → `BrowserElementNotActionableException: element is
  not attached to the DOM` (a reactive re-render detached the node before the click). 1 of
  12 cases.

**F2–F4 verdict:** kyo-ui/kyo-browser browser DOM/signal-timing races, windows-specific
(slower Chrome input/render timing). Test code unchanged by the branch. They route through
kyo-http (kyo-ui → kyo-http; kyo-browser → kyo-http) for CDP transport, but the failure
signatures are application DOM/signal timing, not response-body pooling, so the branch's
kyo-http streaming-pool change is an unlikely cause. **Branch-surfaced** (windows re-enabled),
same family as flakes already hardened. Mine to fix by hardening fill→assert and click→DOM
interactions (retry/settle), as done for ChatScenario.

## Not failures

- **`doctest: validation failed (exit code 1)`** appears in JVM jobs but is the
  `kyo-doctest/failure` **self-test**: it validates a deliberately-broken README
  (`Found: (42: Int) Required: String`) to prove the doctest tool detects failures
  (`failures=1` is the expected result). Intentional meta-fixture, like PTFailSuite. ci-logs
  filters it; not a real failure.
- **arm64 container/fork failures** in older runs (`31753844302` @ `6819f7325c`,
  `31716762245` @ `c4401de4c3`) predate the PidsLimit fix (`23f6a5e551`). On HEAD, arm64
  JVM/JS/Wasm are green. Fixed.

## Open gaps / next steps

1. **Reproduce F1 locally** (loop `kyo-jsonrpcJVM/testOnly
   kyo.JsonRpcHandlerProgressPolicyTest`, jstack the forked JVM on hang) to confirm the
   mutex-across-put deadlock and localize it. Then compare against `origin/main` to settle
   branch-vs-pre-existing definitively.
2. **Root-cause and fix F1** at the ProgressEngine level (do not hold the mono mutex across a
   blocking channel put; or bound/decouple the emit) — reproduce-first, keep a regression
   guard.
3. **Harden F2–F4** the way ChatScenario was hardened (retry/settle around fill→assert and
   click on reactively-rendered nodes).
4. **Watch arm64 Native** in this run for slowness/timeout.

## Questions for Fable

1. F1 deadlock: is the `monoMutex.run { ... writerChannel.put(...) }` in
   `ProgressEngine.scala:135` a real hold-and-block deadlock under 10 concurrent sinks, or is
   the hang elsewhere (writer drain fiber, in-memory transport, `pending.result`)? What is
   the correct fix that keeps wire monotonicity without holding a mutex across a bounded put?
2. Do any branch changes (kyo-http streaming pool, `StreamSubscriber.awaitSubscribed`,
   kyo-net diagnostic) have a **non-obvious** path into F1 or F2–F4 that my dependency read
   missed?
3. F2–F4: is the kyo-http streaming-pool change plausibly implicated in CDP-over-http timing,
   or are these purely kyo-ui reactive-render/Chrome-timing races?
4. Any remaining development-process leakage or oversized narration in the branch's changed
   source comments (per the standing comment-hygiene ask)?
