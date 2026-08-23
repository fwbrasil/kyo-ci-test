# Streak endgame: two JVM concurrency flakes

Full run `32552941932` was red only on two JVM legs; native/wasm/js/windows all green
(the scheduler native-thread-startup fix and the COH-decouple both validated). These two
JVM flakes are the remaining blockers.

## #67 — kyo-tastyJVM `TailrecAnnotationTest` intermittently finds 0 (linux-x64 JVM)

### Symptom
`Expected >= 1 symbol annotated with scala.annotation.tailrec but found 0`. Only under the
full `kyo-tastyJVM/test` suite (runner parallelism 8, ~245 concurrent test files); the
scala-library jar is always on the classpath, so this is a resolution failure, not a missing
jar. NOT COH-related: scala-library is a published artifact scanned in the COH-off fork.

### Root cause (empirically established; supersedes the initial pool hypothesis)
A reliable local reproduction (concurrent `ClasspathOrchestrator.init(standard, ...)` loads,
each computing the `@tailrec` count) fails ~1 load in 24 with:

  `(count=0, symbols=81972, tailrecIndexed=true, errors=0)`

i.e. scala-library is FULLY loaded (81972 symbols), `scala.annotation.tailrec` IS indexed
(`findClassLike` returns it), `cp.errors` is EMPTY (no file dropped) — and yet the count is 0.
So the failure is NOT a dropped jar and NOT the pool: it is a within-load `finalizeMerge`
determinism race, present even in a single `init` (its decoders run concurrently); concurrent
loads only add the CPU contention that surfaces it.

Mechanism: the `@tailrec` tycon is a `PHASE_B_ADDR_OFFSET` address reference, remapped by
`remapType` (`ClasspathOrchestrator.scala:1081-1086`) through `fr.addrToFinal`. `addrToFinal` is
built at `:1058-1061`:

    fr.addrMap.foreach { case (address, partialSym) =>
        val finalIdx = symbolIdMap.getOrElse(partialSym.id.toLong, -1)
        if finalIdx >= 0 then addrToFinal.put(address, finalIdx) }

It inserts ONLY when `finalIdx >= 0`. `scala.annotation.tailrec` is referenced by address from
many files, each holding its own partialSym instance; the merge de-dupes them to one final id in
`symbolIdMap`. Which instance wins is decided by the concurrent decoders' ARRIVAL ORDER at the
single-threaded merger. When a file's addrMap holds a `tailrec` partialSym instance that lost the
dedup (its `.id` absent from `symbolIdMap`), its `addrToFinal` entry is silently skipped, the
`@tailrec` reference keeps the sentinel, resolves Absent at query time, and no annotation matches
"scala.annotation.tailrec" -> 0. `tailrecIndexed=true` confirms `tailrec` IS materialised, under
a different id than the reference holds. This is the address-path analogue of the negId-collision
merge-order bug the sibling test already guards.

### Root cause NAILED (histogram evidence): doubled `scala.` prefix from a nested duplicate package
Instrumenting a 0-load's annotation resolutions: all 98 stdlib `@tailrec` annotations resolve to
**`scala.scala.annotation.tailrec`** (a DOUBLED `scala.` prefix), not `scala.annotation.tailrec`,
so `symbolsAnnotatedWith("scala.annotation.tailrec")` matches 0. This also explains
`tailrecIndexed=true`: `findClassLike` reads the precomputed `byFullName` index (finds the
correctly-merged `tailrec`), while annotation matching walks the owner chain at query time
(`typeFullNameString`->`computeFullName`) and hits a duplicate whose owner chain is
`tailrec -> annotation -> scala -> scala -> root`.

So there are TWO distinct `scala` package descriptors, one owning the other (nested). The
duplicate-package collapse (`ClasspathOrchestrator.scala:923-931`) groups by
`state.packagesByFullName` and picks `minBy(_.id)`; these two never merge because at registration
they carry different fullNames (`"scala"` vs `"scala.scala"`). The nested duplicate's creation is
decode-arrival-order-dependent (the collapse comment at `:910-922` already flags concurrency > 1
as only best-effort deterministic). The address-remap / negId theory is NOT the cause.

Reproduction: concurrent `ClasspathOrchestrator.init(standard, SoftFail, cpus)` loads; ~1 in 24
resolves the 98 `@tailrec` to the doubled name (see AnnotationFidelityConcurrencyTest tailrec case).

