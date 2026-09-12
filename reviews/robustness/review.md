# Live review: kernel robustness

Base `cdefdc9e60`, branch `robustness`, worktree `.claude/worktrees/robustness`. Range and tip are
re-derived by `package-check.sh` at packaging; the walk below is `sequence.json`, verified against
the tip by `sequence.py --verify`. Twenty edits, applied one at a time with the Edit tool, in the
order given here: sixteen in the kernel, two in `kyo-prelude` and `kyo-bench` for the consumer of
the multi-shot fix, and one each in `kyo-data` and `kyo-net` that the branch's CI matrix required.

## What this change is

Two bugs in one day shared a shape the kernel suite never built: a clause that suspends, an effect
answered by a region other than the clause's own, at-top against not-at-top. Each was invisible to
1513 kernel tests and surfaced in a consumer. The robustness list written after them has six items;
this change carries the four that are code or process, and the matrix it adds found a third defect on
its first run, which is fixed here as well.

| piece | what | where |
|---|---|---|
| A | downstream suites in the verification rule | `kyo-kernel/CONTRIBUTING.md`, item 13 |
| B | the shape matrix, its values derived by the fusion law | `EvalShapeTest.scala`, 320 cells |
| fix | multi-shot re-entry is not delimited, found by B | `Handler`, `ArrowEffect`, `Eval`, `ArrowEffectTest` |
| C | one re-entry path for the four loop tails | `Handler` |
| E | the benchmark class compiles against `ContextEffect.handle`'s signature | `KernelBench.scala` |
| F | `Choice.run` resumes through the kernel's re-entry, no inner `run` per alternative | `Choice.scala`, `ChoiceBench.scala` |

Piece D of the derivation, a reference interpreter, was not attempted: the night went to the defect
B found, and D stays on the list as the long-run item with B's matrix as the subset it would
generalise. The full derivation, with the equations, the surface and the forks, is `derivation.md`
beside this file; the parts a reviewer needs are folded in below.

### The defect B found

`handleContRepeated` with a clause that resumes twice, over two or more consecutive occurrences in one
region, does not terminate. Four leaves of work exhaust a 4 GB heap. Minimal shape:

    handleContRepeated(Tag[Ask], ask.map(a => ask.map(b => a + b)))(
        [C] => (_, k) => k(7).map(x => k(8).map(y => x + y)),
        a => a
    )                                                        // 60 expected; hangs at the base

The continuation handed to a clause is `kyo.cont.chain(contA.chain(contB))`: the suspension's own
continuation chained with the loop's registers. At the first occurrence the registers hold body maps,
which belong in `k`. But the clause's result is evaluated by the same loop inside the region, with its
own pending maps in those registers, so when its first resumption `k(7)` reaches the second
occurrence, the registers hold `x => k(8).map(...)`, the enclosing clause's second resumption, and it
is captured into the inner continuation. The inner clause applies that twice, so the outer clause's
remaining work runs twice; each run re-suspends at the second occurrence with a fresh clause whose
continuation captures the same pending work again. Geometric. Single-shot never shows it because the
captured work runs once either way; the README's multi-shot example has one occurrence.

The fix keeps every signature and today's `done` behaviour: when the handler repeats and does not
escape, the continuation handed to the clause is an arrow whose application re-enters a fresh region
through the handler's `resumed` twin, the same clause with `done` as identity. Entering the fresh
region stores the registers as that region's continuation, so the enclosing clause's pending work
sits outside the body again and a later occurrence captures body maps only. `done` still runs once,
at the outer region's end. Confined in allocation to those handlers: the single-shot cont arm pays
one virtual read of `repeated`, measured inside drift. A holding handler, `handleFirstRepeated`, is
not re-entered: it runs its clause at `done`, after the region has exited, and hands the
continuation out, so the capture cannot happen there and its holder re-establishes the region
before applying it, as `Choice.runStream` does per iteration. Candidate B, the delimited reading
with `done` per resumption and a changed `handleContRepeated` signature, is recorded in the
derivation as the alternative and is fork 4 below.

## The walk

One sentence per edit, the sentence to say when applying it.

**A. The rule**

1. `CONTRIBUTING.md`, item 13: a change to the evaluator, the handlers or the representation is
   verified by the kernel suite, `kyo-preludeJVM/test` and `kyo-coreJVM/test` together, with
   `Batch.run` as the consumer that composes the combinators in a shape the kernel suite does not, and
   a public-signature change also runs `kyo-kernelJVM/Jmh/compile`, since the benchmark sources are
   compiled by neither `test` nor CI.

