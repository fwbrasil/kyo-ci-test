# Kernel benchmarks — kyo vs ZIO / cats-effect / Turbolift

Run in the isolated `bracket-leak-explore` worktree on the fix (`bfba740693`). Config: JMH AverageTime, µs/op,
`-f 1 -wi 3 -i 3` (wide screen; losing rows re-confirmed at `-f 3` in each deep-dive case below). Lower is better.
Raw JSON: `scratchpad/kbench.json`. Competitor is the best (fastest) of the three per row.

The fix itself is a cold-path change (`Eval.release` / abandonment), off every KernelBench hot row, so it does not
move these numbers; this is the standalone kyo-kernel-vs-libs investigation.

## Where kyo loses (wide screen, worst first)

| ratio (kyo/best) | row | kyo µs | best competitor | zio | cats | turbolift |
|---|---|---|---|---|---|---|
| 7.50x | foreignCrossingsAnsweredInPlace | 891.97 | turbolift 118.88 | 503.20 | 1086.27 | 118.88 |
| 7.31x | emittingClausesPayRegionRebuild | 117.30 | turbolift 16.05 | 29.97 | 93.63 | 16.05 |
| 6.71x | foreignCrossingsPayRotation | 975.04 | turbolift 145.26 | 478.45 | 1081.58 | 145.26 |
| 5.65x | effectfulIterationViaLoop | 262.23 | turbolift 46.44 | 248.16 | 382.44 | 46.44 |
| 4.23x | contextReadsUnderBindings | 53.48 | turbolift 12.65 | 23.33 | 94.41 | 12.65 |
| 3.88x | deferBindUnderTrailingMap | 43.03 | turbolift 11.09 | 19.03 | 82.60 | 11.09 |
| 2.81x | suspensionBaseline | 123.14 | turbolift 43.86 | 241.77 | 387.61 | 43.86 |
| 2.76x | partialSuspensionBaseline | 120.91 | turbolift 43.80 | 254.92 | 643.68 | 43.80 |
| 2.46x | sharedHandlerPaysDispatch | 167.18 | turbolift 67.85 | 87.73 | 578.00 | 67.85 |
| 2.40x | statefulAnswersPaySuccessorAltRef | 103.92 | turbolift 43.29 | 260.56 | 191.10 | 43.29 |
| 2.29x | suspensionBaselineAltEnv | 102.76 | turbolift 44.83 | 449.45 | 538.13 | 44.83 |
| 2.29x | suspensionBaselineAltInstall | 102.17 | turbolift 44.63 | 271.11 | 536.23 | 44.63 |
| 2.14x | trailingMapsStayLinear | 274.69 | turbolift 128.13 | 321.61 | 787.69 | 128.13 |
| 1.77x | deepRecursionPaysRescuesOnly | 54.38 | turbolift 30.68 | 42.90 | 109.99 | 30.68 |
| 1.75x | deferBindUnderIdleHandler | 20.20 | turbolift 11.56 | 18.88 | 78.83 | 11.56 |
| 1.71x | deepRecursionNoRescue | 2.22 | turbolift 1.30 | 1.86 | 13.39 | 1.30 |
| 1.65x | pureIterationViaArrow | 91.31 | turbolift 55.51 | 190.28 | 127.11 | 55.51 |
| 1.62x | effectfulIterationViaArrow | 121.16 | turbolift 74.80 | 276.86 | 229.67 | 74.80 |
| 1.54x | deepRecursionOneRescue | 2.96 | turbolift 1.93 | 2.79 | 13.90 | 1.93 |
| 1.39x | deferBindPerStep | 15.90 | turbolift 11.48 | 19.08 | 57.58 | 11.48 |
| 1.09x | bracketPerRound | 141.39 | zio 129.73 | 129.73 | 251.89 | 303.95 |

Turbolift (delimited continuations, no effect-row machinery) is the fastest competitor on nearly every row; kyo
beats ZIO and cats on most of the losers anyway (e.g. suspensionBaseline: kyo 123 vs zio 242 vs cats 388), so the
gap is specifically kyo-vs-Turbolift. kyo wins/ties Turbolift on the 28 fused rows (evalFixedOverhead, fusion*,
continuationBodiesFuse, handleLoop*, iteration-via-loop for the pure case, ...): fusion is where kyo is designed
to be fast, and it is.

The losers cluster by mechanism: **crossings/rotation**, **region rebuild on emitting clauses**, **the suspension
atom** (suspensionBaseline + AltEnv/AltInstall/partial), **context reads under bindings**, **deferBind under a
trailing map / idle handler**, **effectful iteration**, **deep recursion**. Deep-dive cases below take these in
order of gap size and shared root.

## Deep dives

