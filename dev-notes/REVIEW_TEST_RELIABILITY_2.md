# Independent Second-Opinion Review: Test-Reliability Campaign

Scope: commit range `1a84184d32^..HEAD` (27 commits), latest `0da6e17e10`
("test-reliability: address held-out review findings"). Formed independently
from the diffs and source; `REVIEW_TEST_RELIABILITY.md` was not read. Analysis
only, no code changed, no tests run.

## Verdict

**The shipped changes are, in aggregate, SAFE and a genuine net improvement to
test reliability.** The large majority of conversions are sound, and several fix
tests that were previously vacuous or genuinely flaky (RaceZip/Meter/Foreach
timing canaries, the reactive-streams self-defeating "safety net", the
JsonRpc/Config/History wall-clock ceilings). The peak-concurrency and
detectable-hang families are, on my reading of the source, robust.

**The methodology is largely sound**, with good self-corrections (the
generous-envelope-vs-discriminating-ceiling distinction; the multiple-runs rule
that caught the composed-Signal flake). It has three real weaknesses: it leans
very heavily on the detectable-hang idiom without accounting for its aggregate
failure-diagnosis cost; its ledgers claim fixes that the code shows did not
happen; and it leaves some genuinely vacuous tests unfixed (correctly surfaced,
but the leave is not fully honest about the false-green they still produce).

The single weakest shipped change is HEAD's `BrowserSettlementTest:696`
`<= 1500ms` ceiling: not vacuous (an improvement over the prior `<= 2400`), but
it substitutes a thin, mischaracterized wall-clock margin for a clean
detectable-hang that was available and was rejected on a factually wrong premise.

Confidence: **Medium-high on the non-browser conversions** (STM, kyo-compat,
kyo-scheduler, kyo-zio, reactive-streams, http, jsonrpc, caliban), which I could
fully reason about from source. **Lower on the browser/CDP timing claims**, which
I could NOT run: the campaign's browser suites need a real Chrome + CDP (and the
pod suites need a Linux container socket this host lacks per the author's own
PHASE2_RESWEEP note). So the `940-982ms` isolated measurement and the
`:696` false-fail risk are reasoned from architecture and from the author's own
numbers, PLAUSIBLE not empirically CONFIRMED by me.

What I could NOT verify:
- Any browser/CDP test end to end (BrowserSettlement/Download/PerCallSchedule/
  Read/Config/History/Isolate/Launcher, CdpBackend*). Timing values are the
  author's.
- The kyo-pod container tests (correctly left unconverted; host has no socket).
- Multi-run stability of any concurrency conversion (the author reports 3 to 15
  runs; I take those as reported, not reproduced).

---

## Findings on the CHANGES

### HIGH-1 (HEAD) BrowserSettlementTest:696 `<= 1500ms` ceiling: thin, mischaracterized margin; a clean detectable-hang was available and dismissed on a false premise

File: `kyo-browser/shared/src/test/scala/kyo/BrowserSettlementTest.scala:653`
(test), `:667` (sibling config), `:685` (tight config), `:696` (the assert),
`:676-684` (rationale comment). Commit `0da6e17e10`.

Status of the change: it correctly replaces a VACUOUS ceiling (`<= 2400` sat
above a ~2000ms leak, so it passed either way) with a DISCRIMINATING one. That
part is a real improvement and the diagnosis is correct.

Two problems remain:

1. **The comment's causal claim contradicts the author's own measurement
   (CONFIRMED).** The comment (`:679-680`) says the isolated tight fiber aborts
   "~100ms retry budget + data-page goto overhead, well under 1s". The commit
   message reports the isolated value as **940 to 982ms**. That is ~10x the
   claimed 100ms and is NOT "well under 1s"; it sits right at 1s. `timed{}`
   (`BaseBrowserTest.scala:77`) wraps only the `waitForText` retry loop (the
   `goto` is outside it), and `waitForText` -> `BrowserAssertion.withStability`
   (`BrowserAssertion.scala:53`) runs `Retry(Schedule.fixed(50.millis)
   .maxDuration(100.millis))` around a per-attempt `evalJs`. With a 100ms
   schedule budget the loop's elapsed being ~950ms means the value is dominated
   by something the comment does not name: most plausibly per-attempt CDP
   round-trip latency while the sibling fiber contends the shared connection
   (the two fibers run under one `Async.zip` on one `isolate.fresh`). So the
   author does not actually know why the value is ~950ms, and the stated
   discrimination ("100ms isolated vs 2000ms leak") is not the mechanism in play.

