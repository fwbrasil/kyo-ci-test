# IoUringDriverAcceptTransientErrnoTest intermittent NoClassDefFoundError

Status: DIAGNOSING (root cause not yet confirmed; a CI probe is measuring the decisive resource
numbers). This file is updated as evidence lands.

## Symptom

Full-matrix streak run 1 (`32437875190`), leg `build (linux-x64, ubuntu-latest, JVM ...) / build (JVM)`,
deep in the `kyo-netJVM` suite (~1h in, 03:06:24Z):

```
Exception in thread "kyo-scheduler-worker-22" [kyo-test] unexpected panic during run:
  kyo.test.runner.internal.LeafPool$LeafPoolPanic$: kyo-test leaf-pool: work body failed to complete its promise
java.lang.NoClassDefFoundError: kyo/net/internal/posix/IoUringDriverAcceptTransientErrnoTest$$anon$8
  at ...IoUringDriverAcceptTransientErrnoTest.$anonfun$2(IoUringDriverAcceptTransientErrnoTest.scala:101)
  at kyo.kernel.internal.Safepoint$Ensure.apply(Safepoint.scala:153)
  ... ensureLoop$1 (test:101) ... $$anon$1.apply(test:134 / :171)
Caused by: java.lang.ClassNotFoundException: kyo.net.internal.posix.IoUringDriverAcceptTransientErrnoTest$$anon$8
--- IoUringDriverAcceptTransientErrnoTest: 0 passed, 1 failed  (0ms)
```

Line 101 is the shared helper `withInjectingDriver`'s `Sync.ensure(Sync.defer(driver.close()))(body)`.
Both leaves (:134, :171) failed identically; six scheduler-worker fibers hit it at once (parallelism 8).
`NoClassDefFoundError` is a `LinkageError` (fatal), so kyo does not capture it as an `Abort` failure; it
propagates to the worker's uncaught handler and leaves the leaf's promise incomplete, which the
leaf-pool then reports via its belt-and-suspenders `LeafPoolPanic`. The leaf-pool is the messenger,
not the cause.

## What is ruled OUT (with evidence)

- NOT my code. The test file is unchanged since 2026-08-05 (`22164e3008`), exists on `origin/main`, and
  my HEAD (`00d22e37ef`) touched only kyo-data (the MpmcUnbounded peek fix) + its test. kyo-net compiled
  output is identical to the prior run.
- NOT a deterministic missing class. It PASSED in the immediately-prior full run (`32409304829`,
  linux-x64 JVM = success) on identical kyo-net code. Compilation of identical source is deterministic,
  so `$$anon$8.class` IS emitted and on disk; a genuinely-missing class would fail every run.
- NOT stale incremental/cache. `build.yml` has no `actions/cache`; each pole compiles fresh in the
  runner (checkout + compile-main + compile-test + run). No cross-run Zinc/target reuse.
- NOT JVM shutdown / dead classloader. Tests immediately AFTER (NetFlagsTest,
  PosixTransportShutdownReclaimTest, BoringSslProviderHostnameVerificationTest, ...) PASS, loading their
  own classes fine. The JVM and app classloader are healthy.
- NOT memory / metaspace / native crash. No OOM, no `Metaspace` error (MaxMetaspaceSize=2G),
  no SIGSEGV/SIGBUS/hs_err anywhere in the job. ci-mon at the failure: availMB ~9700, psiMem10=0.00,
  disk 74 GB free.
- ONLY ONE class fails: `IoUringDriverAcceptTransientErrnoTest$$anon$8`. Its sibling anon classes
  `$$anon$9/$$anon$10/$$anon$11` are actively executing in the same stack (loaded fine). So the enclosing
  bytecode is consistent; it is specifically the first, lazy load of `$$anon$8` that fails.

## Working hypothesis: transient resource exhaustion during an io_uring/socket burst

