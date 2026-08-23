# Phase 2 Audit: Threshold-Sensitive Tests

Full sweep of every `*Test.scala` asserting on an elapsed-time magic number or a load-dependent
count/rate. Companion to `TEST_RELIABILITY_PROCEDURE.md` (Phase 2 section).

## The load-bearing conclusion

**Most "thresholds" are NOT flaky and must be left alone.** They fall into three leave-buckets:

- **Deliberately generous envelopes** (2x-60x the real budget): a ceiling like `elapsed < 5.seconds`
  guarding a sub-millisecond operation is a *don't-hang* guard, not a flaky threshold. Widening or
  fiddling it is tuning; converting it to `Async.timeout` is a refactor, not a reliability fix.
- **Already virtual-clock** (`Clock.withTimeControl` / `TestTimer`): deterministic. Leave.
- **Measurement subjects**: the wall clock / rate IS the thing under test (the `Sleep` primitive,
  time-shift ratios, statistical admission rates). Leave.

The **genuinely flaky** set (a tight ceiling sitting near the expected value, so CI variance crosses
it) is SMALL. That is the real Phase 2 target, plus the range-ceilings whose floor already carries
the invariant.

## Category counts (from the sweep)

| Cat | Meaning | Findings | Disposition |
|---|---|---|---|
| A. FLAKY-CEILING | `elapsed < X` "not too slow" | ~46 | mostly generous -> leave; a few tight -> convert |
| B. DELAY-LOWER-BOUND | `elapsed >= X` delay happened | 12 | robust floors -> mostly leave; some -> `withTimeControl` |
| C. RANGE | `elapsed >= lo && < hi` | ~30 (24 in one file) | keep floor, the ceiling is the flaky part |
| D. VIRTUAL-CLOCK-ALREADY | `withTimeControl`/`TestTimer` | large | leave (deterministic) |
| E. MEASUREMENT-SUBJECT | wall-clock/rate is the subject | ~11 | leave |
| F. COUNT-THRESHOLD | count chosen to usually-pass | 4 | mostly leave/wide; 1 tidy-up |

## Status (decisions + progress)

- **#1 JsonRpcHandlerTest:858** - DONE. Dropped `elapsed < 100`; "noop" has no responder so
  `awaitDrain` completing proves non-blocking. Committed `87a10ef7d0`.
- **#2 BrowserReadTest:1147,1180** - DONE. Fast-path proven by making the retry a detectable
  hang (`Schedule.fixed(1.hour)`); returning at all proves no retry. Committed `c0f1db2a76`.
- **#3 ClockTest "Sleep" subsection** - LEAVE (user decision). Testing real `Async.sleep` via
  `Clock.withTimeControl` would be circular (the clock validating itself). Measurement subject.
- **#4 BrowserPerCallScheduleTest** - DONE. All 26 timing-window tests now assert the abort
  OUTCOME only: the losing schedule is `neverSchedule = Schedule.fixed(1.hour)` so a wrong
  choice hangs the 90s per-leaf timeout, while the short winner aborts at ~100ms. Keeps the full
  per-method per-call-override coverage; zero thresholds. Committed `c326de9971`. Validated 3
  runs (27 passed each, slowest leaf 553ms).
- **#5 CdpBackendLifecycleTest:1148** - DONE. Deleted the `awaitDrain < 5.millis` perf micro-test
  (implementation detail, boundary-tight); behavior covered by the sibling drain-waited test.
  Committed `c0f1db2a76`.

## Genuinely-tight targets (the real Phase 2 work), in priority order

1. **`kyo-jsonrpc JsonRpcHandlerTest:858`** `assert(elapsed < 100)` - the property is "sendUnmatched
   does not block"; the preceding `awaitDrain` completing already proves it. DROP the elapsed
   assertion. Cleanest, lowest-risk.
2. **`kyo-core ClockTest` "Sleep" subsection** (`:265,:279,:280,:290,:300`) - real-clock sleeps with
   tight ceilings (`< 10.millis`, `< 100.millis`). JUDGMENT CALL: the file already uses
   `Clock.withTimeControl` in other subsections, but the "Sleep" subsection may intentionally test
   real-clock integration; converting changes real->virtual semantics. Needs a decision.
3. **`kyo-browser BrowserPerCallScheduleTest`** - 24 `elapsed >= floor && elapsed < ceiling` range
   sites. Floors prove the per-call schedule delay applied (partly a measurement subject); the
   ceilings are the CI-variance risk. Candidate: keep floors, drop ceilings. Big but mechanical;
   JUDGMENT CALL (loosening vs converting).
4. **`kyo-browser BrowserReadTest:1147,1180`** `elapsed < 1.second` fast-path - assert a
   retry-count == 0 (the real "no retry wait" property) instead of the wall clock.
5. **`kyo-browser internal/CdpBackendLifecycleTest:1148`** `elapsed < 5.millis` - Promise-wake-not-
   polling proxy; tight. Hard to make deterministic without `withTimeControl` on the drain clock.
6. **`kyo-core ClockTest:290`** `elapsed < 10.millis` for a zero-duration sleep - property is
   "completes ~instantly"; assert completion.

## Tidy-ups (low value, optional)

- `kyo-scheduler BlockingMonitorTest:507` `eventually(assert(iterations.get() > 1000))` - already
  under `eventually`, so it self-adjusts; the `1000` adds nothing. Could be `> 0`.

## Leave (representative, not exhaustive - see the sweep for all)

- DoS/complexity ceilings (`ParseTest:1502`, `MarkdownTest:454`, `JsonTest` x12, `DocsMarkdownTest:667`)
  - generous, the real property is time; drop-or-leave, a per-leaf timeout catches a true blowup.
- Deadline-enforcement with generous bounds (`ProcessTest:350/366/419/436` `< 5.seconds`) - the
  outcome (timeout Abort / completion) is the real property, the bound is a wide safety net.
- Delay lower-bounds that also assert the effect (`HubTest:354`, `BrowserSettlementTest:468`,
  `MutationSettlementTest:326/381`, `CompilerPoolTest:649`) - robust floors.
- Measurement subjects (`SleepTest`, `ClockTest` TimeShift `:319-332`, `AdmissionTest` rate bands,
  `ConnectDeadlineStrandTest` which already asserts `timedOut == 0`).
- Count thresholds that are wide or gated (`STMStressTest:168` `attempts < 200` - the `done` half is
  robust; `PosixTransportAcceptEmfileTest:154` wide livelock bound; `ContainerItTest:2857` `>= 999`).

## Excluded (flagged by grep, NOT threshold-sensitive)

Semantic invariants / construction-guaranteed counts / deterministic math: `MeterTest:188`,
`ChannelTest:1831/1870`, `AsyncTest:972`, `FlowEngineTest` loop counts, `OTLPClientTest` take-gated
attempts, `ScheduleTest` jitter bounds, `UpdateHistoryTest` timestamp brackets, `ConnectionPoolTest`
monotonic eviction.
