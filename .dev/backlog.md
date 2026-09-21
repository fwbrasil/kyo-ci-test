# Backlog: from the live review to three green full CI runs

Rewritten 2026-09-21, evening. This file is the single source of truth for the work. Work goes top to bottom
through section 1, and this file is updated BEFORE the next item starts.

- Isolated worktree: `.claude/worktrees/ci-green-followup2`, branch `ci-green-followup2`.
- Base commit: `84d9274beb`, the tip of the user's kernel branch `worktree-effervescent-painting-backus`. The
  user's worktree is never edited.
- Nothing reaches the user's worktree except through a live review at the end, one Edit at a time.
- **Green count: 0.** No CI run exists on any commit of this branch. The two green runs on the fork
  (`ci-green-r3c`) are on `400e97f64c`, the retired worktree with the reward hacks. They do not count.
- Pushed for review on 2026-09-21 to `fwbrasil/kyo-ci-test`: `ci-green-followup2-base` (the base commit) and
  `ci-green-followup2` at `b34f254640`. Everything committed after that is not pushed. No PR.

Status words: **PROVEN** = a run whose exit code was read, red before and green after. **RAN GREEN** = passed,
never shown to fail without the fix it guards. **NEVER RUN** = not compiled or not executed. **READ** =
established by reading only.

## Rules that bind every item

- Scope is this backlog. Nothing gets added to the work without the user saying so.
- Commit every change as it is made, with its real state in the message. Verify afterwards.
- Never weaken an assertion, delete a test, or add a retry, sentinel, sleep or spin to get green.
- **No hot loops and no sleeps in tests, and no spawn hook.** Never keep one and justify it in a comment.
- **How an interrupt-window test gets fixed (the user's method):** make the window not exist in the production
  code, then test the observable behavior with a barrier (`Latch`, `Promise`, `assertEventually`).
  - Where the code is already in the unsafe tier, spawn with `Fiber.Unsafe.init` in the same block that takes
    ownership of the fiber. It runs with an EMPTY context (no inherited `Local`s), so it is not a drop-in where
    the fiber needs the caller's `Clock` or `Log`.
  - Otherwise make the acquire the bracket's acquire (`Scope.acquireRelease(acquire)(release)`), or use
    `ensureMap` with the recording in one unsafe block.
- **These tests check races, and rounds expose them.** A barrier leaf keeps its original round count through
  kyo-test's `.times(n)`. The timeout applies per repetition and the body is re-evaluated each time.
- Do not change kyo-test. `assertEventually` retrying forever is fine (every leaf has a 120 s timeout).
- Do not fix the swallowed `IOException`s in kyo-net (on `origin/main` since `2391c36594`).
- Breaking API changes are not a concern.
- ONE run at a time: host sbt through the warm server (`scratchpad/host-sbtc.sh <log> '<cmd>'`), always
  launched in the background from the start. A client at 0% CPU with the server at 0% CPU means the server is
  wedged: kill both and relaunch. A container only for what needs Linux (epoll, io_uring, the fd leak check),
  a real database, or Native.
- Undo or change test edits with the Edit tool, one file at a time, so the user can follow. No `git revert`.
- Red proofs use an uncommitted mutant marked `MUTANT-RED-PROOF`, restored before anything else happens.
  Check `git status --short` and `grep -rn MUTANT-RED-PROOF` first after any compaction.
- Never push or touch a PR without the user asking. Commit only as `Flavio Brasil <fwbrasil@gmail.com>`.
- Say "the base commit", never "your tree": the second reads as the user's worktree.

---

## 1. Work order

| # | item | section | state |
|---|---|---|---|
| 1 | Hot loops in tests, 13 sites added by the kernel work | 3 | all 13 removed; verification in progress |
| 2 | Test edits the user decided to keep: run the ones never run | 2 | 4 of 9 never run |
| 3 | The one known red: kyo-http fd leak on Linux | 4.1 | not diagnosed |
| 4 | Listener work on epoll and io_uring, incl. review finding 7 | 4.2 | never run since the review fixes |
| 5 | Defects to fix, D1 to D10 (agreed with the user) | 5 | D1 fix committed, run in flight; the rest not started |
| 6 | Verification rungs 1 to 4 | 7 | rung 1 partly done |

---

## 2. Test edits the user reviewed and decided to keep

These replaced a bounded real-clock wait by an unbounded barrier or `assertEventually`. The user stopped their
rollback on 2026-09-21 ("I actually think these are good changes") and is reviewing them in the pushed diff.

| file | what changed | state |
|---|---|---|
| `kyo-core/.../SyncTest.scala` | two 1 ms sleeps replaced by a promise barrier and a fiber join (a logic rewrite) | RAN GREEN, JVM and JS |
| `kyo-core/.../FiberTest.scala` | 5 s bound removed, stop flag moved into `Sync.ensure` | RAN GREEN, JVM and JS |
| `kyo-core/.../KyoAppTest.scala` | 2 s bound on the finalizer wait removed | RAN GREEN, JVM and JS |
| `kyo-core/.../StreamCoreExtensionsTest.scala` | four 2 s and 3 s bounds removed | RAN GREEN, JVM and JS |
| `kyo-combinators/.../AsyncCombinatorsTest.scala` | 2 s bound removed | RAN GREEN, JVM, 16 passed |
| `kyo-net/.../TransportStartTlsTest.scala` | 1 ms sleep poll replaced by `assertEventually` | RAN GREEN on kqueue/jdk and nio/jdk (25 passed); its epoll, io_uring and BoringSSL legs are cancelled on the host and need the Linux container |
| `kyo-net/js-wasm/.../JsIoDriverUpgradeHandoffDropTest.scala` | `nanoTime` deadline polls replaced, cleanup in `Sync.ensure`, third poll is a direct assert | RAN GREEN, JS, 3 passed |
| `kyo-jsonrpc/.../JsonRpcHandlerTest.scala` | three leaves: bounds removed, each asserts the caller's outcome (a logic rewrite) | RAN GREEN, JVM |
| `kyo-system/.../CommandTest.scala` | see H4 | RAN GREEN, JVM |

---

## 3. Hot loops in tests

The kernel work (everything on the branch that is not on `origin/main`) carried 13 `nanoTime` busy-wait sites
in tests. They arrived in four commits of its resource-safety work, 2026-09-18 to 09-20: `226f5521b0`,
`3d6814ce0a`, `2f68c38c12`, `113b0410dd`. `origin/main` has 3 of its own in `NioIoDriverTest`, which are not
part of this item; the two kyo-doctest matches are fixture programs that burn CPU on purpose.

The pattern was: spin on a flag until another fiber arms, spin on to a staggered offset, interrupt, repeat 40
to 500 rounds. Measured once: `AeronTransportTest`'s leaf PASSED with the bug it names put back (`ensureMap`
replaced by `map` in `Topic.publish`), with the spin and without it. Sampling does not reach a window a few
kernel steps wide. The spawn hook (a `ContextEffect` fork callback that interrupts the spawning fiber) did
reach it, and the user rejected it as a hack; `SpawnHook.scala` is deleted.