2. **The margin is thin and load-sensitive; a non-flaky alternative exists
   (fix is CONFIRMED available; false-fail is PLAUSIBLE).** Measured isolated
   value 940-982ms, ceiling 1500ms: headroom ~520-560ms on a fast local host,
   for a concurrent browser test polling over a shared CDP connection, which is
   exactly the class that varies 1.5-2x under CI load. If the isolated abort
   ever crosses 1500ms while isolation is in fact holding, it false-fails. The
   author claims detectable-hang "does not fit" because "the sibling's config
   cannot be made infinite without breaking the sibling's own positive #slow
   match" (`:676-684` and PHASE2_RESWEEP). **That premise is false.** The
   sibling matches on `#slow == "arrived"`, which its inline `setTimeout` sets
   at **700ms** (`:657-659`), well below the sibling's current `maxDuration(2s)`
   ceiling. Raising the sibling to `Schedule.fixed(50.millis).maxDuration(1.hour)`
   leaves the 700ms positive match completely intact, while a config LEAK would
   give the tight fiber `maxDuration(1.hour)` on a never-matching predicate ->
   retries forever -> `Async.zip` waits on it -> hangs into the 90s leaf timeout.
   That is a clean, wall-clock-free discriminator: correct path finishes ~950ms
   << 90s, a leak hangs. It is strictly better than the `<= 1500` ceiling and
   avoids the false-fail vector entirely.

Suggested fix: raise the sibling config's `maxDuration` to an effectively
infinite value (`1.hour`), drop `timed{}` and the elapsed assertion, and assert
only that the tight fiber aborts with `BrowserAssertionTimedOutException` (a leak
hangs the leaf). This is the same detectable-hang shape the campaign already used
26 times in BrowserPerCallScheduleTest. Net: HIGH because it is the one shipped
change with a plausible new-flake vector AND a demonstrably better alternative,
and the code comment now enshrines a wrong rationale. Note it is an improvement
over the prior vacuous state, not a regression from a working one.

### MEDIUM-1 (HEAD + f7fe45ddf5) BrowserDownloadTest recordDownloads: back to a 2s sleep, and the arrival-order assertion is partly vacuous

File: `kyo-browser/shared/src/test/scala/kyo/BrowserDownloadTest.scala:490`
(sleep), `:501-504` (assert). Commit `0da6e17e10` (revert), original barrier
from `f7fe45ddf5`.

The revert's premise is CONFIRMED correct: `recordDownloads`
(`Browser.scala:3103`) is built on `onDownload`, which installs a single
per-session dispatcher keyed by sessionId (`Browser.scala:3072`
`downloadEventDispatchers.getAndUpdate(_.update(sidKey, handler))`), so a nested
`onDownload` REPLACES the capture handler rather than composing. The internal
`collected` AtomicRef is not exposed to the body, so there is genuinely no
in-body drain-completion event to await, and reverting the file-existence
barrier is right (WillBegin fires at download start, files land on a separate
pipeline, so file existence does not prove the events drained). Under the
"no observable event -> honest sleep" rule the leave is defensible, and 2s is a
generous envelope for three local `data:` downloads.

Two caveats keep this at MEDIUM rather than "confirm safe":
- The test is back to exactly the fixed-duration race the campaign set out to
  remove, and `recordDownloads` interrupts its drainer fiber and closes the
  channel on body exit (`Browser.scala:3078-3086`), so if the drain ever exceeds
  2s under load, late events are dropped and the assertion under-counts. Low
  probability, but it is a real race, not a barrier.
- The assertion itself (`:502`) is `names.containsSlice(expected) ||
  names.toSet == expected.toSet`. The second disjunct accepts ANY order, so a
  test named "in arrival order" does not actually enforce arrival order. This is
  pre-existing, not introduced by the revert, but it means the test the sleep
  guards is weaker than its name.

Suggested: leave the sleep (the premise holds), but either tighten the assertion
to `names.containsSlice(expected)` only (drop the set-equality escape hatch) or
rename the test, and consider that the deeper gap is a testability hole in
`recordDownloads` (no drain-completion observable), which is a production API
note, not a test fix.

