# Audit: tests that depend on garbage collection

Scope: every `.scala` under the repo. Method: grepped for `System.gc` / `runFinalization` / `Runtime.gc`, then for `WeakReference` / `SoftReference` / `PhantomReference` / `WeakHashMap` / `Cleaner` / `java.lang.ref`, and read each test hit to classify it.

The distinction that matters:

- **GC-dependent (flaky):** forces `System.gc()` and then **asserts a referent was reclaimed**. If GC doesn't collect within the forced rounds, the assertion fails. This is the inherently-flaky shape.
- **GC-defensive (safe):** holds a strong ref to **prevent** premature collection, or tolerates non-determinism (asserts a delta bound, never "was collected").
- **GC-incidental (safe):** calls `System.gc()` but the assertion does not depend on collection happening (asserts survival of a strongly-held object, or uses gc only to trim false positives).
- **Deterministic hook (safe):** exercises the collection *path* via a test-only hook instead of real GC.

## GC-dependent — inherently flaky (the actual finding)

All three live in `kyo-stm/shared/src/test/scala/kyo/STMStressTest.scala`, are `.onlyJvm`, and share the shape `(1 to 5).foreach(System.gc())` immediately followed by an assertion that a `WeakReference` was cleared:

| Line | Test | Assertion depending on GC |
|------|------|---------------------------|
| 1462 | "WeakReference to TRef allocated in doomed transaction becomes GC-eligible after rollback" | `assert(wr.get == null, ...)` |
| 1510 | "nested STM.run with heavy inner-log does not retain log after success" | `assert(cleared >= wrs.size / 2, ...)` |
| 1531 | "after a multi-ref commit, the per-thread CommitBuffer does not retain prior-cycle TRefs" | `assert(wr.get == null, ...)` |

Why they can flake: `System.gc()` is a hint, not a guarantee; even when HotSpot honors it, (a) reference enqueue can lag the `gc()` return, and (b) the referent may still be reachable through a JIT-retained local or an effect-continuation frame that outlives the `Sync.defer` block, so a fixed 5 rounds is not guaranteed to clear it. A fixed-count-then-immediate-assert is the fragile part.

### These are NOT the same case as the ffi test just fixed

The ffi "emits nothing" test had a **deterministic hook** (`testForceLeak`) and did not need GC at all; its GC involvement was accidental pollution from unrelated leaked guards, so the correct fix was to remove the leak. The STM tests are different: the property under test *is* reachability ("does this TRef become collectible"), so GC cannot be removed. The fix is to make the wait robust, not to delete the coverage.

### Recommended fix (keeps coverage, removes fragility)

Replace the fixed `(1 to 5).foreach(System.gc())` + immediate assert with a bounded await-until-cleared loop with a generous deadline (the standard JVM idiom, cf. Guava `GcFinalization.awaitClear`): repeatedly `System.gc()` + a short park, re-checking the `WeakReference`, until cleared or a multi-second deadline; fail only if still not cleared at the deadline. This still verifies the object becomes collectible (full coverage) but tolerates GC/enqueue latency, which is as non-flaky as a reachability test gets on the JVM. A single shared helper (e.g. `awaitCleared(ref, deadline)`) would cover all three sites, plus the `cleared >= half` variant.

## GC-incidental / GC-defensive — safe, no change needed

| Location | Why safe |
|----------|----------|
| `kyo-stats-machine/.../MachineHandlesTest.scala:68` "state persists across a forced GC" | Calls `System.gc()` then asserts the strongly-held `cell` **survived** (count/sum unchanged). GC outcome cannot fail it. |
| `kyo-test/runner/jvm/.../internal/LeakCheck.scala:440` (+ `LeakCheckTest.scala`) | `System.gc()` is a false-positive **trimmer**: "a genuine leak stays referenced and survives the gc, so this trims false positives without hiding real leaks". No assertion on collection. |
| `kyo-stats-otlp/.../OTLPMetricsExporterTest.scala` (counter/histogram leaves) | Registry holds only a `WeakReference`; these leaves keep a `keepAlive` strong ref to **prevent** collection (hardening a CI-observed JVM-arm64 flake). GC-defensive. NOTE: the *gauge* leaf did NOT follow this pattern, see the correction below. |
| `kyo-stats-machine/.../MachineTest.scala` | `.onlyJvm` because weak-ref deref is JVM-only; asserts registry contents (`after.exists`, `> before`), never that a ref went null. |
| `kyo-ffi/native/.../NativeLeakDetectorTest.scala` | Explicitly avoids real GC ("inherently flaky under Scala Native's Immix collector") and uses the deterministic `testForceLeak` hook. |
| `kyo-ffi/jvm/.../JvmLeakDetectorTest.scala`, `GuardRegistryStressTest.scala` | Use `testForceLeak`; after the current fix they leave no guard armed, so no async Cleaner firing. `GuardRegistryStressTest` also asserts a weak-registry delta `<= 0` (tolerant, never "was collected"). |
| `kyo-ffi/jvm/.../JvmScratchTest.scala:132` "arenaCleaner" | Invokes the Cleaner's cleanup action directly; does not wait on real GC. |

