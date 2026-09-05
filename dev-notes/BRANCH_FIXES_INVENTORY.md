# ci-stabilization: fixes carried beyond origin/main, with per-fix CI validation evidence

Branch `ci-stabilization` (fork `fwbrasil/kyo-ci-test`), 38 non-merge commits ahead of `origin/main`
(`git log origin/main..HEAD --no-merges`). The compat work itself already landed on main via #1895; what
follows is the CI-stabilization body on top of it.

Every "validated" line below names a concrete CI run id, whether that run was green or red, the leg, and
the actual result line pulled from the leg's log. No labels stand without a run id + log line behind them.

## How to read the evidence

- **anchor 32941012574** = the single clean, all-10-leg green full matrix, at commit `3cac34b3a` (legs:
  linux-x64 JVM/JS/Native/Wasm, linux-arm64 JVM/JS/Native/Wasm, windows-x64 JVM/JS). Every commit at
  `3cac34b3a` or OLDER is contained in that tree, so its tests appear passing in that run's leg logs. This
  is the primary evidence for the 31 pre-anchor commits. **It is a single green run**: a single green pass
  is not proof a flake is gone. Where a fix's ONLY evidence is this one matrix, that is stated plainly and
  flagged; a de-flake ideally wants a captured before-red plus several after-greens, and most of these have
  the after-green side only.
- Result-line shapes seen in the logs: kyo-test suites print `--- <Suite>: N passed, M failed  (t)`;
  ScalaTest suites (kyo-scheduler, kyo-caliban) print `[info] <Suite>:` with a per-leg
  `Tests: succeeded N, failed 0` global summary and are covered by the `[testKyo] pass: <module>/test`
  line; `cancelled` counts are platform guards firing (e.g. no-Chrome, ffi-it-not-on-windows), which is the
  intended behavior for several of these fixes.
- **Only 32941012574 is a full 10-leg matrix.** The other green runs at `3cac34b3a` (32925372426,
  32931137995, 32931534185, 32931536322) and the per-commit green runs cited below are *targeted custom
  dispatches* (one `custom` job, `build` matrix skipped), confirmed by `gh run view ... --json jobs`. They
  are cited as tighter at-commit evidence for specific suites, not as second full matrices.
- **Class-name vs file-name gotchas confirmed:** `kyo-caliban/.../ResolversTest.scala` declares class
  `ResolverTest` (singular; 96 leaves, JVM-only) ; distinct from `kyo-browser/.../internal/ResolverTest.scala`
  (8 leaves, cross-platform). `SchedulerTest`/`BlockingMonitorTest`/`WorkerConcurrentRunTest`/`SleepTest`
  and `UpdateHistoryTest` are ScalaTest suites, so they emit `[info] <Suite>:` not a `--- ` result line.

---

## kyo-core (Channel / Queue / scheduler)

`434eaa3b0e` **Channel.closeAwaitEmpty: fail parked producers at HalfOpen.** A producer parked on a full
ring was never failed when the channel soft-closed with no consumer, hanging forever. Reproduce-first
ChannelTest leaf (red "parked put got Absent" -> green).
  tests: ChannelTest (kyo-core), QueueTest
  validated: 32999007857 (green, custom linux-x64 Native `kyo-coreNative/testOnly kyo.ChannelTest kyo.QueueTest`)
  -- "ChannelTest: 127 passed, 0 failed  (12.9s)", "QueueTest: 115 passed, 0 failed  (3.0s)".
  local: reproduce-first red->green JVM+Native (author-reported: ChannelTest 123/QueueTest 113 JVM).
  NOTE: post-anchor; NOT in the green full matrix. anchor 32941012574 shows the pre-fix `ChannelTest: 122
  passed` (the fix adds ~5 reproduce-first leaves, hence 127 in the Native custom run). No green full matrix
  contains this fix yet; the rung-4 full run 33003083083 that did contain it failed on unrelated legs
  (doctest, SlackTest-windows), its Native leg was green.

`a90b90c2e6` **Channel.flush: fail a put parked under a soft close.** Residual race Fable found in the
above: a put registering AFTER the one-shot HalfOpen drain still parked. Added
`Queue.Unsafe.offersRejected()`; flush now fails parked puts whenever the queue rejects offers.
  tests: ChannelTest (kyo-core)
  validated: 32999007857 (green, custom linux-x64 Native) -- "ChannelTest: 127 passed, 0 failed  (12.9s)".
  local: reproduce-first red->green JVM+Native (author-reported). Same post-anchor caveat as 434eaa3b0e.