### MEDIUM-2 (leave) caliban ResolversTest: two cancellation tests remain genuinely vacuous, and a ledger claims they were fixed

File: `kyo-caliban/src/test/scala/kyo/ResolversTest.scala:976-979` and
`:1191-1192`. Not touched by the committed caliban change (`8946b9018a`, which
only converted the afterInit test at `:1658`).

`:976-979`: sends `{"type":"complete","id":"forever"}`, then
`Async.sleep(200.millis)`, then `yield "ok"`, and the outer assert is
`assert(result == "ok")`. The asserted value is a hardcoded constant unrelated to
whether the server actually cancelled the subscription. VACUOUS: it passes even
if cancellation is completely broken. `:1191-1192`: `Async.sleep(100.millis)`
then `succeed("... no leaked fiber")` asserts nothing about fiber leakage.

The author DID surface both in the ledgers as "vacuous-constant = separate
test-quality issue (needs a hook)", which is honest and the correct diagnosis.
But two things: (a) the leave is not fully honest in the test itself (these
provide false green and nothing in the code flags it); and (b) PHASE2_RESWEEP
line ~131 states ":976/:1398 assert constant strings (vacuous) - fixed as part
of the conversion above", which the code contradicts (they were LEFT, not fixed).
This is exactly why the brief says judge from code, not ledgers. Per
"Leave No Issue Behind" these are the work, not a permanent leave: a server-side
active-subscription/active-fiber counter the client can poll, or asserting the
server stops emitting `next` after `complete`, would make them real. CONFIRMED
vacuous; suggested fix as above; at minimum correct the ledger.

### LOW-1 (HEAD) CdpBackendIntegrationTest close then ConnectionLost: safe, confirm

File: `kyo-browser/.../internal/CdpBackendIntegrationTest.scala:236-243`. Commit
`0da6e17e10`.

CONFIRMED safe and a genuine strengthening (was assertion-free `yield succeed`).
No race: `close(1.second)` runs `closeOrderly` (`CdpBackend.scala:137`) which
awaits the drainer's `getResult` before returning, so the endpoint is fully torn
down when `close` returns (documented invariant at `:106-114`, code confirms).
No wrong-exception risk: a send on a closed endpoint maps `Closed` and
`JsonRpcTransportError | JsonRpcLifecycleError` and the `-32800` timeout ALL to
`BrowserConnectionLostException` (`send`, `CdpBackend.scala:49-68`); the only
non-ConnectionLost branch is a generic protocol error, impossible on a locally
closed connection (no server reply). `BrowserConnectionLostException` is a
`BrowserConnectionException` (`BrowserException.scala:109`), so `Abort.run`
catches it and the `Success`/`other` branches fail loudly. Confirm safe.

### LOW-2 (ddb190e965) finagle interrupt test relies on ScalaTest's 150ms default patience

File: `kyo-scheduler-finagle/jvm/.../KyoFinagleSchedulerServiceTest.scala:72-79`.

The class extends `Eventually` with NO `patienceConfig` override (CONFIRMED),
so `eventually { f.raise(error); assert(p.isInterrupted == Some(error)) }` gets
ScalaTest's default 150ms timeout / 15ms interval. Twitter `raise` is idempotent
and retried, and propagation after `cdl.await()` is fast, so this is very likely
fine, and it is not a regression (the old code asserted exactly once with no
retry, so the new retrying form is at least as robust). But it is the one
`eventually` in the campaign left on the short default; if propagation ever
exceeds 150ms under load it false-fails. Suggested: add an explicit
`timeout(scaled(Span(2, Seconds)))` as the sibling WorkerTest/BlockingMonitor
conversions do (WorkerTest overrides patience to 15s, so that conversion is
safe; this one does not).

### LOW-3 (STM 60e95fc6e4, and the procedure table) the `>= 1` attempt barrier does not strictly prove a genuine retry

File: `kyo-stm/shared/src/test/scala/kyo/STMStressTest.scala:216,:631,:1817`
(the single-waiter `assertEventually(attempts.get.map(_ >= 1))` cases).