No hot loop added by the kernel work remains on this branch. State per site:

| # | site | what was done | state |
|---|---|---|---|
| H1 | `AsyncTest` "an interrupt landing at the timeout's spawn" | leaf DELETED (`df7ff24b24`): both of its forms were rejected. Nothing else is owed: `Async.timeout`'s fork is already the acquire of `Sync.acquireReleaseWith`, so the window is closed by construction, and the barrier leaf "interrupting a timeout interrupts the computation it guards" covers the observable behavior | suite RAN GREEN, 134 |
| H2 | `HubTest` `Hub.use` spawn leaf | production fix (`df7ff24b24`): `Hub.initUnscopedWith` spawns with `Fiber.Unsafe.init` in the block that builds the hub and applies `f`, so the window does not exist. Leaf removed. Behavior change: the publisher no longer inherits `Local`s | RAN GREEN, 38 |
| H3 | `HubTest` listener abandonment | `Hub.listen` already registers the close before the add. Barrier leaf "a listener whose fiber is interrupted is not left in the set", `.times(500)`, all platforms | one round RAN GREEN on the JVM; 500 rounds and JS in flight |
| H4 | `CommandTest` "an interrupt landing during spawn" | a `Command.Unsafe` wrapper requests the interrupt on the forking fiber, then forks (`d0fa1d21b0`, `2861009d7e`). One round. Gate removed | RAN GREEN on the JVM, 51. No red proof, JS never run. It interrupts the current task from test code, which is close to what the user rejected in the spawn hook: raise it at review |
| H5 | `JsonRpcHandlerTest` "closing the endpoint while a request is being dispatched" | one-round barrier leaf for a close after the handler entered (`06ce10b1c7`). The two spawn-hook leaves of the base commit, which place the window itself, are still in the file with their own private hook | RAN GREEN on the JVM, 44. The base commit's hook leaves are the same mechanism the user rejected: raise it at review |
| H6 | `AeronTransportTest` publication hand-off | barrier leaf "a publisher stopped while it holds its publication closes it", `.times(80)` (`22bdfdbe11`). `Topic.publish` closes the window with `ensureMap` | NEVER RUN, in flight |
| H7 | `AeronClientTest` connect stops | 40 rounds, latch-armed, stop requested directly, gate removed (`2861009d7e`). The connect-join window it used to sample has an unfixed bug, held by the base commit's `pendingUntilFixed` leaf | NEVER RUN, in flight |
| H8 | `BrowserLauncherJvmTest` launch stop | production fix (`cd8c87ee12`): the Chrome spawn is the acquire of `Scope.acquireRelease`. Barrier leaf stops the launch once the OS shows its Chrome, `.times(40)`. The base commit's leaf was VACUOUS: its token starts with `--`, `pgrep -f <token>` rejects it as an illegal option and prints nothing, so the count was always zero and the `pkill` cleanup never matched. The new barrier exposed it as a 2 m timeout; fixed with a `--` before the pattern and a fail-closed exit-code check (`f19b7a612f`). Whether an interrupted launch really reaps its Chrome was therefore never tested before | RAN GREEN on the host JVM with the real count: 3 passed, 40 rounds in 3.8 s, no Chrome left behind |
| H9 | `HttpServerTest` bind | `HttpServer.init` already registers its finalizer before the bind. Barrier leaf "a server whose owning fiber is interrupted releases its port", `.times(80)`, all platforms (`2ecaf6a601`) | one round RAN GREEN on the JVM; rounds in flight |
| H10 | `HttpServerTest` client connection | the client already tracks a connection in the step that creates it. Barrier leaf "a request stopped in flight leaves no connection behind once its client closes", `.times(300)` | one round RAN GREEN on the JVM; rounds in flight |
| H11, H12 | `SqlClientInterruptTest`, two leaves | spin removed, stop requested directly, rounds (120 and 200), assertions and bounds unchanged, gate removed (`1b69e878d2`) | NEVER RUN. Needs the real Postgres container on JVM and JS |
| H13 | `FlowEngineLifecycleTest` supervision | production fix (`af574a4edd`): `superviseDetached` recorded the supervision in a `map` after the spawn, a real gap. Now `ensureMap` with the registry update and the completion callback in one unsafe block; the spawn stays `initUnscoped` because renewals run on the `Clock` local. Barrier leaf through the file's `settle` helper, `.times(30)`, all platforms | one round RAN GREEN on the JVM; rounds in flight |

