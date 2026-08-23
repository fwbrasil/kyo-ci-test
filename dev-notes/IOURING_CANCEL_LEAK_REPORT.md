# Run #1 failure analysis + io_uring CancelIntegrationTest fd-leak groundwork

CI run under analysis: **32186725050** (fork `fwbrasil/kyo-ci-test`), head commit **96d2cbdb7c**.
Read logs with `REPO=fwbrasil/kyo-ci-test GH_REPO=fwbrasil/kyo-ci-test scripts/ci-logs.sh run 32186725050 --failures`
and per job with `scripts/ci-logs.sh job <db> --full`.

## 1. The three distinct root causes in run #1

| # | Jobs | Failure | Status |
|---|------|---------|--------|
| A | linux-arm64 JVM | `kyo.SignalTest` leaf `combineLatest interleaved ...` TIMEOUT 2m (hang) | **FIXED** commit `420d93c3f0`, reproduced on arm64 (15/500 old vs 0/500 new), 3x suite green |
| B | linux-x64 JS, linux-x64 Wasm, linux-arm64 JS, linux-arm64 Wasm, windows-x64 JS | `(<mod>JS/Wasm / Test / fastLinkJS) Referring to non-existent method java.lang.Thread.sleep(long,int)` via RunnerTest scalatest `Eventually` | **FIXED** cherry-pick `2d2ecfcc5f`, local JS+Wasm test-link of kyo-core + kyo-test-runner passes |
| C | linux-x64 JVM | `kyo-sql-testsJVM / Test / test` leak-check: leaked ESTABLISHED client socket | **OPEN**, this report |

A and B are committed on the branch (HEAD `2d2ecfcc5f`). C is the remaining blocker.

## 2. Failure C: the exact leak

From `scripts/ci-logs.sh job 95872015108 --full` (linux-x64 JVM), end-of-run leak check:

```
Uncaught exception when running tests: kyo.test.runner.internal.LeakCheck$Detected: kyo-test leak check failed:
  - file-descriptor leak (1): socket:[1414330] [ESTABLISHED local:50654 remote:39179]
  driver diagnostics at probe time:
=== IoUringDriver@1321797995 processSharedTransport ===
closed=false reapExited=false ringExited=false reapCycles=46540
pending(1)=[20331->Read(fd=44,id=188978561856,client,@CancelIntegrationTest.scala:62:100) ]
inFlight=[h188978561856=1 ] closeAfterDrain(0)=[] pendingCloses=0 stalledSends=0
(kyo-sql-testsJVM / Test / test) sbt.TestsFailedException: Tests unsuccessful
```

One leaked resource: a still-ESTABLISHED client socket (fd=44) to the Postgres container, with an
io_uring **Read still in flight** (`inFlight=[h...=1]`), submitted from `CancelIntegrationTest.scala:62`.

io_uring is Linux-only (the JVM backend registry selects io_uring>epoll on Linux, kqueue on macOS,
`IoBackendPlatform`). So this path is **not reachable on the host mac JVM**; reproduction requires a
Linux container with io_uring (see section 6).

## 3. The failing leaf

`kyo-sql-tests/shared/src/test/scala/kyo/postgres/CancelIntegrationTest.scala`, leaf
"interrupting the query's fiber releases the caller" (line 55), the `client.query` is at line 62:

```scala
SqlClient.initWith(url) { client =>
  Latch.initWith(1) { started =>
    Fiber.initUnscoped(
      started.release.andThen(Abort.run[SqlException](client.query(longQuery)))   // line 62, longQuery = SELECT pg_sleep(30)
    ).flatMap { queryFiber =>
      started.await.andThen {
        queryFiber.interrupt.map { interrupted => assert(interrupted) }           // only asserts the caller is released
      }
    }
  }
}
```

