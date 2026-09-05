# A class that is on disk, in a healthy JVM, that the classloader cannot find

Written for a reader with no prior context. The branch is `ci-stabilization` at `7453571414`, a CI
stabilization branch whose goal is three consecutive green full-matrix runs.

## The failure

Full linux-x64 JVM leg, run `33253764606`. One suite failed:

```
=== IoUringDriverAcceptTransientErrnoTest ===
Exception in thread "kyo-scheduler-worker-15" java.lang.NoClassDefFoundError:
    kyo/net/internal/posix/IoUringDriverAcceptTransientErrnoTest$$anon$8
  at ...IoUringDriverAcceptTransientErrnoTest.$anonfun$2(IoUringDriverAcceptTransientErrnoTest.scala:101)
  at kyo.kernel.internal.Safepoint$Ensure.apply(Safepoint.scala:153)
  ...
Caused by: java.lang.ClassNotFoundException: ...IoUringDriverAcceptTransientErrnoTest$$anon$8
  at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:580)
--- IoUringDriverAcceptTransientErrnoTest: 0 passed, 1 failed  (0ms)
```

Line 101 is `Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))` inside the suite's
`withInjectingDriver` helper. The error is the FIRST line after the suite banner, at 0ms, and repeats on
three different scheduler workers.

It is intermittent and has recurred across the drive for some time. It passes in isolation: a rung-2 run of
the whole `kyo.net.internal.posix.*` package on linux-x64 reported this suite `2 passed, 0 failed (57ms)`.

## The tree already had a diagnosis for this, and it is now disproven

`build.sbt:219-226` attributes THIS EXACT signature to compact object headers:

> "Compact object headers (JEP 519) are buggy on JDK 25 under the forked test workload ... hit JDK-8380060
> and a G1 concurrent-mark metadata corruption, **surfacing as a rare ClassNotFoundError for a class present
> on disk (the io_uring test flake)**. Force COH OFF in the forks"

That mitigation landed in `c33c995093` (2026-08-24) and `git merge-base --is-ancestor c33c995093 HEAD`
confirms it IS an ancestor of the tip `7453571414` the failing run `33253764606` was built from.

That alone is only an inference (the build.sbt LINE is present), so the flag was checked directly:
`sbt 'show kyo-netJVM/Test/javaOptions'` lists `-XX:-UseCompactObjectHeaders`. sbt passes
`Test/javaOptions` to the forked test JVM, and a fork does NOT inherit the driver's `JVM_OPTS`, so the fork
really does run with COH off. **The recorded attribution does not hold**, and the `build.sbt` comment
documents a root cause that has since been falsified.

TRAP FOR ANYONE RE-CHECKING THIS FROM A CI LOG: the failing run's log contains 30 occurrences of
`-XX:+UseCompactObjectHeaders` (COH ON) and ZERO of the OFF flag. That is NOT evidence the fork ran with COH
on. Those 30 are the driver's `JVM_OPTS`/`JAVA_OPTS` env lines being echoed repeatedly; the build keeps COH
ON for the driver deliberately (it needs the header savings for the Scala.js/Wasm linker). Fork
`javaOptions` are not echoed to the log at all, so the OFF flag's absence there proves nothing either way.
Read `Test/javaOptions`, not the log.

Missing this on the first pass was a real gap: the information was in the build definition the whole time,
and a report calling itself self-contained had no business omitting the tree's own prior attribution for the
same failure signature.

## Eight things it is NOT, each with evidence

1. **Not the known `Sync.ensure`-on-Abort bug.** That bug is the finalizer NOT RUNNING when the body
   short-circuits via `Abort`. Here the ensure machinery IS running: `Safepoint$Ensure.apply` -> `ensureLoop`
   are live frames, and the failure is that it cannot LINK a class while doing so. Ordering confirms the
   direction: the `NoClassDefFoundError` is at log line 30270 and
   `LeafPoolPanic: work body failed to complete its promise` at 30297, so the class-load failure causes the
   incomplete promise, not the reverse. This matters because pending it under that bug's exception would
   mislabel a class-loading defect and hide it from the person fixing the Abort bug.
2. **Not missing codegen locally.** A clean local compile emits `$$anon$1` through `$$anon$17`, including
   `$$anon$8`.
3. **Not missing codegen** (conclusion stands, but the probe was the wrong argument for it). The dispatched
   probe (`33258352818`) compiled a DIFFERENT build whose anon listing disagrees with both the local build
   and the failing run, so it cannot speak for the failing run's tree. The conclusion survives on the failing
   run's own evidence instead: the frame `$anonfun$2(...:101)` referencing `$$anon$8`, and the executing
   `$$anon$1` at `:134`, are products of the same compilation unit as `$$anon$8.class`. A compile that
   emitted the reference emitted the class file.
4. **Not a compile racing the test run.** The last `compiling ... Scala sources` line is at log line 3023;
   the failure is at 30270.