### Case 1 — `suspend.map(f)` does not fuse; it allocates a `DeferWith` (the dominant loser)

**Isolation.** `suspensionBaseline` = `ask.map(a => loop(i+a))` is 2.81x; `suspensionFusesContinuation` =
`askWith(a => loop(i+a))` (identical otherwise) is competitive with Turbolift. So the gap is entirely the
`.map` over a suspension vs the fused `askWith`. The same shape drives `trailingMapsStayLinear` (2.14x, extra
`.map(x=>x)`), `deferBindUnderTrailingMap` (3.88x), `effectfulIterationViaLoop` (5.65x), `effectfulIterationViaArrow`
(1.62x), `contextReadsUnderBindings` (4.23x, `ContextEffect.suspend.map`), `partialSuspensionBaseline` (2.76x),
`suspensionBaselineAlt*` (2.29x), and half of the crossing cost.

**Mechanism (code, `Pending.map` line 69).** `v.map(f)` runs `run(v, Arrow.id)`; when `v` is a `Pending` (a
suspension is), `shouldDefer` is true and it allocates `new Pending.DeferWith{ value = v; apply = run(v2, cont.chain) }`
wrapping the suspension. `ArrowEffect.suspendWith` (line 66) instead builds a `SuspendArrowWith` with `f` fused into
the node (`cont = this`, `apply` transforms the answer in place). So `ask.map(f)` = suspension node + a `DeferWith`
wrapper the evaluator must reach and step; `askWith(f)` = one node. The delta is a `DeferWith` allocation and an
extra evaluator hop per suspension step. Turbolift (delimited continuations, no per-suspension continuation node)
does not pay it.

**Profiling (`-prof gc`).** `suspensionBaseline` = 480120 B/op; `suspensionFusesContinuation` = 240096 B/op —
exactly 2x. The `.map` over the suspension adds one `DeferWith` node per step (the fused path allocates only the
suspension node). Time on the same run: ~143 vs ~42 µs/op. So the fused API `askWith`/`suspendWith` is the measured
**ceiling** for this shape, and it is already competitive with Turbolift.

**Optimization experiment (attempted, reverted).** In `map`'s defer path I tried fusing a bare `SuspendArrow`
(cont = `Arrow.id`) into a `SuspendArrowWith` instead of wrapping it in a `DeferWith` — i.e. making
`suspend(tag,in).map(f)` build what `suspendWith(tag,in)(f)` builds. The extra check sits only on the already-slow
defer branch, so the hot fused-map path (v not `Pending`) is untouched. It hit a real type wall: reconstructing the
`SuspendArrowWith[i,o,e,st,…]` from a `case s: SuspendArrow[i,o,e,st,?,?]` pattern does not carry the
`e <: ArrowEffect[i,o]` bound (a GADT-existential limitation), and satisfying it needs casts the kernel rulings
forbid without sign-off ("a cast that compiles and is wrong"). Reverted.

**Clean fix direction (candidate, separate change).** Give `Suspend` a type-preserving rebuild — a `private[kyo]`
method on the node that chains an `Arrow[B,C,S2]` into its own `cont` and returns the suspension of type `C`
(the node has its own `I,O,E,A` in scope, so no existential recovery, no cast). `map`/`flatMap` over a suspension
then call it instead of allocating a `DeferWith`. Expected effect: `suspensionBaseline` -> the 240096 B/op / ~42 µs
ceiling, carrying `trailingMaps`, `effectfulIteration`, `contextReads`, `partialSuspension`, and part of the
crossings with it. This is a kernel hot-path + representation change, so it is its own derivation + live review, not
folded into the bracket-leak fix. In the meantime, hot suspension loops that want the ceiling today use
`askWith`/`suspendWith` (as `suspensionFusesContinuation` does).

### Case 2 — foreign crossings (`foreignCrossingsPayRotation` 6.7x, `AnsweredInPlace` 7.5x)

`ask.map(a => ask2.map(t => loop))` under an inner `Ask` handler nested in an outer `Ask2` handler: each inner
`Ask` answer has to cross the `Ask2` region and rotate back.

- **Allocation (`-prof gc`)**: `PayRotation` 2,400,374 B/op, `AnsweredInPlace` 1,520,286 B/op — the heaviest rows.
- **CPU (async-profiler, cpu)**: the hot stacks are `Eval.loop` and `Handler$ContHandler.answering` (the
  per-crossing dispatch), not user code.
- **Mechanism (code, `Suspend.crossing`)**: answering an operation that must re-enter regions the evaluator already
  left builds, per crossing, a `Park` slice carrying the continuation + `resume`, a `Stack.Snapshot` of the regions
  to reinstall, and an `Arrow.Step`; the `ask.map` `DeferWith` (case 1) rides on top. So the 2.4 MB/op is
  snapshot+park+step per crossing plus the case-1 wrapper. Turbolift captures a native delimited continuation and
  pays neither the snapshot nor the wrapper.
