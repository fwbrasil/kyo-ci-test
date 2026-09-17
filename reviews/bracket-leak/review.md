# Live review — bracket-leak fix

Applies the fix delta to `effervescent-painting-backus` (currently at `778f630155`, the tip of the first live
review). Each hunk is applied with the Edit tool, one at a time, with the one sentence below. Full delta:
`reviews/bracket-leak/fix.diff` (net +86 / −125, 5 files). Derivation: `reviews/bracket-leak/derivation.md`.

Order is dependency-first: the kernel change and its tests, then the aeron consumer and its test.

## 1. `kyo-kernel/.../internal/Eval.scala` — remove the stranded-Ensure recovery

1a. Remove the recovery helpers (`leftmost`, `ensuring`, `settledResource`, `firstStep`, `ensuringInCont`).
    *"These applied a bracket `Ensure` for an acquire that stopped mid-way; a stopped acquire owes nothing, so
    they go."*

1b. Defer case: a settled value under a deferral owns nothing (`case _ => ()`), and tighten the comment.
    *"With no recovery, a settled value the deferral holds owns no region, so the walk stops there."*

1c. Park case: remove the `settledResource`/`ensuringInCont` block.
    *"The Park walk collects the regions actually installed in `entries`; it no longer reconstructs one that was
    never installed."*

1d. Top-level settled arm `case settled => ()` (was `ensuring(settled, cont)`).
    *"Same: a settled top-level value owns nothing."*

1e. Keep the tagged-form Safepoint save/restore; only correct its comment (drop the stale "applies a region's
    `Ensure`" clause, since the recovery is gone).
    *"The recovery is gone, so the walk applies no `Ensure`, but the tagged walk still runs on a just-interrupted
    fiber's stopped Safepoint and needs a live state or #1735's regions and #1928's drain are lost; keep the
    save/restore and correct the comment. (An earlier pass removed this as 'dead' on a JVM-only check; CI showed
    #1928 hangs on JS without it, and the JS/linux-x64 bisect confirms 6d87653b91 with it present passes #1928.)"*

## 2. `kyo-kernel/.../BracketTest.scala` — owns-nothing regression test

2a. Replace the two recovery tests ("releases a bracket stranded by a polling map", "declines a stranded
    release") with one: "a bracket whose acquire is interrupted before it finishes owns nothing".
    *"The contract for the generic bracket: interrupted-before-finish owns nothing; the inner region still runs
    its finalizer."*

## 3. `kyo-core/.../ScopeInterruptTest.scala` — the Sync.ensure acquire, race-robust

3a. Rename the case to "…runs that finalizer, and the bracket never over-releases", assert
    `acq == fin && rel <= acq && acq > 0` (was `rel == acq`), and rewrite the comment.
    *"`Sync.ensure`'s own transform is inside the acquire, so an interrupt there stops it mid-step, and whether the
    value reaches the outer bracket before the stop is a race whose outcome differs by platform and run: the JVM and
    Native preempt finely so the acquire usually stops short (`rel` near 0), JS usually lets it complete
    (`rel == acq`), and arm64 JS lands in between. The base tip's recovery masked this by over-releasing on the JVM
    too. The only cross-platform invariant is that the inner region-from-start always releases (`fin == acq`) and the
    bracket never over-releases (`rel <= acq`); the deterministic owns-nothing case is in the kernel `BracketTest`.
    (An earlier `rel == 0` and a `rel == (if Platform.isJVM then 0 else acq)` both guessed a fixed value for a racy
    outcome; CI on all four JS/Native targets settled it.)"*

## 4. `kyo-aeron/.../Topic.scala` — own the produced resource at the Done poll

4a. Publication: the token-guard becomes an outcome finalizer that frees the token before Done and closes the
    publication on an abnormal exit after Done; capture the publication when the Done poll clears `tokOwned`.
    *"On Done the driver takes the token and hands back the publication; the guard owns it from that step until
    the use's finalizer takes over, so an abandonment after Done closes it instead of leaking it."*

4b. Publication registration: `map` -> `ensureMap`.
    *"The clean hand-off must be atomic too: register the use's `Sync.ensure` in the step the publication
    arrives, not a poll later."*

4c./4d. Subscription: the symmetric guard + `ensureMap`.
    *"Symmetric to the publication add."*

## 5. `kyo-aeron/.../AeronTransportTest.scala` — comments to the new mechanism

5a. The two leak-on-interrupt tests are already un-pended on the branch; update their comments to the
    guard/`ensureMap` mechanism (no assertion change).
    *"Same tests, now green by owning the resource at production rather than by a kernel recovery."*

## Evidence (local, this branch)

- kernel `kyo-kernelJVM/test`: 1747 passed, 0 failed (5 canceled, 2 pending).
- core `kyo-coreJVM/test`: 0 failed (16 pre-existing `pendingUntilFixed`).
- aeron `kyo-aeronJVM/testOnly kyo.AeronTransportTest`: 34 passed, 0 failed (both leak-on-interrupt tests; the
  "does not free a token already taken" no-double-close test still passes).
- #1735 passes and is no longer corrupted by the recovery.
- CI: dispatched on the branch (see overnight-log.md for the run link).
- Benchmarks + compile times: see `reviews/bracket-leak/bench.md`.
