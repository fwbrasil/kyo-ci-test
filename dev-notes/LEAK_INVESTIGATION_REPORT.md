# kyo-sql-testsJVM `processSharedTransport` fd-leak: investigation report

Branch `kyo-compat-external-bindings`. This documents a flaky io_uring file-descriptor leak that fails
`build (linux-x64, ...) / build (JVM)` in CI, the full investigation to date, one fix that reduced but did
NOT eliminate it, and the open questions I want Fable to reason about.

**Nothing here is fixed. The leak still reproduces.** The single applied code change is commit `c24f3af0ec`
(reduces but does not close the leak).

---

## 1. The symptom (exact)

End-of-run kyo-test leak check fails in the `kyo-sql-testsJVM` forked test JVM:

```
kyo.test.runner.internal.LeakCheck$Detected: kyo-test leak check failed:
  - file-descriptor leak (1): socket:[36248215] [ESTABLISHED local:33704 remote:36293]
=== IoUringDriver@... processSharedTransport ===
closed=false reapExited=false ringExited=false reapCycles=~45000
  pending(1)=[NNN->Read(fd=44,id=188978561876,client) ] inFlight=[h188978561876=1 ]
  closeAfterDrain(0)=[] pendingCloses=0 stalledSends=0
```

Invariant signature, IDENTICAL every reproduction:
- Exactly **one** leaked descriptor: a **client** socket, **ESTABLISHED**, remote = a DB container mapped port.
- The io_uring driver has a **standing armed Read** on it (`inFlight=1`), `fd=44`, handle id in the family
  `18897856187x` (observed 188978561876/77/91 — same family, within ~15 ids, i.e. a near-deterministic
  position in the connection-open order).
- `pendingCloses=0` and `closeAfterDrain=0` => **`closeHandle` was NEVER called for this fd**. The kyo-net/
  kyo-sql layer never asked to close this connection. It is not a driver-drops-the-close bug; the close was
  never requested.
- All test suites PASS (0 failures). The ONLY failure is the leak check. The leaked connection outlives the
  entire run.

## 2. Reproduction (reliable, local)

```
KYO_POD_SOCKET=/run/podman/podman.sock STAGE_BORINGSSL=1 \
  scripts/build.sh --env podman-ci sbt 'kyo-sql-testsJVM/test'
```
- Native arm64 io_uring in a Linux container (podman-ci), real Postgres + MySQL started by the suite via the
  mounted podman socket. Each run ~6.5 min.
- io_uring / Linux only (macOS host = kqueue = never leaks). Reproduces on both linux-x64 (CI) and linux-arm64
  (local), so it is arch-agnostic, io_uring-specific.
- **Rate ~1 in 8** un-instrumented on the fixed code (1 leak / 9 runs and counting). The base (unfixed)
  reproduced on the 1st run. A true clean-vs-fixed rate comparison (control) has not been completed.

## 3. What is established (facts, not guesses)

1. **Separate from the 2m TLS hang.** In CI the `kyo-sql-postgresJVM` job (a different forked JVM) showed every
   TLS test taking ~2m 3s (STUCK at 1m, 2m, then PASS) — but that JVM passed. The leak is in `kyo-sql-testsJVM`
   and reproduces locally with NO 2m hang. Treat them as independent unless proven otherwise.