The counter is incremented at the TOP of the transaction body, BEFORE `ref.get`.
So observing `attempts >= 1` does not guarantee that attempt's read already saw
the pre-publish value: increment -> (observer sees `>= 1`, publishes) -> the same
attempt's `ref.get` reads the fresh value -> completes without ever retrying.
The comments claim this proves a genuine retry-wake; strictly it does not for the
boundary attempt. This is NOT a stability bug (the actual assertion,
woken/completed, holds either way and is non-vacuous, since STM retry is
schedule-based re-execution and the waiter completes correctly), and it is
deterministic (no fixed sleep), so it is a real improvement over the old
`Async.sleep`. It just over-claims. The multi-waiter `>= N` cases
(`:277,:301,:744`) are stronger. NIT/LOW; if strictness is wanted, increment
AFTER a `ref.get` that observed the unsatisfiable value, or wait `>= 2`.

---

## Findings on the PROCEDURE (TEST_RELIABILITY_PROCEDURE.md)

### MEDIUM-P1 Detectable-hang is promoted as a general tool without weighing its AGGREGATE cost

The procedure endorses "make the wrong path effectively infinite so it hangs into
the leaf timeout" and the campaign applies it at dozens of sites (26 in
BrowserPerCallScheduleTest alone, plus BrowserRead/Config/History/Isolate,
jsonrpc, reactive-streams). Per-site the trade is defensible, and correction #3
honestly lists the three per-site downsides (debugger = Infinity leaf, coarse
TimedOut signal, suite-timeout coupling). What it does not weigh is the
**aggregate** effect: when a whole FAMILY of tests converts to the same
detectable-hang, a single real feature regression (e.g. per-call schedule
override stops applying) no longer fails one leaf fast with a precise elapsed
message; it hangs EVERY converted leaf to the full 90 to 120s timeout, turning a
crisp red into a multi-leaf, multi-minute "TimedOut" wall with no indication of
which property broke. `assertEventually` compounds this: it is unbounded
(`TestBase.scala:442`, `Retry(Schedule.fixed(10.millis))` with no `.take`/
`.maxDuration`), bounded only by the 120s leaf timeout (Infinity under a
debugger, `TestBase.scala:493-495`). The rule should cap detectable-hang usage:
prefer a precise outcome or a peak/effect assertion whenever one exists (as the
peak-concurrency conversions did), and reserve detectable-hang for the residual
cases where correct and broken paths share an outcome and no finite discriminator
exists. As written, the procedure treats it as a first-class default, and the
campaign's usage rate shows that bias.

### MEDIUM-P2 Correction #2 is sound in principle but its application to `:713` embeds a factual error

Correction #2 ("a wall-clock ceiling IS the correct assertion when correct and
broken paths share the same OUTCOME and differ ONLY in elapsed time ... use a
discriminating ceiling positioned strictly between the two regimes") is a correct
refinement. The problem is the campaign applied it to `:713` while a detectable-
hang WAS available (see HIGH-1: the sibling's positive match at 700ms sits below
its ceiling, so the ceiling has headroom to be made infinite). Correction #2's
own guard ("neither ... detectable-hang ... applies") was asserted for `:713` on
a false premise. The rule needs an explicit precondition before reaching for a
discriminating ceiling: first prove the losing schedule cannot be made infinite
WITHOUT breaking the winning path, and "cannot" means the winning path's positive
event is at or above the losing ceiling, not merely that a ceiling exists.

### LOW-P1 "Assert the event, never elapsed" plus the counter table under-specify the retry-proof barrier

The decision table row "a fiber to be blocked/retrying (STM) | a counter
incremented at the top of its body, >= 1" bakes in the LOW-3 imprecision:
incrementing at the top and waiting `>= 1` does not prove the counted attempt
observed the pre-publish value. For a barrier whose stated purpose is "proves the
waiter genuinely retried", the counter must be incremented after a read that saw
the unsatisfiable value, or the threshold must be `>= 2`. Minor, but the
procedure is the thing being copied, so the imprecision propagates.

### Sound aspects of the procedure (credit)

