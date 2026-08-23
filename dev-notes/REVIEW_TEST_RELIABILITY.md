# Held-out review: test-reliability campaign (`1a84184d32^..HEAD`, 26 commits)

Independent, skeptical review of the "assert the event/effect/outcome, never elapsed time"
campaign. Judged from the diffs and the surrounding source, not from the author's commit
messages or ledgers (those were read only to understand intent). Analysis only; no code was
edited and no tests were run.

---

## Verdict

**The shipped changes are largely sound and, on balance, improve reliability.** The great
majority are principled conversions that replace a fixed sleep or a wall-clock threshold with a
*genuine* barrier: the exact promise/latch a callback completes, a counter the work increments,
`fiber.getResult` / `awaitDone`, `pendingPuts`, `blockedWorkerStatus`, or the observable outcome
that the property actually depends on. Several conversions also *strengthen* coverage by deleting
a construct that made the named property untested (a self-defeating manual interrupt, a
"result != null" check, a callback the test only hoped had fired).

I found **no CRITICAL and no HIGH issues.** The real residue is:

- **2 MEDIUM**: one PLAUSIBLE runtime race I could not run to confirm (the download
  file-existence barrier), and one CONFIRMED-but-pre-existing vacuous test that the campaign
  examined and left with a mislabel (the sibling-isolation `:713` ceiling).
- A handful of LOW items (a `peak <= N` → `peak == N` strengthening that trades away a
  cannot-false-fail property; two coverage-reducing deletions; a bridge-semantics dependency; an
  off-label JDK monitoring API used as a barrier).
- NITs (diagnostic degradation from the detectable-hang idiom; one imprecise commit message).

**The methodology is mostly sound but has two genuine gaps** (P1, P2 below): the
"generous-envelope → leave" carve-out never checks that the envelope sits *below* the failure
value, and "assert outcome, never elapsed" has a real blind spot for isolation tests whose
correct and broken paths share an outcome and differ only in elapsed time.

Confidence: **HIGH** for the JVM core/compat/stm/scheduler/http conversions (fully reasoned from
source, and the framework safety net is confirmed). **MEDIUM** for the browser (CDP/Chrome) and
container conversions, which I could not execute; my concerns there are marked PLAUSIBLE.

Framework fact that underpins the whole campaign, confirmed by reading
`kyo-test/api/shared/src/main/scala/kyo/test/internal/TestBase.scala`:
- `assertEventually` (`:439`) retries `Retry[AssertionError](Schedule.fixed(10.millis))` with
  **no `.take`/`maxDuration`** — it polls **forever**, bounded only by the per-leaf timeout.
- The default per-leaf timeout (`:493`) is **120s** (`Duration.Infinity` when a debugger is
  attached). So every "detectable hang" (1.hour schedule, `Async.never`, unbounded
  `assertEventually`) is caught by the 120s leaf timeout on a non-debug run. The 1.hour idioms
  are correctly `>>` 120s. This safety net is real and the conversions depend on it entirely.

---

## Findings on the CHANGES (ranked by severity)

### MEDIUM

**M1 — `BrowserDownloadTest` recordDownloads: file-existence is a proxy for the target event, and the 2s margin was removed. (commit `f7fe45ddf5`, `kyo-browser/.../BrowserDownloadTest.scala:482`) — PLAUSIBLE**

The sleep `Async.sleep(2.seconds)` was replaced by
`assertEventually(all three files exist)`. The test then reads the `recordDownloads` chunk and
asserts it carries three `WillBegin` events (`names.containsSlice(expected)` at `:504`).

- The barrier gates on **local-filesystem** file existence; the assertion depends on three
  **CDP-websocket** `WillBegin` events having been drained into the kyo `recordDownloads` chunk.
  These are two independent async pipelines. "File C exists on disk" does not *guarantee*
  "WillBegin C has been processed into the chunk" — Chrome emits `WillBegin` first, but whether
  the kyo drainer has processed it by the time the FS poll sees the file is a race.