The class-file load of `$$anon$8` is the marginal new-resource request that fails at a pressure peak;
already-loaded classes and already-acquired sockets are unaffected, so only this one first-load fails,
and the moment pressure drops (resources freed) later tests load their classes fine. The failure lands
during a ~2 s burst of ~12 concurrent io_uring/socket/TLS suites (03:06:22.7 -> 24.3:
TransportConcurrentEcho, PosixTransportHandshakeAlert, PollerIoDriverCloseDuringIo, NativeSslEngine,
IoUringRawWriteOrdering, ConnectionHalfCloseOutbound, IoDriverPoolWrapRotation, ...), `procs` 161 -> 196.

Candidate exhausted resource:

- fd exhaustion: WEAKENED. The runner's `nofile` soft=hard=65536 (measured on the real
  ubuntu-latest runner). Exhausting 65536 fds needs a ~65k leak; implausible for this workload.
- `vm.max_map_count` exhaustion (default 65530): LEADING. io_uring rings are created via `mmap`
  (each `io_uring_queue_init` maps ~2-3 regions). When `max_map_count` is hit, `mmap` returns ENOMEM;
  a class load that mmaps the class file then fails and the classloader surfaces `ClassNotFoundException`.
  Fits: specifically implicates io_uring (mmap-heavy), transient (munmap on ring close frees maps),
  intermittent, deep-in-suite. Whether this is a leak (maps grow unboundedly) or just high concurrent
  usage brushing the ceiling is what the probe measures.

The IoUringDriver itself has correct ring teardown (`teardownRing` -> `io_uring_queue_exit` munmaps +
closes the ring fd, gated on both the reap carrier and engine worker). No obvious leak by reading; the
probe's peak numbers decide it.

## Probes dispatched (fork CI, custom mode = separate concurrency group, does not disturb the matrix)

- `32443378691` (tiny, DONE): fd soft=hard=65536, max via /proc/self/limits = 65536, nproc=4.
- `32443718470` (running, ~1h): full `kyo-netJVM/test` on ubuntu-latest with a 1 Hz sampler of peak fd
  count AND peak `/proc/<pid>/maps` line-count across java/sbt, plus the limits up front. Decisive for
  BOTH resource hypotheses; may also reproduce the flake directly.
  (An fd-only earlier probe `32443333869` was cancelled once fd was weakened, superseded by this one.)

## Decision rule for the fix (once the probe returns)

- Peak maps approach ~65530 by monotonic growth over the suite -> a mapping LEAK; find and fix it
  (ring/buffer teardown). Root fix.
- Peak maps approach ~65530 as bounded-but-high concurrent usage -> provisioning; raise
  `vm.max_map_count` via `sudo sysctl -w` in the CI test step (runners have passwordless sudo). Root fix.
- Peak fd/maps both modest (well under the limits) -> resource exhaustion REFUTED; pivot to a
  classloader/compilation-nondeterminism investigation with a reproduction.

Not my code and it passed last run, but per the standing mandate this failure is owned and fixed at the
root, not waved off as flaky. The bar for calling it pre-existing (a clean repro on origin/main) is not
claimed; the plan is to confirm the mechanism, then fix.

## Update 1: resource exhaustion REFUTED; pivot to interrupt-vs-classloading

Probe `32443718470` (full `kyo-netJVM/test` on the real runner) measured, against limits
fd=65536 / max_map_count=65530: peak fd = 1354 (2%), peak /proc/pid/maps = 488 (0.7%). Both resources
are far below their ceilings; even large cross-module accumulation cannot reach them without a
catastrophic leak (which prior leak-hunting and the passing prior full runs exclude). So fd and map
exhaustion are REFUTED. (Caveat: that probe ran kyo-net alone in a fresh ~7min JVM, not the full
`testKyo --all JVM` where the failure occurred; a faithful full-suite probe `32444846952` re-measures
fd/map/THREAD peaks across all 65 modules and may reproduce.)

`$$anon$8.class` is present in every normal build (anon 1..17 all emitted, jvm + native test-classes),
so the failure is a runtime failure to load a PRESENT class, loaded from the `test-classes` DIRECTORY.