By the file's own docstring the leaf pins ONLY "the caller is released"; the wire-cancel reclaim half
is deliberately pinned elsewhere (`SqlConnectionCancelTest`). The query fiber is **unscoped**
(`Fiber.initUnscoped`), so the leaf does not await its unwind before the enclosing `SqlClient.initWith`
scope tears the client down.

## 4. Driver semantics: cancel is not close

`IoUringDriver.cancel(handle)` (line 906) fails every pending promise for the handle so the caller stops
waiting, but **deliberately does NOT remove the pending entries, free buffers, or close the fd** (lines
907-909): the SQEs are still kernel-owned and their CQEs still reap. The socket fd is reclaimed only by
`closeHandle` (line 983), which records the handle in `pendingCloses` and submits the actual close
(which submits the ASYNC_CANCEL for the in-flight Read and closes the fd).

Therefore: `pendingCloses=0` in the dump proves **`closeHandle` was never called** for fd=44, and
`inFlight=1` with `reapCycles=46540` proves **no ASYNC_CANCEL was ever submitted**. The connection's
close path never ran at all. This is not "a close that hung"; it is "a close that was never requested."

## 5. Pool exit/reclaim architecture and where it must have failed

`kyo-sql/shared/src/main/scala/kyo/internal/client/SqlConnectionPool.scala`:

- Every lease resolves through `decideExit` (line 717), fired by a `Scope.ensure` finalizer
  (`resolvingOnce`, line 687), on whichever edge the lease leaves by including interrupt.
- For an interrupted in-flight statement (`error=Present && reclaimable && conn.inFlight`, line 732) it:
  1. `cancelsInFlight.incrementAndGet()` (line 735),
  2. spawns `cancelAndReclaim` on a fresh **unsupervised** carrier `Fiber.Unsafe.init(supervised)`
     (line 745), wrapped in `Sync.ensure` that decrements `cancelsInFlight` on exit (line 739).
- `cancelAndReclaim` (line 770) runs `conn.cancelInFlight` then `conn.drainToIdle`, then
  `releaseToPool` (reusable) or `destroyAndFreeSlot` (line 796). `destroyAndFreeSlot` (817) calls
  `pool.discard(conn)` -> `conn.closeNow` -> driver `closeHandle` (the only path that would set
  `pendingCloses` and cancel the in-flight Read).
- `closeAll` (line 187) is documented to wait on `cancelsInFlight` AND the slot channels (idle
  condition line 251: `cancelsInFlight.get()==0 && all slots at capacity`), precisely so a reclaim
  carrier that outlives the closeAll racing it still runs; and `releaseToPool` (808) destroys instead of
  pooling if `pool.isClosed`.

**The dump is inconsistent with any branch of `decideExit` having run for this connection**: had the
reclaim branch run, an ASYNC_CANCEL would have been submitted (inFlight would drain, reapCycles would
have reaped it) and a close requested (`pendingCloses>0`); had the else branch run, `destroyAndFreeSlot`
-> `closeHandle` would have set `pendingCloses`. Neither is visible. So the connection was **orphaned**:
its `decideExit` finalizer either never fired, or fired but its spawned reclaim carrier never ran to the
point of cancel/close, AND `closeAll` did not cover it.

### Leading hypotheses (to confirm by reproduction + instrumentation)

- **H1 (interrupt vs unscoped-fiber finalizer).** Interrupting the *unscoped* query fiber may not run
  its internal lease `Scope.run` finalizer (`decideExit`) before the enclosing `SqlClient.initWith`
  scope closes and `closeAll` runs; if the `cancelsInFlight` increment has not happened and the slot has
  not yet been handed back at the instant `closeAll` samples its idle condition, `closeAll` could observe
  a false-idle and finish, leaving the still-unwinding fiber's connection orphaned. The LIFO finalizer
  order (increment before slot-return) is what is supposed to prevent this; the interrupt edge may not
  preserve that order the way the typed-abort edge does.
- **H2 (reclaim carrier lost at teardown).** The reclaim runs on `Fiber.Unsafe.init`, an unsupervised
  carrier. If the forked test JVM begins shutting down (whole run over) before that carrier is
  scheduled, and `closeAll`'s wait was already satisfied (H1), the carrier never cancels/closes.