### Confirmed origin: two divergent computeFullName implementations
scala-library pickles a package clause as NESTED PACKAGE nodes (an outer `PACKAGE(scala)` containing
an inner `PACKAGE(scala.annotation)`); the inner node's path still decodes to the compound name
`"scala.annotation"` (`AstUnpickler.extractPackageName`, `:1208-1237`) while its per-file owner is
the outer `scala` package (`:311-315`). So a package's flat Name already carries the whole prefix
AND it is owner-nested. Two `computeFullName` implementations then disagree:

- Registration side (`ClasspathOrchestrator.scala:2644-2671`) STOPS at the first Package
  (`if c.kind == SymbolKind.Package then done = true`, `:2664`), because the flat name is the whole
  prefix. So `byFullName["scala.annotation.tailrec"]` is correct and `findClassLike` resolves
  (`tailrecIndexed=true`).
- Query side (`Tasty.scala:4250-4269`) had NO such guard: it walked the whole owner chain, so
  `tailrec -> scala.annotation(compound) -> scala -> root` produced `"scala.scala.annotation.tailrec"`.
  This is the path `symbolsAnnotatedWith -> annotationFullNameMatches -> typeFullNameString ->
  computeFullName` takes, hence 0.

Order dependence: `scala.annotation` is declared by several files that collapse to one canonical
descriptor; different files give it a different owner (nested file -> `scala`; flat file -> root),
and `descs(canonical).ownerId` is written last-write-wins over `for fr <- fileResults` in
decode-arrival order (`:1011-1018`, `fileResults` filled at `mergeOneInto:765`). Nested-owner file
last -> doubled -> 0; root-owner file last -> flat -> 98.

### Fix (applied): stop the query-side walk at a COMPOUND-named package
`Tasty.scala:4259-4260` computeFullName: stop the owner walk when `cur` is a Package whose flat
Name is compound (`n.indexOf('.') >= 0`). A compound package name (`"scala.annotation"`) already
carries its entire dotted prefix from root, so re-walking its owner can only double it; stopping
yields `"scala.annotation.tailrec"` regardless of whether that descriptor's owner is `scala` or
root, so the `:1011-1018` last write can no longer affect the computed name. Fixes the doubling for
every symbol at its source; the `minBy(_.id)` canonical choice and merge order become irrelevant.

NOTE: an initial version stopped at ANY Package. That regressed the Java path
(`ClasspathAnnotatedJavaTest`, `AnnotationLikeBaseTest`): Java classes use SIMPLE-named nested
packages (`java` > `lang`), which do NOT carry the prefix, so stopping at `lang` truncated
`java.lang.Deprecated` to `lang.Deprecated` and `symbolsAnnotatedWith("java.lang.Deprecated")`
returned 0. The compound-name discriminant is what distinguishes the two: compound = prefix already
embedded (stop); simple = walk the owner chain. Verified: full kyo-tastyJVM suite green, Java tests
pass, 0 doubled resolutions across many concurrent-load runs vs the prior ~4%.

### Per-scope jar pool (commit a1da7cd345): a hardening, not this fix
`JvmJarPool.active` was a process-global `AtomicReference[JarMappedReaderPool]`
(`ZipHandlePlatform.scala`) that all concurrent `withClasspath` scopes shared: concurrent
installs clobbered it and one scope's teardown (`set(null)` + `closeAll()`) yanked the pool from
scopes still loading, churning one-shot mmaps of the large scala-library jar. That is a genuine
cross-scope-state bug (perf churn; a theoretical drop path under extreme mmap pressure), fixed by
binding the pool per scope through an inheritable `Local`. But it is NOT the cause of the 0-count
above (which has no dropped files). Kept as hardening; the actual TailrecAnnotationTest fix is the
`finalizeMerge` remap determinism fix above.

## #66 — kyo-aeron `TopicInvariantsTest` "two concurrent Topic.stream consumers" hang (linux-arm64 JVM)

### Symptom
The leaf hangs to the 2-minute kyo-test timeout (fiber parked in `LockSupport.parkNanos`).
Two concurrent `Topic.stream[Int]("aeron:ipc")` consumers, same stream-id (`tag.hash.abs`);
one of the two IPC subscription Images never reaches connected. Slow-runner-specific
(emulated arm64 / windows); 15/15 PASS locally on fast native mac.