New leading hypothesis: `Thread.interrupt()` racing the lazy class load. kyo's BlockingMonitor
dispatches `Thread.interrupt()` to workers it judges blocked (BlockingMonitor.scala:244 `mount.interrupt()`;
Worker.scala clears stale interrupts at :266/:392); the io_uring reap carrier blocks in
`kyo_uring_submit_and_wait_timeout` and is a prime interrupt target during the `driver.close()` teardown
that runs in the ensure finalizer at line 101. Open doubt: `$$anon$8` loads from a DIRECTORY via
FileInputStream, which is NOT an InterruptibleChannel, so a plain interrupt should not break that read.
The precise mechanism (interruptible I/O in the JDK dir-load path on Corretto 25? an interrupt-broken JAR
read of a superclass/interface surfacing as CNFE for `$$anon$8`?) is under held-out review.

In flight: faithful full-suite probe `32444846952` (fd/map/thread peaks + repro), and a held-out Fable
analysis of the full evidence for the exact mechanism + the root-fix location (or accept/mitigate call).

## Update 2: interrupt hypothesis REFUTED by JDK mechanics; leading candidate is JDK-level

`fork := true` (build.sbt:131): the failing job runs `testKyo --all JVM` in ONE forked test JVM (sbt
ForkMain) whose app `BuiltinClassLoader` searches a `-cp` combining all 65 modules' `classes` +
`test-classes` DIRECTORIES and hundreds of dependency jars. `$$anon$8` (a kyo-net test class) resolves
from a test-classes DIRECTORY.