- The old 2s sleep gave a huge propagation margin. The new barrier can return within tens of ms
  of the clicks (data: URLs are instant), *shrinking* the margin. Under CI load, `recordDownloads`
  can return with only two events → `containsSlice` / `toSet` fails. So this conversion can be
  **racier** than the sleep it replaced, not just cosmetically sleep-free.
- The stale docstring at `:459-465` now contradicts the code: it describes gating "via a Promise
  that the test's own onDownload handler completes when the THIRD WillBegin event lands... gates
  the body's return on the drainer fiber having delivered all three events" — which is exactly the
  correct barrier, and the pattern the author used elsewhere (FiberTest, ZStreams). The code uses
  file-existence instead.

Failure scenario: three clicks fire; files A/B/C land quickly; the FS poll observes all three at
~40ms; `recordDownloads` returns; WillBegin C is still in the websocket receive queue → chunk has
2 events → `containsSlice(Chunk(A,B,C))` false and `toSet != {A,B,C}` → leaf fails.

Suggested fix: gate on the actual event count (the onDownload/Promise pattern the docstring
already describes, i.e. complete a `Promise` from the recording handler on the third `WillBegin`),
not on file existence. At minimum, update the docstring to match the code.

**M2 — Sibling-isolation `:713` left with a ceiling that sits *above* the value it must reject (vacuous). (commit `fa3eda3faa`; `kyo-browser/.../BrowserSettlementTest.scala:693`) — CONFIRMED (pre-existing ceiling, examined-and-left by the campaign)**

The test "Sibling `Async.zip` fibers see their own `withConfig` scope" runs a slow fiber with
`retrySchedule(fixed(50ms).maxDuration(2.seconds))` and a tight fiber with
`retrySchedule(fixed(50ms).maxDuration(100.millis))`, and asserts on the tight fiber
`elapsedMs <= 2400`.

- If isolation **works**, the tight fiber aborts at ~100ms.
- If isolation **fails** and the tight fiber leaks the sibling's 2s config, it aborts at ~2000ms.
- `2000 <= 2400` — so the leak **passes** the assertion. The failure-shape check
  (`BrowserAssertionTimedOutException`) also passes under a leak (it still times out). **The test
  passes whether isolation works or not**; only `elapsed > 2400ms` fails it, which is neither the
  correct nor the primary-failure case. It is vacuous with respect to the property it names.

The campaign explicitly examined this site (PHASE2_RESWEEP.md records reverting a detectable-hang
attempt here) and left it, correctly noting the detectable-hang cannot apply (the sibling schedule
cannot be made infinite without breaking its own positive `#slow` match) — but labelled it a
"borderline measurement subject" and kept the `<= 2400` ceiling. It is not a measurement subject;
its *purpose* is isolation, and isolation here can only be verified by a ceiling positioned
**between** 100ms and 2000ms (e.g. `< 800ms`), which is the one legitimate wall-clock assertion in
the whole campaign. The 24x-widened `<= 2400` ceiling defeats it.

Suggested fix: assert the tight fiber's elapsed is bounded *below the sibling's 2s budget*
(a ceiling in the ~500-800ms band), which is the correct discriminator; keep the sibling's
bounded config. This is the case where "assert outcome not elapsed" is wrong (see P2).

### LOW

**L1 — `peak <= N` strengthened to `peak == N` trades away a cannot-false-fail property. (commits `b62b75a6ed`, `f6f6356416`; ForeachTest `:190/:203/:22`, RaceZipTest `:71`, MeterTest `:94`) — CONFIRMED (design), low probability**

The bounded-concurrency canary went from `assert(peak.get() <= 2)` to `assert(peak.get() == 2)`,
and the unbounded/zip/meter canaries assert `peak == N`. `peak <= N` is guaranteed by the
bound/permit and can never false-fail; `peak == N` additionally requires the scheduler to
*actually deliver* N-way overlap, so it *can* false-fail if valid scheduling under-delivers
concurrency (`peak < N`).