**B. The matrix**

2. `EvalShapeTest.scala`, new: ten scenarios, every handler kind with every arm it has, each
   stating only what its clause does to one occurrence; the fusion law (`law`, `lawState`, `runs`,
   a fold of that one occurrence over n) derives the value and the clause-run count for n in 0 to
   3, and the at-top law (an inert `ContextEffect` binding or an inert `handleCont` region above the
   handler changes nothing) and the suspension law (a clause that first performs an effect handled
   outside the region answers the same) assert it across eight configurations, 320 cells, every one
   asserting a concrete value and none written by hand.

**The fix**

3. `Handler.scala`: `import kyo.bug`, for the next edit.
4. `Handler.scala`, `ContHandler`: `resumed` is the handler a repeated continuation re-enters through,
   this handler with `done` as identity, defined only by handlers whose clause resumes inside the
   region and `bug` otherwise;
   `reentering(k)` wraps a continuation so each application re-enters the region through
   `Pending.handle(k(x), resumed, ())`, an `Arrow.Step` deferring on a pending input in the same arm
   shape as `Arrow.apply`.
5. `ArrowEffect.scala`, `handleContRepeated`: the handler defines its `resumed` twin as a `val`, a
   `ContHandler` whose `run` delegates to the handler's own through the `outer` self alias and whose
   `done` is identity, built once at region entry.
6. `ArrowEffect.scala`, the recovering `handleContRepeated` overload: the same twin.
7. `Eval.scala`, cont arm: the continuation handed to the clause is `handler.reentering(raw)` when
   the handler repeats and does not escape, and `raw` otherwise, `raw` being today's chain or
   crossing; a holding handler runs its clause after the region has exited and hands the
   continuation out, so it is never re-entered, and `escaping` is read only when `repeated` holds.
8. `ArrowEffectTest.scala`: four cases pin the fix, a clause resuming twice over two occurrences
   (60), `done` running once at the outer end and not per resumption (1060, not 4060), three
   occurrences (180), and a throw after a second resumption reaching the outer `recover` (4), since
   the recovering overload's twin carries no `recover` of its own: its output is the body's `A`, and
   `recover` yields the region's `B`.

**C. One re-entry path**

9. `Handler.scala`: `attachReentryUnlessSettled(reentry, outcome)` is the tail the four loop sites
    spelled, a pending outcome gets the cont attached through `attachReentry`, a settled one passes
    through without building the arrow, `inline` so the fused walks expand it as the branch they
    carried.
10. `Handler.scala`: `attachReentryUnlessSettled2`, the same over the state-carrying outcome.
11. `Handler.scala`, `LoopHandler.answers`: the tail becomes the call.
12. `Handler.scala`, `LoopStateHandler.answers`: the tail becomes the call.
13. `Handler.scala`, `answersLoop`: the tail becomes the call; the `k.asInstanceOf` on the line is
    the one the site already carried.
14. `Handler.scala`, `answersLoopState`: the tail becomes the call.

**E. The benchmark class compiles**

15. `KernelBench.scala`: four `ContextEffect.handle(Tag[X])(...)` calls become
    `ContextEffect.handle(Tag[X], ...)`, the two-group signature every context handler has since
    `cdefdc9e60`; the benchmark sources had not been compiled since, which item 13 now prevents.
16. `KernelBench.scala`: two rows enter a multi-shot region, `repeatedClausesPayReentry` (the
    `suspensionBaseline` program under `handleContRepeated`, one region, ten thousand operations) and
    `repeatedRegionsPayEntry` (a region per operation), the rows that measure the twin built per
    region and the arrow built per operation, since no row entered such a region before.

**F. The consumer**

17. `Choice.scala`, `run`: the clause is `Kyo.foreach` over the alternatives applied to the
    continuation, flattened once; the inner `Choice.run(cont(v))` it wrapped each resumption in was
    the consumer re-entering a region by hand, which the continuation now does on every application,
    so keeping it would enter two regions per resumption.
18. `ChoiceBench.scala`, new in `kyo-bench`: `run` and `runStream` over ten sequential binary
    choice points, the rows that measure what Choice pays per resumption.

**Outside the kernel**