- The generous-envelope-vs-discriminating-ceiling correction (#1) is exactly
  right: a ceiling is only "generous" if it sits strictly below the smallest
  value it must reject; otherwise it is vacuous. This is the correct general
  rule and the author applied it correctly to diagnose the `:713` `<= 2400` as
  vacuous.
- "Leave an honest sleep rather than a fake-deterministic hack" and the banned
  spin-loop / tuned-count rules are correct and well-argued (the kyo-data
  raw-thread-soak lesson is a real trap).
- The multiple-runs gate is vindicated by the composed-Signal case (passed 9/10,
  flaked on the 10th, reverted). That is the procedure working as intended.

---

## Reverts and leaves: assessment

- **SignalTest composed-signal streamChanges (reverted to sleeps).** Correct
  call. The subscribe-window race in `Signal.awaitAny` has no production
  observability hook, the conversion was VALIDATED flaky (9/10), and a reliable
  sleep genuinely beats a flaky pseudo-barrier. Sound reason, right decision.
- **kyo-pod container tests (reverted, not committed).** Correct. Compile-green
  is not runtime-green for a container test and the host has no socket; the
  author refused to ship an unrun container change. This is the right call and
  consistent with the project's container rule.
- **STMStressTest simulated-slow-work / sleep-is-subject / cooperative-yield
  leaves.** Correct category calls; these are genuine durations with no crisp
  event.
- **MpscUnsafeQueueTest:61 (vacuous, left as a reported bug).** Correctly
  surfaced; the naive fix starves the harness, so a redesign is genuinely needed.
  Defensible as out-of-scope for a sleep pass GIVEN it is surfaced, but it is a
  real vacuous test the user should schedule, not a permanent leave.
- **UnsafeQueueBaseTest concurrent harness (left).** This is a genuine latent
  false-green: the invariant assertions are built into the returned `Seq[Thread]`
  at thread-construction time, before the threads run, so the memory-visibility /
  size-invariant tests never assert their invariant. Correctly surfaced by the
  author; still a latent problem worth fixing (a test that passes when the
  concurrency invariant is violated). Same category as MEDIUM-2.

## Strongest / clearly-correct changes (not only negatives)

- **Peak-concurrency canaries (`f6f6356416`, `b62b75a6ed`): the best work in the
  campaign.** RaceZip/Meter/Foreach replaced `elapsed < 5s` (which could not
  distinguish parallel from sequential and, for Meter, never tested the permit
  limit) with an increment-before-suspend peak counter. Because each task
  increments THEN suspends on a genuine async delay, every task increments before
  any resumes on any cooperative scheduler, so `peak == N` is robust, not racy,
  and `peak == 2` for the bounded/Meter cases genuinely tests the bound
  (strictly better than `peak <= 2`). These make previously-vacuous canaries real.
- **reactive-streams PublisherToSubscriberTest (`0cd30a8eff`).** Removes a
  self-defeating "safety net" that interrupted the subscriber fibers directly
  (which made the propagation the test names untestable) and replaces it with
  awaiting each subscriber's `getResult`. This fixes a vacuous test and turns it
  into a real propagation check (a broken propagation now hangs to the leaf
  timeout).
- **BrowserPerCallScheduleTest (`c326de9971`), 26 sites.** Mechanical, well-sized
  detectable-hang: losing schedule `= fixed(1.hour)`, winner aborts ~100ms, leaf
  timeout 90s. Full per-method coverage kept, all wall-clock windows gone,
  correct path (validated slowest 553ms) has huge headroom under the 90s leaf.
- **http HttpWebSocketTest (`2c08c40cdb`) and zio ZStreamsTest (`538ae08362`).**
  Both replace settle sleeps with the real synchronization event (server-observed
  close promise; started-signal + park + interrupt-and-await). The zio
  `chunkSize = 1` detail (so the parking pull does not swallow the first element)
  is a genuine correctness fix for the barrier, and the finalizer-registered-
  before-interrupt ordering is correctly argued.
- **BlockingMonitorTest / WorkerTest (`38ac21620e`, `8519cea407`).** Wait on the
  real `blockedWorkerStatus().isDefined` event with explicit bounded patience,
  and WorkerTest's 15s patience override makes the recovery `eventually` safe.

## Anything missed / residual

- **caliban `:976`, `:1191` vacuous tests** are still live (MEDIUM-2); the
  ledger's "fixed" claim is wrong.
- **UnsafeQueueBaseTest** construction-time-assert false-green is a real latent
  bug, surfaced but unfixed.
- **BrowserDownloadTest arrival-order assertion** has a set-equality escape hatch
  that undercuts its own name (MEDIUM-1).
- **Aggregate detectable-hang diagnosis cost** (MEDIUM-P1) is the one systemic
  concern: the campaign is correct per-site but the family-wide conversion
  degrades the red-build signal for whole feature areas.
- I did not run any browser/CDP/container suite, so every browser timing number
  in this report is the author's, reasoned against source, not reproduced.
