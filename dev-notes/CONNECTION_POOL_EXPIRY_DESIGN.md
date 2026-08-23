# ConnectionPool idle-expiry: fix design

Held-out review + design. Analysis only; no code was edited, built, or run for this
document. Every claim below was checked against the source at the cited `file:line`.

Deliverable scope: verify the report, settle mechanism/attribution from the code, and
specify the complete fix (production expiry that is real, not merely check-passing) with
exact numbers, lifecycle, race-safety, cross-platform behavior, the regression test, and
the rejected alternatives.

---

## 1. Verification verdict on the report

The report is substantially correct on the defect and the constraints. Corrections and
sharpenings follow.

### Confirmed

- **The pool never proactively expires idle connections.** `ConnectionPool.scala` contains
  no `Fiber`, `Async`, `Clock`, `Scheduler`, `Thread`, or timer. Eviction is evaluated
  only inside `HostPool.poll` (the `elapsed > idleTimeoutNanos` branch at
  `ConnectionPool.scala:159`) and only when a later request polls that host. `release`
  (`ConnectionPool.scala:47`, `HostPool.release:172`) only sets `timestamps(idx) =
  System.nanoTime()` (`:182`) and publishes; it inspects no timeout and closes nothing.
  Verified: the report's core claim holds exactly.

- **The default client is process-lifetime and never closed.** `HttpClient.defaultClient`
  (`HttpClient.scala:51-56`) is a lazy `val` calling `initUnsafe(transport, 100,
  60.seconds)` (`:55`). Nothing closes it. `HttpClient.local` seeds every companion method
  with it (`:58-59`).

- **`closeFiber` does not close the shared transport.** `HttpClientBackend.closeFiber`
  (`:1294-1309`) marks closing, drains `pool.close()` (`:1301`), and closes every tracked
  connection via `registry.closeAll` (`:1303`); it deliberately leaves the process-shared
  transport open (`:1304-1306`). Confirmed.

- **The leak-check timing tension is real.** `fdDrainBudgetNanos = 30_000_000_000L`
  (`SbtRunner.scala:144`); `awaitFdDrain` (`LeakCheck.scala:256-265`) returns only the
  descriptors that survive the whole budget, and returns immediately on an empty first
  sample (`:258`). Default idle timeout 60s > 30s drain budget, so a reaper firing at 60s
  cannot close a connection pooled seconds before `done()` inside the window. Confirmed.

- **A leaked socket cannot be allowlisted.** `fdLeaks` (`LeakCheck.scala:282-289`) matches
  the allowlist against the descriptor target (`socket:[inode]`), whose inode changes every
  run; `defaultAllowlist = Chunk("processSharedTransport")` (`:42`) matches frames, not
  inodes. The `Detected` message spells this out (`:489-490`). Confirmed.

### Corrected / sharpened

- **The socket category IS checked in the kyo-httpJVM fork.** The report is silent on why a
  socket leak surfaced there; I confirmed it directly. `checkSockets =
  suites.exists(s => s.leakCheck && s.leakCheckSockets)` (`SbtRunner.scala:123`) is
  fork-global: one suite with sockets on enables the probe against every descriptor in the
  fork. No kyo-http test disables sockets (grep of `kyo-http` for `leakCheckSockets`
  returns nothing; `BaseHttpTest.scala` does not override the category). The
  `SbtRunner.scala:119` comment "e.g. BaseHttpTest disables only sockets" is **stale/wrong**
  and should be fixed in passing: `BaseHttpTest` (`kyo-http/shared/src/test/scala/kyo/
  BaseHttpTest.scala:3-18`) only overrides `aroundLeaf` to widen the request timeout; it
  sets no leak-category toggle. This matters: the socket probe is unconditionally live for
  kyo-http, so the default-client strand is caught fork-wide regardless of which leaf owns
  it.