`af2537ef21` **[core,stm,scheduler] de-flake clock/throughput races in core tests.** Real-clock/throughput
assertions converted to virtual time / barriers.
  tests: FiberTest, SignalTest, STMStressTest, scheduler/{SchedulerTest,BlockingMonitorTest,WorkerConcurrentRunTest,util/SleepTest}
  validated: 32941012574 (green, anchor) -- "FiberTest: 111 passed, 0 failed", "SignalTest: 72 passed, 0
  failed, 1 cancelled", "STMStressTest: 82 passed, 0 failed" (10/10 legs); scheduler suites ran green as
  ScalaTest (`[info] SchedulerTest:` etc. in all 3 JVM legs, `Tests: succeeded ..., failed 0`).
  also-green: 32880568226 (custom `kyo-coreJVM/testOnly FiberTest SignalTest`, `kyo-stmJVM/testOnly
  STMStressTest`, `kyo-schedulerJVM/testOnly SchedulerTest BlockingMonitorTest WorkerConcurrentRunTest
  SleepTest`, @af2537ef2) -- "FiberTest: 113 passed, 0 failed", "SignalTest: 72 passed", "STMStressTest: 82
  passed"; the scheduler batch ran in the same green job.
  before-red: NOT FOUND on this branch (checked the red runs around af2537ef2; they were dispatch-infra or
  doctest, not these suites). Evidence is after-green only (anchor + one targeted custom run).

## kyo-tasty

