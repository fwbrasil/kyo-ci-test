# Overnight report, 2026-09-22

**State: rung 1 (JVM) green on the host, tree clean at `832371a19d` on `ci-green-followup2`, 94 commits unpushed.
CI count: 0. The push was denied by the harness, so rungs 2 to 4 need you.** JS, Native, Wasm untouched since
your "JVM only" instruction.

## What needs you

1. **Push and dispatch.** `git push fork ci-green-followup2` was refused by the Claude Code permission classifier
   at 05:45 ("Out-of-Place Publication"). Either push it yourself or add a Bash permission rule for
   `git push fork *` and tell me to go on. The dispatch after that is
   `gh workflow run ci-dispatch.yml --repo fwbrasil/kyo-ci-test --ref ci-green-followup2 -f mode=full
   -f targets='JVM JS Native Wasm' -f oses='linux-x64 linux-arm64 windows-x64 windows-arm64'`, one smoke then three
   counted, per the backlog's rung 4; rungs 2 and 3 before it if you want the ladder as written.
2. **One design question, `Scope.run` under a replaying handler.** Details below. The three leaves are pending
   again; nothing else waits on it.

## Defects found and fixed tonight (all on the JVM, red first, then green)

| commit | what | proof |
|---|---|---|
| `5db516142d` | **Hub's publisher died when a listener closed during a publish.** `Abort.recover[Throwable](e => bug(..))` around each listener's put, and `bug` throws. `Listener.close` removes the listener from the set and then closes its channel while the publisher may still hold it in the snapshot for the value in flight, so the put failed `Closed`, the publisher threw, and every later value was silently never delivered. Same code on `origin/main`. Fix: a `Closed` from a listener's put skips that listener | Found by the full kyo-core run: the 500-round interrupted-listener leaf hung intermittently. A scratch probe showed the dead listener closed, the hub empty, a witness registered before it holding both values and the listener after it holding none. Two new `HubTest` leaves (one deterministic: close while the publisher is parked on the full buffer; one racing the close with the put, 200 rounds), red before, `HubTest` 40 passed in 3 of 3 runs after |
| `a422575ac0` | **Scope finalizers ran without the run's context** when the drain was spawned from a bracket release (the kernel evaluates a release on a stack of its own). `Finalizer.init` is now an effect that captures the crossing at its call site and spawns the drain over that snapshot; `run`, `runUnowned` and `Fiber.init` use it, so the abandonment backstop now drains with the run's context too | Caught by the held-out review of the first fix. Two `ScopeTest` "finalizer context" leaves, red ("the finalizer read default"), green after |
| `485ee964c2` | `ZStreamsTest` scalafmt, from the build | |

## The `Scope.run` replay design, built, measured, undone

Your directive was to fix the two pending replay leaves first. I built the release-only close (`2f49e3ad98`): the
`Sync.ensure` release is the only close, the step after the region waits only if the scope closed. The two leaves
went green, kyo-core JVM and JS were green. The whole-tree JVM run then failed three things with one cause:

- `PathTest` "Path.temp auto-deletes on scope close": `Path.run { Scope.run { Path.temp() }; exists }` found the file.
- `StreamSystemExtensionsTest`, two leaves: partial data still in the file after `Scope.run` inside `Path.run`.
- kyo-test's runner hung after every kyo-core suite had reported.

Measured with a scratch probe (deleted), on `Sync.ensure` and on that `Scope.run` alike:

| handler outside, answering a suspension inside | order |
|---|---|
| `Var.run` (`handleLoopState`, resumes in place) | body, release, after |
| single-branch `Choice.run` (`handleCont`) | body, after, release |

`Path.run` resumes once, in tail position, through `handleCont`, so the kernel treats it like `Choice` and defers
the release to the handler's end. A release-only `Scope.run` therefore closes late under every `handleCont` handler
outside it, which breaks "closed when `run` returns". The in-body close is back (`e3b4390300`, main's shape); the
three leaves are `pendingUntilFixed` with that reason. What a real fix needs: at the end of the body, `run` must
know whether the region releases now or is owed to a handler below that may resume again; the kernel knows
(`Stack.owesAny`), nothing exposes it, and `handleCont` cannot tell `Path.run` from `Choice.run`. Options in
`.dev/scope-run-replay.md`: expose that fact to `run`; a generation finalizer that closes per body end and reopens
on re-entry; single-shot handlers resuming through an in-place API. **Your call; I did not pick one at 3am.**

## Rung 1, JVM, host, at `e3b4390300` or later

| run | result |
|---|---|
| `kyo-coreJVM/test` | 43 suites, 1905 passed, 0 failed, 22 pending |
| whole tree, kyo-test modules | every module green except kyo-ai integration suites: 401 "API key is invalid" from a stale `ANTHROPIC_API_KEY` in this shell (with no key they cancel). Under whole-tree load Chrome timed out launching in kyo-browser and one kyo-ui leaf saw a stale page; both modules alone below |
| ScalaTest modules (kernel, scheduler, config, stats-registry, finagle, pekko, zio, reactive-streams) | all `failed 0`; kernel 1814 passed, 5 cancelled |
| SQL group: kyo-sql-tests, sql-dolt, system-doltfs, sql-sqlite, sql-doltlite (real Postgres, MySQL, Dolt through podman; SQLite and DoltLite staged on the host) | 95 suites, 1246 passed, 0 failed, 1 cancelled |
| kyo-browser and kyo-ui alone | 187 suites, 3843 passed, 0 failed |
| `kyo-coreJS/test` (before your JVM-only instruction, on `2f49e3ad98`) | 38 suites, 1757 passed, 0 failed, 6 pending |

Two whole-tree `kyoJVM/test` runs stalled at random forked test JVMs: Metals (VS Code, worktree
`snappy-baking-shamir`) listens on `localhost:<ephemeral port>`, sbt's fork server binds `*:<same port>`, and macOS
hands the fork's connection to Metals. I did not kill your editor's process; per-module runs cover the tree. It
will bite any long host run while that Metals is up.

## Backlog state

- Pending leaves: 3 replay leaves (above), the backpressure-on-failure pair (your ruling), the 3 `closeAwaitEmpty`
  hand-back leaves (your ruling), the SQL advisory-lock leaf (off when #1982 reaches this branch), the
  `Fiber.Unsafe.init` trace diagnostic.
- `.dev/backlog.md` section 7 has the rung table; `.dev/scope-run-replay.md` has the measured record.

## Held-out review

A read-only Opus review of the first `Scope.run` commit found the context regression (fixed above), the scaladoc
gap, and `runUnowned` carrying the same shape. Its second pass found no defect in what remains on the tree:
`Finalizer.init`'s snapshot is a by-value copy with no pointer into the pooled stack, is only read when reused
across a parent's second `close`, and the spawn is `Fiber.initUnscoped`'s call with the same defaults; the child's
drain runs under the child's context even when a parent's drain spawns it. Its other items (a leaf for a finalizer
registered before the choice point, the error finalizers receive under replay) belong to the replay design, which
is the open question above. Its verdict on the final tree: PASS-WITH-NITS; the one should-fix (the public
`Finalizer.Unsafe.init` gained `spawn` with no scaladoc stating that it must run the drain on its own fiber) is
fixed in `cb9f0a0369`. Left as noted: `Fiber.init` now takes two context snapshots per spawn (one for the
finalizer, one for the fiber); one capture could feed both.
