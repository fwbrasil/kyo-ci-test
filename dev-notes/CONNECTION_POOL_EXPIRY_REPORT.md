# kyo-net ConnectionPool: idle connections are never proactively expired (fd leak)

## Executive summary

The kyo-net `ConnectionPool` never closes idle connections on its own. Staleness is
evaluated **only inside `poll`**, i.e. only when a later request happens to ask for a
connection to that same host. `release` merely timestamps the connection and drops it
into a ring; nothing else ever inspects the ring. Consequently a keep-alive connection
to a **still-live** server, released into a pool that is never polled again and whose
owning client is never closed, stays `ESTABLISHED` for the life of the process. On CI
this surfaces intermittently as a kyo-test end-of-run **file-descriptor leak** in
`kyo-httpJVM`.

The task: **make the connection pool properly expire (close) idle connections**, so an
idle connection is released within the idle timeout regardless of whether the pool is
ever polled again, without adding hot-path cost, without blocking threads (kyo effect
discipline), and reconciled with the leak-check grace window.

This document is the problem statement + evidence + constraints. The fix design is
delegated to a held-out reviewer.

---

## 1. Symptom (CI)

- Run `32044698869` (full matrix, fork `fwbrasil/kyo-ci-test`), **linux-x64 JVM** leg,
  job `95430054999`. Task `(kyo-httpJVM / Test / test)` failed with
  `kyo.test.runner.internal.LeakCheck$Detected`.
- Every individual suite reported `0 failed`. The failure is the **end-of-fork leak
  check**, not an assertion:
  ```
  [error]   - file-descriptor leak (1): socket:[230618] [ESTABLISHED local:41332 remote:36761]
  ```
- The `=== IoUringDriver@... processSharedTransport === / === PollerIoDriver@... === / === NioIoDriver@... ===`
  lines in the report are `kyo.internal.Diagnostics.dumpAll()` **context** attached to the
  finding (LeakCheck.scala:469), NOT the leaked resource. The leaked resource is the single
  `ESTABLISHED` socket.
- `ESTABLISHED` (not `CLOSE_WAIT`/`FIN_WAIT`) means **neither peer sent a FIN** — the server
  is still alive and the connection is fully open. `local:41332` is the client's ephemeral
  port, `remote:36761` a test server port. So it is a live client→server keep-alive
  connection.

### Frequency / reproduction status

- **Intermittent.** The linux-x64 JVM leg was `success` in the two prior full runs
  (`31992340030`, `31960149769`).
- **Not reproduced locally.** On linux-arm64 (podman): 8+ iterations of the full
  `kyo-httpJVM/test` fork, plus 1 run of only `HttpServerResilienceTest`, all clean, 0 leaks.
  Likely x64-specific timing, or simply rare. The one CI hit was x64.
- A hypothesis that `HttpServerResilienceTest` (which deliberately orphans pooled
  connections under server churn) was the source was **refuted**: running that suite alone
  is clean. It `closeNow`s its servers, so its connections get FIN'd and do not stay
  `ESTABLISHED`. The leaked connection is to a server that stays **up**, so it belongs to a
  test that leaves a server alive AND uses a client whose pool is never drained.

**The specific leaking leaf is not yet attributed.** `KYO_TEST_LEAK_DEBUG=1` makes the runner
run leaves serially and stamp each surviving descriptor with `opened by test: <leaf>`; it
must be run where the leak reproduces (x64). Attribution is a prerequisite for a validated
fix (repro-before-fix), but the *pool defect* below is verified from the code and is the
root regardless of which leaf trips it.

---

## 2. Root cause (verified from code)

File: `kyo-net/shared/src/main/scala/kyo/net/internal/ConnectionPool.scala`.

`ConnectionPool[K, C]` wraps a `ConcurrentHashMap[K, HostPool]`. Each `HostPool` is a
lock-free MPMC ring (Vyukov) of idle connections:

```
final private[internal] class HostPool(capacity: Int):        // line ~131
    private val connections = Array.fill[Maybe[AnyRef]](capacity)(Absent)
    private val timestamps  = new Array[Long](capacity)
    private val sequences   = new AtomicLongArray(...)
    private val head        = new AtomicLong(0)
    private val tail        = new AtomicLong(0)
    private val inFlight     = new AtomicInteger(0)
```

The two relevant operations:

- **`release(key, conn)`** (ConnectionPool.scala:47 → `HostPool.release` :172): drops the
  connection into the ring and records `timestamps(idx) = System.nanoTime()`. **It does not
  inspect the timeout, does not arm any timer, does not close anything.**

- **`poll(key)`** (ConnectionPool.scala:42 → `HostPool.poll` :140): the **only** place the
  idle timeout and liveness are evaluated:
  ```
  val elapsed = System.nanoTime() - ts
  if elapsed > idleTimeoutNanos then discardConn(conn); poll(...)   // stale -> close, retry
  else if !isAlive(conn) then       discardConn(conn); poll(...)   // dead  -> close, retry
  else Present(conn)                                                // live  -> hand out
  ```

There is **no background sweeper**: `ConnectionPool.scala` references no `Scheduler`,
`Fiber`, `Async`, `Clock`, `Thread`, or timer. Eviction is purely lazy-on-`poll`.

### Consequence

`idleConnectionTimeout` is effectively a **"discard-if-stale-on-reuse"** rule, not a
**"close-after-idle"** timer. A connection released to a host pool is closed only if:
1. a later `poll` for that host finds it stale/dead, **or**
2. the whole pool is closed (`ConnectionPool.close()` at :79 drains every host pool and
   returns the connections for the caller to close; `HttpClientBackend.closeFiber` at :1294
   then closes them + `registry.closeAll`).

If neither happens — no further request to that host, and the owning client is never
closed — the connection stays open **indefinitely**, holding its fd. `isAlive` stays `true`
because the server is up and no FIN has arrived, so even a hypothetical liveness sweep would
keep it.

### Why the owning client is never closed

- Scoped clients (`HttpClient.init`, HttpClient.scala:164) close their pool + tracked
  connections on `Scope` exit (`closeFiber`, HttpClientBackend.scala:1294-1309). Note
  `closeFiber` explicitly does **not** close the shared transport (:1304-1306) — it closes
  the pool and connections only, so draining a client's pool is safe w.r.t. the shared
  transport.
- The **process-global default client** (`HttpClient.default`, HttpClient.scala:26:
  `initUnsafe(transport, 100, 60.seconds)`) is a lazy `val`, process-lifetime, and **never
  closed** by design. Anything it pools is reaped by nobody. Any test that issues a request
  through the ambient/default client (rather than a scoped `HttpClient.init`) against a
  server it leaves running will strand that pooled connection.

---

## 3. The design tension the fix must resolve

The naive fix ("add a background sweep that closes connections idle > timeout") does **not**
satisfy the leak check as configured:

- Leak-check fd drain grace = **30s** (`fdDrainBudgetNanos = 30_000_000_000L`,
  `kyo-test/runner/jvm/.../SbtRunner.scala:144`). `awaitFdDrain` (LeakCheck.scala:462)
  re-samples `/proc/self/fd` until the leaked set drains or 30s elapse. It only ever drops
  descriptors that **close during** the window; a descriptor that stays open the whole 30s is
  reported.
- Default idle timeout = **60s**. A connection idle < 60s at check start would still be open
  through the entire 30s grace even with a sweep firing at 60s → still flagged.
- A **socket** cannot be excused by the frame-based allowlist (its inode changes every run;
  LeakCheck.scala `Detected` message). The process-shared transport **carriers** (fibers) are
  allowlisted via the `processSharedTransport` frame, but the pooled connection's **fd** is
  not and cannot be.

So the pool must **close** idle connections (release their fds), not merely mark them
evictable, and it must do so on a timescale/mechanism compatible with both production
keep-alive behavior and the end-of-run check. Simply widening the grace or shortening the
timeout are levers to weigh, not obviously-correct answers.

---

## 4. Constraints on the fix

1. **Hot path is lock-free and Unsafe.** `poll`/`release`/`tryReserve`/`unreserve` run on
   every request and take `AllowUnsafe` with no effect context. The fix must not add
   per-request contention or allocation on the steady path.
2. **No blocking threads.** kyo forbids `Thread.sleep`, `synchronized`, blocking waits.
   Any timed background work must use `Async`/`Fiber`/`Clock` suspension.
3. **The default client's pool is process-lifetime and never closed.** Its idle connections
   must be reaped by the pool itself (or a mechanism the pool owns) — nothing external will.
   Whatever carrier does the reaping, if it lives for the process it should be marked so it
   is not itself a leak (see `ProcessSharedTransport.whileBuilding` /
   `processSharedTransport` allowlist, kyo-net/.../ProcessSharedTransport.scala).
