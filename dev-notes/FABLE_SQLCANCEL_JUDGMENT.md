# Judgment: SqlConnectionCancelTest "Async.timeout ... fires the wire cancel" CI hang

## Verdict: PASS-MY-READ

Test-timing race, pre-flight interrupt. Not a custody bug, not the Sync.ensure finalizer bug. Correct action is (a): make the leaf deterministic with `Clock.withTimeControl`, keeping the real `Async.timeout` expiry path as the interruption source.

## 1. Root cause confirmed, with one refinement

The hang requires `report(events, 3)` to see fewer than 3 events. Given the probe's mechanics, that is possible ONLY when the timeout's interrupt lands before "statement" is emitted. Three sub-windows, all pre-statement:

- **W1, before the lease acquires a connection.** `Async._timeout` starts the sleep the moment the wrapper runs (`kyo-core/shared/src/main/scala/kyo/Async.scala:192-196`: `Fiber.initUnscoped(v)` then `clock.unsafe.sleep(after)`), so the 100ms budget covers fiber scheduling latency plus slot-channel init plus `factory.open`. On a loaded windows runner the lease fiber can simply not reach the statement in 100ms. Result: 0 events, and `decideExit` may not even run for a connection (reservation-only unwind).
- **W2, connection acquired, flag not yet raised.** `decideExit` (`SqlConnectionPool.scala:731-753`) sees `conn.inFlight == false`, takes `case _`, releases/destroys. 0 events. This is by design and is exactly what the passing "interrupt while connecting" leaf asserts.
- **W3, flag raised, `emit("statement")` not yet run** (`tracked` sets the flag before the emit, SqlConnectionCancelTest.scala:170-171 vs 92). Reclaim runs, emits "cancel","drain": 2 events, still short of 3. Astronomically narrow; W1/W2 dominate.

In every window `report(events, 3)` blocks on `events.take` forever: the 2-minute TIMEOUT naming nothing, the exact shape the suite's own `drained` scaladoc warns about (SqlConnectionCancelTest.scala:278-293). The idle thread dump is consistent: suspended kyo fibers occupy no threads, but in this script the reclaim has no parking point (no `cancelHangs`/`drainHangs`/`drainGate`, `Channel.offer` is non-suspending), so a launched reclaim always ran to completion; the dump plus the script rules out an in-progress reclaim.

**Custody hole ruled out.** If `decideExit` sees `inFlight == true`, "cancel" and "drain" are guaranteed:

- The only pre-reclaim lowering of the flag is the probe's `settle` via `tracked`'s `Sync.ensure` (test:172, 184-185), gated on `Connection.leftSessionIdle`. For an interrupt the error is `Present(Result.Failure(Timeout))` (`Async.scala:196` interrupts with the error) or a plain interrupt panic; `Timeout` is not a `SqlException`, so `leftSessionIdle` returns false (`Connection.scala:269-273`, `case Present(_) => false`). The flag stays up.
- Finalizer order is inner-to-outer on the same unwinding fiber: `settle` runs before the lease's `Scope.ensure` (`resolvingOnce`, `SqlConnectionPool.scala:697`) reaches `decideExit`. So `decideExit`'s read is post-settle; nothing mutates the flag between it and `cancelInFlight`'s re-check (test:126-128) except the drain's own `settled` (test:151-155), which runs after "drain" is emitted.
- `Fiber.Unsafe.init(supervised)` (`SqlConnectionPool.scala:745`) failing to launch would flake every deterministic interrupt leaf equally; they pass 30/30. `cancelTimeout` is 30s against a microsecond reclaim; no ordering hole.

So `cancelAndReclaim` (`SqlConnectionPool.scala:770-800`) cannot emit fewer than "cancel","drain" once the reclaim gate passed. The only missing-event source is the pre-statement interrupt. The failing leaf is also the ONLY in-flight leaf that does not await "statement" before its interruption source can fire, violating the suite's own stated contract ("deterministic without a timing assumption", test:17-18); every sibling gates on `report(events, 1)` first.