- **Report section 4.6 ("idle connections may carry a parked recv... reusing the existing
  custody/close path") is already satisfied by construction, not a new requirement.** The
  reaper will close via the pool's `discardConn` hook, which for the HTTP client is exactly
  `registry.remove(conn); conn.http1.close(); conn.transport.close()`
  (`HttpClientBackend.scala:1360-1363`) — the identical hook `poll` already invokes to evict
  a stale connection (`ConnectionPool.scala:160`). Any parked-recv teardown that stale
  eviction performs today, the reaper inherits verbatim. This is a correctness *argument*,
  not new work (see §3.5).

- **Report section 5 candidate B (per-connection idle timer) should be rejected outright**,
  not merely "evaluated": it puts one `Clock.sleep` fiber per idle connection on the hot
  `release` path, violating constraint 4.1 (no per-request allocation on the steady path).
  See §6.

- **Attribution (`KYO_TEST_LEAK_DEBUG=1` on x64) is NOT a prerequisite** for this fix, and
  the report's framing of it as one ("Attribution is a prerequisite for a validated fix",
  §1) is too strong. Code analysis settles the mechanism (§2). The deterministic repro is a
  unit test targeting the defect (§5), not the intermittent fork symptom. Attribution stays
  useful as confirmation and is recommended opportunistically, but it does not gate the fix.

### Verdict

Report is a correct problem statement. The defect is real and verified from code; the
timing tension is real; the constraints are right. The two things it under-specifies (which
path trips the leak, and how to make the check deterministic while keeping production 60s)
are resolved below.

---

## 2. Mechanism and attribution conclusion

**The CI leak is path (a): the never-closed process-lifetime default client strands a
keep-alive connection to a still-live server. Path (b) — a close/release race in a scoped
client — is NOT a reachable strand.**

### Why path (b) cannot strand an ESTABLISHED connection

Three independent guards close the scoped-client race; two would suffice.

1. **The registry is authoritative and covers every connection.** Every connection the
   client creates is registered by `trackConn` (`HttpClientBackend.scala:1030-1032`)
   immediately after `connect`, before any send (`poolWithImpl:1215`). Pooled connections
   are a strict subset of registered ones. `closeFiber` calls `registry.closeAll(conn =>
   closeUnsafe(...))` (`:1303`), and `ConnectionRegistry.closeAll`
   (`ConnectionRegistry.scala:51-56`) closes *every* still-registered connection by claiming
   each via `conns.remove`. A connection leaves the registry only through `discardConn`'s
   `registry.remove` (`HttpClientBackend.scala:1361`), which also closes it. So every
   connection is either closed by `closeAll` or already discarded-and-closed. There is no
   escape.

2. **`register` closes a connection created during shutdown.**
   `ConnectionRegistry.register` (`:26-35`) re-reads `closingFlag` after adding and, if
   closing raced in, claims the connection back and closes it (`:32`). `trackConn`'s comment
   documents this (`HttpClientBackend.scala:1026-1029`).

3. **The ring's own close/release linearization is already fixed.** `release` re-reads
   `closed` after publishing (`ConnectionPool.scala:58-63`) and, if close raced in, drains
   and discards the host pool itself; the head CAS makes disposal exactly-once against
   `close()`'s own `drainClaimed` (`:213-237`). The `raceProbe` seam (`:39,50`) exists
   solely to exercise this window in `ConnectionPoolTest`. This guard predates this task and
   is sound.

Guard (1) alone defeats path (b): even if a connection escaped the ring entirely, the
registry would still close it. So a scoped client's `close()` cannot leave an ESTABLISHED
socket behind. Path (b) is not the bug.

### Why path (a) is the bug

The default client never calls `closeFiber`, so `pool.close()` and `registry.closeAll`
never run for it. A connection returned to its pool on the success path — `releasingConn`
wins the CAS and calls `pool.release(key, conn)` for a buffered response
(`HttpClientBackend.scala:1059`) — sits in the ring with a timestamp, registered, and is
closed by nobody. `isAlive` stays `true` (server up, no FIN), so even a hypothetical
liveness sweep would keep it. The `releasingConn` scaladoc names this exact scenario:
"on the process-global default client, which is never closed, that idle connection then
outlives the request with no close ever requested" (`:1041-1043`).