### Mechanism (mapped)
- The connect-wait in `Topic.stream` (`Topic.scala:295`) returns `backpressured`
  (`TopicBackpressureExhaustedException`) while `!subscriptionIsConnected`, retried by
  `Retry[TopicBackpressureException](retrySchedule)` on the default schedule
  (`Topic.scala:37`: `Schedule.linear(10.millis).min(Schedule.fixed(1.second)).jitter(0.2)`),
  which never exhausts. An Image that never connects parks forever. Only the ADD phase is
  bounded (`defaultAddTimeout = 10.seconds`); the connect phase is not. The unbounded wait is
  intentional for the legitimate "subscriber waits for a publisher" pattern, so a blanket
  connect-timeout would break product semantics.
- The two subscriptions are genuinely distinct (no subscription cache; `addSubscriptionDeadline`
  drives a fresh async add per call), so this is a connect race, not shared-subscription
  round-robin starvation.
- The test's probe/latch handshake is already maxed out (comment concedes "the test-level
  timeout is the only backstop for a genuinely unconnectable image"); the recurrence shows the
  test-level approach is exhausted (a prior test-side fix, task #34, already landed here).

### Diagnosis: CPU starvation of the embedded aeron driver/client conductor threads (a livelock)
Platform note: ALL platforms (including JVM) drive the C shim `kyo_aeron.c` via kyo-ffi (Panama on
JVM); there is no io.aeron Java-client path. So the shim's driver threading is in scope for the
arm64 JVM hang.

It is a LIVELOCK, not a hard deadlock: `defaultRetrySchedule` (`Topic.scala:37`) is infinite, so the
connect-wait retries forever with `Async.sleep`; a single thread dump catching all carriers parked
is what a sleep-dominated livelock looks like. Chain: consumer N releases `receivingN` on its first
received element (`.tap` before `.filterPure`); if one consumer's IPC image is never observed
`is_connected`, its latch never releases, the publisher probes forever and never offers the real
batch, and the OTHER (connected) consumer only ever sees filtered-out probes, so both `take(3)` and
both `consumer.get` block forever.

Root cause: the zero-config embedded driver `Topic.run` starts runs DEDICATED threading
(`aeron_driver_start(driver, false)`, `kyo_aeron.c:378-410`) = three agent pthreads
(conductor/sender/receiver) whose idle strategies busy-spin under load, PLUS the C client's own
conductor pthread. On a 2-4 vCPU / emulated / windows runner those four native threads starve the
kyo carriers and, critically, the client conductor that processes the image-available event which
flips `aeron_subscription_is_connected` (`:989-1001`). So the second same-(channel,streamId) IPC
image's connectivity is never observed within the 2-min window. Evidence: NO `TopicTransportFailedException`
(a client-liveness death would fire `fatalError` -> a terminal abort, not a hang); passes on
many-core mac, fails only on constrained hosts (a contention signature). Ruled down: IPC flow-control
(handshake guarantees both images got a probe before the batch; the publisher is unjoined so it alone
cannot hang the test), a shim concurrent-add race (adds serialize under `g_registry_mutex` +
`close_mutex`), and publisher-side connect-wait (needs only one image).

### Fix (applied): make the embedded driver CPU-frugal (SHARED threading)
`kyo_aeron.c` `kyo_aeron_driver_start`: `aeron_driver_context_set_threading_mode(ctx,
AERON_THREADING_MODE_SHARED)` before `aeron_driver_init`, so all driver agents run in ONE thread
instead of three. This frees CPU for the client conductor (which flips `is_connected`) and the kyo
carriers, so the second IPC image is observed connected promptly even on a constrained host. A
product-robustness fix for the zero-config path, NOT a timeout raise or test change; callers needing
DEDICATED low-latency drive an external driver via `Topic.run(aeronDir)`. Local (fast mac) cannot
reproduce the hang, so validation is: full kyo-aeron suite green locally (SHARED does not break any
aeron test), then arm64 CI (loop TopicInvariantsTest; the hang must be gone). If arm64 still hangs
after SHARED, escalate to the client-conductor idle strategy and the driver-counters instrumentation
the diagnosis named (two subscriber-position counters present + advancing distinguishes starvation
from a genuine driver-side single-image race).