In practice this is robust: each task increments `active` at the very top (no wait before the
increment) and only then holds for 50-100ms, so even on a single carrier thread all N increments
land before any hold's timer fires — `peak` reaches N. To get `peak < N` the scheduler would have
to fail to run N trivial increments within the hold window (~20ms/task), which is not realistic
even on a loaded runner. So this is LOW, not a live flake. But it is a real reduction of the
"cannot false-fail" property that `<=` had, and it is worth stating that `peak == N` is only safe
because the increment precedes the hold; if a future edit moved the hold before the increment, it
would become genuinely flaky.

Note: the concurrency-detection *direction* is a strict improvement — the old `elapsed < 500ms`
could not tell parallel from sequential (a sequential run of five 100ms legs is ~500ms, right at
the boundary), whereas `peak == N` distinguishes them cleanly.

**L2 — Two coverage-reducing deletions/weakenings lean entirely on the leaf timeout. (commits `c0f1db2a76`, `fa3eda3faa`)**

- `CdpBackendLifecycleTest:1148` (deleted, `c0f1db2a76`): the `awaitDrain ... < 5.millis`
  micro-test was the *only* guard that `awaitDrain` wakes via a `Fiber.Promise` rather than a 5ms
  poll loop. The `< 5.millis` bound was genuinely flaky (any GC pause trips it), so deletion is
  defensible, but the "no busy-poll" property is now untested — the sibling drain-waited test
  covers a *different* property ("waits for in-flight work"). Real, if minor, loss.
- `CdpBackendIntegrationTest:228` (`fa3eda3faa`) becomes `... backend.close(1.second) ... yield
  succeed` — assertion-free. It now proves only "close did not hang/throw," relying solely on the
  leaf timeout. The old test also only checked timing, so this is not a *regression*, but it is a
  test that now asserts nothing about the close's effect.

**L3 — ZStreams kyo-side finalizer assertion depends on the bridge synchronously awaiting the kyo finalizer. (commit `538ae08362`; `kyo-zio/.../ZStreamsTest.scala:279`) — PLAUSIBLE-LOW**

