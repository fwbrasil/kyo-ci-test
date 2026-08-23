# Held-out review: ConnectionPool idle-expiry reaper + configurable default-client idle timeout

Scope reviewed: `git diff f1db832de3..HEAD` (two commits: `93287ae8` kyo-net reaper,
`31a07dc6` kyo-http configurable idle timeout). Judged independently against the source,
not against the design/report docs (those were cross-checked, not trusted).

Files read in full: `ConnectionPool.scala`, `ConnectionPoolTest.scala`,
`ConnectionPoolConcurrencyTest.scala`, `HttpClient.scala`, `defaultIdleTimeout.scala`,
`HttpClientBackend.scala`, `SqlConnectionPool.scala`, `ConnectionRegistry.scala`,
`StaticFlag.scala`, `Clock.repeatWithDelay`, `LeakCheck.scala`, `build.sbt`.

## Verdict

**SHIP** (no blockers). The core is correct: the reaper's ring interaction is race-free
by the same head-CAS arbitration the existing `poll`/`drainClaimed` protocol relies on, the
age-ordered-prefix scan is sound, the lifecycle (spawn in `init`, interrupt in `close`) is
right, and the SQL blast radius is contained because the reaper does exactly what
poll-eviction already does on idle ring slots only.

One item is a **must-verify** rather than a code defect: the 2s default-client idle timeout
is applied to *every* module's test fork (`kyo-settings`, not kyo-http-only), so the full
test matrix must be green with it, not just `kyo-httpJVM`. The remaining findings are nits
(one comment overclaims a monotonicity property; a couple of coverage gaps; a timing-based
regression test).

---

## 1. Race-safety of `HostPool.sweepExpired` (ConnectionPool.scala:226-253) — SOUND

**Happens-before of the pre-CAS timestamp read.** The sweep reads `seq = sequences.get(idx)`
(an `AtomicLongArray.get`, i.e. a volatile/acquire load) and only reads `timestamps(idx)`
after confirming `seq >= currentHead + 1`. `release` writes `connections(idx)` and
`timestamps(idx)` as plain array stores, then `sequences.lazySet(idx, currentTail + 1)`
(a release store). Observing the published sequence via the acquire load establishes the
release/acquire edge, so the plain timestamp write is visible. This is byte-for-byte the
same ordering `poll` already relies on (`:190-200`: read seq, then read `timestamps(idx)`).
Sound.

**No double hand-out vs. concurrent `poll`.** Both claim the head slot with
`head.compareAndSet(currentHead, currentHead+1)`. `head` is a monotonically increasing
counter claimed one step at a time, so there is no ABA: once head passes `currentHead` the
CAS can never succeed again. Whichever of poll/sweep wins the CAS owns the slot; the loser
re-reads (poll recurses, sweep `else loop()`). A user therefore never receives a connection
the reaper closed, and the reaper never closes one a poll handed out. Correct.

**No double-close vs. `close()`/`drainClaimed`.** `drainClaimed` (:297-321) and `sweepExpired`
both claim via the same head CAS, so each ring slot is claimed by exactly one. A connection
drained by `close()` goes to the builder; one claimed by the reaper is closed via `discardConn`.
Never both.

**Cannot close a `release` mid-publish.** A releaser CASes `tail` to `T+1` before it stores
the connection/timestamp and publishes `seq[idx']=T+1`. `tail.get()` in the sweep therefore
already sees `T+1`, but the sweep only reaches physical slot `idx'` at `currentHead == T`
(head advances monotonically and `tail-head <= capacity` rules out an earlier lap colliding
on the same physical index — `H ≡ T mod capacity` with `H < T` needs `H = T-capacity < head`).
At `currentHead == T` the un-published slot reads `seq < currentHead+1`, so the sweep STOPS.
It never claims the mid-publish slot. Verified against the ring geometry.