## 2. Not the Sync.ensure finalizer bug

Two independent proofs:

- The interruption here is a fiber interrupt (`task.unsafe.interrupt(error)`, `Async.scala:196`), the edge `Sync.ensure` DOES cover; the documented bug is the typed-abort edge. And the lease resolution deliberately uses `Scope.ensure` (`SqlConnectionPool.scala:669-697`), which covers both edges.
- Had the finalizer been skipped, the deterministic siblings (interrupt, scope teardown, race-loser, parent-death) would hang the same way; they are stable.

## 3. Recommended fix: (a) deterministic restructuring via Clock.withTimeControl

`Async.timeout` is Clock-driven (`Async.scala:193-196` schedules `clock.unsafe.sleep(after)` on the ambient `Clock` Local), and `Clock.withTimeControl` (`Clock.scala:317-341`) replaces that clock with one whose sleeps complete only on `advance`. Child fibers inherit Locals, so a lease fiber started inside the control sees the controlled clock. That gives a race-free leaf that still exercises the genuine timeout machinery: the same sleep-fiber-completes -> `task.unsafe.interrupt(Result.Failure(Timeout))` -> unwind -> `Scope.ensure` -> `decideExit` -> reclaim path, only the clock implementation under it differs.

Restructure (matches the Scope-teardown sibling's shape: prove in-flight first, then fire the source):

```scala
"Async.timeout on a statement in flight fires the wire cancel" in {
    val config = baseConfig("timeout")
    withProbePool("timeout") { (pool, events) =>
        Clock.withTimeControl { control =>
            Fiber.initUnscoped(
                Abort.run[Timeout](Async.timeout(100.millis)(lease(pool, config)(_.simpleQuery(Sql.hang))))
            ).flatMap { fiber =>
                // The statement is on the wire before time moves: the timer can only fire in-flight.
                report(events, 1).flatMap { first =>
                    control.advance(101.millis).andThen {
                        fiber.get.flatMap { outcome =>
                            report(events, 2).map { seen =>
                                assert(first == Chunk("statement"))
                                assert(seen == Chunk("cancel", "drain"), s"expected the reclaim chain to run, saw $seen")
                                outcome match
                                    case Result.Failure(_: Timeout) => succeed
                                    case other                      => fail(s"expected the lease to end in a Timeout, got $other")
                            }
                        }
                    }
                }
            }
        }
    }
}
```

Notes that make this sound:

- Nothing on the path to "statement" sleeps (acquireTimeout Infinity skips the connect budget, `SqlConnectionPool.scala:637-638`; probe `open` is synchronous), so `report(events, 1)` completes under frozen time.
- `advance(101.millis)` (strictly past the deadline, avoids any `<` vs `<=` edge in the tick) completes the sleep task; everything downstream is completion/event-awaited (`fiber.get`, `report`), never time-awaited, so no new race.
- The reclaim's own `timeoutWithError(30.seconds)` never needs to fire; the reclaim body completes and interrupts the budget sleep (`Async.scala:197`), under either clock.
- `TimeControl` used sequentially from the test's fiber only, per its thread-safety note (`Clock.scala:254-255`).

## Same latent race in a sibling: fix it in the same change

The "a statement that overruns queryTimeout fires the wire cancel" leaf (test:762-776) has the identical structure: `bounded` starts the `queryTimeout` timer when the op begins (`SqlConnectionPool.scala:296-307`, via `leaseStatement`'s `bounded(config)(op(conn))` at line 77), before the probe raises the flag, and the leaf goes straight to `report(events, 3)`. Narrower window (no connect inside the budget) but the same W2/W3 stall exposure under CI load, with the same 2-minute-hang failure shape. Apply the same restructure: lease in a child fiber under `Clock.withTimeControl`, `report(events, 1)`, `advance`, `fiber.get`, `report(events, 2)`, asserting `SqlConnectionQueryTimeoutException`.

No main-code change is warranted; the pool's behavior in every window is the asserted contract.