Any test that (i) issues a request through the ambient/default client (a bare
`HttpClient.getJson(...)` etc., not a scoped `HttpClient.init`), (ii) against a server that
stays up, and (iii) does not poll that host again, strands one ESTABLISHED socket for the
fork's life. That is the `[ESTABLISHED local:… remote:…]` finding. The generalization: **any
client whose pool is never closed** (the default client by design; a forgotten
`initUnscoped` client by test bug) strands its idle connections. The fix targets the
general class.

### Attribution

`KYO_TEST_LEAK_DEBUG=1` (serial leaves + `opened by test: <leaf>` stamping,
`LeakCheck.scala:365-378`) would confirm *which* leaf strands the socket, but is **not
needed to land the fix**: the reaper (§3) closes the leak for every unclosed-pool client
regardless of leaf. Recommended as post-fix confirmation that the reproducing leaf uses the
ambient default client (vs. a forgotten `initUnscoped`), which would tell us whether a
test-hygiene follow-up is also warranted. Not a blocker. The deterministic repro is §5.

---

## 3. The chosen fix

Two parts, because production correctness and the deterministic check are genuinely
different concerns and the directive binds both:

- **Part 1 — a pool-owned reaper fiber** that periodically closes idle-expired connections.
  This is the production-correctness fix: the pool now expires idle connections regardless
  of polls or client close, on every platform. It is what the user's directive requires.
- **Part 2 — a configurable default-client idle timeout**, default 60s (production
  unchanged), set to a small value in the test fork's JVM options. This makes the
  fork-level leak check pass *deterministically* without widening the grace and without
  changing production keep-alive behavior.

Both are required. Part 1 alone does not make the 30s check deterministic for a 60s-timeout
connection pooled just before `done()`. Part 2 alone (short prod timeout) would silently
change product behavior. Together they satisfy "expire in production AND pass the check",
which is the binding requirement.

### 3.1 Part 1 — the reaper: carrier and lifecycle

**Carrier.** One recurring fiber per pool, spawned at `ConnectionPool.init`, using
`Clock.repeatAtInterval(interval) { sweep }` (`Clock.scala:579-615`), which returns a
`Fiber[Unit, _]` that can be interrupted. No `Thread.sleep`, no `synchronized`, no blocking
wait — the fiber parks on the ambient `Clock` between sweeps and is off-scheduler while
parked (so it is invisible to the fiber-leak probe, which only catches runnable/spinning
fibers; `LeakCheck.scala:22-24`).

`ConnectionPool.init` (`:106-120`) is `(using AllowUnsafe)`; add `(using Frame)`. Evaluate
the `Clock.repeatAtInterval` computation once at init under `AllowUnsafe` (e.g.
`Sync.Unsafe.evalOrThrow(...)`, the same class of unsafe-spawn the drivers and
`HttpClientBackend` already use — `IoUringDriver.scala:1421` `Scheduler.get.schedule(...)`,
`HttpClientBackend.scala:414` `IOTask(...)`), store the resulting `Fiber.Unsafe` handle in
a pool field, and interrupt it in `close()`.