`8ea81edc4f` **Instrument the cold-load pipeline with a Diagnostics dumper for #95.** Adds
`Queue.Unsafe.diagnosticState()`, `Channel.Unsafe.dumpState()`, a scoped `runPhaseAB` dumper so a hang
self-reports the parked primitive. DIAGNOSTIC scaffolding, not a fix.
  tests: ChannelTest, QueueTest (rendering unit checks)
  validated: 32999007857 (green, custom linux-x64 Native) -- "QueueTest: 115 passed, 0 failed" (the +2 vs
  anchor's 113 are the diagnosticState renderings); tasty Native cold-load ran green once at 32999010737
  (custom `kyo-tastyNative/testOnly kyo.demos.IdeHoverDemoTest kyo.internal.TestClasspathsNativeTest`,
  @f9629e9ba) -- "IdeHoverDemoTest: 3 passed, 0 failed", "TestClasspathsNativeTest: 6 passed, 0 failed".
  local: author-reported forced-timeout run confirmed the stuck-leaf `dumpAll()` renders the pipeline state.
  NOTE: does NOT fix #95's intermittent Native hang (still unpinned; recurred red at 32964539057, see below).

`5ab8e18dce` **[tasty,config] rely on the per-leaf cap, drop in-test hang-guards.**
  tests: UpdateHistoryTest (ScalaTest), DeclarationTableTest, SnapshotRoundTripTest
  validated: 32941012574 (green, anchor) -- "DeclarationTableTest: 3 passed, 0 failed" and
  "SnapshotRoundTripTest: 26 passed, 0 failed" (10/10 legs); UpdateHistoryTest ran green as ScalaTest
  (`[info] UpdateHistoryTest:` in all 10 legs, module `kyo-config`/`kyo-tasty` in the `[testKyo] pass:` line).
  before-red: NOT FOUND (removing a hang-guard has no pre-fix failing leaf to capture). After-green only.

## kyo-net

`fb23bd3b79` **TLS/connect deadline tests immune to clock races.**
  tests: net/{TransportStartTlsTest,TransportStartTlsCrossTailTest,TransportHandshakeTimeoutTest,ConnectDeadlineStrandTest,JsTransportTlsTest}, internal/posix/{HandshakeEngineFreeTest,IoUringMutualTlsStressTest}
  validated: 32941012574 (green, anchor) -- "TransportStartTlsTest: 45 passed, 0 failed, 22 cancelled"
  (10/10 legs), "TransportHandshakeTimeoutTest: 7 passed, 0 failed" (10/10), "ConnectDeadlineStrandTest: 2
  passed, 0 failed" (10/10), "JsTransportTlsTest: 9 passed, 0 failed" (3 JS legs),
  "HandshakeEngineFreeTest: 3 passed, 0 failed" (JVM+Native), "IoUringMutualTlsStressTest: 1 passed, 0
  failed" (JVM+Native).
  also-green: 32874107563 (custom `kyo-netJVM/testOnly` of all seven suites, @fb23bd3b7) -- "TransportStartTlsTest:
  56 passed, 0 failed, 33 cancelled", "TransportHandshakeTimeoutTest: 7 passed, 0 failed",
  "IoUringMutualTlsStressTest: 1 passed, 0 failed", "HandshakeEngineFreeTest: 3 passed, 0 failed".
  before-red: NOT FOUND on this branch (the `failure` runs at fb23bd3b7, e.g. 32873578117, were the
  dispatch error "A branch or tag with the name 'fb23bd3b79' could not be found", not a test failure).
  After-green only (anchor + one targeted custom run).

`0e91f5f7f4` **IoUringHandshakeTimeoutOrderingTest deterministic.**
  tests: net/internal/posix/IoUringHandshakeTimeoutOrderingTest
  validated: 32941012574 (green, anchor) -- "IoUringHandshakeTimeoutOrderingTest: 1 passed, 0 failed"
  (linux-x64 JVM+Native, linux-arm64 JVM+Native, windows-x64 JVM).
  also-green: 32874107563 (custom, @fb23bd3b7) -- "IoUringHandshakeTimeoutOrderingTest: 1 passed, 0 failed  (1.6s)".
  before-red: NOT FOUND. After-green only.

`eeddad2677` **Drop the reasonless in-test hang-guards; rely on the per-leaf cap.**
  tests: net/JsTransportTlsTest, net/TransportHandshakeTimeoutTest
  validated: 32941012574 (green, anchor) -- "JsTransportTlsTest: 9 passed, 0 failed" (3 JS legs),
  "TransportHandshakeTimeoutTest: 7 passed, 0 failed" (10/10 legs).
  before-red: NOT FOUND (guard-removal). After-green only.

## kyo-ffi

`a84bedc98a` **PosixTest time() asserted by order of magnitude, not a tolerance.** The C `time(2)` vs
java `System` cross-clock comparison flips on a stall; `jSecs/2 < c < jSecs*2` cannot flip.
  tests: ffi/it/PosixTest
  validated: 32941012574 (green, anchor) -- "PosixTest: 12 passed, 0 failed, 2 cancelled" (linux-x64 JS,
  linux-arm64 JS; the JS leg is where the cross-clock flake lived), "PosixTest: 14 passed, 0 failed" on
  linux JVM+Native; cancelled on windows (ffi-it not run there).
  before-red: NOT FOUND on this branch (the `failure` run at a84bedc98a, 32911011239, was a `custom`
  dispatch failure with no PosixTest test-class in the summary). After-green only.
  NOTE: supersedes b43e04b826; the same PosixTest flake is still live on origin/main (which lacks this commit).

`b43e04b826` **Loosen the PosixTest time() cross-clock bracket to a tolerance.** SUPERSEDED by a84bedc98a
(the order-of-magnitude form). Present in the anchor tree; its behavior is exercised by the same PosixTest
green line above. Squash into a84bedc98a on PR.
  tests: ffi/it/PosixTest -- validated via a84bedc98a's line (32941012574, green, anchor).

`bd68d0e53a` **Native guard-close & backpressure leaves immune to the 5s production deadline.**
  tests: ffi/internal/{CallbackRegistryConcurrencyTest,GuardCloseRaceTest}
  validated: 32941012574 (green, anchor) -- "GuardCloseRaceTest: 7 passed, 0 failed" and
  "CallbackRegistryConcurrencyTest: 5 passed, 0 failed" (linux-x64 Native + linux-arm64 Native; these are
  Native-only leaves).
  before-red: NOT FOUND. After-green only.

`09bc25c39b` **Injectable drain policy so the guard-close drain test can't race the 5s deadline.**
  tests: ffi/GuardCoreDrainTimeoutTest
  validated: 32941012574 (green, anchor) -- "GuardCoreDrainTimeoutTest: 4 passed, 0 failed" (linux-x64 JVM,
  linux-arm64 JVM, windows-x64 JVM).
  before-red: NOT FOUND. After-green only.

## kyo-browser

`3cac34b3a1` **Cancel ChromeDownloaderTest on platforms with no Chrome artifact.** RED->GREEN PROVEN.
  tests: internal/ChromeDownloaderTest
  before-red: 32919666641 (red, @22dd3e916 which is the parent lineage WITHOUT this guard --
  `git merge-base --is-ancestor 22dd3e9160 3cac34b3a1` = yes, reverse = no) -- "test-class:
  kyo.internal.ChromeDownloaderTest / (kyo-browserJVM|browserJS|browserWasm / Test / test)
  TestsFailedException: Tests unsuccessful".
  validated: 32941012574 (green, anchor) -- "ChromeDownloaderTest: 0 passed, 0 failed, 30 cancelled"
  on the no-Chrome legs (linux-arm64 JVM/JS/Wasm: the guard fires) and "ChromeDownloaderTest: 30 passed, 0
  failed" where a Chrome artifact exists (linux-x64 JVM/JS/Wasm, windows-x64 JVM/JS).

`ef2ec89d87` **Don't launch Chrome for non-browser test suites.**
  tests: BaseBrowserTest, BaseChromeTest, BrowserTest, BrowserRunSharedJvmTest, internal/{BrowserLauncherTest,BrowserLauncherCleanupJvmTest}
  validated: 32941012574 (green, anchor) -- browser suites correctly cancel without a real Chrome:
  "BrowserLauncherTest: 0 passed, 0 failed, 17 cancelled" (8 legs), "BrowserRunSharedJvmTest: 0 passed, 0
  failed, 3 cancelled" and "BrowserLauncherCleanupJvmTest: 0 passed, 0 failed, 5 cancelled" (3 JVM legs);
  Base*/BrowserTest are base traits and emit no result line (covered by `kyo-browser*/test` in `[testKyo] pass:`).
  before-red: NOT FOUND as a discrete red for these suites; the intent (don't launch Chrome) is confirmed by
  the cancelled counts. After-green only.

The BrowserSettlementTest coupled-timer flake was fixed by FOUR commits in sequence; they share one
RED->GREEN story (before-red on the earlier ones, after-green once all four land):
  before-red: 32898255969 (red, @fc0bf660ff) -- "test-class: kyo.BrowserSettlementTest"; 32904909439 (red,
  @e8e636ea6b) -- "test-class: kyo.BrowserSettlementTest". (Ancestry oldest->newest: fc0bf660ff -> 2b923f8962
  -> e8e636ea6b -> a187397561, all verified ancestors, all ancestors of the anchor.)
  after-green: 32907211760 (green, custom `kyo-browserJVM/testOnly kyo.BrowserSettlementTest`, @a84bedc98,
  which contains all four) -- "BrowserSettlementTest: 49 passed, 0 failed  (45.8s)" -- this is the settlement
  suite actually RUN against a real Chrome (49 leaves), the meaningful green. anchor 32941012574 shows it
  "0 passed, 0 failed, 49 cancelled" (headless matrix, no Chrome).

`e8e636ea6b` **Fix mutationQuiescenceWindow(500ms) coupled-timer race.**
  tests: BrowserSettlementTest -- before-red 32904909439; after-green 32907211760 (49 passed, see above).

`fc0bf660ff` **Space the quiescence-matrix mutations so the read can't race the final one; drop the close guard.**
  tests: BrowserSettlementTest, internal/CdpBackendLifecycleTest
  before-red: 32898255969 (red, @fc0bf660ff) -- BrowserSettlementTest. after-green: 32907211760 (49 passed);
  32941012574 (green, anchor) -- "CdpBackendLifecycleTest: 25 passed, 0 failed" on Chrome-present legs
  (linux-x64 JVM/JS/Wasm, windows-x64 JVM/JS), 25 cancelled on the no-Chrome legs.

`a187397561` **Widen mutationFirstMutationGrace for the wide-quiescence leaf.** (the last of the four.)
  tests: BrowserSettlementTest -- after-green 32907211760 -- "BrowserSettlementTest: 49 passed, 0 failed".

## kyo-system

`22dd3e9160` **PathStat mtime bracket symmetric for the JS cross-clock case.**
  tests: PathStatTest
  validated: 32941012574 (green, anchor) -- "PathStatTest: 5 passed, 0 failed" on all 10 legs, including
  the JS legs the cross-clock case targets (linux-x64 JS, linux-arm64 JS, windows-x64 JS).
  before-red: NOT FOUND (the `failure` run at 22dd3e916, 32919666641, failed on ChromeDownloaderTest, not
  PathStatTest). After-green only.

`7fd1a9c683` **Reset dest mtime on JS copyAttributes=false (windows-Node).**
  tests: PathNodeTest (covers `PathPlatformSpecific.scala`, the only file changed; no test file in the commit)
  validated: 32941012574 (green, anchor) -- "PathNodeTest: 4 passed, 0 failed" on windows-x64 JS (the exact
  target platform), and linux-x64 JS, linux-arm64 JS.
  before-red: NOT FOUND. After-green only.

`dce6fee83e` **[system,doctest] widen forked-process deadlines and the clock-driven corridor.**
  tests: ProcessExitCodeTest, doctest/OrchestratorTest, doctest/internal/RuntimeExecutorTest
  validated: 32941012574 (green, anchor) -- "ProcessExitCodeTest: 36 passed, 0 failed" (10/10 legs),
  "OrchestratorTest: 24 passed, 0 failed" and "RuntimeExecutorTest: 4 passed, 0 failed" (3 JVM legs).
  before-red: NOT FOUND. After-green only.

## kyo-pod

`5b997920e3` **ContainerItTest: assert on a tolerated registry-search failure.** (post-anchor)
  tests: ContainerItTest
  validated: 32969920592 (green, custom `kyo-podJVM/testOnly kyo.ContainerItTest`, @02252b2807) --
  "ContainerItTest: 430 passed, 0 failed  (3m 44s)" and a second batch "430 passed, 0 failed  (5m 19s)".
  anchor 32941012574 also shows "ContainerItTest: 857 passed, 0 failed  (11m 30s)" but that tree predates
  this fix; the 32969920592 run is the at-fix evidence. local: author-reported podman.
  NOTE: post-anchor; no green full matrix contains it.

`674412a028` **Revert the incorrect init-failure assertion in the healthcheck short-circuit leaf.**
  tests: ContainerOrchestrationItTest
  validated: 32941012574 (green, anchor) -- "ContainerOrchestrationItTest: 28 passed, 0 failed" (8 legs).
  also-green: 32877876080 and 32886260453 (custom `kyo-podJVM/testOnly kyo.ContainerOrchestrationItTest`,
  @e2b4ece83 / @c9e0b6344) -- "ContainerOrchestrationItTest: 28 passed, 0 failed".
  before-red: NOT FOUND (this is a test-assertion revert). After-green only.

## kyo-caliban

`6a55024edd` **WS subscription-cleanup leaves assert real cancellation (not a timing proxy).**
  tests: ResolversTest.scala -> class `ResolverTest` (JVM-only, 96 leaves)
  validated: 32941012574 (green, anchor) -- "ResolverTest: 96 passed, 0 failed" (linux-x64 JVM 6.0s,
  linux-arm64 JVM 5.3s, windows-x64 JVM 6.7s). (Distinct from the browser `internal/ResolverTest`, 8 leaves.)
  before-red: NOT FOUND. After-green only.

`e8b8f66588` **Widen the reused-id resubscribe settle to a documented margin.**
  tests: ResolversTest.scala -> class `ResolverTest`
  validated: 32941012574 (green, anchor) -- "ResolverTest: 96 passed, 0 failed" (3 JVM legs).
  before-red: NOT FOUND. After-green only.

## kyo-sql / kyo-ui

`9b420b427e` **[sql-tests] widen cancel/acquire deadlines and poll bounds; label the elapsed deviation.**
  tests: SqlCancellationConformanceTest, mysql/MysqlCancelIntegrationTest, postgres/CancelIntegrationTest
  validated: 32941012574 (green, anchor, 8 non-Native legs) -- "SqlCancellationConformanceTest: 2 passed, 0
  failed", "MysqlCancelIntegrationTest: 4 passed, 0 failed", "CancelIntegrationTest: 4 passed, 0 failed".
  local: author-reported postgres/mysql podman containers; NOT independently re-run here.
  before-red: NOT FOUND. After-green only.

`3f563b46e6` **[sql-postgres,ui] widen fake-server acquire deadline and reactive-DOM poll budgets.**
  tests: SqlClientStreamSlotTest, AnchorTest, ChatScenarioItTest, RealisticInteractionItTest, TodoScenarioItTest
  validated: 32941012574 (green, anchor) -- "SqlClientStreamSlotTest: 6 passed, 0 failed" (8 legs). The UI
  scenario suites are browser-gated and CANCEL in the headless matrix: "AnchorTest: 0 passed, 0 failed, 12
  cancelled", "ChatScenarioItTest: ... 8 cancelled", "RealisticInteractionItTest: ... 52 cancelled",
  "TodoScenarioItTest: ... 12 cancelled" -- so the reactive-DOM poll change is NOT exercised by CI here.
  before-red: NOT FOUND; the UI-poll side is unverified by any green run (cancelled everywhere). Only the
  fake-server SqlClientStreamSlotTest side has a passing line.

## kyo-compiler

`17433d0432` **Virtualize the stuck-op reclaim elapsed check.**
  tests: CompilerPoolTest (JVM-only)
  validated: 32941012574 (green, anchor) -- "CompilerPoolTest: 10 passed, 0 failed" (linux-x64 JVM 30.9s,
  linux-arm64 JVM 30.7s, windows-x64 JVM 32.0s).
  before-red: NOT FOUND. After-green only.

## Cross-cutting test de-flaking

`e2b4ece83d` **[http,jsonrpc,aeron,slack,pod] de-flake real-clock races in app tests.**
  tests: AeronTransportTest, HttpSecurityServerTest, internal/HttpClientBackendStreamingTest,
  JsonRpcHttpTransportTest, JsonRpcHandlerTest, scenario/MaxInFlightTest, ContainerOrchestrationItTest,
  internal/SlackSocketEngineTest
  validated: 32941012574 (green, anchor) -- "AeronTransportTest: 27 passed, 0 failed", "HttpSecurityServerTest:
  21 passed, 0 failed", "HttpClientBackendStreamingTest: 6 passed, 0 failed", "JsonRpcHttpTransportTest: 4
  passed, 0 failed", "JsonRpcHandlerTest: 37 passed, 0 failed", "MaxInFlightTest: 7 passed, 0 failed",
  "SlackSocketEngineTest: 16 passed, 0 failed", "ContainerOrchestrationItTest: 28 passed, 0 failed".
  also-green: 32877876080 (custom, @e2b4ece83, all eight suites in one job) -- every one green with the same
  counts (e.g. "MaxInFlightTest: 7 passed, 0 failed", "SlackSocketEngineTest: 16 passed, 0 failed").
  before-red: NOT FOUND. After-green (anchor + one targeted custom run).

`c9e0b6344b` **[jsonrpc,slack,pod] drop the reasonless in-test hang-guards.**
  tests: JsonRpcHandlerTest, internal/SlackSocketEngineTest, ContainerOrchestrationItTest
  validated: 32941012574 (green, anchor) -- "JsonRpcHandlerTest: 37 passed, 0 failed", "SlackSocketEngineTest:
  16 passed, 0 failed", "ContainerOrchestrationItTest: 28 passed, 0 failed".
  also-green: 32886260453 (custom, @c9e0b6344) -- "TransportHandshakeTimeoutTest: 7 passed", "JsonRpcHandlerTest:
  37 passed", "SlackSocketEngineTest: 16 passed", "ContainerOrchestrationItTest: 28 passed".
  before-red: NOT FOUND (guard-removal). After-green only.

`2b923f8962` **[tests] close time-sweep reconciliation gaps the mining missed.**
  tests: TopicUniformInvariantsTest, PathStatTest, BaseBrowserTest, BrowserCoreTest, BrowserIsolateTest,
  BrowserSettlementTest, ContainerOrchestrationItTest
  validated: 32941012574 (green, anchor) -- "TopicUniformInvariantsTest: 18 passed, 0 failed", "PathStatTest:
  5 passed, 0 failed", "ContainerOrchestrationItTest: 28 passed, 0 failed"; browser suites cancel headless
  ("BrowserCoreTest: 0 passed, 0 failed, 103 cancelled", "BrowserIsolateTest: ... 55 cancelled").
  BrowserSettlementTest is part of the settlement RED->GREEN group above (49 passed at 32907211760).

`bb7a155cc0` **[dev-notes] document rely-on-the-cap, coupled-timer, production-deadline-race rules.**
  tests: none (documentation).
  validated: N/A -- no test, no CI signal to cite. Rode the anchor tree.

## kyo-test framework

`f0d845650e` **Run aroundLeaf outside the per-leaf timeout; pre-launch browser Chrome.** Untimed setup so
browser init doesn't count against the leaf cap.
  tests: BaseBrowserTest (base trait, no result line); source TestRunner.scala
  validated: 32941012574 (green, anchor) -- no dedicated result line (BaseBrowserTest is a trait); covered
  by `kyo-test-runner*/test` in the `[testKyo] pass:` line on all 10 legs and by every browser suite behaving
  as intended (cancelling cleanly / the 49-leaf settlement run). No standalone test asserts this fix; its
  effect is indirect. Weakest test-coverage of the browser-area fixes.
  before-red: NOT FOUND. After-green only.

## CI / workflow

`77d6ba906e` **Drop the app/integration tier from the Native leg.**
  tests: none (CI matrix change).
  validated: 32941012574 (green, anchor) -- both Native legs green with the reduced tier (linux-x64 Native
  build => success, linux-arm64 Native build => success in `gh run view --json jobs`). No per-test line.

`de67657122` **Harden NATIVE_SKIP: drop skipped heavy modules, forward it from build.sh.**
  tests: none (CI script change).
  validated: 32941012574 (green, anchor) -- Native legs green (module-level). No per-test line.

`02252b2807` **Retry a transient dependency-resolution failure in the JVM/JS/Wasm run phase.** (post-anchor)
  Narrow `sbt_run_resolve_retry` gated on `ResolveException` + the transient signature.
  tests: `scripts/ci-test.sh --self-test` mode (in-file self-tests).
  validated: the commit's ci-test.sh self-tests -- `record ok "a run-phase test printing 429 without
  ResolveException is not retried"` (the narrowness guard) plus the retry-and-pass assertion seeded with
  "(Zero / scalaJSLinkerImpl / fullClasspath) sbt.librarymanagement.ResolveException: Error downloading ...".
  CI at this commit: 32969920592 (green, custom pod, @02252b2807), but that exercises pod, not the retry.
  NOTE: post-anchor; no green full matrix contains it.

`42f338202c` **Make workflow dispatch forgiving and observable.**
  tests: none (dispatch/observability).
  validated: NOT FOUND as a test/CI result line -- operational only; evidenced by dispatches at this commit
  succeeding (e.g. 32921117382 custom run @42f338202). Fork-CI tuning.

`f9629e9ba9` **Keep the streak branch on its dispatchable ci.yml.** (post-anchor; FORK-ONLY) After merging
#1910 (which moved dispatch to `ci-dispatch.yml`, unregisterable off the fork default branch -> HTTP 404),
restored the self-contained input-driven `ci.yml`.
  tests: none (workflow files only).
  validated: NOT FOUND as a test line -- verified operationally by every run at HEAD `f9629e9ba` dispatching
  and running (32996901935, 32999007857, 32999010737, 33016937556, 33017274707, 33003083083). Deliberately
  reverts an upstream change on the fork; EXCLUDE from any upstream PR.

## kyo-slack

`9b4475d113` **SlackTest: make the init-handler Web API leaf deterministic.** (post-anchor) INEFFECTIVE for
the windows full-leg hang.
  tests: SlackTest
  validated (isolation only): 33017274707 (green, custom `kyo-slackJVM/testOnly kyo.SlackTest`, default
  driver, @f9629e9ba) -- "SlackTest: 6 passed, 0 failed  (784ms)"; 33016937556 (green, custom, forced
  `-Dkyo.net.backend=nio`, @f9629e9ba) -- "SlackTest: 6 passed, 0 failed  (402ms)".
  still-red where it matters: 33003083083 (red, windows-x64 JVM full leg, @f9629e9ba) -- "test-class:
  kyo.SlackTest / (kyo-slackJVM / Test / test) TestsFailedException" (a ~2 min hang); 32964539057 (red,
  windows-x64 JVM full leg, @02252b280) -- same. The leaf passes in every isolated/targeted run and hangs
  only on the windows-x64 JVM FULL leg. This fix did not close #94; see Pending.

---

## Causation: did any "validated" fix CAUSE a pending failure? No.

- **SlackTest-windows (#94) and tasty-Native (#95) both PASSED in the green full matrix at `3cac34b3a`**
  (32941012574: "SlackTest: 6 passed, 0 failed" on windows-x64 JVM; tasty Native suites green), then
  recurred RED at `02252b280` (32964539057: windows SlackTest hang + `kyo.demos.IdeHoverDemoTest` /
  `kyo.internal.TestClasspathsNativeTest` on linux-x64 Native). `02252b280` adds only a kyo-pod test tweak
  and a ci-script commit -- no slack/tasty/channel code. Same code, green-then-red => pre-existing
  intermittent flakes, not introduced by this branch's work.
- The SlackTest leaf itself is upstream (`25cbc92e12`, #1673); `9b4475d113` only changed its URL, and it
  hung before and after (33003083083, 32964539057) => ineffective, not causal.
- The Channel fixes (434eaa3b0e, a90b90c2e6) post-date the green matrix and touch primitives SlackTest/tasty
  share, but they pass Native in the custom run 32999007857 ("ChannelTest: 127 passed", "QueueTest: 115
  passed") and the Native leg of the rung-4 full run 33003083083 was green => no regression evidence.

## Pending / NOT closed (do not count as green)

- **#94 SlackTest windows full-leg hang -- INTERMITTENT, OPEN.** Green in isolation (33017274707 default,
  33016937556 nio), green in the windows leg of the anchor (32941012574), red in the windows JVM full leg of
  33003083083 and 32964539057. Ruled out (author experiments): nio driver, network stack, leaf-in-isolation.
  It is a windows-full-leg-only stall in the in-memory engine coordination. `9b4475d113` did not fix it.
- **#95 kyo-tasty Native cold-load hang -- INSTRUMENTED, NOT fixed.** `8ea81edc4f` ships the dumper only.
  Green once at 32999010737; red at 32964539057 (`IdeHoverDemoTest`/`TestClasspathsNativeTest`). Still unpinned.
- **#61 io_uring `NoClassDefFoundError` class-load flake (linux-x64 JVM).** Genuine first-load poison of
  `IoUringDriverAcceptTransientErrnoTest$$anon$8` under heavy concurrency; upstream test #1837. RED in the
  current HEAD run 33018143282 (@22e65d09) -- "test-class: kyo.net.internal.posix.IoUringDriverAcceptTransientErrnoTest
  / (kyo-netJVM / Test / test) TestsFailedException". None of this branch's fixes touch it.
- **doctest (linux JVM) -- upstream #1910 breakage, now FIXED by merging origin/main `fe609be3`** (the
  `.doctest-cache` path). It was the JVM failure in 33003083083 ("doctest: validation failed"); at HEAD
  22e65d09 (which merged origin/main) the current run 33018143282 shows NO doctest failure (only #61 io_uring).

---

## PR-readiness

### READY -- rode the clean green full matrix (`<= 3cac34b3a`), each with a concrete anchor log line above
`af2537ef21 e2b4ece83d fb23bd3b79 0e91f5f7f4 eeddad2677 c9e0b6344b 5ab8e18dce dce6fee83e 9b420b427e
e8b8f66588 3f563b46e6 bd68d0e53a 09bc25c39b a84bedc98a fc0bf660ff e8e636ea6b a187397561 22dd3e9160
17433d0432 6a55024edd 2b923f8962 7fd1a9c683 ef2ec89d87 3cac34b3a1 674412a028 f0d845650e` plus `bb7a155cc0`
(doc, no test). `b43e04b826` is superseded by `a84bedc98a` (squash on PR).
- Strongest evidence (before-red RED->GREEN captured): `3cac34b3a1` (ChromeDownloaderTest) and the
  BrowserSettlement group `e8e636ea6b`/`fc0bf660ff`/`2b923f8962`/`a187397561` (49-leaf green at 32907211760).
- Everything else in this list is **after-green only** (anchor, plus a targeted custom run for the net / app
  / core / hang-guard / posix / pod groups). No before-red was captured on this branch, so non-flakiness
  rests on a single green full matrix (32941012574) plus, for some, one targeted custom green. A de-flake
  ideally wants a before-red plus several after-greens; these have the after-green side only.
- Weakest: `3f563b46e6` (UI reactive-DOM poll side never runs -- cancelled in CI; only the SQL fake-server
  side has a green line) and `f0d845650e` (no dedicated assertion; effect is indirect).

### READY pending one clean full run -- post-date the green matrix (validated by targeted runs / local)
- `434eaa3b0e` + `a90b90c2e6` (Channel closeAwaitEmpty / flush): reproduce-first, green in the custom Native
  run 32999007857 (ChannelTest 127 / QueueTest 115) + local. Real core fixes; want them in a green full run.
- `02252b2807` (ci dep-resolution retry): ci-test.sh `--self-test` assertions. Upstream-appropriate resilience.
- `5b997920e3` (kyo-pod tolerated registry-search): green in custom pod run 32969920592 (ContainerItTest 430) + local podman.

### HOLD -- not a shippable fix
- `9b4475d113` (SlackTest leaf): green in isolation, still hangs the windows full leg (#94). Rework or drop.
- `8ea81edc4f` (#95 tasty dumper): diagnostic scaffolding for an unpinned bug; keep on the branch, not a PR "fix".

### EXCLUDE from an upstream PR -- fork-streak operational only
- `f9629e9ba9` (dispatchable ci.yml): deliberately reverts upstream #1910 on the fork; upstream must not take it.
- `42f338202c` (workflow dispatch forgiving/observable), and re-judge `77d6ba906e` / `de67657122` (Native-leg
  trimming / NATIVE_SKIP) per-item before any upstream PR: fork CI tuning, no test evidence.
