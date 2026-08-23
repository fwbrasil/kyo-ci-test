# JvmLeakDetectorTest flaky failure on main

## Symptom

```
=== JvmLeakDetectorTest ===
  [PASS] open guard, testForceLeak emits stderr leakWarning
  [FAIL] open guard, close it, then testForceLeak emits nothing  *** FAILED ***
    captured == ""
    |
    [kyo-ffi] Ffi.Guard opened at Frame(<internal>:0:0, <internal>, <internal>, <internal>) was garbage-collected without close(). ...
    false
  [PASS] testForceLeak is idempotent after the warning fired once
```

The test that failed opens a guard, **closes it**, forces the leak path, and asserts stderr captured nothing. It captured a real leak warning. Note the wording: **"was garbage-collected without close()"**, the message emitted by the shared `java.lang.ref.Cleaner`, not by the just-closed guard.

## Root cause: an asynchronous, process-global stderr time-bomb

Three things combine:

1. **The leak warning goes to process-global `System.err`.**
   `JvmGuard.LeakWarning.run()` (jvm/.../JvmGuard.scala:106) does `java.lang.System.err.println(FfiErrors.leakWarning(frame.show))`. The test captures leaks by swapping `System.setErr` (`captureStderr`), which is process-wide, not per-thread and not per-suite.

2. **The Cleaner fires on its own daemon thread, asynchronously, whenever GC collects a leaked guard.**
   `JvmLeakDetector.register` (jvm/.../JvmLeakDetector.scala:44) arms `cleaner.register(guard, warning)`. `LeakWarning` deliberately does **not** reference the guard, so a leaked guard is collectible and the Cleaner *will* fire its warning at an arbitrary later time, on the Cleaner thread.

3. **Several tests deliberately open guards with `Frame.internal` and never close them**, leaving them armed with the real Cleaner:
   - `JvmLeakDetectorTest` test 1 "open guard, testForceLeak emits stderr leakWarning" (JvmLeakDetectorTest.scala:32) leaks 1 guard.
   - `JvmLeakDetectorTest` test 3 "testForceLeak is idempotent" (JvmLeakDetectorTest.scala:54) leaks 1 guard.
   - `GuardRegistryStressTest` "concurrent opens without close seed leak detection" (GuardRegistryStressTest.scala:92) leaks **16** guards.

   All use `given frame: Frame = Frame.internal`, which is exactly the frame in the captured stray warning.

### The race

Tests within a suite are `sequential`, but that does not help here:

- **Intra-suite:** test 1 leaks a `Frame.internal` guard and returns. While test 2 ("close it, emits nothing") holds the global `System.err`, GC collects test 1's leaked guard and the Cleaner daemon prints the leak warning into test 2's capture buffer. `captured == ""` fails.
- **Inter-suite:** suites run with **parallelism 8** (see the run header). `GuardRegistryStressTest` leaks 16 `Frame.internal` guards concurrently; any one collected during test 2's window corrupts the capture.

`sequential` only serializes leaves within one suite. It cannot fence an async Cleaner thread or a concurrently-running sibling suite, both of which write to the same process-global `System.err`. The failure is therefore timing-dependent (GC-driven), i.e. flaky.

### Why the failing test's own guard is not the culprit

In `closeWithPolicy` (GuardCore.scala:173) the state CASes to `StateClosing` *before* `postCloseHook` runs (GuardCore.scala:206). `postCloseHook` calls `leakCleanable.clean()`, which both de-registers the Cleaner and runs `warning.run()` with state already non-`StateOpen`, so it no-ops. The closed guard is correct and inert. The stray line comes from a *different*, still-armed leaked guard.

## The fix

The intentional-leak tests use `testForceLeak` precisely so they do **not** depend on real GC. There is no reason to leave the real Cleaner armed afterwards; doing so plants a process-global stderr time-bomb. **No test relies on the real Cleaner firing** (`grep` for `System.gc` / `.ignore` in `kyo-ffi/jvm/src/test` finds nothing).

Close each deliberately-opened guard after its `testForceLeak` output has been captured. Closing:
- cancels the Cleaner registration (`clean()` de-registers), so no async firing, and
- flips state to non-`StateOpen`, so any firing is a no-op.

Closing happens *after* the `testForceLeak` emit is already in the buffer (state is `StateClosing` during close, so `clean()`'s `warning.run()` no-ops), so no assertion weakens.

Concretely:

1. `JvmLeakDetectorTest` test 1 (JvmLeakDetectorTest.scala:31-34): close `g` after `testForceLeak`, inside the capture block.
2. `JvmLeakDetectorTest` test 3 (JvmLeakDetectorTest.scala:53-57): close `g` after the two `testForceLeak` calls.
3. `GuardRegistryStressTest` leak test (GuardRegistryStressTest.scala:95-99): close all 16 guards after the force-leak loop.

This removes every source of the stray `Frame.internal` warning and makes the "emits nothing" assertion robust, without depending on GC timing, without relabeling anything flaky, and without weakening any assertion.

## Verification plan

Run `kyo-ffiJVM/test` (full module so the suites interleave under parallelism 8), repeatedly, to confirm the "emits nothing" assertion no longer catches a stray line. A single pass is not sufficient evidence for a GC-timing flake; loop the module test enough times to trust it.