- **H3 (increment/slot-return not both on the interrupt edge).** If, under interrupt, the slot is
  returned by a different mechanism than `decideExit`'s sibling finalizer, `closeAll`'s "slots at
  capacity" can be true while `cancelsInFlight` is still 0.

All three converge on the same fix shape: **the client/pool teardown must not be able to complete while
any leased connection is still unwinding its exit**, i.e. close the false-idle window between "caller
released by interrupt" and "reclaim registered in `cancelsInFlight`". The exact mechanism is chosen after
the reproduction shows which hypothesis holds.

## 6. Reproduction (in progress)

Host mac JVM uses kqueue, so the io_uring path only runs in a Linux container. `scripts/build.sh --env
podman` runs the test JVM in a `--privileged --ulimit memlock=-1:-1` Linux container (so io_uring init +
ring buffer locking work, build.sh line 244) and, with `KYO_POD_SOCKET` set, mounts the host podman
socket so the nested Postgres container starts via docker-out-of-docker (build.sh line 260).

Command:
```
KYO_POD_SOCKET=<host podman socket> \
  scripts/build.sh --env podman --arch arm sbt 'kyo-sql-testsJVM/testOnly kyo.postgres.CancelIntegrationTest'
```

The leak is intermittent (prior runs green; the #20-27 rework validated a clean container leak run), so
reproduction may require looping the suite until the race fires. Once reproduced, add
`KYO_TEST_LEAK_DEBUG=1` (per the leak-check message: runs leaves serially and appends
`opened by test: <leaf>` to each descriptor) and instrument `decideExit` / `closeAll` to confirm which
hypothesis holds before choosing the fix.

## 6b. Reproduction findings (local, arm64 io_uring container)

Env confirmed working: `KYO_POD_SOCKET=/run/user/501/podman/podman.sock scripts/build.sh --env podman
--arch arm sbt 'kyo-sql-testsJVM/testOnly kyo.postgres.<T>'` runs the io_uring path (privileged Linux
container) with nested Postgres over the VM podman socket.

- One faithful `CancelIntegrationTest` run: leaf 55 PASSED (32.3s), no leak. Leak is intermittent.
- A concurrent stress harness (`CancelLeakStressScratchTest`: 20 concurrent × 4 waves of
  open-client / start `pg_sleep(30)` / interrupt / tear down, DEFAULT config, no timeout altered)
  PASSED with NO end-of-run leak, but surfaced two related teardown races:
  - `IllegalStateException: Already closed` at `IoUringDriver.submitConnect` (IoUringDriver.scala:436),
    ~21 times: a queued connect drained after its transport closed. `CQE for unknown key=0` alongside.
  - A mid-run `processSharedTransport` dump showed up to `pending(19)` in-flight ops (reclaim `Read`s +
    `Connect`s) during the storm, ALL of which settled before the end-of-run leak check.

Conclusion: on arm64 with spare cores the transient in-flight ops self-heal (reap carrier drains them)
before the leak check, so the exact end-of-run leak does not land. The CI leak is a RUN-END timing
condition: an interrupt reclaim (or its connection) still in-flight at the moment the GLOBAL
`runEndOfRunChecks` leak check snapshots. Reproducing it needs CI-like load (the 4 vCPU cap) so the
in-flight op is still unsettled at the check, OR a longer full-suite run whose last interrupt-teardown
lands close to the global check. `cancelTimeout=2s` bounds each reclaim, so the window is ~2s per
interrupt; the leak fires only when the run ends inside that window with the connection not yet closed.

Two candidate fix loci (a correctness fix, never a timeout change):
- POOL: `closeAll`'s grace-expiry force-close (SqlConnectionPool.scala:198-206) closes only `idleConns`,
  never the quarantined in-flight-reclaim connection; track quarantined connections and force-close them.
- DRIVER: the `submitConnect` "Already closed" path leaks the just-created socket fd when a queued
  connect is drained after its transport closed; the connect fd must be closed on that failure edge.

## 7. Batch status

Failures A and B are fixed and committed (HEAD `2d2ecfcc5f`). Failure C is the remaining blocker and is
NOT yet fixed. No push until C is fixed and validated in the podman io_uring env, so the next full-matrix
run carries all three fixes at once.

## 8. Post-fix local stress validation (podman-ci, io_uring, 4-vCPU cap)

Ran `kyo-sql-testsJVM/testOnly kyo.postgres.CancelIntegrationTest` x3 under `--env podman-ci
--arch arm` (the CI resource cap: 4 vCPU, 16 GB, CI=true, SBT_TASK_LIMIT=1) with nested Postgres
over the VM podman socket, i.e. the exact job class that failed C in run #1.

Result:
- Leak-check clean on ALL three iterations (`leakLines=0` every time), including the one that
  otherwise flaked. The quarantine put-then-recheck fix (`de59e50f8e`) holds under the CI cap +
  io_uring: no leaked ESTABLISHED client socket, no in-flight Read at the end-of-run snapshot.
- Iterations 1 and 3: 4/4 leaves passed.
- Iteration 2: 2/4 leaves failed, NOT with a leak but with `ContainerHealthCheckException:
  retry schedule exhausted in 2s after 1 attempt(s)`.

### 8a. The iter-2 health-check flake (environmental, tracked, NOT a leak/cancel bug)

Mechanism (Container.scala:2192-2234 driver + ContainerPredef.scala:33-58 `readinessLoop`):
Postgres readiness is `readinessLoop(psql -c "SELECT 1")` whose `check` runs a 120s poll loop
INSIDE the container via a single host `exec`, with `schedule = Schedule.done` (one host attempt;
the in-container loop is the retry). The observed reason is the OUTER driver message
("retry schedule exhausted in <elapsed>") with elapsed=2s and attempts=1 — i.e. `check` raised in
~2s, far below the 120s in-container budget. A budget exhaustion would instead read "readiness
probe did not pass within 120s". So this is the `Result.Failure(cause)` arm (ContainerPredef.scala
:49-52): `container.exec` itself failed fast while the postgres entrypoint was mid-restart (the
postMortem log tail shows "received fast shutdown request / waiting for server to shut down", the
temp-init-server teardown before `exec postgres`). With `Schedule.done`, that transient exec-RPC
failure is terminal: no retry.