## Correction (exhaustive re-sweep)

A first pass reported only the three `STMStressTest` cases and stamped `OTLPMetricsExporterTest` wholesale "safe". That was wrong: a second, exhaustive sweep (all platforms; `System.gc`/`runFinalization`, Native `scalanative.runtime.GC`, JS `WeakRef`/`FinalizationRegistry`, every reference type, `WeakHashMap`, `Cleaner`/`finalize`, memory/`OutOfMemoryError`, the `.flaky` marker, and every `Stat` metric-registration site) found one more:

- **`OTLPMetricsExporterTest` "exports registered gauge at interval"** (`.onlyJvm.flaky`, line 154). `StatsRegistry.Store` holds each metric as `WeakReference[A]` (StatsRegistry.scala:46,50). The leaf did `val _ = Stat.initScope(...).initGauge(...)` — dropping the only strong ref — so across the ~1s export interval the JVM could collect the gauge and it vanished from every export, failing `assert(found.isDefined)`. It was masked with `.flaky` rather than fixed, even though the counter/histogram leaves in the same file already solved the identical hazard (keep the metric reachable via `discard(x)` inside the export-wait `Loop`). Fix: mirror the siblings (named `val gauge`, `discard(gauge)` inside a take-up-to-10 `Loop`), then drop `.flaky`.

Everything else touching a weak registry is either already hardened (OTLP counter/histogram), synchronous with the ref held (`StatTest`, `MachineTest`, `MachineStatsDemo`), or GC-defensive/deterministic. `RuntimeReflectionDemoTest` and `BrowserLauncherPlatformTest` matched only via `java.lang.reflect`; `BufferNegativeSizeTest` mentions `OutOfMemoryError` in scaladoc only. Remaining `.flaky` tests are browser/network/DOM/concurrency timing, not GC.

### Cross-check from the main side (risk-surface enumeration)

Because keyword-sweeping test files twice missed the OTLP gauge leaf, a third pass enumerated every MAIN-source `WeakReference`/`SoftReference`/`PhantomReference`/`WeakHashMap`/`Cleaner` holder and traced each to its tests:

- **`kyo-ai` `LLM.AIRef`** (a `WeakReference[AI]` keying `State.instances`, "so a dropped AI becomes reclaimable"): never examined before. Verdict SAFE. `state.instances` is a `Dict[AIRef, AISession]`; `.size` counts Dict entries (the `AIRef` key objects), and GC only nulls the weak referent *inside* an `AIRef`, it does not remove the entry (only explicit `State.pruned`/`without` do, which run during the loop while the AIs are alive). `AITest:432/:467` assert live-instance counts, not GC-driven decreases, and no kyo-ai test forces GC. `LLMTest:1148` tests `AIRef` equality-by-id deterministically.
- **`kyo-compiler` `CompilerPool`**: uses a deterministic size/idle-bounded `Cache` (kyo-core `Cache`, not Soft/Weak-backed) with a deterministic close-on-evict finalizer ("observes the reclaim deterministically"). `CompilerPoolTest`'s eviction/"finalizer"/"reclaim" language is deterministic, not GC. SAFE.
- **ffi** (`GuardRegistry`/`JvmLeakDetector`/`NativeGuard`/`NativeLeakDetector`), **stats** (`StatsRegistry`, `OTLPMetricsExporter`): covered above.

No `SoftReference` or `PhantomReference` exists anywhere in the tree; `ReferenceQueue`/`reachabilityFence` appear only in comments. The main-side surface and the test-side sweep converge on the same set, so the audit is exhaustive.

## Bottom line

Three families of genuinely GC-flaky tests existed: the three `STMStressTest` weak-reference cases (converted to a deterministic `CommitBuffer` check), the ffi leak tests (fixed to leave no armed guard), and the `OTLPMetricsExporterTest` gauge leaf (fixed to keep the metric reachable, `.flaky` dropped). Everything else that touches GC is either defending against it, synchronous with the ref held, or using a deterministic hook.