**`close()` racing a running sweep.** `close()` interrupts the reaper asynchronously, then
drains. Because `sweepExpiredHosts` runs inside a single non-suspending `Sync.Unsafe.defer`,
an in-flight sweep completes rather than observing the interrupt mid-loop; at most one further
sweep can run before the next `Clock.sleep` delivers the interrupt, and after `pools.clear()`
that sweep's `pools.forEach` is a no-op. Any overlap with `drainClaimed` is still exactly-once
by the head CAS. Safe.

## 2. Age-ordered-prefix assumption (ConnectionPool.scala:234-235) — SOUND, with one comment overclaim

The seq gate guarantees the sweep never reads a stale timestamp from a previous lap: for
physical slot `idx` at `currentHead = idx + k·capacity`, readable requires `seq[idx] =
currentHead+1`, which only the current-lap releaser sets; a prior lap's poll left
`seq[idx] = currentHead` (writable), which trips the mid-publish STOP, not a stale read.
Capacity wrap is handled correctly. No over-close of a fresh slot: the CAS validating
`head == currentHead` proves the slot was not polled+refilled since (a refill needs a poll
that advances head), so `timestamps(idx)` still belongs to the occupant whose seq was
observed. All correct.

**Nit (comment accuracy, ConnectionPool.scala:215-217, 234-235).** The claim "the ring is
ordered by idle age ... everything behind it is fresher" is not *strictly* true under
concurrent multi-producer `release`. Two releasers can capture `nanoTime()` (read after their
respective `tail` CAS, at `release`:266) in an order inverted from their slot order, so a
head-ward slot `T` can carry a larger timestamp than slot `T+1`. Consequence: the sweep can
STOP at a fresh `T` and leave a staler `T+1` behind. This is bounded and self-correcting: `T`
is reaped once `now - ts_T > timeout` (or is polled), which advances head to `T+1`, reaped
next; the extra time `T+1` survives beyond its own deadline equals the inversion gap
`ts_T - ts_{T+1}` — the scheduling skew between the two releases (µs–ms normally; a longer
producer stall keeps the slot in the mid-publish STOP state, so it can't be skipped). No
leak, no unbounded strand. Worth softening the comment to "approximately age-ordered; a
strictly-stale connection is closed within one additional sweep" rather than asserting strict
monotonicity. Severity: nit.

## 3. `now` captured once per sweep (ConnectionPool.scala:129) — SOUND

`now` is captured once and applied across all host pools. A connection released during the
sweep has `ts > now`, so `now - ts < 0 <= idleTimeoutNanos` → treated fresh, never closed.
Later-swept pools use a marginally stale `now`, which only makes expiry slightly *less*
aggressive (caught next sweep). `now - timestamps(idx)` is the canonical `System.nanoTime()`
subtraction that is wraparound-safe for any interval under ~292 years. `timestamps(idx)`'s
default `0` is never read for an occupied logical slot because the seq gate precedes the read.
No correctness or overflow issue.

## 4. Reaper lifecycle (ConnectionPool.scala:116-124, 88-101) — SOUND

`Clock.repeatWithDelay(interval, interval)(...)` is built on `Fiber.initUnscoped`
(`Clock.scala:554`), so the reaper is a genuine pool-lifetime background fiber not tied to any
Scope — correct for a pool that must reap independently of the caller's scope. It parks on the
ambient live `Clock` between sweeps (no thread blocking, one fiber per pool). Evaluating the
`Fiber < Sync` synchronously in `init` via `Sync.Unsafe.evalOrThrow` is the same unsafe-spawn
idiom the drivers and `HttpClientBackend` use; it only enqueues the fiber.

`reaper` (`@volatile Maybe[Fiber.Unsafe]`) is written in `startReaper` and read in `close()`.
No race: `close()` needs a pool reference that `init` only returns after `startReaper`
completes, and the first sweep is delayed by `startAfter = interval`, so the field is always
assigned before any sweep and before any `close()`. The interrupt
`r.interrupt()` resolves to `Fiber.Unsafe.interrupt()(using Frame, AllowUnsafe)`
(`Fiber.scala:451`), and `close()` supplies both (`given Frame = frame`; `(using AllowUnsafe)`
on the method) and `kyo.discard`s the returned Boolean. Correct.

Interval `= max(idleTimeout/2, 50ms)` closes an idle connection within ~1.5× the timeout
(sweeps at T/2, T, 1.5T; a connection idle at 0 is closed at the first sweep where
`now-ts > T`), matching the test's observed ~140ms for a 100ms timeout. The infinite-timeout
guard (`init`:158) correctly spawns nothing, leaving every `Duration.Infinity` test pool
byte-identical in behavior.

Minor divergence from the design doc (which specified `repeatAtInterval`): the code uses
`repeatWithDelay`, whose `Schedule.delay(startAfter).andThen(Schedule.fixed(delay))`
(`Clock.scala:505`) targets fixed wall-clock points anyway, so the cadence is materially the
same. Not a defect.

## 5. Reaper error handling (ConnectionPool.scala:240-246) — CORRECT

Per-connection `discardConn` wrapped in `try/catch NonFatal → Log.live.unsafe.error`, then the
scan continues; fatals escape. This is the right call: one bad connection must not stop the
reaper from expiring the rest, and swallowing a `VirtualMachineError` to keep a reaper alive
during process death would be wrong. It matches kyo-sql's own discard callback
(`SqlConnectionPool.scala:940-944`, same `NonFatal`-only + `Log.live.unsafe.error`) rather than
`ConnectionRegistry.runClose`'s blanket `catch _`, and the choice is defensible: the registry
swallows because a best-effort shutdown close has nowhere to report, whereas the reaper is a
long-lived loop where a fatal genuinely should propagate.

A fatal thrown by `sweepExpired` itself kills the reaper fiber silently — acceptable, since the
only in-body throws outside the `try` are array/atomic ops that don't throw under the invariants,
and a fatal there implies the JVM is already going down. Note `connections(idx).get` (:237) is
outside the `try` and assumes `Present`; that assumption is valid (the seq gate proves published
and the winning CAS proves no consumer took it, so the slot is `Present`), unlike `drainClaimed`
which defensively matches `Present`/`Absent`. Consistency nit only.

## 6. Process-lifetime reaper vs. the end-of-run leak check — ROBUST, not coincidental

The default client's reaper runs forever. It is correctly invisible to all three probes
(`LeakCheck.scala`):

- **Fiber probe** keys on `Scheduler.get.loadAvg()` / `busyFiberTraces()` — runnable/spinning
  work only (`:76-77, 185-191`). A fiber parked on `Clock.sleep` is off-scheduler and holds no
  worker load; the class doc states this explicitly (`:22-24`). Between sweeps `loadAvg == 0`,
  so `awaitSchedulerIdle` settles. A sweep is microseconds; the small chance of overlapping the
  settle window is transient and self-resets (the window requires *continuous* quiescence).
- **Thread probe** flags only non-daemon threads (`:82-83, 108`). Scheduler and timer threads
  are daemons, so the reaper's carrier/timer never trip it.
- **FD probe**: the reaper holds no descriptor; it *closes* the leaked connection's fd. With the
  2s test-fork timeout, a connection pooled just before `done()` is closed within ~3s (2s timeout
  + 1s interval) and its fd disappears from a later `awaitFdDrain` sample, well inside the 30s
  budget. `awaitFdDrain` (`:256-265`) only ever drops a descriptor that actually closed, so this
  is the mechanism the fix depends on and it is sound.

Robust by design, not a coincidence. The only residual is the microsecond sweep landing in the
settle window, which is not a persistent state and does not gate.

## 7. kyo-sql blast radius — CONTAINED

Confirmed `SqlConfig.idleTimeout` defaults to `10.minutes` (`SqlConfig.scala:88`), finite, so
every SQL pool now spawns a reaper sweeping every 5 minutes. This is safe:

- **Idle ring slots only.** The reaper claims via the head CAS and only touches connections in
  `[head, tail)` — i.e. released-and-idle ones. A leased connection has been polled out (head
  advanced past it, slot cleared); a quarantined `cancelsInFlight` connection is in neither ring
  nor closed. Neither is reachable by the sweep. So lease/reclaim/`cancelsInFlight` accounting is
  untouched.
- **Identical to existing poll-eviction.** The reaper's action = claim an idle ring slot + call
  `discardConn`, which is exactly what `poll` already does when it evicts a stale/dead connection
  (`:201-206`). It goes through the pool's raw discard callback (`conn.closeNow` +
  `NonFatal`-log), *not* `destroyAndFreeSlot`, so it does not touch the slot channel or metrics —
  correct, because an idle ring connection holds no slot permit (the permit was returned by
  `withSlot` when the lease ended; ring membership and permits are decoupled). Since poll-eviction
  is already correct w.r.t. SQL accounting, the reaper inherits that correctness.