**Host runs of 2026-09-21 evening, which supersede the state column above** (all `SBT_EXIT=0`, rounds included):

| suite | JVM | JS |
|---|---|---|
| `HubTest` (H2, H3, 500 rounds) | 38 passed | 36 passed |
| `AsyncTest` (H1) | 134 passed | not rerun since the leaf was deleted |
| `CommandTest` (H4) | 51 passed | 51 passed |
| `JsonRpcHandlerTest` (H5) | 44 passed | 38 passed |
| `AeronTransportTest` (H6, 80 rounds) | 35 passed | 35 passed |
| `AeronClientTest` (H7, 40 rounds) | 7 passed, 1 pending | 7 passed, 1 pending |
| `BrowserLauncherJvmTest` (H8, 40 rounds) | 3 passed | JVM-only suite |
| `HttpServerTest` (H9 80 rounds, H10 300 rounds) | 320 passed | 319 passed (H10 is JVM and Native only) |
| `FlowEngineLifecycleTest` (H13, 30 rounds) | 6 passed | 6 passed |
| `SqlClientInterruptTest` (H11, H12) | NEVER RUN: needs the Postgres container | NEVER RUN |

Still owed for this section: H11 and H12 on the real Postgres container; every suite above on Linux, Native and
Wasm; no leaf here has a red proof, because the windows they used to sample no longer exist or never did.

