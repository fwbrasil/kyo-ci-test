# Derivation — bracket ownership on completion, aeron owns its resource at production

## The contract (the reviewer's ruling)

> we do not provide guarantees when the acquire stops/fails but if it completes we ensure closing

A bracket owes a release only if its acquire **completes** (produces the value the release is owed). An acquire
that **stops** (interrupt) or **fails** produced no value the outer bracket owns, so it owes nothing. The inner
regions a stopped acquire installed (an inner `Sync.ensure`) still run their own finalizers; that is their own
guarantee, not the outer bracket's.

## What was wrong

The live-reviewed branch (`778f630155`) carried a **stranded-`Ensure` recovery** in `Eval.release`
(`settledResource` / `firstStep` / `ensuringInCont` / `ensuring` / `leftmost` + a Park-case block). On
abandonment it walked the parked computation for a bracket `Ensure` left un-applied and applied it with the
value the remainder had settled to. That is a release for an acquire that **stopped mid-way** — precisely the
guarantee the contract forbids. Two consequences, both observed:

- It could not serve the real `Sync.acquireReleaseWith` shape at all (the `Ensure` sits behind `Sync.ensure`'s
  own `Abort.get` transform, which the walk declines to reach), so `ScopeInterruptTest`'s `rel == acq`
  assertion failed anyway.
- It **corrupted** the racing-scopes drain (#1735): applying a stranded `Ensure` during the abandonment walk
  produced item loss ("every racer puts it back" lost an item).

The aeron leak was a **separate** bug in the wrong layer: `Topic.publish`/`stream` produce the publication or
subscription at the add's Done poll but register its closer only after the add returns, in a trailing `map`. An
interrupt taken at Done (where the driver has already allocated the resource) parks before the closer installs,
leaking the resource.

## The equation

`Bracket(acquire)(use)(release)` = run `acquire`; when it produces a value `a`, install a region owning
`release(a, _)` **atomically** (`ensure`, the poll-free arrow); run `use(a)` under it. Abandonment tears down
the installed regions it finds. There is no third case: a value the acquire never produced is a region never
installed, and a region never installed is nothing to tear down.

So the recovery has no counterpart in the equation. It is not an accelerator of any combinator; it is a second,
contradictory answer to "what does a stopped acquire owe". Removing it makes the evaluator match the equation:
the walk **reads** the installed regions (Park entries, context handlers, owed remainders) and runs their
releases, and **runs nothing** of the computation.

A resource a consumer opens by a side effect *inside* its acquire (aeron's driver-side publication) is the
consumer's to own where it is produced, because only the consumer knows the resource exists before the acquire
returns. `ensureMap` is the poll-free registration for exactly that: own it in the step it arrives.

## The fix, per file

- **`Eval.release`**: delete the recovery helpers and the Park-case recovery block; a settled value owns nothing.
  **Keep** the tagged-form Safepoint save/restore around the abandonment walk, and correct its comment (drop the
  now-stale "applies a region's `Ensure`" clause). The recovery removal (contract change) is independent of the
  save/restore: the tagged walk still runs on a just-interrupted fiber's stopped Safepoint and needs a live state,
  or #1735's regions and #1928's drain are lost. An earlier pass removed the save/restore as "dead" on a JVM-only
  check; CI then showed #1928 hangs on JS/Native without it, and the JS/linux-x64 bisect confirms `6d87653b91`
  (recovery removed, save/restore present) passes #1928 while HEAD without it hangs. See `1928-regression.md`.
- **`BracketTest`**: the two recovery tests become one, "a bracket whose acquire is interrupted before it finishes
  owns nothing" (the outer release does not run; the inner region's finalizer does).
- **`ScopeInterruptTest`**: the `rel == acq` case becomes race-robust. At the base tip the recovery masked the
  stopped-acquire case by over-releasing on the JVM too (`rel == acq` everywhere); removing it exposes the real
  behavior, and CI on all four JS/Native targets shows the outer-bracket release count is not deterministic per
  platform: JVM and Native preempt finely so the acquire usually stops short (`rel` near 0), x64 JS lets it
  complete (`rel == acq`), arm64 JS is a race (`rel == acq - 1` in one run), and a run may land anywhere between.
  Both a `rel == 0` and a `rel == (if Platform.isJVM then 0 else acq)` assertion are the same mistake: a fixed value
  for a racy, platform-skewed outcome. The assertion becomes `fin == acq && rel <= acq && acq > 0`: the inner
  `Sync.ensure` (region from the start) always runs its finalizer, and the bracket never over-releases. The
  deterministic owns-nothing invariant is guarded in the kernel `BracketTest`.
- **`Topic`**: the add's token-guard also owns the produced resource. On Done the driver takes the token and
  hands back the resource; the guard owns it until the use's finalizer takes over on a clean hand-off, and closes
  it on an abnormal exit after Done. The outer registration uses `ensureMap` so the clean hand-off is atomic too.
- **`AeronTransportTest`**: the two leak-on-interrupt tests (already un-pended on the branch) pass; comments
  updated to the guard/`ensureMap` mechanism.

## Surface

Changes are confined to `Eval.release` (kernel), its two tests, and aeron's `Topic` + its test. No node kind, no
new type, no new combinator. `Sync`, `Scope`, `Bracket.apply`/`ensuring`/`ensuringWith` are unchanged: the
contract was already theirs; the recovery was the thing that broke it.
