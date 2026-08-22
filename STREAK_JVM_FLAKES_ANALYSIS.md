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

### Fix (in progress)
Make the address->final-id resolution deterministic regardless of merge order. Candidate: when
`symbolIdMap.getOrElse(partialSym.id, -1)` misses while building `addrToFinal`, fall back to
resolving the partialSym by its fully-qualified name via `state.fullNameIndex -> symbolIdMap`
(the same fullName path `globalizeUnresolvedNegIds`/`negRemap` already uses at `:1044-1050`), so a
deduped-away instance still maps to the canonical final id. Exact site being confirmed against the
merge/dedup construction; verified against the reproduction before commit.

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

### Status / open question
Needs a faithful reproduction (emulated arm64 or under-load full aeron suite) to determine WHY
the second concurrent same-stream-id IPC Image never forms while the publication is
demonstrably alive (probe keeps it alive until both connect). Leading candidate: media-driver
conductor starvation or cross-test resource contention under the concurrent aeron suite on a
slow runner. Fix direction (product-level image-formation robustness vs. driver-resource /
test-concurrency) is a strategic call to make AFTER reproduction, not a timeout raise.