**Do not spawn a reaper when `idleConnectionTimeout` is infinite.** `elapsed > Infinity` is
never true, so an infinite-timeout pool has nothing to expire. Skipping the spawn keeps
every existing `Duration.Infinity` pool (all of `ConnectionPoolTest.mkPool`,
`ConnectionPoolTest.scala:16-17) unchanged — no new fibers, no behavior delta — and is
semantically correct. Guard: `if idleConnectionTimeoutNanos is finite then spawn`.

**Interval.** `interval = idleConnectionTimeout` (one knob; a connection is closed within
`[t, 2t)` of going idle). Rationale: simplest correct mechanism, one timer per pool, and the
2× worst case is an acceptable, documented property of fixed-interval idle expiry, not a
bug. With the test default timeout at 2s (§3.4), a leaked default-client connection closes
within `[2s, 4s]`, far inside the 30s fd-drain window. With the production 60s, connections
expire within `[60s, 120s]` idle. (A deadline-aware refinement — after each sweep, sleep
until the ring head's timestamp + timeout — tightens expiry to ~`t` but needs a min-deadline
scan across host pools; deferred as an optional enhancement, not required for correctness.)

**Cancellation.** `ConnectionPool.close()` (`:79-88`) interrupts the stored reaper handle
(before or after draining; order does not matter given head-CAS exclusivity). A scoped
client's reaper is thus torn down on `Scope` exit via `closeFiber → pool.close()`. This is
what keeps every scoped/unscoped client's reaper from surviving to `done()`.

### 3.2 Part 1 — the ring sweep (`HostPool.reapExpired`)

Add `HostPool.reapExpired(idleTimeoutNanos, discardConn)` and a `ConnectionPool`-level
`reapExpired()(using AllowUnsafe)` that iterates `pools.forEach((_, hp) =>
hp.reapExpired(...))` when `!closed`.

The Vyukov ring supports head-poll and tail-push only, and entries are FIFO-ordered by
release (tail-CAS order, which tracks release time). Expired entries are therefore the
oldest = nearest `head`. The sweep is a **peek-timestamp-then-claim from head**:

```
reapExpired loop:
  h   = head.get()
  idx = (h % capacity)
  seq = sequences.get(idx)
  if seq < h + 1 then return                 // empty / head slot not yet published
  ts  = timestamps(idx)                       // safe to read: see invariant below
  if (now - ts) <= idleTimeoutNanos then return   // FIFO: newer entries are fresher -> stop
  else if head.compareAndSet(h, h + 1) then   // claim exactly this entry
      val conn = connections(idx)
      connections(idx) = Absent
      sequences.lazySet(idx, h + capacity)    // republish slot as writable, one lap ahead
      conn match { case Present(c) => discardConn(c); case Absent => () }
      loop()
  else loop()                                 // lost the head to a concurrent poll/reaper; retry
```

This mirrors `HostPool.poll` (`:141-169`) and `drainClaimed` (`:213-237`); it reuses the
same head-CAS + `lazySet(idx, h + capacity)` publication.

### 3.3 Part 1 — race-safety argument

**Peek-then-claim is atomic-enough by the ring invariant.** `head` is monotonic: it only
ever advances via `compareAndSet(x, x+1)` and never returns to a prior value. So if
`head.compareAndSet(h, h+1)` succeeds, `head` was continuously `h` from the `seq`/`ts` read
through the CAS. The slot at `idx` cannot have been refilled in that window: a refill needs
a `release` to publish `seq(idx) == currentTail == h + capacity`, but we observed
`seq(idx) == h + 1`, and `tail - head <= capacity` while `head == h`. Therefore the `ts` and
`conn` we read are exactly this entry's, and discarding it (given it was expired at read
time) is correct. If the CAS fails, `head` moved (a concurrent `poll`, another reaper turn,
or `close()`'s drain claimed it) and we retry.

- **No double-use vs `poll`.** `poll` and the reaper both claim the head via the same
  `head.compareAndSet`. Whoever wins removes the entry; the loser's CAS fails and it retries
  with the advanced head. A reaped connection is never handed to `poll`. And the reaper only
  claims entries it will *discard* (expired), never a fresh entry `poll` wants — and even
  the expired prefix is consistent, because `poll` also discards expired-from-head
  (`:159-161`) before returning the first live one.

- **No double-close vs `close()`.** `close()` drains via `drainClaimed`'s head CAS
  (`:213-237`); the reaper uses the same head CAS. A slot is claimed by exactly one of
  `{poll, reaper, close-drain}`, so `discardConn` runs once per connection. `close()` also
  interrupts the reaper. Even a sweep in flight when `close()` runs is safe: head-CAS
  exclusivity holds during the overlap, and `discardConn` (`registry.remove` +
  `http1.close` + `transport.close`) is idempotent (double `remove` is a no-op; the closes
  are idempotent), so a benign overlap cannot corrupt state.

- **No resurrection after `close()`.** The reaper checks `!closed` before iterating and
  iterates existing `pools` entries via `forEach` (never `getPool`, which would re-create a
  `HostPool` after `pools.clear()` at `:87`). A `HostPool` reference captured before
  `clear()` still coordinates its drain with `close()` via head CAS.

### 3.4 Part 2 — configurable default-client idle timeout

Read the default client's idle timeout from a system property at `HttpClient.defaultClient`
construction, defaulting to 60s:

- In `HttpClient.scala:51-56`, replace the literal `60.seconds` with a value parsed from
  `System.getProperty("kyo.http.client.defaultIdleTimeout")` (a `Duration`), defaulting to
  `60.seconds` when unset or unparseable. Only the *default* client reads it;
  `init`/`initUnscoped` keep their explicit `idleConnectionTimeout` parameters unchanged
  (`HttpClient.scala:172-200`). Public signatures do not change.

- In `build.sbt`, add `Test / javaOptions += "-Dkyo.http.client.defaultIdleTimeout=2s"` to
  the shared test settings (alongside the existing `Test / javaOptions` at `:203,210,216`;
  `-Dkyo.*` test properties are already precedented at `:1187,1218`). Every forked test JVM
  then constructs its default client with a 2s idle timeout; production leaves the property
  unset and keeps 60s.

**Why 2s.** With `interval = idleTimeout`, a default-client connection closes within
`[2s, 4s]` of going idle. `done()` observes a quiescent fork; the connection was pooled at
some point before `done()`, so at `done()` it is already ≥0s idle and closes within ≤4s,
well inside the 30s `awaitFdDrain` budget (`SbtRunner.scala:144`). Deterministic pass. Any
value below ~10s keeps `idleTimeout + interval + jitter` comfortably under 30s; 2s is chosen
for margin. It is functionally safe: a default-client request whose next same-host reuse is
>2s later simply reconnects (a new connection), never a failure.

### 3.5 Part 1 — clean close of a pooled keep-alive (constraint 4.6)

The reaper closes through the pool's `discardConn` hook, which for the HTTP client is
`registry.remove(conn); conn.http1.close(); conn.transport.close()`
(`HttpClientBackend.scala:1360-1363`). This is the *same* hook `poll` calls to evict a stale
connection (`ConnectionPool.scala:160`). A pooled keep-alive connection armed to detect
server EOF is torn down identically to how stale-eviction tears it down today: `http1.close`
closes the HTTP/1 connection's channels (cancelling the parked body-channel recv) and
`transport.close` releases the fd. The reaper does not "drop the ref"; it invokes the proven
custody/close path. Correctness is inherited from `poll`'s existing eviction, not newly
introduced. (Verification item, low risk: confirm `Http1ClientConnection.close` cancels the
pooled connection's parked recv — implied, since `poll`'s stale eviction relies on it.)

### 3.6 Part 1 — marking the process-lifetime reaper (constraint 4.3)

The default client's reaper runs for the process lifetime. While parked in `Clock.sleep` it
is off-scheduler and invisible to the fiber-leak probe, and it is not a `Diagnostics`
component so `StrandedOpCheck` never samples it (`StrandedOpCheck.scala:49-53`). So it is
unlikely to trip any end-of-run check unmarked. For determinism (a sweep could momentarily
land inside the 500ms scheduler-idle settle window, `SbtRunner.scala:142`), mark it, exactly
as the drivers mark their process-lifetime carriers:

- Route the process-lifetime reaper's loop body through a wrapper method whose name contains
  the substring `processSharedTransport`, e.g. `def processSharedTransportReapLoop(...) =
  reapLoop(...)`. This puts the marker on the carrier's kyo trace and JVM stack, so
  `LeakCheck`'s existing `defaultAllowlist = Chunk("processSharedTransport")`
  (`LeakCheck.scala:42`) and `StrandedOpCheck`'s reuse of it (`StrandedOpCheck.scala:53`)
  both excuse it — no allowlist change needed. This mirrors `IoUringDriver`'s
  `processSharedTransportCycle` delegating to `runCycle`
  (`IoUringDriver.scala:1529-1536`).

- The wrapper is selected by a `processLifetime: Boolean` captured at construction, decided
  synchronously at spawn time (like the drivers reading `ProcessSharedTransport.isBuilding`
  at `start()`, `IoUringDriver.scala:1421`). A scoped/unscoped client's reaper uses the
  plain `reapLoop` and is **not** marked: it is cancelled on `close()` and, were it ever to
  survive, it *should* trip the check (mirroring the `ProcessSharedTransport` reasoning that
  an owned transport keeps the plain frame, `ProcessSharedTransport.scala:14-16`).

- Prefer an **explicit `processLifetime` flag** threaded through the init chain over reusing
  `ProcessSharedTransport.whileBuilding`. The default client is not built inside
  `whileBuilding` (only `NetPlatform.transport` is, `NetPlatformTransportBase.scala:17-18`),
  and the reaper's carrier runs on a worker thread, not the construction thread, so the
  build-scoped thread-local would not be observed at the right time. An explicit boolean is
  correct and less fragile.

### 3.7 Touched files (every site)

kyo-net:
- `kyo-net/shared/src/main/scala/kyo/net/internal/ConnectionPool.scala`
  - `ConnectionPool` class: new field holding the reaper `Fiber.Unsafe` handle
    (`Maybe`), initialized in `init`.
  - `init` (`:106-120`): add `(using Frame)`; if `idleConnectionTimeout` is finite, spawn
    the reaper (`Clock.repeatAtInterval(interval) { reapExpired() }`, evaluated under
    `AllowUnsafe`), routing through `processSharedTransportReapLoop` when the new
    `processLifetime` flag is set, else `reapLoop`; store the handle.
  - Add `processLifetime: Boolean = false` parameter to `init` (and thread it to the class).
  - New `reapExpired()(using AllowUnsafe)` on `ConnectionPool`: `if !closed then
    pools.forEach((_, hp) => hp.reapExpired(idleConnectionTimeoutNanos, discardConn))`.
  - New `HostPool.reapExpired[C](idleTimeoutNanos, discardConn)` (§3.2).
  - `close()` (`:79-88`): interrupt the stored reaper handle.
- `kyo-net/shared/src/test/scala/kyo/net/internal/ConnectionPoolTest.scala`: regression test
  (§5), plus a `close()`-cancels-reaper test.

kyo-http:
- `kyo-http/shared/src/main/scala/kyo/HttpClient.scala`
  - `defaultClient` (`:51-56`): read `kyo.http.client.defaultIdleTimeout` (default 60s).
  - `initUnsafe` (`:947-954`) and the `defaultClient` call site: pass `processLifetime =
    true` for the default client only; `init`/`initUnscoped` pass `false` (default).
- `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala`
  - `HttpClientBackend.init` (`:1348-1374`): add a `processLifetime` parameter (default
    `false`) and forward it to `ConnectionPool.init` (`:1356`).

kyo-test / build:
- `build.sbt`: `Test / javaOptions += "-Dkyo.http.client.defaultIdleTimeout=2s"` in shared
  test settings (near `:203`).
- `kyo-test/runner/jvm/src/main/scala/kyo/test/runner/internal/SbtRunner.scala:119`: fix the
  stale "BaseHttpTest disables only sockets" comment (drive-by; the claim is false).

No public API signature changes; all init changes are on `private[kyo]` internals or add
defaulted parameters. No MIMA impact.

---

## 4. Cross-platform analysis

`ConnectionPool` is shared kyo-net code; the reaper must work or be a correct no-op on
JVM/JS/Native.

- **JVM / Native (multi-threaded scheduler).** `Clock`, `Async`, `Fiber` are kyo-core and
  present on both. `Clock.repeatAtInterval` parks the reaper between sweeps; sweeps run on
  scheduler workers. The head-CAS race-safety (§3.3) is exactly the concurrency the ring was
  built for.

- **JS/Wasm (single-threaded).** `Clock`/`Async`/`Fiber` exist; the timer fires on the
  event loop and the sweep runs to completion with no concurrent `poll` (single-threaded).
  The `drainClaimed`/`reapExpired` "spin until a mid-publish claim lands" branch is, per the
  existing invariant, never taken single-threaded (`ConnectionPool.scala:210-211`). The
  reaper is **real on JS, not a no-op** — which is what the directive wants (production
  expiry everywhere), even though the leak *check* is JVM-only (`LeakCheck.openFdTargets`
  returns `Absent` off Linux, `LeakCheck.scala:48-65`).

- **The marker mechanism is JVM/Native-relevant only.** `ProcessSharedTransport` is a
  `jvm-native` concern; the JS bootstrap has no `Diagnostics`-registering drivers and no
  marker (`NetPlatformTransport.scala` js-wasm, `:9-16`). A JS process-lifetime reaper needs
  no marking (nothing samples it). The `processSharedTransportReapLoop` wrapper is a plain
  method name and compiles on every platform; it simply has no allowlist consumer on JS.

Conclusion: the mechanism is uniform and correct on all three platforms; only the
end-of-run *check* it satisfies is JVM-specific.

---

## 5. Repro / regression test design

Primary guard targets the **defect** (reaper-driven expiry without a poll), not the
intermittent fork symptom. It fails on today's code (no reaper) and passes after the fix.

**File:** `kyo-net/shared/src/test/scala/kyo/net/internal/ConnectionPoolTest.scala`
(source-prefixed, shared, so it runs JVM/JS/Native). **Base:** `kyo.net.Test` (the file's
existing base, `ConnectionPoolTest.scala:8`).

**Test 1 — "reaper closes an idle connection with no further poll".** Non-blocking: the
`discardConn` hook completes a kyo `Promise`; the test awaits it under a generous timeout.
No `CountDownLatch.await`, no `Thread.sleep`.

```
"reaper" - {
  "closes an idle connection past the timeout without a poll" in run {
    Sync.Unsafe.defer {
      val closed = Promise.Unsafe.init[String, Any]()
      val pool = ConnectionPool.init[NetAddress, String](
        maxConnectionsPerHost = 2,
        idleConnectionTimeout = 50.millis,        // finite -> reaper spawns
        isAlive = _ => true,                       // still-live server: only expiry can close it
        discard = c => closed.completeDiscard(Result.succeed(c))
      )
      pool.release(key1, "conn1")
      // deliberately NO poll: today nothing ever closes conn1
      closed.safe.get                              // completes within ~[50ms,100ms] after the fix
    }.map(c => assert(c == "conn1"))
  }
}
```

Wrap the `.get` in a bounded `Async.timeout` (a few seconds) so today's code fails *fast*
(timeout) rather than hanging. On today's code the promise never completes → the test fails
for the right reason (the connection was never expired). After the fix it completes within
one interval → passes. This directly asserts the concrete behavior (the exact connection is
discarded), per the "assert on concrete values" rule.

**Test 2 — "close() cancels the reaper".** Create a finite-timeout pool, `close()` it, and
assert no further discard fires for a released connection after close (release-after-close
discards immediately via `:48`, so use a fresh connection released *before* close and assert
the reaper does not double-fire, or assert the reaper handle is interrupted). Guards against
a scoped client leaking its reaper fiber.

**Optional leaf-level guard (kyo-http).** A `BaseHttpTest` leaf that starts a server which
stays up, issues one request through the **ambient default client**, and does not poll again
would, pre-fix, strand an ESTABLISHED socket the fork's socket probe catches; post-fix (2s
default timeout + reaper) it drains within the 30s window. This reproduces the *symptom* but
is intermittent and fork-global; keep it secondary to Test 1, which is the deterministic
defect guard. If added, place it in the suite matching the source it exercises (not an
orphan file).

---

## 6. Rejected alternatives

- **B — per-connection idle timer (report §5.B).** One `Clock.sleep(timeout)` fiber per
  `release`, cancelled on `poll`. Rejected: it allocates a fiber on the hot `release` path
  (`ConnectionPool.scala:47`), violating constraint 4.1 (no per-request allocation/
  contention on the steady path), and multiplies fibers by idle-connection count. The
  per-pool reaper is O(1) fibers per pool and touches `release`/`poll` not at all.

- **C — leak-check-side drain of the default client's pool (report §5.C).** Rejected: it
  fixes only the check, not production's never-expiring idle behavior, and directly violates
  the user's binding directive that *the pool* must expire connections. It also could not
  reach the pool cleanly (the default client is an opaque lazy `val`).

- **D-blunt — widen `fdDrainBudgetNanos` past the idle timeout (report §5.D, the "raise the
  grace" reading).** Set the drain budget > 62s so a 60s-reaped connection drains in-window.
  Rejected: (i) it makes every fork that ends holding a fresh default-client connection wait
  ≥60s at `done()` (`awaitFdDrain` only returns early on a clean sample,
  `LeakCheck.scala:258`); (ii) it slows every *genuine* never-closing-descriptor diagnosis
  to ≥60s; (iii) it does not exercise the reaper — it just waits out the timeout. Part 2
  (short test timeout) is deterministic, fast, and actually tests expiry.

- **Reduce the production default idle timeout to <30s for everyone.** Rejected: a silent
  product behavior change (more reconnects for every default-client user) taken only to pass
  a test. Part 2 keeps production at 60s and changes only the test JVM.

- **Test-discipline only — route all test requests through scoped clients / drain the
  default client in the test base.** Rejected as *the* fix (viable only as defense-in-depth):
  it does not exercise the reaper, does not fix production, is fork-global-fragile (one
  non-`BaseHttpTest` suite that uses the ambient client re-opens the hole), and sidesteps
  rather than honors the "pool must expire" directive. The reaper is the fix; disciplined
  scoping is a good habit, not a substitute.

- **No reaper; rely on `pruneClosed`/liveness.** Rejected: the leaked connection is
  ESTABLISHED and `isAlive == true` (server up), so no liveness-based prune would ever close
  it. Only an idle-time-based close does.

---

## 7. Residual open questions (empirical)

1. **Does `Http1ClientConnection.close` cancel a pooled keep-alive's parked recv cleanly?**
   Argued yes by inheritance from `poll`'s existing stale-eviction path (§3.5), which uses
   the identical `discardConn`. Low risk; worth one confirming read of
   `Http1ClientConnection.close` during implementation. Not a design fork.

2. **Exact `AllowUnsafe` spawn incantation for a cancellable recurring fiber from `init`.**
   `Clock.repeatAtInterval` returns `Fiber[Unit, _] < Sync`; the interruptible
   `Fiber.Unsafe` handle must be obtained under `AllowUnsafe` at `init`. The codebase has
   two established unsafe-spawn patterns to follow (`Scheduler.get.schedule(...)`
   `IoUringDriver.scala:1421`; `IOTask(...)` `HttpClientBackend.scala:414`); pinning the
   precise call (`Sync.Unsafe.evalOrThrow` vs. a direct `Fiber.Unsafe` init) is an
   implementation detail to settle against the current `Fiber`/`Clock` API, not a design
   choice. No behavioral fork.

3. **Confirmation-only:** run `KYO_TEST_LEAK_DEBUG=1` on x64 once post-fix to attribute the
   historical leaf and confirm it used the ambient default client (vs. a forgotten
   `initUnscoped`, which would justify a small test-hygiene follow-up). Not a blocker; the
   reaper closes both classes.

None of these changes the design; they are implementation confirmations.