Found while setting up the Aeron run, fixed and PROVEN on this machine: `build-aeron.sh` could not recover
from a cached clone the macOS temp reaper had hollowed out (`b34f254640`).

---

## 4. What blocks green

### 4.1 The one known red: `kyo-httpJVM/test` fails the end-of-run leak check (Linux container, `79e8094db0`)

- **Symptom:** `LeakCheck$Detected: file-descriptor leak (1): socket:[292025] [ESTABLISHED local:40378
  remote:35003]`. The process-shared `NioIoDriver` holds that channel with `pendingReads=1`. sbt reported
  "Forked test harness failed: java.io.EOFException", `BUILD_EXIT=1`. No leaf failed; the run-level check did.
- **Known:** a client connection on the NIO transport was still open with a read armed when the run ended.
- **Not known, not to be guessed:** which leaf opened it, what introduced it, whether it reproduces every run.
- **Task:** `kyo-httpJVM/test` in a Linux container with `KYO_TEST_LEAK_DEBUG=1` (the check reads Linux socket
  state) to attribute the descriptor to a leaf. Then a reproducing leaf, root cause, fix. If it does not
  reproduce under leak debug, which serialises leaves, repeat without it and count.
- **2026-09-21 evening, Linux container at `20e363955e`, no leak debug:** `all kyo-httpJVM/test kyo-netJVM/test
  kyo-jsonrpcJVM/test`, `BUILD_EXIT=0`, 328 suites, 0 failed, 0 timed out, no leak detected. The leak DID NOT
  REPRODUCE in this run. Three more `kyo-httpJVM/test` runs in one container followed (`BUILD_EXIT=0`,
  `HttpServerTest` 320 passed each, no leak): 4 clean runs of 4 at HEAD against 1 leak in 1 run at `79e8094db0`.
  The cause was never found, so this is "stopped reproducing", not "fixed". Owed: a longer repeat (10 or more) to
  put a number on it, and if it shows up, the leak-debug run to attribute it. Commits since the red that touch this path: the interrupted-listen fix
  (`85d07419aa`) and the `UdsBackend` change (`032daf2dbb`); neither is known to be the cause.

### 4.2 Listener work on Linux backends

RAN GREEN in the Linux container of 2026-09-21 evening (`20e363955e`, `BUILD_EXIT=0`): full kyo-net and
kyo-jsonrpc JVM. `TransportListenerFdReleaseTest` 9 passed on epoll, nio and io_uring (3 cancelled are the kqueue
legs); `NioIoDriverTest` 54; `JsonRpcTransportUnixTest` 5; `JsonRpcHandlerTest` 44; `TransportStartTlsTest` 61
passed, the edited leaf on epoll and io_uring with both TLS implementations. Before that run, everything after
the review fixes (`72e1124cc2`, `85d07419aa`, `032daf2dbb`) had run on macOS and Node only.

Review finding 7 is still open: the single-shot re-bind in `TransportListenerFdReleaseTest` can go red with no
defect in `released` (inferred, not run): a parallel leaf binding port 0 can be handed the same port, and on
io_uring an in-flight accept SQE holds the kernel socket after the fd number is closed. The leaf passed on
io_uring under parallel leaves in the container run above, once; that is one sample, not a proof.

Windows has no local target. It stays unverified until CI.

---

## 5. Defects to fix (agreed with the user on 2026-09-21: "ok, proceed")

Rule learned here: "it is on `origin/main`" is not a reason to skip, and the `IOException` ruling covers only
the swallowed `IOException`s. Check with the user BEFORE planning to leave anything pending.