The old test had `ZIO.sleep(50.millis)` after `fiber.interrupt` ("give time for cleanup to
propagate"); the new test drops it and asserts `assert(streamFinalized.get())` immediately after
`result <- fiber.interrupt`. This is correct **iff** `fiber.interrupt` (ZIO) awaits the fiber's
teardown *and* `ZStreams.run` bridges the kyo `Scope` finalizer into the ZIO fiber's finalizer
chain so that interrupt-await covers it. That is the ZIO interrupt contract and the evident design
intent, and the author validated 15 runs — but it is a genuine semantic dependency: if the kyo
finalizer runs on a fiber not awaited by ZIO's interrupt, `streamFinalized` can be false at the
assert. The sibling zio-side test (`:101`) is cleaner: it awaits `finalized.await` (the latch the
`ensuring` block releases) directly, with no ordering assumption. The `chunkSize = 1` handling (so
the parking second pull does not swallow the first element into an unfinished chunk) is a correct
and non-obvious detail.

**L4 — `getNumberOfDependents()` used as a synchronization barrier. (commit `c48bf48890`; `kyo-core/.../AsyncPlatformSpecificTest.scala:67`)**

`assertEventually(cf.getNumberOfDependents() > 0)` gates the interrupt on the fiber having
registered its cancel dependent. The JDK documents `getNumberOfDependents()` as an *estimate* for
monitoring, "not for synchronization control." For a coarse `> 0` threshold inside
`assertEventually` (which tolerates transient inaccuracy) it is acceptable, and the final
assertion is the real property (`cf.isCancelled`), so a mis-estimate cannot produce a false green.
Low risk, but off-label; worth a comment acknowledging it.

### NIT

**N1 — Detectable-hang degrades the CI signal on red builds. (commits `c326de9971`, `6d8994d8a0`, `7cc24ef3f3`, `fa3eda3faa`, `c0f1db2a76`)**

The 26-test `BrowserPerCallScheduleTest` conversion (and the other `neverSchedule`/`1.hour`
conversions) replace a precise assertion (`elapsed in [50,1500)ms`, which said exactly what went
wrong) with a coarse one: on a correct build the short schedule aborts at ~100ms; on a regression
the call hangs into the 90s leaf timeout. This is the right call for *stability* (the window was
CI-flaky), but two costs are real and unstated: (a) a single regression in the per-call-schedule
feature hangs **all 26** leaves at 90s each; (b) the failure is reported as "TimedOut," with no
indication of which schedule/value was wrong. The technique is sound; the diagnosis cost is a real
tradeoff, not free.

**N2 — Imprecise commit-message justification (change itself is fine). (commit `6d8994d8a0`, BrowserConfigTest)**

The message says the old `elapsed < 5s/3s` ceilings "could not distinguish the configured budget
from the 8s default." In fact an 8s-default fallback (~8s) *exceeds* the 5s ceiling and would have
failed the old test. The reasoning is off, but the new detectable-hang version (infinite outer
config) is strictly more robust, so the change stands.

**N3 — `BrowserLauncherTest` "very short timeout fails fast" tests spawn-failure, not the timeout. (commit `7cc24ef3f3`)**

The conversion (drop `elapsed < 30s`, keep the typed `BrowserSetupFailedException`) is correct for
what the test *does*, but as the new comment itself notes, a nonexistent executable fails at spawn,
not via the `launchTimeout(500.millis)` path — so the test name overstates what is verified.
Pre-existing; the campaign neither introduced nor surfaced it.

**N4 — FlowApiTest `/signal/x` (commit `796f04ce74`)**: dropping the pre-signal sleep assumes a
just-created (not-yet-running) execution accepts a signal (`body.contains("true")`). Likely fine
(signals buffered against the persisted record; `FlowEngine.start` persists before returning), and
the author verified the suite green, but it is a mild ordering assumption the "record is present
the instant the POST returns" note does not fully cover.

---

## Findings on the PROCEDURE (methodology)

### MEDIUM

**P1 — The "generous envelope → leave" carve-out never checks the envelope is *below* the failure value. (PHASE2_THRESHOLD_AUDIT.md "Deliberately generous envelopes"; PHASE2_RESWEEP "Upper bound relaxed 3× for CI tolerance")**

The audit classifies a ceiling as safe-to-leave if it is "2x-60x the real budget," reasoning that
a wide ceiling is a don't-hang guard rather than a flaky threshold. That is true for a ceiling
guarding a *hang* (sub-ms op under `< 5s`). It is **false** when the ceiling is meant to
*discriminate* two finite regimes: widening it "3x for CI tolerance" can push it past the value it
must reject, making the assertion vacuous. M2 (`:713`, `<= 2400` above a 2000ms leak) is the exact
failure. The rule as written would bless it. Corrected rule: a ceiling is a valid leave only if it
still lies strictly between the correct value and the smallest failure value it must catch; a
"generous envelope" that exceeds the failure value is not generous, it is vacuous.

**P2 — "Assert the outcome, never elapsed time" has a real blind spot: isolation tests where correct and broken paths share an outcome. (TEST_RELIABILITY_PROCEDURE.md core principle + Phase 2 step 2)**

The procedure's default is that the real property is an event/outcome, and elapsed time is only
ever the subject for benchmarks / rate-regimes / the clock itself. It misses a fourth class:
tests whose property is *which of two budgets/configs a fiber used*, where both the correct and
the leaked path abort with the **same exception type** and differ **only** in elapsed time. There,
elapsed time IS the correct assertion (a ceiling positioned between the two budgets), and both the
"assert outcome" rewrite (the outcomes are identical) and the detectable-hang rewrite (the losing
schedule cannot be made infinite without breaking the winning sibling) fail. The procedure
half-covers this under "measurement subject," but the carve-out is drawn around "the wall clock is
the subject," not "the wall clock is the only discriminator," and the `:713` execution shows the
gap: it was mislabelled a measurement subject and left vacuous rather than fixed with a
discriminating ceiling.

### LOW

**P3 — Detectable-hang as a general tool depends entirely on a correctly-sized, finite leaf timeout, and the procedure does not state the precondition. (TEST_RELIABILITY_PROCEDURE Phase 2; PHASE2_THRESHOLD_AUDIT)**

Confirmed against the framework: the technique's only bound is the per-leaf timeout (120s default,
suite-overridable, `Infinity` under a debugger). Consequences the procedure omits: (a) under a
debugger every converted regression hangs *forever*; (b) a suite that lowers its timeout below a
converted correct-path duration would false-fail; (c) a suite with the timeout raised, or a future
`neverSchedule`/`1.hour` reduced below the leaf timeout, would silently stop catching the
regression. The idiom is fine, but it moves correctness into the leaf-timeout config, which the
procedure treats as invisible. It should state: "detectable-hang is valid only where the leaf has
a finite timeout strictly shorter than the infinite duration and longer than the correct path."