Why this is a nested-repro artifact, not a real-CI failure:
- In the podman-ci repro the readiness `exec` crosses test-JVM-container -> VM podman socket ->
  postgres container (docker-out-of-docker); a transient exec hiccup during postgres's legitimate
  init-restart is nested-topology-specific.
- On real CI the test JVM runs directly on the runner with a one-hop exec; run #1's
  kyo-sql-testsJVM leg reached the end-of-run leak check, meaning all CancelIntegrationTest leaves
  ran (health checks passed) on real CI linux-x64.
- The health-check machinery (kyo-pod) is unchanged by this branch's cancel/leak work; the flake
  would reproduce identically on origin/main in the same nested env.

Latent robustness gap worth a follow-up (tracked, NOT fixed mid-streak because it is not an
observed CI failure and a push resets the streak): `readinessLoop`'s `check` treats a transient
`container.exec` Result.Failure as terminal under `Schedule.done`. A transient exec-transport
failure during a legitimate service restart should be retried rather than failing readiness
outright. Fix shape (when taken): either retry the exec on Result.Failure within the check, or give
readinessLoop a small bounded retry schedule for the exec-transport failure arm (distinct from the
in-container poll budget). Run 1's kyo-sql legs (linux-x64 + linux-arm64) are the live arbiter of
whether this touches real CI; if either fails with ContainerHealthCheckException it upgrades from
latent to a streak blocker and is root-fixed immediately.