| # | defect | kernel work? | state |
|---|---|---|---|
| D1 | **A handed-back value is dropped by `close`.** Reproduction `6832ce40a5` (4 leaves, `putBack` called directly). Fix `029e57275b`: handed-back values live in a `returned` queue, served ahead of parked producers, read first by the zero-capacity readers, collected by `close`, untouched by the closed drains, polled only under the `batchInProgress` claim | yes (`putBack` is from `8ef4868841`) | **PROVEN on the JVM:** `ChannelTest` 142 passed with the fix; with a marked mutant that stops `close` collecting the queue, the 2 close leaves failed on their backlog assertion and the 2 next-taker leaves passed; mutant restored. JS run in flight |
| D2 | **`closeAwaitEmpty` forfeits a handed-back value.** The base commit's scaladoc calls it intended ("the drain settles one element short"). Same data loss as D1: the producer was told the value was accepted. Fix: stay open until the value is consumed. Reproduction first | yes | not started |
| D3 | **`pendingPuts` and `pendingTakes` count entries of interrupted fibers.** `origin/main`'s own `pollNextLive` scaladoc says a dead put "stays in the puts queue"; the counts are plain queue sizes; the contract is "fibers currently waiting". The kernel work's interrupt tests use these counts as "the fiber is parked" barriers. Design agreed: a `deadTakes` counter incremented by `parkedTake`'s interrupt finalizer and decremented when a flush polls past a completed take; the same for puts with `pollNextLive` as the decrement site; nothing on the fast path. Reproduction first: park, interrupt, wait for the fiber to end, assert the count is 0 | on main; affects the kernel work | not started |
| D4 | **`Counter.get` resets on read.** `UnsafeCounter.get = adder.sumThenReset()`, byte-identical on main; documented as deliberate in `kyo-stats-registry/README.md`, and `delta()` is built on it. `SqlCancellationConformanceTest` subtracts a "before" read that is itself a reset, so it goes falsely red whenever earlier leaves left a nonzero count; `CancelIntegrationTest` works only because its target is 1. Fix: non-destructive read, exporter delta on its own path, both tests, README. Proof includes the real Postgres and MySQL container suites | no | not started |
| D5 | **9 `pendingUntilFixed` leaves with one cause: a resource created in one step, its release registered in the next.** Actor `ask` subscribe (1), Aeron connect join (1), CDP (6: the dialog drainer, the background-color, emulated-media and download-policy overrides, the freeze-style and marks injections), the SQL advisory lock (1). Fix with the user's method: register the release before or within the acquiring step, then remove the `pendingUntilFixed` marker | yes | not started |
| D6 | **`JsonRpcHandlerTest` carries the base commit's private spawn hook for 2 leaves.** Close the window in the engine, replace the leaves with barrier leaves, remove the hook | yes | not started |
| D7 | **`CommandTest`: my wrapper interrupts the current fiber from test code.** `Command.spawn` is already bracketed. Replace with a barrier leaf that stops once the process is up, `.times(80)` | mine | DONE (`7d88e088f7`): RAN GREEN, 51 passed on the JVM (80 rounds in 3.4 s) and on JS (7 s). The first JS attempt failed at `fastLinkJS` on a stale incremental product; `kyo-systemJS/clean` fixed it |
| D8 | **`BrowserLauncherJvmTest` "terminateTree..." sleeps 300 ms then asserts.** Count surviving processes instead | yes | DONE (`eab0320667`): RAN GREEN on the JVM, 3 passed; the count must see the tree before `terminateTree` and zero the moment it returns |
| D9 | **3 `nanoTime` loops in `NioIoDriverTest`**, on main since `4b609a3923` (2026-07-31). READ, and they are NOT the defect of section 3: a random jitter of at most 2 µs or 60 µs before one call, sampling the crossing between the test's carrier and the driver's OS selector thread, next to deterministic leaves that pin both orderings. No production window to close and no barrier for the crossing | no | **LEFT AS IS, agreed with the user on 2026-09-21** |
| D10 | **kyo-net prose describes a transport-wide `close()` that does not exist, and a `listeners` set is written and never read.** Identical on main. Correct the prose; if a transport-wide close is genuinely missing, raise it with the user, do not invent one | no | not started |

### Waiting on the user's decision (kernel semantics, not call-site fixes)

Listed to the user on 2026-09-21; no ruling yet. Do not touch without one, and do not drop them either.

- **Value stranded at a join (2 `pendingUntilFixed` leaves):** `Async.uninterruptible` (AsyncTest) and `Scope.run`'s
  drain await (ScopeInterruptTest).
- **Leaves that contradict a recorded decision (2):** "by decision there is no backpressure on abnormal exit"
  (ScopeTest, StreamCoreExtensionsTest). Either the decision stands and the leaves go, or the decision changes.