4. **Cross-platform.** kyo-net targets JVM / JS / Native. The mechanism must work (or be a
   correct no-op) on all three; the leak check itself is JVM-only but the pool is shared code.
5. **Race-safety with `poll` and `close`.** A reaper closing an idle connection must not race
   a concurrent `poll` handing the same connection out (double-use), nor a `close()` draining
   the ring (double-close). The existing `poll`/`release`/`close` already coordinate via the
   ring's head/tail CAS + `sequences` publication and the `closed` re-read in `release`
   (:47-63); the fix must fit that model.
6. **Idle connections may carry a parked recv.** A pooled keep-alive connection is typically
   armed to detect server-initiated close (EOF). Closing it must cancel that recv and close
   the fd cleanly, reusing the existing custody/close path (`HttpConnection` close via
   `HttpClientBackend.closeUnsafe`/`discardConn`), not just drop the reference.
7. **Repro-before-fix.** A deterministic reproduction is required: a client with a short idle
   timeout that pools a connection to a live server, then a probe asserting the connection is
   closed after the timeout with no further poll (and/or a kyo-test leaf that leaves such a
   connection and shows the leak check catches it, then passes after the fix). The current
   suite has no such test — the defect went unnoticed because every existing path either
   re-polls, closes the server (FIN), or closes a scoped client.

---

## 5. Candidate directions (for the reviewer to evaluate/decide — not prescriptive)

- **A. Per-pool reaper fiber.** On pool init, spawn one `Async` loop (`Clock.sleep(interval)`
  then sweep every `HostPool`, closing connections with `elapsed > timeout`), cancelled on
  `pool.close()`. For the default client the reaper is process-lifetime and must be
  allowlist-marked. Interval vs the 30s grace / 60s timeout must be chosen so a leaked idle
  connection actually closes within the check window (implies interval + timeout materially
  below 30s for the *test* configuration, or a separate consideration for how the check
  treats pooled sockets).
- **B. Per-connection idle timer.** On `release`, arm a `Clock.sleep(timeout)` that closes the
  connection if still pooled; on `poll`, cancel it. No global sweep; cost is one timer per
  idle connection.
- **C. Leak-check-side drain.** Have the runner drain the process-lifetime default client's
  pool before the fd probe. Narrower (only fixes the check, not production's
  never-expiring-idle behavior), and arguably wrong per the user's directive that *the pool*
  should expire connections.
- **D. Timeout/grace reconciliation.** Independent of A/B: decide the default idle timeout,
  the test-configuration timeout, and whether the leak-check grace should exceed the idle
  timeout so a reaped connection drains within the window.

The user's directive is explicit: **the connection pool must properly expire connections.**
That favors a pool-owned mechanism (A or B) over a check-side patch (C), with D as the
timing reconciliation.

---

## 6. Key file references

- Pool: `kyo-net/shared/src/main/scala/kyo/net/internal/ConnectionPool.scala`
  - `poll` :42 / `HostPool.poll` :140 (only staleness check)
  - `release` :47 / `HostPool.release` :172 (timestamp only)
  - `close` :79 (drain-all, returns conns to caller)
  - `HostPool` fields :131-137; no background eviction anywhere in the file
- Client: `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala`
  - `pool` field :32; `isAlive` :257; `ConnectionPool.init` :1356
  - `closeFiber` :1294-1309 (closes pool + tracked conns, NOT the transport)
- Default client: `kyo-http/shared/src/main/scala/kyo/HttpClient.scala:26`
  (`initUnsafe(transport, 100, 60.seconds)`), never closed (:21)
- Leak check: `kyo-test/runner/jvm/src/main/scala/kyo/test/runner/internal/LeakCheck.scala`
  - fd/socket probe :449-473; `awaitFdDrain` :256/:462; `Detected` message :483
  - grace budget `fdDrainBudgetNanos = 30_000_000_000L` in
    `kyo-test/runner/jvm/src/main/scala/kyo/test/runner/internal/SbtRunner.scala:144`
- Process-shared transport marker/allowlist:
  `kyo-net/shared/src/main/scala/kyo/net/internal/ProcessSharedTransport.scala`;
  `LeakCheck.defaultAllowlist = Chunk("processSharedTransport")` (LeakCheck.scala:42)
