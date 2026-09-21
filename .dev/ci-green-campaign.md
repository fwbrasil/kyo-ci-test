# CI green campaign: READ THIS FIRST AFTER ANY COMPACTION

Goal: three consecutive fully green FULL CI runs on `worktree-effervescent-painting-backus`.

**Green count: 0.** No full CI run exists on any post-review commit. Current rung: 1 (local).

Last rewritten: 2026-09-21, afternoon. If anything below contradicts another document, this file and
`backlog.md` win. Every other campaign note is renamed `STALE-*` and carries a warning; do not act on them.

---

## 1. Check the working tree before anything else

Red-proof runs use deliberate mutants, UNCOMMITTED and marked `MUTANT-RED-PROOF` in a comment:

- `kyo-core/shared/src/main/scala/kyo/Channel.scala`: `readParked` put back to the pre-fix reader behaviour.
- `kyo-core/shared/src/main/scala/kyo/Async.scala`: `Async.timeout`'s bracket replaced by a plain `map`.

Run `git status --short` and `grep -rn MUTANT-RED-PROOF kyo-core`. If either file is modified, restore it
with the Edit tool to match `HEAD`, confirm `git diff` is empty, and only then continue. **Never commit a
mutant.** (`git checkout <rev> -- <file>` is denied by the permission classifier here; use Edit.)

## 2. Where things are

| what | where |
|---|---|
| the user's tree, pristine, never edit | `.claude/worktrees/effervescent-painting-backus`, branch `worktree-effervescent-painting-backus`, `84d9274beb` |
| the ACTIVE isolated worktree, all work happens here | `.claude/worktrees/ci-green-followup2`, branch `ci-green-followup2` |
| the task list with each item's real state | `.dev/backlog.md` here |
| retired worktree, contains four reward hacks | `.claude/worktrees/ci-green-campaign` (`400e97f64c`); its notes are `STALE-*` |

How the work list was derived: the retired worktree changed 51 files over base `c66cb003f6`. Against the
user's tree, 13 are identical, 20 were changed during the live review, 18 (the clock sweep) were never
ported. `backlog.md` section A is that remainder; section B is what was found along the way.

Nothing reaches the user's tree except through a live review at the end: edits applied ONE AT A TIME with the
Edit tool while the user watches. Never patches, never bulk application.

## 3. Rulings from the user that bind everything

- **Never reward-hack.** The signature: the invariant did not hold, so the observation was made looser (a
  sentinel, a widened assertion, a bounded retry, a spin, a loosened scaladoc). A red test exposing a real
  bug is the deliverable.
- **Never present a contract-loosening option as a "question" or "open question".** Decide the correct fix
  and do it. Ask only when something is genuinely value-underdetermined.
- **Do not change kyo-test.** `assertEventually` retrying forever is fine; every leaf has a default timeout.
- **Do not fix the swallowed `IOException`s in kyo-net** that are on `origin/main` (since `2391c36594`). New
  code of mine logs instead of swallowing.
- **Breaking API changes are not a concern.**
- **ONE run at a time, total.** Never a host sbt and a container together, never two containers. Before
  launching anything, confirm nothing of mine is running (`podman ps`, and `pgrep -fl sbt-launch` for a
  process in this worktree).
- **Containers only when necessary** (they are slow): Linux-only backends (epoll, io_uring), the Linux-only
  fd leak check, Native. Host sbt for everything else. JS is single-threaded, so a CPU cap never justifies a
  container for a JS run. A host run reads the live tree, so do not edit the modules it compiles while it
  runs.
- **No real clock in tests** where a barrier exists: `Latch`, `Channel`, `Fiber.get`, `assertEventually`,
  `Clock.withTimeControl`. Never keep a sleep and justify it in a comment.
- Commit as you go with the real state in the message. Never push. Never touch a PR. Commit only as
  `Flavio Brasil <fwbrasil@gmail.com>` (`git config user.email` first).
- Fable subagents are analysis only. Brief them to REFUTE.

## 4. What is proven, as of this rewrite (exit codes read)

- `HubTest` on JS with the spin leaf gated to the JVM: 35 passed in 6.7 s (`BUILD_EXIT=0`); CI had it at 1m40s.
- `Listener.released` at `79e8094db0`, Linux container, JVM: `TransportListenerFdReleaseTest` passed on epoll,
  nio and io_uring; `NioIoDriverTest` 54 passed; `JsonRpcTransportUnixTest` 5 passed; full `kyo-netJVM/test`
  and `kyo-jsonrpcJVM/test` green.