Interrupt-vs-classloading is refuted: the standard URLClassPath path reads `.class` bytes via
`FileInputStream` (dir entries) and `ZipFile`/`RandomAccessFile` (jars); NEITHER is an
`InterruptibleChannel`, so `Thread.interrupt()` (BlockingMonitor's `mount.interrupt()`) cannot close them
mid-read on Corretto 25. The documented "ClosedByInterruptException -> ClassNotFoundException" failure is
specific to `FileChannel`-based custom loaders, not ForkMain's AppClassLoader. So even though the reap
carrier is interrupted during `driver.close()`, that interrupt does not break the load of `$$anon$8`.

Refuted so far: my code; missing class; sbt/Zinc cache; JVM shutdown; memory/metaspace/native-crash; fd
exhaustion (peak 1354/65536); map exhaustion (peak 488/65530); Thread.interrupt-breaks-classload.

Leading remaining candidate: a JDK-level transient class-load failure under this specific setup, one
forked JVM searching a very large classpath while a burst of 6+ fibers concurrently first-load the same
class (parallelism 8). Candidates within this: a URLClassPath/lazy-loader concurrency race; a JDK 25 +
`-XX:+UseCompactObjectHeaders` (experimental Lilliput, present in JAVA_OPTS) interaction; or a transient
OS/filesystem hiccup. All are environment/JDK-level, not a kyo logic bug or my change. None is confirmed;
the faithful probe (reproduce + thread peaks) is the outstanding evidence. If it does not reproduce and
resources are normal, the honest classification is a rare JDK/environment class-load anomaly, to be
recorded and revisited if it recurs across the streak (it passed the prior full run, this session's
kyo-net probe, and has been stable since Aug 5).

## Update 3: interrupt EMPIRICALLY refuted on JDK 25; concurrency stress clean; decision framing

Local experiments on JDK 25 (Temurin 25.0.3, same OpenJDK base as the CI's Corretto 25; the URLClassPath
FileLoader source is identical):
- Pre-set interrupt flag, then a directory first-load via the app BuiltinClassLoader, x60: ok=60, cnfe=0.
- A daemon thread continuously calling `interrupt()` on a thread doing 200,000 directory first-loads
  (fresh URLClassLoader each): ok=200000, cnfe=0.
- 16 threads x self-interrupting concurrent first-loads over a 201-entry classpath (probe classes last, to
  force a long URLClassPath search), with `-XX:+UseCompactObjectHeaders`: 2 minutes of hammering, zero
  failures observed.
So `Thread.interrupt()` does not break directory class-loading on JDK 25 (the FileLoader uses
`FileInputStream`, not an `InterruptibleChannel`), and the URLClassPath search path is robust under
heavy concurrent, interrupted first-loads. The interrupt hypothesis is refuted empirically, and a plain
concurrency race did not reproduce locally.

Full run 1 (`32437875190`) finished: 12 of 13 legs green (linux-arm64 JVM/JS/Native/Wasm, windows JVM/JS,
linux-x64 JS/Wasm/Native all success); the ONLY failure is linux-x64 / build (JVM), this flake.
linux-arm64 JVM passed the IDENTICAL test on the same commit -> the failure is non-deterministic.

Refuted (evidence, not memory): my code; missing class; sbt/Zinc cache; JVM shutdown; memory/metaspace/
native-crash; fd exhaustion (peak 1354/65536); map exhaustion (peak 488/65530); `Thread.interrupt`
breaks classload (200k+ clean); a plain concurrency race (2 min 16-thread stress clean). Remaining, all
JDK/environment-level and not reproducible so far: a deeper URLClassPath/BuiltinClassLoader race only
manifest under the real 65-module forked-JVM classpath; a JDK 25 experimental-feature interaction; or a
transient runner FS hiccup.

Outstanding: faithful full-suite probe `32444846952` (all 65 modules in one forked JVM, the exact failing
workload; reproduce + fd/map/thread peaks). If it reproduces, root-cause with the live handle. If it does
not (likely, given the rarity and the clean local stress), this is a genuine value-underdetermined call
to bring to the user: (a) accept the rare, non-kyo flake and keep driving the streak (it passed 12/13
legs and every prior full run); (b) a scoped retry safety-net for a transient class-load LinkageError
leaf-panic, consistent with the accepted native-crash-retry precedent; or (c) a JDK-side change (e.g.,
drop the experimental `-XX:+UseCompactObjectHeaders`) pursued only with evidence. Recommendation pending
the probe.

## Update 4: faithful probe done, resources definitively refuted, no repro; conclusion + recommendation

Faithful full-suite probe `32444846952` (all 65 modules, one forked JVM = the exact failing workload):
- io_uring flake did NOT reproduce: `IoUringDriverAcceptTransientErrnoTest: 2 passed, 0 failed (65ms)`.
- Resource peaks at full-suite scale (limits fd=65536, max_map_count=262144, threads_ulimit=63882):
  FD-peak=1097, MAP-peak=1431, THREAD-peak=77. All far below limits. Resource exhaustion definitively
  refuted for the real workload.
- The probe's rc=failure is UNRELATED: a `/tmp/sbt_.../README.md:5:1 ... No warnings can be incurred
  under -Werror` doctest failure from running the full `ci-test.sh JVM test` (which includes doctest) in
  the custom job. Readme/doctest is a SEPARATE CI job in the real matrix, not the JVM test leg; this is a
  probe-invocation artifact, not a kyo regression.

Final characterization: a rare, non-deterministic, linux-x64-JVM transient CLASS-LOAD anomaly for a
class that is present on disk, in the forked test JVM, NOT caused by kyo code or this branch's changes.
Unreproducible across: the arm64-JVM leg of the same run (passed the identical test), the prior full run,
a kyo-net-only CI probe, this faithful full-suite CI probe, and 200k+ interrupted local first-loads.
Refuted with evidence (never memory): my code; missing class; sbt/Zinc cache; JVM shutdown;
memory/metaspace/native-crash; fd exhaustion; map exhaustion; Thread.interrupt-breaks-classload; a plain
concurrency race. No independent path remains to a better next attempt without a live reproduction that
has resisted every attempt.

Decision (value-underdetermined, CI-policy, needs the user): this failure is the SAME CATEGORY as the
mandate's accepted native-crash-retry safety net (rare, non-deterministic, infra/JDK-level, "a real bug
to fix eventually but doesn't disqualify a green"). Recommendation: extend that safety net to cover a
transient class-load `LinkageError`/`NoClassDefFoundError` leaf-panic on the JVM leg, scoped narrowly
(re-run only the affected tests via the existing --quick retry path; a genuine test failure still fails),
so the streak is robust to it. Alternative: accept it as a rare non-kyo flake and keep driving the streak
(it passed 12/13 legs and every prior full run). Not implementing either unilaterally: adding a retry to
a not-positively-root-caused failure without the user's OK would be relabeling-to-get-green, which the
no-reward-hack rule forbids; expanding the user-blessed native-retry policy is a scope-affecting change
that warrants confirmation.

## Update 5: ROOT CAUSE FOUND (evidence-backed) - `-XX:+UseCompactObjectHeaders` is buggy on JDK 25

The CI JAVA_OPTS enable `-XX:+UseCompactObjectHeaders` (COH, JEP 519), an EXPERIMENTAL, opt-in JDK 25
flag deliberately turned on in `f6d0418e2a [build] ... enable compact headers (#1700)`. It has CONFIRMED
OpenJDK correctness bugs, whose documented workaround is to disable COH:
- JDK-8380060 "C2: Wrong execution with COH and arraycopy" - WRONG EXECUTION (silent wrong results),
  "worked around by disabling COH or the arraycopy intrinsic."
- G1 concurrent-mark metadata corruption (SIGSEGV in the oop load barrier during G1PauseRemark).
- Shenandoah test failures with COH enabled.

kyo's test workload hits exactly these triggers: heavy concurrency, pervasive arraycopy (Chunk/Span/
arrays), `-XX:+UseG1GC`, and heavy `sun.misc.Unsafe::objectFieldOffset` (Scala `LazyVals$`, kyo's Unsafe
tier). A rare, non-deterministic `ClassNotFoundException` for a class that is PRESENT on disk is the
"impossible", corruption-like symptom this JIT/GC-metadata-corruption class of bug produces (a garbled
reference / class-name String / metadata word during a concurrent GC or a mis-JITted arraycopy). It fits
every established fact: rare, non-deterministic, present-class-fails-to-load, no resource exhaustion, not
interrupt, not kyo logic, JVM otherwise healthy, arm64 passed the same test (probabilistic corruption did
not hit that leg this run).

COH is set in: `.github/workflows/{ci,build,readme}.yml`, `.jvmopts`, `scripts/build.sh`,
`scripts/build-selftest.sh` (an assertion), and `build.sbt:215` (`Test / javaOptions` = the FORKED TEST
JVM, where the flake occurs). COH is a memory optimization, not load-bearing for correctness; disabling
it = standard JDK behavior.

FIX has a real tradeoff (a design decision, not a trivial flag removal). `build.sbt:212-215` shows COH
was added DELIBERATELY to cut fork heap pressure: "the test forks allocate heavily (kyo-tasty decodes 80k
symbols), so this cuts heap pressure where the forks run closest to their cap." The forks are pinned at
`-Xmx5g` and "two 5GB forks plus the ... driver fit the 16GB box." Removing COH reintroduces that heap
pressure and risks trading the rare class-load flake for kyo-tasty OOMs at the 5GB cap. So the fix is not
"just disable a perf flag"; it needs a heap-management decision, and it reverses the deliberate #1700
choice. This is escalated to the user, not done unilaterally.

Fix options:
- A. Disable COH + handle the heap: raise the fork cap (e.g. -Xmx6g) or drop the ForkedTestGroup fork
  parallelism to 1 (slower CI, no OOM risk, no COH corruption). Removes the confirmed-buggy flag.
- B. Keep COH + a scoped retry safety-net for the rare corruption-induced leaf-panic (mirrors the
  accepted native-retry; keeps the memory benefit; leaves the JDK bug latent).
- C. Keep COH + accept the rare flake (fragile streak).
Recommendation: A (remove the confirmed-buggy flag; it is a correctness issue, not just perf), with the
heap handled by dropping fork parallelism to 1 on CI (safest) or a measured heap bump, validated that
kyo-tasty does not OOM without COH. Pending user direction; whether removing COH causes a kyo-tasty OOM
at 5GB is testable (rung-2: kyo-tastyJVM/test without COH).

Sources: JDK-8380060 (bugs.openjdk.org/browse/JDK-8380060); JEP 519 (openjdk.org/jeps/519);
lincheck #915 (github.com/JetBrains/lincheck/issues/915).
