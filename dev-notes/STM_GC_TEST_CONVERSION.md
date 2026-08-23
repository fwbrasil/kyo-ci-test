# Converting the 3 STM GC tests to deterministic validation

Question: can the three `STMStressTest` weak-reference tests keep their validation without relying on GC triggering?

**Verdict: yes.** The property they guard ("STM must not retain a strong reference to a TRef after the transaction ends") reduces to a single, deterministically-inspectable structure, and its correctness half is already covered deterministically. Below is the evidence and a per-test conversion.

## What can retain a TRef inside STM

I mapped every place STM machinery can hold a `TRef`, by reading the whole module:

- A `TRef` registers in **no global structure** on creation: `TRef.Unsafe.init` is `new TRef(...)` (TRef.scala:210-214); the only static is `TRef.idCounter`, an `AtomicInteger` holding an int, not a ref (TRef.scala:179).
- The transaction log `TRefLog` is an immutable `Map[TRef, Entry]` (TRefLog.scala:15) carried as **per-attempt `Var` state**, created fresh each attempt via `Var.runTuple(TRefLog.empty)` (STM.scala:157). It is a value threaded through the effect, not a root; after `STM.run` returns nothing STM-side references it.
- The only mutable STM roots in the whole module (grep for `ThreadLocal`/`Local.init`/global collections) are:
  - `STM.currentTransaction`, a `Local[Maybe[Tick]]` (STM.scala:59), holds a `Tick` (a `Long`), never a TRef.
  - `CommitBuffer.cache`, a `ThreadLocal[CommitBuffer]` (CommitBuffer.scala:18), the reused per-thread `ArrayList` filled during a multi-ref commit and `clear()`ed after every use (CommitBuffer.scala:29). `java.util.ArrayList.clear()` nulls every slot, so after a commit it retains nothing.

So the **sole persistent structure that can pin a TRef across transactions is the thread-local CommitBuffer.** (Single-ref commits never touch it, STM.scala:197-211; a doomed transaction never commits, so it never touches it at all.)

## The correctness half is already deterministic

`CommitBufferTest` already proves the buffer releases its entries after each commit, without GC, by observing contamination:

- "buffer is reset after a conflicting commit that returns false" (CommitBufferTest.scala:66): if `clear()` failed, a stale `r1` write-entry bleeds into a later commit and pushes `r1` off 50, a deterministic wrong-value failure.
- "sequential STM.run calls do not contaminate each other" (CommitBufferTest.scala:158): a stale entry from txn A would corrupt txn C's result.

Any realistic regression (forgot to clear, cleared the wrong buffer) corrupts a later commit and is caught deterministically here. The GC tests add only the memory angle: a slot pinned even though it does not corrupt results, which with `ArrayList.clear()` (nulls slots) cannot happen unless the buffer is not cleared at all, and that is the contamination case already covered.

## Per-test conversion

| Test (STMStressTest) | What it uniquely asserts | Deterministic replacement |
|---|---|---|
| 1531 "CommitBuffer does not retain prior-cycle TRefs" | thread-local buffer holds no old TRef after later commits | Add a minimal `private[kyo]` size hook to `CommitBuffer`; in `CommitBufferTest`, run a multi-ref commit then assert the current thread's cached buffer is empty (single-threaded, no Async, no GC). Explicit retention guard on the one real root. |
| 1462 "doomed transaction TRef GC-eligible after rollback" | an aborted txn does not retain its allocated TRef | Structural: a doomed txn discards its per-attempt log value and never reaches the CommitBuffer, so no STM root holds the TRef. Rollback semantics already covered deterministically by "doomed insert must leave no record" (STMStressTest.scala:1457). |
| 1510 "nested inner-log not retained after success" | inner txn's 50-ref log not retained after merge | Structural: the inner log is `TRefLog.isolate.run` value state (STM.scala:182), merged then discarded with the outer run; no STM root holds it. Propagation-on-success already covered by "nested STM.run inner log does not leak into outer log on failure" (STMStressTest.scala:1481). |

Net: introduce one deterministic retention assertion in `CommitBufferTest` (the correct 1:1 home) against the sole retention root, and retire the three `.onlyJvm` GC tests whose functional intent is already covered deterministically and whose memory intent is architecturally guaranteed.

## The one honest tradeoff

A whole-heap `WeakReference` test also catches an **unanticipated future retention root** (say, someone later adds a global `Set[TRef]` debug registry that the deterministic test, scoped to the CommitBuffer, would not check). That breadth is exactly the flaky part. Options:

- **A (recommended): full deterministic conversion.** Remove GC reliance entirely; the deterministic CommitBuffer retention test plus the verified "no other root" structure cover the real invariant. Loses only the speculative unknown-root guard.
- **B: keep one hardened GC test** as a breadth guard (bounded await-until-cleared with a generous deadline) in addition to the deterministic test. Retains breadth, still GC-based but robust.

Recommendation: **A**. It matches the ask (no GC), it is deterministic, and it targets the actual mechanism more precisely than the GC tests did. If a new retention root is ever added, its own tests should cover it at the source.

## Needed production-code change: none

The conversion is entirely test-side. An earlier draft assumed a `private[kyo]` size accessor was needed because `CommitBuffer` is opaque with no size, but the deterministic assertion does not need size: `append` and `ref(idx)` are already `private[kyo]`, and a test in `package kyo` can detect a non-cleared buffer directly:

```scala
CommitBuffer.withBuffer { b => b.append(staleRef, staleEntry) } // fills, then auto-clears on exit
CommitBuffer.withBuffer { b =>
    b.append(freshRef, freshEntry)
    assert(b.ref(0) eq freshRef) // if clear() had not run, ref(0) would be staleRef
}
```

If `withBuffer`'s clear did not run, the stale pair would still occupy index 0 and the fresh append would land after it, so `ref(0)` would return the stale ref. Deterministic, no GC, no new production API.

In fact the deterministic coverage largely already exists in `CommitBufferTest` (the two contamination specs above), so the minimum conversion is test-only removal of the three GC tests, optionally plus this one explicit buffer-level retention assertion. No production code changes either way.