**P4 — Unbounded `assertEventually` inherits the same dependency.** The procedure defends
`assertEventually` over a spin loop (correctly — it suspends via `Async` and adds no fixed happy-
path delay), but does not note that a *never-true* condition hangs to the leaf timeout. That is
acceptable here because every converted leaf has the 120s default, but it is the same unstated
precondition as P3.

### Internal consistency

**P5 — The flagship technique picks tuned numbers, which the core principle forbids.** The core
principle says "if you are choosing or adjusting a number, you have not found the event... the act
of tuning is proof it is a timing hack." Yet the detectable-hang idiom *chooses* `1.hour` (and
`withTimeout(2.hours)` in one case, deliberately sized above `neverSchedule`'s 1h step) and relies
on it exceeding the leaf timeout — a second tuned number. This is a defensible, well-reasoned
exception (the numbers are not tuned *to the expected value*, only to be "effectively infinite"),
but the procedure never acknowledges that its own headline technique selects durations, which
reads as a contradiction a faithful follower could trip over.

---

## Strongest / clearly-correct changes (defended)

These are unambiguous improvements; several fix latent false-green bugs, not just sleeps:

- **STMStressTest attempt-counter barriers** (`60e95fc6e4`, `840ec5996c`): incrementing a counter
  at the top of the retrying transaction body and awaiting `assertEventually(attempts >= N)` is a
  genuine, zero-tuning barrier. I checked the potential "publish lands in the window before the
  first `r1.get`" race (`:194`): the increment and the `r1.get`/`retryIf` are in the same
  synchronous STM body with no suspension between them, so the publish can only land *between*
  attempts, after a read of the pre-publish value — the final `nr >= 2` is guaranteed, not flaky.
  The barrier also proves the wake-path is actually exercised (not a vacuous first-attempt read),
  which the fixed sleep never did. Validated 4-5 full-suite runs.
- **FiberTest CPromise barriers** (`6b768f1346`): textbook await-the-exact-callback-event. The
  callback-*failure* test now signals the promise *before* throwing, upgrading "hoped the callback
  fired" to "proved it fired"; the outcome test drops a now-impossible `result != null`.
- **PublisherToSubscriberTest** (`0cd30a8eff`): removes a self-defeating manual interrupt of the
  four subscriber fibers that ended them regardless of whether cancellation propagated — i.e. the
  named property was previously *untested*. Awaiting each `getResult` makes propagation the actual
  pass condition. Strict coverage gain.