2. **Not the ConnectionPool release-vs-close race.** PR #1837 (`22164e3008`, in main and in this branch) fixed
   that race in the kyo-net `ConnectionPool` (`release` re-reads `closed` + `drainDiscard`; `close` drains
   head->tail). The kyo-sql pool IS that kyo-net `ConnectionPool` (`SqlConnectionPool.init` at
   `kyo-sql/.../SqlConnectionPool.scala:913`), so #1837 already applies. The leak persists regardless => a
   different mechanism. (The handoff `~/workspace/kyo-sql-leak-handoff.md` says the same: "the leak PERSISTS
   after that fix.")
3. **The discard callback closes correctly.** `SqlConnectionPool.init` passes `discard = conn => conn.closeNow`
   (`:933`), and `Connection.closeNow` is synchronous `Unit` (`kyo-sql/.../db/Connection.scala:201`). So
   `drainDiscard`/`pool.discard` do close. Not the bug.
4. **`Fiber.Unsafe.init` detaches.** The reclaim carrier is spawned via `Fiber.Unsafe.init(supervised)`
   (`SqlConnectionPool.decideExit:691`) which builds `IOTask(Sync.defer(v), Trace.saved(), Context.empty)`
   (`kyo-core/.../Fiber.scala:418`) — `Context.empty`, so it does NOT inherit the interrupted parent's
   interrupt context.
5. **kyo interrupt model** (as I understand it, needs Fable confirmation): interrupts are delivered when a
   fiber is suspended on an async promise (the interrupt completes that promise with a Panic and the success
   continuation is skipped). A **Sync** finalizer registered as the immediate continuation of an async acquire
   runs synchronously before any interrupt can land. An async op (a suspending Log/metric) BETWEEN acquiring a
   resource and registering its finalizer is an interrupt window.
6. **Clean-run reclaim accounting is balanced.** With low-overhead atomic counters over the reclaim lifecycle
   (quarantine / reclaimStart / cancelDone / drainDone / terminal / ensureDec), 3 CLEAN runs all read
   `12/12/12/12/12/12`. i.e. in a run with no leak, exactly 12 connections are quarantined and all 12 reclaim
   fully to their close/pool terminal.
7. **Instrumentation does NOT reliably reproduce.** With counters (3 runs) and with `System.err.println`
   per-stage (4 runs), 0/7 reproduced. At a ~1/8 base rate, `(7/8)^7 ≈ 0.39`, so this is plausibly variance,
   NOT proof that instrumentation masks the race. I earlier over-concluded "instrumentation masks it"; that is
   not established.
8. **KYO_TEST_LEAK_DEBUG attribution is serial-only.** `LeakDebug` forces `LeafPool.globalK=1` (serial) and
   snapshot-diffs per leaf; parallel attribution is not supported. The handoff's "3 serial runs green" is only
   3 samples (`(7/8)^3 ≈ 0.67`), so it does NOT prove serial masks it either.

## 4. The fix I applied (commit c24f3af0ec) — REDUCES BUT DOES NOT ELIMINATE

Hypothesis: an interrupt landing between obtaining a connection and registering its exit finalizer strands it.
The statement acquire paths registered the exit finalizer (`onLease -> decideExit`) AFTER the opening
`Log.debug` and the `recordAcquire`/`recordLeaseAcquired` metrics (all suspending):

- `acquireAndRun` `Present` branch (pooled conn) — `SqlConnectionPool.scala:395`
- `connectAndRun` (new conn) — `:432`
- `acquireScoped` `Absent` branch (stream connect) had a suspending `Log.debug` inside the `resolvingOnce` body
  after `connect`, before `Scope.ensure(decideExit)` — `:818`

The `acquireScoped` acquire-instrument comment (`:791-795`) explicitly names the statement path as "the
reverse ... loses the finalizer" — so `acquireScoped` had been fixed to register-finalizer-first and the
statement/stream-connect paths had not.

Fix: register the finalizer (`onLease` / `Scope.ensure`) FIRST — it runs synchronously on the connection's own
continuation — then run the log/metrics inside its body.

**Result: the leak still reproduces with the byte-identical signature (fd=44, id 18897856187x).** So either
this window is not the (or not the only) leaking path, or there is a second window/path. The rate on the fixed
code is ~1/9 so far, not clearly lower than unfixed.

## 5. Current leading hypothesis (UNconfirmed) — the cancel-request connection

The leaked connection is a `client` with a standing armed **Read**, opened at a near-deterministic position,
never asked to close, in a run where interrupt/cancel tests fire. The Postgres cancel protocol opens a
**separate, fresh connection** to send `CancelRequest`; the server then closes it without a reply.

- `PostgresSqlConnection.cancelInFlight` -> `underlying.cancel(address, tlsMode, tls)`
  (`kyo-sql-postgres/.../PostgresSqlConnection.scala`). This is called from `cancelAndReclaim`
  (`SqlConnectionPool.scala:718`) inside the reclaim carrier.
- Open question: does `underlying.cancel(...)` open a cancel-request connection, arm a read on it (to await the
  server's FIN), and is that connection GUARANTEED to be closed on every edge (success, timeout, interrupt of
  the reclaim carrier)? If the reclaim carrier is interrupted, or `Async.timeoutWithError(cancelTimeout)` fires,
  while the cancel-request connection has a read armed, is that connection's fd closed? A leaked cancel-request
  connection would match the signature exactly (client, armed read, never handed to the pool so never in
  `closeAll`, `closeHandle` never called).

This is the strongest untested lead. `underlying.cancel` (in `PostgresConnection`) has not yet been read.

## 6. Other unexplored paths

- Admin connections in `SqlSharedContainers` (CREATE/DROP DATABASE via `PostgresConnection.connect`), scoped
  per leaf; behavior on leaf interrupt not verified.
- `warmUp` (`SqlConnectionPool.scala:147`) — brackets successes/failures; looks safe but not proven under
  interrupt.
- The reclaim carrier itself: `Async.timeoutWithError(cancelTimeout)(reclaim).handle(Abort.run).map { outcome
  => destroyAndFreeSlot/releaseToPool }`. If the reclaim carrier is interrupted (not by the parent — it is
  detached — but is anything else interrupting it?) between `cancelInFlight` and the terminal `.map`, the
  original leased connection's close is via the terminal only; but clean-run counters show terminal==quarantine,
  so in clean runs every reclaim reaches its terminal.

## 7. Questions for Fable (analysis only — do NOT edit, run, or instrument)

1. Read `PostgresConnection.cancel` / `underlying.cancel(address, tlsMode, tls)` and the MySQL equivalent.
   Does the cancel-request connection arm a read, and is it closed on EVERY edge (success / cancelTimeout /
   interrupt of the detached reclaim carrier)? Is THIS the leaked `client` connection with the standing read?
2. Confirm or correct my kyo interrupt model (section 3.5). Specifically: when an async acquire completes with
   Success and the fiber has a pending interrupt, does the immediate Sync continuation (a `Scope.ensure`
   registration) run before the interrupt is delivered, or can the interrupt preempt it?
3. Given the near-deterministic handle id (`18897856187x`, fd=44), what single connection in the
   kyo-sql-tests run is opened at that position, and through which code path? Is there a passive way to identify
   it (e.g. a lexical `Frame`/`Trace` carried to the io_uring `PosixHandle`, printed by the leak dump) that does
   NOT perturb the teardown race?
4. Is my commit `c24f3af0ec` fix correct-but-insufficient (a real second window remains), or is it addressing a
   window that is not actually on the leaking path? Should it be kept?
5. Propose the concrete root cause and the exact fix, with file:line, that closes the leak on ALL edges without
   weakening any test and without `leakCheckSockets(false)`.

## 8. Key files

- `kyo-sql/shared/src/main/scala/kyo/internal/client/SqlConnectionPool.scala` — pool, lease, acquire, reclaim,
  decideExit, closeAll. (My fix is here.)
- `kyo-sql-postgres/shared/src/main/scala/kyo/internal/postgres/PostgresSqlConnection.scala` — cancelInFlight,
  drainToIdle, close/closeNow.
- `kyo-sql-postgres/shared/src/main/scala/kyo/internal/postgres/PostgresConnection.scala` — the wire
  connection, `cancel`, `connect`, `terminate`, `drainToReadyForQuery`.
- `kyo-net/shared/src/main/scala/kyo/net/internal/posix/IoUringDriver.scala` — the leak dump (`~:1388-1400`),
  `closeHandle` (`:983`), `registerDeferredClose`, `pendingCloses`.
- `kyo-net/shared/src/main/scala/kyo/net/internal/ConnectionPool.scala` — the #1837-fixed ring.
- `kyo-sql-tests/shared/src/test/scala/kyo/postgres/CancelIntegrationTest.scala` — the interrupt/reclaim leaves.
- `~/workspace/kyo-sql-leak-handoff.md` — the prior (stale but useful) diagnosis; its section 4 hypothesis is
  the orphaned `cancelAndReclaim` fiber.