- Review fix 1 (uninterruptible release promise): reproduction red on the host for kqueue and nio
  (`SBT_EXIT=1`, "an interrupted awaiter settled released before the listener was even closed"), then green
  at `72e1124cc2` (`SBT_EXIT=0`, 4 passed, `NioIoDriverTest` 54 passed). Not yet run on epoll or io_uring.

- Zero-capacity `Channel` batches (B1): leaves timed out on the host with the pre-fix reader mutant, then
  `ChannelTest` 138 passed with the fix (`020eac3684`).
- `AsyncTest` spawn-hook leaf (A4): timed out with `Async.timeout`'s bracket mutated to `map`, passes on the
  real code. `SpawnHook` lives in `kyo-core/shared/src/test/scala/kyo/SpawnHook.scala`.
- `Hub` publisher handover (NEW production fix, `318fc36bb9`): leaf red with `map` ("the stop got between the
  publisher's spawn and the hub's handover"), green with `ensureMap`, filtered run on the warm server.
- kyo-core sweep edits on the host JVM: `SyncTest` 47, `FiberTest` 122, `KyoAppTest` 15,
  `StreamCoreExtensionsTest` 190, `AsyncTest` 135, all passed.
- Full `kyo-coreJS/test` on the host, round 1 (A1, A5): 1751 tests, 0 failed, 0 timed out, `SBT_EXIT=0`.
- Interrupted listen leak (`85d07419aa`): leaf timed out on Node before the fix, then green on Node and JVM
  (`SBT_EXIT=0`, 6 passed each, `NioIoDriverTest` 54 passed).

Still UNVERIFIED: `UdsBackend`'s await-the-listen change (`032daf2dbb`, run in flight), B5 (flow leaf),
`AsyncCombinatorsTest`'s sweep edit, the review fixes 2, 4, 5 of `72e1124cc2` beyond compiling and not
breaking the suites above, and everything on epoll, io_uring, Native, Wasm and Windows since those fixes.

How to run fast: `scratchpad/host-sbtc.sh <log> '<sbt command>'` uses the warm sbt server. Filter leaves
with `-- --filter=**text**` (a single `*` does not cross the path separator, and a filter that matches
nothing exits 0 with zero tests: check the count).

## 5. In flight when this was written

Nothing (updated late afternoon, 2026-09-21). No run is in flight and no mutant is in the tree. The queue is
`backlog.md` section 1.

Scratchpad: `/private/tmp/claude-501/-Users-fwbrasil-workspace-kyo--claude-worktrees-effervescent-painting-backus/2b86dbfc-d1cf-4e5c-8e87-7977b4863423/scratchpad`.
It is ephemeral; nothing there is the only copy of anything that matters.

## 6. Next, in order

**`backlog.md` section 1 is the work order. Nothing else is.** Sections 4 and 5 above are a morning snapshot
and are superseded by `backlog.md` sections 3, 4 and 6 wherever they differ.

The clock sweep (old item A4) is DROPPED by the user: it rewrote passing tests, mostly without proof, and made
several weaker. Do not resume it. What replaced it: roll back nine test files with the Edit tool (backlog
section 2), and treat the 13 hot loops the kernel branch added as their own defect (backlog section 3).

The user's standing complaint, earned: leaving the backlog order, expanding scope, and keeping a sleep or a
spin with a justifying comment. Work from `backlog.md` top to bottom and update it before each next item.

## 7. Local run rules learned the hard way

- kyo-net and kyo-sql runs in a container go through `scripts/build.sh` with `STAGE_BORINGSSL=1
  STAGE_SQLITE=1 STAGE_DOLTLITE=1` as needed; a missing flag looks exactly like broken code.
- Read `BUILD_EXIT` / `SBT_EXIT`, never a green-looking tail. kyo-test self-tests print intentional
  `*** FAILED ***` lines.
- A cold container costs 20 to 30 minutes before the first test. Several at once, next to other sessions'
  host sbt, made everything slower.
- If `podman ps` refuses connections while the machine says "running": `podman machine stop`, then `start`.
- The session hook refuses `git -C <other worktree>` and compound commands it cannot verify; keep git
  commands plain and run them from this worktree.