- **Non-suspension invariant respected.** `sweepExpired` calls `discardConn` synchronously inside
  the `AllowUnsafe` defer, so `conn.closeNow` runs in exactly the non-suspending context the SQL
  callback documents as its contract (`SqlConnectionPool.scala:48-56`).
- **No leaked SQL reaper.** `SqlConnectionPool.closeAll` calls `pool.close()`
  (`:191`), which interrupts the reaper. An unclosed SQL pool would leave a parked reaper — but
  parked fibers aren't flagged, and 5-minute-first-sweep means a test-lifetime pool never even
  sweeps; if anything the reaper *reduces* the pre-existing unclosed-pool idle-connection leak.

## 8. StaticFlag config (defaultIdleTimeout.scala, HttpClient.scala:55, build.sbt:208) — SOUND shape; ONE must-verify

- **Read-once timing is safe.** `-Dkyo.http.client.defaultIdleTimeout=2seconds` is set at JVM
  launch (`javaOptions`); `StaticFlag.value` resolves at class load of the `defaultIdleTimeout`
  object, first touched when the `defaultClient` lazy val evaluates (first ambient client use) —
  long after JVM start. So the fork reads 2s, production reads 60s. No ordering hazard.
- **Shape is idiomatic.** `StaticFlag` is the repo's typed/validated resolve-once mechanism; a
  `private[kyo]` object whose FQCN is the `-D` key is a legitimate ops seam even though code can't
  reference the object. No MIMA impact (internal object; `HttpClient` signatures unchanged;
  `initUnsafe` bypasses `initUnscoped`'s `require(idleConnectionTimeout > Zero)`, and 2s > 0
  anyway). The scaladoc is accurate and well-scoped. Good.