- **Verdict**: partly case 1 (the `ask.map` wrapper, addressable by the case-1 fuse), partly structural to the
  "effects cross a foreign handler by parking a slice + snapshotting regions" design. The structural part is a real
  design question (could a crossing that is answered-in-place, as `AnsweredInPlace` is, avoid the full snapshot?),
  its own investigation. async-profiler *alloc*-site confirmation was blocked by sbt's `;` command-chaining eating
  the profiler option separators; the gc B/op + cpu stacks + the `crossing` code name it without it.

### Case 3 — `emittingClausesPayRegionRebuild` (7.3x), compute-bound not allocation

A `handleLoop` whose clause emits (`tick.map(t => Loop.continue(t))`) under an outer `Tick` handler.

- **Allocation**: 232,336 B/op — the same order as the fused baseline, i.e. NOT allocation-driven.
- **Mechanism**: the cost is path length. Each emitted value rebuilds the region as a fresh `Handle` value (the
  effectful-clause model: the clause lives outside the region it serves, so `done`/emit rebuilds it). 7.3x slower at
  flat allocation means the rebuild + re-dispatch per emit is the cost, on `Eval.loop`.
- **JIT (`-XX:+PrintInlining`)**: the handler's answer method (`KernelBench$$anon$…::answers`) is **693–707 bytes**
  and is refused as "hot method too big", so the region rebuild per emit is a real call, not inlined. (Also refused,
  as expected by design: `Eval.drainRemainders` 428 B, `drainCleanOwn` 262 B, `Stack.dump` 166 B, `Safepoint.resolve`
  226 B, `Eval.park` 152 B — the drive/teardown are deliberately large.)
- **Verdict**: structural to the emitting-clause model, and the answer method is over HotSpot's hot-inline budget.
  Addressing it means shrinking that method (move the region rebuild's cold work out of line so the hot answer fits
  the budget) — a targeted, separate optimization. No allocation to remove.

### Case 4 — `effectfulIterationViaLoop` (5.65x)

`Loop(...)(i => ask.map(a => Loop.continue(i+a)))` under a handler: 1,120,226 B/op. This is case 1 (`ask.map` ->
`DeferWith` per step) compounded with the `Loop` continue/done wrapping. The case-1 fuse addresses the `ask.map`
half; the `Loop` half is the `Loop.Continue`/`Loop.Done` currency (already a documented concession with pinned
tests). `effectfulIterationViaArrow` (1.62x) is the same `ask.map` cost without the `Loop` currency, which is why it
is milder.

## Compilation times (`CompileBench`, in-process dotc per fixture, ms/op, fork 1)

| fixture | ms/op | fixture | ms/op |
|---|---|---|---|
| Baseline | 142.9 | ForComprehensions | 822.5 |
| TagDerivation | 361.5 | MapChainWide100 | 1141.0 |
| MapChain10 | 388.6 | NestedMaps | 1195.6 |
| SuspendSites | 446.0 | ForCompDeep25 | 1701.9 |
| ForCompShallow | 459.9 | (MapChainDeep100 ~ see json) | |
| EffectRowGenerics | 460.6 | | |
| FlatMapChains | 640.0 | | |

The heavy fixtures are the deep for-comprehension (`ForCompDeep25` 1.70 s) and the wide/nested map chains
(`MapChainWide100` 1.14 s, `NestedMaps` 1.20 s): `Frame` derivation and the `CanLift` summon per site are the
per-site macro cost the module compiles under (this is the fragile-equilibrium suspension cascade the kernel skill
describes). These are a baseline for the bracket-leak fix, which adds no new macro sites (it removes code), so it
does not move them. Raw JSON: `scratchpad/cbench.json`.

## Summary of the investigation

kyo is fast where it fuses (28 rows at/ahead of Turbolift) and pays where it cannot: a `.map` over a suspension
allocates a `DeferWith` (case 1, the widest cause, ceiling = `askWith`), and crossing a foreign handler
snapshots+parks per crossing (case 2, the deepest gap). The one clean, high-leverage kernel optimization surfaced is
the **case-1 suspend-map fuse** (a type-preserving `Suspend` rebuild), which would move `suspensionBaseline`,
`trailingMaps`, `effectfulIteration`, `contextReads`, `partialSuspension`, and the `ask.map` share of the crossings
toward the fused ceiling. It is a hot-path + representation change, so it is its own derivation + live review, kept
entirely out of the bracket-leak fix.