- **ExchangeTest `awaitDone` + ChannelTest `pendingPuts/pendingTakes`** (`c48bf48890`): exact
  terminal-state / pending-count events; the dropped "let it block" sleeps precede assertions that
  are deterministically false regardless of timing (channel full/empty, nothing polls), so their
  removal is safe.
- **MutationSettlementTest / ProcessTest / HubTest outcome assertions** (`bffc8514a2`,
  `f08cfd8be9`, `b62b75a6ed`): the DOM text (`"before"`/`"updated"`/`"queued-write"`),
  `result == Absent`, and `result == (1 to 10)` each fully carry the timing property the dropped
  ceiling/floor was proxying; an early/late return reads the other value or hangs.
- **HttpWebSocketTest** (`2c08c40cdb`): the client handler awaits the promise the server completes
  from its `closeReason` — the connection stays open until the close is genuinely observed.
- **Scheduler barriers** (`8519cea407` WorkerTest `eventually`, `38ac21620e`
  `blockedWorkerStatus().isDefined`, `ddb190e965` idempotent-`raise`-under-`eventually`): each
  replaces a fixed settle with the exact state the sibling tests already await; the retained
  `Thread.sleep(200)` in WorkerTest is honestly kept and documented as a load-bearing teardown
  ordering settle with no clean event (see below).
- **BrowserPerCallScheduleTest** (`c326de9971`): despite N1, this is a careful conversion — the
  `withTimeout(2.hours)` variant correctly reasons that `2.seconds` would let the wrong path abort
  at the cap (not hang) and so would not discriminate. Full per-method coverage preserved.

---

## Anything missed (convertible cases left / unsafe leaves)

- **The one genuine unsafe leave is M2/`:713`**: recorded as a "borderline measurement subject"
  when it is actually a vacuous isolation assertion. It should have been surfaced as a
  test-quality bug (ceiling above the leak value) and fixed with a discriminating ceiling, not
  left. This is the sharpest instance of P1/P2.
- **The recordDownloads stale docstring (M1)** describes the correct barrier the code does not
  use — a missed opportunity to apply the author's own better pattern.
- **Honest leaves that are correctly handled** (not misses): the author *does* surface, rather
  than silently skip, several genuine latent test-quality bugs and declares them out of scope for
  a sleep pass — `MpscUnsafeQueueTest:61` (vacuous per-producer FIFO check because
  `start.countDown()` runs after the 1s sleep), `UnsafeQueueBaseTest` (invariant asserts built at
  thread-construction time, before the threads run), `ResolversTest:976/:1398/:1191`
  (constant-string "vacuous" assertions). Flagging these in the ledger rather than converting them
  is the right call for a sleep-focused pass; they are correctly *routed*, not dropped.
- **Reverts are the correct calls, for sound reasons**: the composed-signal SignalTest x13 revert
  (the observe-then-set window is real — `switchMap`/`combineLatest` re-arm via `Async.race` with a
  subscribe gap, and the author caught the flake only on the ~10th run, banking the "one green run
  proves nothing" lesson); the kyo-data queue "fix" that spun 8 threads for 1s and starved the
  harness (a redesign, not a sleep edit); the WorkerTest `Thread.sleep(200)` retention (dropping it
  hung the test — no clean teardown event exists); the `BrowserSettlement:713` detectable-hang
  attempt (validated STUCK before commit); the kyo-pod `:477/:149` reverts (unvalidatable without a
  Linux socket backend, honestly marked "compile-green is not runtime-green" and not committed).
  Each revert reason checks out against the code.

Net: the campaign is disciplined, the reverts and most leaves are correct, and the conversions
overwhelmingly raise reliability. Fix M1 (gate on the event, not the file) and M2 (a discriminating
ceiling for the isolation test), fold P1/P2 into the procedure, and the body of work is solid.