- **MUST-VERIFY — global blast radius.** The `-D` lands in `kyo-settings` (`build.sbt:130,208`),
  applied to *every* module's test fork, not kyo-http only. So kyo-sql, kyo-pod, kyo-aeron, and
  any module that issues a request through the ambient `HttpClient.get*`/`post*` now reaps default-
  client idle connections after 2s. Functionally this is transparent (a reaped connection is
  silently re-opened), so only a test that asserts *same-connection reuse across a >2s idle gap*
  (a connection-id/fd-count assertion, or "still pooled after N seconds") would break. The author
  reports `kyo-httpJVM/test` green; the residual risk is another module's fork. Given the branch
  is being driven to a full-matrix green (tasks #31/#45), this is validate-the-matrix rather than
  a code fix, but it should be an explicit gate, not an inference from the kyo-http run alone.
  Severity: must-verify (not a code defect).

## 9. Test quality (ConnectionPoolTest.scala:116-166) — GOOD core regression; minor gaps

- **The new "reaper expires an idle connection with no further poll" leaf genuinely
  fails-without/passes-with.** With no reaper nothing inspects the released connection, so
  `discardCount` stays 0, `waitForReap(250)` returns false after ~5s → assert fails. With the
  reaper, the 50ms-interval sweep closes it at ~150ms → passes. It uses `Async.sleep` (not
  `Thread.sleep`) and the live Clock, and closes the pool to interrupt the reaper. Correct on the
  claimed JVM/JS/Native.
- **Timing-based, not fully deterministic.** It waits a 5s budget for a ~150ms event. That is a
  33× margin, so flaking needs a >5s scheduler-timer stall (which would fail much else), but it is
  not the latch/Clock-controlled determinism CONTRIBUTING prefers. Acceptable for an inherently
  wall-clock behavior; noting it as a small residual flake risk under extreme load. Severity: nit.
- **Zero-timeout change (:133-135) still tests what it claims.** The synchronous body (release ×2,
  poll, close) completes in microseconds — long before the 50ms first sweep — so `poll` performs
  both evictions (`discardCount == 2`) exactly as before; the added `pool.close()` interrupts the
  spawned reaper for hygiene (a zero timeout is finite, so a reaper is now spawned). Even a race
  with the first sweep is arbitrated by the head CAS, so no double-discard. The comment is
  accurate. Correct.
- **Coverage gaps (nits):** (a) no leaf asserts `close()` actually cancels the reaper (that no
  further discard happens after close); (b) no multi-host sweep leaf, though `sweepExpiredHosts`
  iterates `pools.forEach`; (c) no leaf asserts a *fresh* connection is NOT reaped while a stale
  sibling is (the age-prefix stop). None blocks; (b) and (c) would harden the ring-geometry
  claims in §1–§2.

Good hygiene elsewhere: every finite-timeout test pool (`:122` Zero, `:147` 100ms) is closed;
all other test pools use `Duration.Infinity` (no reaper), including
`ConnectionPoolConcurrencyTest`. No dangling test reapers.

## 10. Hygiene — CLEAN

- No em/en dashes or ellipses on added lines (checked). No marketing adjectives. Comments explain
  current invariants (race-freedom, age-prefix, the leak scenario the test guards), not change
  history. The build.sbt comment correctly justifies 2s (60s outlasts the 30s drain window).
- Naming/idiom consistent with the file (`reaper`, `startReaper`, `sweepExpired`, `Maybe`/`Present`/
  `Absent`, `discardConn`).
- **API/MIMA:** `ConnectionPool.init` gained `(using ... frame: Frame)` and the constructor a
  `frame` param, but `ConnectionPool` is `final private[kyo]` — no public-surface/MIMA impact. All
  five call sites (HttpClientBackend, SqlConnectionPool, three kyo-net test files) satisfy `Frame`
  via the ambient given or the auto-derived `Frame` macro. `defaultIdleTimeout` is `private[kyo]`.

## Note (pre-existing, not introduced): double-close idempotency reliance

For the HTTP client, `discardConn` is `registry.remove(conn); conn.http1.close();
conn.transport.close()` and closes **unconditionally** (ignores the `conns.remove` result),
whereas `registry.closeAll` closes only if it wins `conns.remove`. If `close()`'s
`registry.closeAll` wins the remove of a connection the reaper is concurrently discarding, both
paths call `transport.close()` on it. This is safe only because `transport.close()`/`http1.close()`
are idempotent, an assumption the codebase already documents and relies on
(`HttpClientBackend.scala:804` "close() is idempotent"). It is **not new**: `poll`-eviction uses the
identical `discardConn` and races `closeAll` the same way today; the reaper just adds another caller
of the same hook, and `close()` interrupts it first, shrinking the window. Flagging for awareness,
not as a regression.

---

## Must-fix / must-verify shortlist

1. **Must-verify (not code):** full test-matrix green with `-Dkyo.http.client.defaultIdleTimeout=2seconds`
   applied to *every* module's fork — confirm no module asserts default-client same-connection reuse
   across a >2s idle gap. Do not infer matrix-green from the kyo-http run alone.

## Recommended (non-blocking) nits

2. Soften the `sweepExpired` monotonicity comment (§2): "approximately age-ordered; a strictly-stale
   connection is closed within one additional sweep," not strict head→tail monotonicity.
3. Add coverage (§9): a `close()`-cancels-reaper assertion and a multi-host / fresh-not-reaped leaf.
4. Optional: mirror `drainClaimed`'s `Present`/`Absent` match in `sweepExpired` instead of
   `connections(idx).get`, for consistency (the `.get` is provably safe, so this is style only).