19. `Span.scala`, `updated`: an explicit index check raising the `IndexOutOfBoundsException` the
    scaladoc already promises, in `Chunk`'s shape and message; the JVM's array store delivered it,
    Scala.js treats the store as undefined behaviour and its fatal error ends the node process, and
    the Wasm backend traps with the same effect, which is how the branch's CI matrix found it, on
    every JS and Wasm job, through the `SpanTest` case on the branch's ancestry.
20. `RearmSurvivorsTest.scala`: the leaf arms write before read, so the write registration precedes
    the read registration in the poller driver's command order and is in the log by the time the
    read event can fire; armed the other way, the EOF event could be dispatched and the driver closed
    before the write registration was applied, which the linux-arm64 JVM job reported as a missing
    `registerWrite`. What the leaf pins, no rearm under edge-triggered registration, does not depend
    on the order.

The name in edits 9 and 10 is not `reenter`, the derivation's working name: `LoopStateHandler.reenter(state)`
already exists as the lifecycle hook a region receives on re-entry, and an uncurried overload of
`attachReentry` itself would differ from the arrow form `Eval` applies by a comma.

## Adjudication

Every construct `flags.sh` enumerates on the diff's added lines, plus the two classes it cannot emit,
each with a verdict that is a category from the cast ladder, a measurement, a `moved` provenance or
`REMOVE`. The table is `flags.md`; folded in here.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | ArrowEffect.scala:213 | `new Handler.ContHandler[I, O, E, A, A, S & S2]:` | allocation | measured: the `resumed` twin, built once per region entry. `repeatedRegionsPayEntry`, base against tip, `-f 3 -prof gc`: 16 bytes per region, 4.7 ns per region, +8.8% on a row that does nothing but enter and leave such regions (`bench/compare-rows-base-vs-AC.md`) |
| F2 | ArrowEffect.scala:274 | `new Handler.ContHandler[I, O, E, A, A, S & S2]:` | allocation | measured: as F1, the same object for the recovering overload |
| F3 | Handler.scala:106 | `new Arrow.Step[O[V], A, E & S]:` | allocation | measured, and the number is a regression on a row no consumer has: one arrow per operation answered by a clause that resumes inside its region, and one region node per application. `repeatedClausesPayReentry`, base against tip, `-f 3 -prof gc`: 64 bytes and 61 ns per operation, +615% (`bench/compare-rows-base-vs-AC.md`). Every other row is inside drift. The mechanism, the consumer's numbers and the decision are in the benchmark section and open ruling 5 |
| F4 | Handler.scala:110 | `case p: Pending[O[V], S3] @unchecked => Effect.defer(p, this, cont2)` | cast | erasure-forced: a typed pattern binding at the arm's type, the runtime test being `Pending` alone; the same arm as `Arrow.apply`'s and `Suspend.crossing`'s |
| F5 | Handler.scala:387 | `else outcome.asInstanceOf[Outcome[A < (E & S), B < S] < S]` | cast | moved: the pass-through cast `LoopHandler.answers` and `answersLoop` each carried at their tail, written once. Representation assertion: the two outcome types differ only in the `Continue` payload, and a settled outcome reaching the tail is not a `Continue` |
| F6 | Handler.scala:414 | `else outcome.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]` | cast | moved: as F5, for the state-carrying outcome |
| F7 | Handler.scala:479 | `result = attachReentryUnlessSettled[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o)` | cast | moved: the same `k.asInstanceOf` on the same site at the base; erasure-forced, the fused walk rebinding `k` per operation |
| F8 | Handler.scala:557 | `result = attachReentryUnlessSettled2[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o2)` | cast | moved: as F7 |
| H1 | Eval.scala, cont arm | `val continuation = if handler.repeated && !handler.escaping then handler.reentering(raw) else raw` | hot-path cost | measured, inside drift: `repeated` is a virtual `Boolean` on the arm every `handleCont` answer takes, `escaping` a second read only when `repeated` holds; no row outside the 5% band survives `-f 3`, base against tip over the whole class; the numbers are in the benchmark section |
| H2 | Handler.scala:93 | `def resumed: ContHandler[I, O, E, A, A, S] = bug(...)` | claim | justified by construction, with the reach stated: only `reentering` calls `resumed`, only `Eval`'s cont arm calls `reentering`, under `repeated && !escaping`; five sites override `repeated` at the tip, the two `handleContRepeated` handlers and their twins, which override `resumed`, and `handleFirstRepeated`, which also overrides `escaping`, so the arm never asks it |