- **`Scope.run` closes its scope after each shot of a replaying handler (2):** ScopeTest.
- **Other gaps (3):** the effect trace does not carry spawning frames into a child fiber (FiberTest); nested
  `Choice` ordering (ChoiceTest); one in `EvalTest`, not read yet.

Closed earlier: **B5** (`FlowEngineLifecycleTest` vacuous assertion) is superseded by H13.

---

## 6. Done, with proof

- **A1. `ChannelTest` lost values in the full JS run.** Root cause was B1. Full `kyo-coreJS/test` on the host,
  4 rounds, each 1751 tests, 0 failed, 0 timed out, `SBT_EXIT=0`. Full `kyo-coreJVM/test` not yet run.
- **B1. Zero-capacity `Channel` never returned batch elements.** Six leaves timed out before the fix
  (`81a96804ac`), `ChannelTest` 138 passed after it (`020eac3684`).
- **A2. Listener release** (`2a49180e6b`, `79e8094db0`): `Listener.released` on NIO, posix and Node;
  `UdsBackend` awaits it before the unlink. Linux container at `79e8094db0`: release suites green on epoll, nio
  and io_uring, full kyo-net and kyo-jsonrpc JVM green. Adversarial review: SOUND WITH FIXES, 8 findings.
  Findings 1 to 6 fixed in `72e1124cc2`: 1 is PROVEN (red on kqueue and nio, then green); 2, 4 and 5 compile
  and break no suite but have no test of their own; 3 and 6 are a removed racy assertion and a comment.
  Finding 8 fixed in `85d07419aa` and `032daf2dbb`: the leaf timed out on Node before, green on Node and the
  JVM after; `JsonRpcTransportUnixTest` 5 passed on the JVM and 5 on JS. Finding 7 is open (4.2).
- **A3. `HubTest` on JS:** the 500-round spin leaf cost 1m40s per JS job. It is gone (H3).
- **A5. `QueueTest` on JS:** 128 passed in each of the 4 rounds. linux-arm64 waits for CI.
- **B7. `build.sh`** hid a failed coursier download (`017ece3388`, `fee86913c2`). Exercised by every podman
  run since.

Observed, not a failure: two `SignalTest` leaves take 1m08s each on JS on this Mac against the 120 s timeout
(5000 iterations of a 1 ms sleep poll). On CI Linux they take 21 s on Wasm and 6 s on Native.

---

## 7. Verification rungs

| rung | what | state |
|---|---|---|
| 1 | local: full JVM and JS of the whole tree; Native and Wasm for touched modules | kyo-core JS done (4 rounds); the rest not started |
| 2 | one `mode=custom` CI dispatch with every proven suite | not started |
| 3 | CI: Windows x64 and arm64 (JVM, JS); Native and Wasm on both Linux poles | not started |
| 4 | full CI on all four OS poles: one uncounted smoke, then three counted | not started |

Every rung-4 dispatch must pass `oses='linux-x64 linux-arm64 windows-x64 windows-arm64'`; the dispatch default
omits windows-arm64. A red at any rung sends that item back to rung 1 and resets the green count to zero.
CI needs a push, which only the user asks for.

---

## Log

- 2026-09-21 morning: A3 proven; A2 implemented and run in a Linux container; B1 reproduced and fixed; review
  of `Listener.released` returned 8 findings; fixes 1 to 6 committed.
- 2026-09-21 midday: interrupted-listen leak reproduced and fixed at six sites; `UdsBackend` awaits the listen.
- 2026-09-21 afternoon: A1 closed on JS with 4 green rounds. Mutant proof on `AeronTransportTest` showed the
  leaf passes with its bug present, with or without the spin. `build-aeron.sh` fixed along the way. Branch
  pushed to the fork for the user's review.
- 2026-09-21 evening: spawn hook removed at the user's direction; `Hub` fixed with `Fiber.Unsafe.init`; all 13
  hot loops removed; two more production gaps found by reading and fixed (`FlowEngine.superviseDetached`,
  `BrowserLauncher.spawnChrome`); rounds reinstated with `.times`. JVM run of the single-round versions:
  `HubTest` 38, `AsyncTest` 134, `FlowEngineLifecycleTest` 6, `HttpServerTest` 320, `SBT_EXIT=0`.