5. ~~Not JVM exhaustion or teardown.~~ **RETRACTED, this elimination was invalid.** Its evidence was the
   absence of exhaustion messages in the log plus "the classloader worked before and after". Both arguments
   fail against the actual JDK code, verified in the JDK 25.0.3 source shipped on this machine
   (`java.base/jdk/internal/loader/BuiltinClassLoader.java`):

   ```java
   private Class<?> findClassOnClassPathOrNull(String cn) {      // :686
       ...
           try { return defineClass(cn, res); }
           catch (IOException ioe) {
               // TBD on how I/O errors should be propagated     // :693, SWALLOWED
           }
       return null;
   }
   ```
   and the caller at `:580`, the exact frame in our stack: `if (c == null) throw new ClassNotFoundException(cn);`

   So ANY transient I/O failure reading that one `.class` file (EMFILE, ENFILE, EIO, short read) is silently
   converted into a bare `ClassNotFoundException` with no cause and no log trace. The absence of "Too many
   open files" is exactly what this failure mode PREDICTS, not evidence against it: heap, metaspace and disk
   exhaustion announce themselves, an fd or I/O failure at this site does not. And "worked before and after"
   has almost no discriminating power, because JVMS 5.4.3 resolution-error caching poisons the constant-pool
   entry after the first failure, so a single microseconds-wide window at the first resolution of `$$anon$8`
   explains the 0ms, both leaves failing, and the repeats across workers, with no further disk access at all.

   Only teardown remains eliminated (67 suites passed after). Resource pressure does NOT.
6. **Not stale incremental state carried between commits.** `.github/actions/setup` caches only
   `~/.cache/coursier`, `~/.ivy2/cache` and `~/.cache/kyo-browser`. No `target/` directory is cached, so
   every CI run compiles from scratch.
7. **Not a jar replaced under the running JVM.** `exportJars` appears nowhere in `build.sbt` or `project/`,
   so test classes are on the classpath as plain directories. The "cache a jar index, swap the jar, later
   loads fail" mechanism cannot apply.
8. **Not compilation bleeding into the run process.** `ci-test.sh` drives the JVM leg as three separate sbt
   processes: `testKyo --phase compile-main --all JVM`, then `--phase compile-test --all JVM`, then
   `testKyo --all JVM`. Compilation finishes before the process that runs the tests starts.

## One resolved sub-puzzle, so it is not mistaken for a second bug

The `Exception in thread "kyo-scheduler-worker-N"` banners and the repeats across workers 15, 14 and 2 are
propagation, not cause. `NoClassDefFoundError` is a `LinkageError`, which kyo treats as fatal;
`FatalFiberTest` documents the policy in its own leaf name ("LinkageError completes the IOPromise with a
Panic before the worker rethrows"). Each retry hits the same missing class and kills another worker. The
line `java.lang.LinkageError: simulated NoClassDefFoundError` elsewhere in the same log is `FatalFiberTest`
doing this deliberately, and is unrelated.

## One unexplained observation, recorded rather than dropped

Three builds of the SAME commit do not agree on which anonymous classes exist:

| build | anon set |
|---|---|
| clean local compile | `$$anon$1` .. `$$anon$17` |
| CI probe `33258352818` | `$$anon$2` .. `$$anon$17`, NO `$$anon$1` |
| the failing run | `$$anon$1` was EXECUTING (frame at `:134`); `$$anon$8` was missing |

The others are not renumbered; one member is simply absent. That is more consistent with a lambda being
emitted as an anonymous class in one build and an `invokedynamic` in another than with a numbering shift.
It may be a JDK or inline-expansion difference between the local and CI toolchains rather than the same
fault. It has not been chased.

## The fd-pressure variant is weak: the ceiling is 65536

The leading mechanism after the judge's review was a transient I/O failure in the class read, with fd
exhaustion as its most attractive form (this is the fd-heaviest fork in the build, and it only fails on a
loaded leg). The fork's actual ceiling was unknown, so it was measured directly on the runner
(run `33265413452`, `mode=custom` on ubuntu-latest):

```
SOFT=65536 HARD=65536
Max open files            65536                65536                files
fs.file-max=9223372036854775807
nr_open=1048576
```

HotSpot raises the soft limit to the hard limit, so the forked test JVM runs with 65536. Reaching that
would take a severe descriptor leak, which kyo-test's own end-of-run fd probe is built to catch and did not
report, and the suites on both sides of the failure passed. So the fd sub-variant is UNLIKELY, and the part
of the mechanism that made it attractive (fd-heaviest fork, loaded leg) does not survive at this ceiling.

What survives of the mechanism is the narrower claim, which is still sound and still the best available:
the JDK converts ANY `IOException` during the class read into a bare `ClassNotFoundException` with no cause
and no log line, so a transient read failure of any origin (EIO, a short read, a page-cache or filesystem
hiccup) is invisible by construction. Those are rarer than fd exhaustion, which is why this measurement
matters: it removes the common explanation and leaves only uncommon ones.

## Where this leaves it

No mechanism. Eight eliminations is real narrowing, but it is not a diagnosis, and further hypotheses
generated from this same evidence have hit diminishing returns. The observation that would actually
discriminate is a failing run WITH the on-disk class set captured at the moment of failure, and the failure
is intermittent so it cannot be forced.

## The strategic question

The drive's goal is three consecutive green full-matrix runs. This defect is intermittent, has recurred over
a long period, and currently blocks the climb at rung 3.

1. Is there an elimination above that is weaker than it looks, or a mechanism the eight do not cover?
2. Is the differing anon-class set across builds a lead worth pulling, or a toolchain artifact?
3. What is the cheapest instrument that captures on-disk state at the moment of failure, given the failure
   is intermittent and appears only on a loaded full leg?
4. Strategically: block the drive until this is root-caused, instrument and continue, or proceed to rung 4
   carrying it as a known open defect? Note the standing rule that every failure is to be fixed at root, and
   the competing reality that re-rolling an intermittent until green banks a green over an unexplained
   defect.