## Evidence

Verification, on the tip with C:

| what | result |
|---|---|
| `kyo-kernelJVM/clean` then `compile`, batch | clean |
| `kyo-kernelJVM/Jmh/compile` | clean |
| `kyo-kernelJVM/test` | 1737 passed, 0 failed, 5 canceled (the `DebuggerTest` sessions, which cancel when the debugger is compiled out, as before) |
| `kyo-preludeJVM/test` and `kyo-coreJVM/test` | 2665 passed, 0 failed, run again after edit 17 with the same result, `ChoiceTest` 33 passed |
| `EvalShapeTest` | 320 cells, all green; the multi-shot cells hang at the base |
| `kyo-netJVM/testOnly RearmSurvivorsTest` | 2 passed after edit 20 |
| `SpanTest` | 237 passed on each of JVM, JS and Wasm after edit 19 |

Benchmarks. `KernelBench`, 49 rows, is the class; `package-check.sh` confirms it references
`kyo.kernel`. The rows the fix reaches by name, before running: every row answering through
`handleCont`, since the arm gained the `repeated` test, with `escaping` read only when it holds. The
rows C reaches: `handleLoopAnswersInPlace`,
`handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, and every fusion row, since the tails
are inside the fused templates.

A first round, the base (`dcadee780d`, the base plus edit 15 alone, so the class compiles) against
the fix alone, same session, back to back, `-f 1`, all 49 rows: `bench/compare-base-vs-A.md`. No
`handleCont` row outside the 5% band. Three rows outside it, none on a path the fix touches:

| row | -f 1 | -f 3 |
|---|---|---|
| `collectOverCollection` | -8.8% | +1.8%, inside error |
| `nestedPayloadsUnwrapInMaps` | -6.0% | +0.1%, inside error |
| `pureIterationViaArrow` | +9.4% | -8.1%, errors of 10 and 15 percent on the score |

`-f 3` on those three, both legs back to back: `bench/compare-base-vs-A-f3.md`, zero suspects.

Three legs in one session, back to back, `-f 1`, 49 rows each: the base (`dcadee780d`, the base
plus edit 15 so the class compiles), the fix alone (A, edits 3 to 8), and the tip (edits 9 to 14
added). The fix against the tip attributes C alone, one variable (`bench/compare-A-vs-AC.md`): zero
suspects; the rows the tails sit in, `handleLoopAnswersInPlace` +0.4%, `handleLoopFusesContinuation`
+0.0%, `statefulAnswersPaySuccessor` -0.9%, and every fusion row within 2%. The base against the tip
is the change's number (`bench/compare-base-vs-AC.md`): zero suspects. Rows outside the band in
either comparison, all inside their combined errors at `-f 1`, and their `-f 3` confirmation:

| row | base against tip, -f 1 | fix against tip, -f 1 | -f 3, base against tip | -f 3, fix against tip |
|---|---|---|---|---|
| `pureIterationViaArrow` | -6.8% | -6.3% | +6.9%, errors of 11 and 12 percent, noise | +3.8% |
| `foreignCrossingsAnsweredInPlace` | -6.2% | inside | +0.5% | -0.9% |
| `continuationBodiesFuse` | -5.9% | -5.9% | -1.8% | +4.4% |
| `bracketEnsuringOnly` | +12.0% | inside | -2.6% | +1.0% |

`-f 3`, three legs back to back on those four rows: `bench/compare-base-vs-AC-f3.md` and
`bench/compare-A-vs-AC-f3.md`, zero suspects in both.

**The multi-shot rows, and the regression they show.** Two rows added by edit 16 enter a
`handleContRepeated` region, which no row did before. Base against tip, `-f 3 -prof gc`,
`bench/compare-rows-base-vs-AC.md`:

| row | base | tip | per unit |
|---|---|---|---|
| `repeatedRegionsPayEntry`, 1000 regions of one operation | 53.7 us, 88,104 B | 58.4 us, 104,120 B | +4.7 ns and 16 B per region: the twin |
| `repeatedClausesPayReentry`, one region of 10,000 operations | 98.7 us, 480,121 B | 706.4 us, 1,120,181 B | +61 ns and 64 B per resumption: one region entered per application |

The second is a regression of 7.2 times on that row, and nobody has accepted it. The mechanism is
the design: every application of a repeated handler's continuation enters a region, and a region's
entry and exit is what `contextRegionsPayEntryExit` and `emittingClausesPayRegionRebuild` already
measure at 73 and 89 ns per region, so the row sits at the floor, not above it. No cheaper frame
would do, because what stops an inner occurrence from capturing the clause's pending work is a
handler on the stack above that work: a crossing packs every stack entry between an occurrence and
the handler that answers it into the continuation. The base was flat on this row because it was
wrong on the shape the matrix found. The tree's one consumer of the repeated handlers, `Choice.run`,
paid the delimiter by hand, wrapping each resumption in a fresh `Choice.run`; with the kernel
delimiting, that wrapper is a second region per resumption, and edit 17 removes it. What that nets
for Choice is the `ChoiceBench` comparison below; the decision on the price itself is open ruling 5.

`ChoiceBench`, ten sequential binary choice points, throughput, higher is better, `-f 3`, base
against tip in one session, `bench/compare-choice-base-vs-AC.md`:

| row | base | tip | |
|---|---|---|---|
| `run` | 1,273 ops/s | 4,063 ops/s | 3.2 times faster: the kernel's twin region replaces the base's inner `Choice.run` per resumption and its second flatten, and edit 17 drops that inner region |
| `runStream` | 5,208 ops/s | 5,042 ops/s | -3.2%, inside the combined error |

A first draft re-entered every repeated handler, `handleFirstRepeated` included. On that draft
`run` was already 2.2 times faster than the base before edit 17 (1,280 against 2,860 ops/s), and
`runStream` was 24% slower (4,936 against 3,721 ops/s), because every resumed computation carried a
twin region on top of the fresh `handleFirstRepeated` the stream's loop installs per iteration, a
region that could not prevent anything. That measurement is what produced the condition in edit 7,
and `runStream` is back at the base with it.

CI, on `fwbrasil/kyo-ci-test`: the full matrix (linux-x64, linux-arm64, windows-x64; JVM, JS, Native,
Wasm), run 34672876184 on the gated-matrix commit, found every JS and Wasm job dying in `kyo-data`'s
`SpanTest`: the branch's ancestry adds an out-of-bounds case for `Span.updated`, whose scaladoc
promises `IndexOutOfBoundsException` while the code relied on the JVM's array store; on JS the fatal
undefined-behaviour error escapes the harness and node exits, on Wasm the store traps with the same
effect. Fixed by edit 19, reproduced locally before the fix (the same run-terminated exception)
and verified after it: `SpanTest` 237 passed on each of JVM, JS and Wasm, the out-of-bounds case
included. The matrix's final state is reported with the sweep below.

The module-by-module sweep of every sbt project on the final tree is the last item of the overnight
work and is reported in the summary that proposes this review.

## Open rulings

1. **The overload pair.** `Arrow.apply(v: A)` against `apply[S2](v: A < S2)`: at an erased input the
   static argument type picks the semantics. Evidence: the `Batch` regression, the two `PollTest`
   ascriptions, and the contract cases in `ArrowEffectTest`. The matrix in B does not reach the
   pair: its scenarios type their continuations. Declared, not touched.
2. **The `PollTest` ascriptions** are workarounds under the ruling of 2026-08-28 and are left in
   place because their root cause is fork 1.
3. **D not attempted**, per the rule in the derivation; the outcome is reported above.
4. **Fork 4, ruled A by the author under the overnight autonomy.** The alternative, candidate B, is
   `done` per resumption with a changed `handleContRepeated` signature, the standard delimited
   reading. Edit 8's second case (1060, not 4060) is the line that pins the ruling; reversing it is a
   public-surface decision. B would not change the cost in ruling 5: it re-enters a region per
   resumption as well.
5. **The per-resumption region is the price of a kernel that delimits.** Accepting A means every
   multi-shot resumption enters a region, 61 ns and 64 bytes, and a clause that resumes exactly once
   under `handleContRepeated` pays it where the base ran flat; such a clause belongs under
   `handleCont`, and no consumer in the tree has that shape. The alternative is the base's contract,
   documented rather than enforced: a repeated clause with pending work between resumptions must
   re-enter a region itself, as `Choice.run` did, and one that does not hangs. Recommendation: A,
   with edit 17, because the kernel is then correct by construction for the shape the matrix found
   and the one consumer pays less than it paid before: `Choice.run` 3.2 times faster than the base,
   `runStream` unchanged.
