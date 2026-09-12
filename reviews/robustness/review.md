# Live review: kernel robustness

Base `cdefdc9e60`, branch `robustness`, worktree `.claude/worktrees/robustness`. Range and tip are
re-derived by `package-check.sh` at packaging; the walk below is `sequence.json`, verified against
the tip by `sequence.py --verify`. Thirty edits, applied one at a time with the Edit tool, in
the order given here: twenty-one in the kernel and the build (its test runner and the benchmark
configuration), two in `kyo-prelude` and `kyo-bench` for the consumer of the multi-shot fix, two in
`kyo-data` and `kyo-bench` for the Span fix and its number, and two in `kyo-net`, the last four being
what the branch's CI matrix required.

## What this change is

Two bugs in one day shared a shape the kernel suite never built: a clause that suspends, an effect
answered by a region other than the clause's own, at-top against not-at-top. Each was invisible to
1510 kernel tests and surfaced in a consumer. The robustness list written after them has six items;
this change carries the four that are code or process, and the matrix it adds found a third defect on
its first run, which is fixed here as well.

| piece | what | where |
|---|---|---|
| A | downstream suites in the verification rule, and CI compiling the benchmark sources | `kyo-kernel/CONTRIBUTING.md`, item 13; `project/TestKyo.scala` |
| B | the shape matrix, its values derived by the fusion law | `EvalShapeTest.scala`, 320 cells |
| fix | multi-shot re-entry is not delimited, found by B | `Handler`, `ArrowEffect`, `ArrowEffectTest`, `BracketTest` |
| C | one re-entry path for the four loop tails | `Handler` |
| E | the benchmark class compiles against `ContextEffect.handle`'s signature | `KernelBench.scala` |
| F | `Choice.run` resumes through the kernel's re-entry, no inner `run` per alternative | `Choice.scala` |

Piece D of the derivation, a reference interpreter, was not attempted: the night went to the defect
B found, and D stays on the list as the long-run item with B's matrix as the subset it would
generalise. The full derivation, with the equations, the surface and the forks, is `derivation.md`
beside this file; the parts a reviewer needs are folded in below.

**H**, the release walk delivering the payload: found by reading during the fork 1 analysis, a
bracket whose resource is itself a computation had its release handed the `Nested` box on
abandonment with budget, because the walk offered the waiting `Ensure` the settled value as found
rather than unnested as every settled arm does. Reproduced first (edit 27, failing on the tip with
the release seeing `Nested@...`), fixed with one unnest (edits 25 and 26); `EvalTest` and
`BracketTest` 233 passed, and the three suites run again on this tip.

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

The fix keeps every signature and today's `done` behaviour, and it lives in the handler that
declares it repeats: the two `handleContRepeated` overloads wrap the continuation they hand the
clause, in `run`, so that each application re-enters a fresh region under the handler's re-entered
form, the same clause with `done` as identity. Entering the fresh region stores the registers as
that region's continuation, so the enclosing clause's pending work sits outside the body again and
a later occurrence captures body maps only. `done` still runs once, at the outer region's end.
`Eval` and `ContHandler` are unchanged by the fix, so the single-shot path pays nothing, and
`handleFirstRepeated` is untouched by construction: a holding handler runs its clause at `done`,
after the region has exited, and hands the continuation out, so the capture cannot happen there and
its holder re-establishes the region before applying it, as `Choice.runStream` does per iteration.
Two drafts preceded this shape, one wrapping in `Eval` under `handler.repeated` and one gating that
on `!escaping`; the derivation records what each cost and why the rehearsal moved the wrap here.
Candidate B, the delimited reading with `done` per resumption and a changed `handleContRepeated`
signature, is recorded in the derivation as the alternative and is fork 4 below.

## The walk

One sentence per edit, the sentence to say when applying it.

**A. The rule**

1. `CONTRIBUTING.md`, item 13: a change to the evaluator, the handlers or the representation is
   verified by the kernel suite, `kyo-preludeJVM/test` and `kyo-coreJVM/test` together, with
   `Batch.run` as the consumer that composes the combinators in a shape the kernel suite does not;
   and the benchmark sources, which no test task compiles, are compiled by CI's compile-test phase,
   with `kyo-kernelJVM/Jmh/compile` as the local check before a signature change is pushed.
2. `TestKyo.scala`, `execute`: the compile-test phase's tasks gain the benchmark compile for the
   selected modules that carry one.
3. `TestKyo.scala`, `jmhCompileTasks`: a module whose project carries the `jmh` configuration gets
   `Jmh/compile` in compile-test, the modules found from the build rather than listed, which is the
   mechanical form of what item 13 says and is the step that would have caught edit 18.
4. `build.sbt`, kyo-kernel: the `Jmh` configuration compiles into its own class directory, because
   scaladoc reads every TASTy file in the main one and a benchmark class there, against a framework
   only the jmh classpath carries, failed the doc build once `Jmh/compile` had run, a sequence the
   step above makes ordinary.
5. `EffectTrace.scala`: the scaladoc's `[[splice]]` resolved to nothing, `splice` living on the
   companion; it links `[[EffectTrace.splice]]`, the one warning the kernel's doc build emitted.

**B. The matrix**

6. `EvalShapeTest.scala`, new: ten scenarios, every handler kind with every arm it has, each
   stating only what its clause does to one occurrence; the fusion law (`law`, `lawState`, `runs`,
   a fold of that one occurrence over n) derives the value and the clause-run count for n in 0 to
   3, and the at-top law (an inert `ContextEffect` binding or an inert `handleCont` region above the
   handler changes nothing) and the suspension law (a clause that first performs an effect handled
   outside the region answers the same) assert it across eight configurations, 320 cells, every one
   asserting a concrete value and none written by hand.

**The fix**

7. `Handler.scala`: two helpers join the `Handler` object beside `attachReentry`. `reentered(outer)`
   is the handler a re-entered region runs under, `outer` with `done` as identity so the re-entered
   region yields the body's value and `outer`'s `done` still runs once, and `repeated` as `outer`
   is, so what a re-entered region owes is held across the clause's resumptions and released where
   that region ends. `reentering(k, reentered)` is the continuation wrapped so that each application
   re-enters, `Pending.handle(k(x), reentered, ())`, an `Arrow.Step` deferring on a pending input in
   the same arm shape as `Arrow.apply` and unnesting a settled one.
8. `ArrowEffect.scala`, `handleContRepeated`'s scaladoc: each application of the continuation
   re-enters the region, a bracket acquired inside one resumption is released where that
   resumption's region ends, before the clause applies the continuation again, and only one acquired
   in the extent the region holds waits for the region's end; the recovering overload's scaladoc
   inherits this by reference.
9. `ArrowEffect.scala`, `handleContRepeated`: the handler builds its re-entered form once, as a
   `val`, and wraps the continuation it hands the clause in `run`, so the re-entry lives with the
   handler that declares it repeats and nothing in `Eval` or `ContHandler` changes.
10. `ArrowEffect.scala`, the recovering overload: the same, and its re-entered form carries no
   `recover`, since `recover` yields the region's `B` where the re-entered region yields the body's
   `A`, so a throw inside a re-entered region unwinds to this handler's `recover`.
11. `ArrowEffectTest.scala`: five cases pin the fix, a clause resuming twice over two occurrences
   (60), `done` running once at the outer end and not per resumption (1060, not 4060), three
   occurrences (180), a throw after a second resumption reaching the outer `recover` (4), and a
   hundred thousand sequential operations under the repeated handler, which now nests a region per
   resumption, stack safe like every sibling handler's depth case.
12. `BracketTest.scala`: a bracket acquired inside one resumption and captured by an inner
   occurrence's continuation is released once, where the re-entered region ends, before the outer
   clause resumes again, which is what `reentered`'s `repeated` buys and where the release moved to.

**C. One re-entry path**

13. `Handler.scala`: `attachReentryToPending(reentry, outcome)` is the tail the four loop sites
    spelled, a pending outcome gets the cont attached through `attachReentry`, a settled one passes
    through without building the arrow, `inline` so the fused walks expand it as the branch they
    carried. C is item 4 of the robustness list, one re-entry path rather than four spellings of it;
    it is not part of the fix and travels with it because the tails are where a loop region's
    re-entry is attached, the same rule the fix applies to a repeated region.
14. `Handler.scala`: `attachReentryToPending2`, the same over the state-carrying outcome.
15. `Handler.scala`, `LoopHandler.answers`: the tail becomes the call.
16. `Handler.scala`, `LoopStateHandler.answers`: the tail becomes the call.
17. `Handler.scala`, `answersLoop`: the tail becomes the call; the `k.asInstanceOf` on the line is
    the one the site already carried.
18. `Handler.scala`, `answersLoopState`: the tail becomes the call.

**E. The benchmark class compiles**

19. `KernelBench.scala`: four `ContextEffect.handle(Tag[X])(...)` calls become
    `ContextEffect.handle(Tag[X], ...)`, the two-group signature every context handler has since
    `cdefdc9e60`; the benchmark sources had not been compiled since, which edits 2 and 3 now catch.
20. `KernelBench.scala`: three rows enter a multi-shot region, `repeatedClausesPayReentry` (the
    `suspensionBaseline` program under `handleContRepeated`, one region, ten thousand operations),
    `repeatedRegionsPayEntry` (a region per operation) and `repeatedRegionsPayEntryRecovering` (the
    same through the recovering overload), the rows that measure the re-entered handler built per
    region and the arrow built per operation, since no row entered such a region before.

**F. The consumer**

21. `Choice.scala`, `run`: the clause is `Kyo.foreach` over the alternatives applied to the
    continuation, flattened once; the inner `Choice.run(cont(v))` it wrapped each resumption in was
    the consumer re-entering a region by hand, which the continuation now does on every application,
    so keeping it would enter two regions per resumption.

**Outside the kernel**

22. `Span.scala`, `updated`: an explicit index check raising the `IndexOutOfBoundsException` the
    scaladoc already promises, in `Chunk`'s shape and message; the JVM's array store delivered it,
    Scala.js treats the store as undefined behaviour and its fatal error ends the node process, and
    the Wasm backend traps with the same effect, which is how the branch's CI matrix found it, on
    every JS and Wasm job, through the `SpanTest` case on the branch's ancestry.
23. `RearmSurvivorsTest.scala`: the leaf arms write before read, so the write registration precedes
    the read registration in the poller driver's command order and is in the log by the time the
    read event can fire; armed the other way, the EOF event could be dispatched and the driver closed
    before the write registration was applied, which the linux-arm64 JVM job reported as a missing
    `registerWrite`. What the leaf pins, no rearm under edge-triggered registration, does not depend
    on the order.
24. `RearmSurvivorsTest.scala`: the leaf also asserts that the write registration precedes the read
    registration in the log, so the order it now rests on is pinned rather than assumed.

The name in edits 13 and 14 is not `reenter`, the derivation's working name: `LoopStateHandler.reenter(state)`
already exists as the lifecycle hook a region receives on re-entry, and an uncurried overload of
`attachReentry` itself would differ from the arrow form `Eval` applies by a comma. Nor is it
`attachReentryUnlessSettled`, the name it carried until the rehearsal read the "unless" as an edge
case: `attachReentryToPending` says what it does to a pending outcome, and a settled one passes
through.

**H. The release walk delivers the payload**

25. `Eval.scala`, the comment above `leftmost` in `release`: the value is offered unnested, as
   every settled arm delivers it, since a value that is itself a computation is carried boxed.
26. `Eval.scala`, `ensuring`: `step(Nested.unnest[Any](v))`, the walk's delivery made equal to the
   `Ensure` arm's own.
27. `EvalTest.scala`: the reproduction, a bracket acquiring a computation, parked before its region
   is installed, abandoned through the budgeted release; the release must receive the resource
   itself, `eq`, not its box.

**G. The tooling**

The kernel skill's pipeline names three files that did not exist; this change wrote them and they
are new files, applied whole. In the user's tree they are written under
`.dev/kyo-kernel/.claude/skills/kernel/`, untracked, where the repository's rule (`76c675ea94`)
puts the kernel skill's artifacts; the branch tracks them under `kyo-kernel/.claude/skills/kernel/`
for preservation only.

28. `flags.sh`, new: emits one row per construct of concern on a diff's added lines (casts, `Any`,
   `@unchecked`, allocations on hot paths, terminology), the skeleton `flags.md` adjudicates.
29. `package-check.sh`, new: re-derives every mechanical claim a package makes (tip, commit count,
   surface, clean tree, flag count against the table, the walk reproducing the tip, and whether
   each benchmark class named references the package under review), one OK, CHECK or STALE per line.
30. `rulings.md`, new: the reviewer's objections verbatim and dated, the rehearsal lens's rubric,
   with the 2026-09-12 entry from this change's status report.

## Adjudication

Every construct `flags.sh` enumerates on the diff's added lines, each with a verdict that is a
category from the cast ladder, a measurement, a `moved` provenance or `REMOVE`. The table is
`flags.md`; folded in here.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | Handler.scala:333 | `new ContHandler[I, O, E, A, A, S]:` | allocation | measured on this shape: the re-entered handler, built once per region entry by `Handler.reentered(outer)` and captured by the handler's `val`. One session, both legs back to back on an idle machine, `-f 3 -prof gc`, base against tip: time in `bench/compare-rows-base-vs-tip-clean.md`, allocation from the `gc.alloc.rate.norm` lines of `bench/base-rows-4.log` and `bench/tip-rows-4.log`. `repeatedRegionsPayEntry` (a `handleContRepeated` region per operation, 1000 per invocation): 55.0 us against 58.8 us, +6.8%, 3.8 ns per region; `repeatedRegionsPayEntryRecovering` (the same through the recovering overload): 58.0 us against 59.2 us, +2.0%, 1.1 ns per region; both rows 88,104 B/op against 104,120 B/op, 16 bytes per region, the one object. The full-class session agrees (`bench/compare-base-vs-tip-f3.md`: `repeatedRegionsPayEntry` +3.7%) |
| F2 | Handler.scala:352 | `new Arrow.Step[O[X0], A, E & S]:` | allocation | measured on this shape, and the number is a regression on a row no consumer has: one arrow per suspension a repeated handler answers, built in the handler's `run`, and one region entered per application. `repeatedClausesPayReentry` (`suspensionBaseline` under `handleContRepeated`, 10,000 operations, each resumed once), the same session as F1: 101.4 ± 1.1 us against 637.4 ± 10.8 us, 54 ns per operation, 6.3 times (`bench/compare-rows-base-vs-tip-clean.md`); 480,121 B/op against 720,140 B/op, 24 bytes per operation (`bench/base-rows-4.log`, `bench/tip-rows-4.log`; 64 on the earlier shape, whose wrap lived in `Eval`: 1,120,181 B/op in `bench/AC-rows.log` against the same base in `bench/base-rows.log`). The full-class session's `-f 3` confirmation agrees, 99.8 against 636.1 us, 6.4 times (`bench/compare-base-vs-tip-f3.md`). The mechanism, the consumer's numbers and the decision are in the package's benchmark section and its open ruling 5; this row is not closed by this table |
| F3 | Handler.scala:356 | `case p: Pending[O[X0], S3] @unchecked => Effect.defer(p, this, cont2)` | cast | erasure-forced: a typed pattern binding at the arm's type, the runtime test being `Pending` alone; the same arm as `Arrow.apply`'s and `Suspend.crossing`'s |
| F4 | Handler.scala:399 | `else outcome.asInstanceOf[Outcome[A < (E & S), B < S] < S]` | cast | moved: the pass-through cast `LoopHandler.answers` and `answersLoop` each carried at their tail, written once. Representation assertion: the two outcome types differ only in the `Continue` payload, and a settled outcome reaching the tail is not a `Continue` |
| F5 | Handler.scala:426 | `else outcome.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]` | cast | moved: as F4, for the state-carrying outcome |
| F6 | Handler.scala:491 | `result = attachReentryToPending[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o)` | cast | moved: the same `k.asInstanceOf` on the same site at the base; erasure-forced, the fused walk rebinding `k` per operation |
| F7 | Handler.scala:569 | `result = attachReentryToPending2[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o2)` | cast | moved: as F6 |
| F8 | Eval.scala:646 | `case step: Arrow.Ensure[Any, Any, Any] @unchecked => collect(step(Nested.unnest[Any](v)), Arrow.id, 0)` | cast | erasure-forced: the walk is over `Arrow[Any, Any, Any]`, existential from out here (the base's own comment on `leftmost`), so the unnest answers at `Any`, the same `Nested.unnest[Any]` spelling as `answersLoop`'s settled arm; the `@unchecked` typed pattern is the base's line, unchanged |
| F9 | Eval.scala:646 | the same line | carrier | moved: the `Any` carriers are the base's, `Arrow.Ensure[Any, Any, Any]` on this line since the walk was written; the added `[Any]` is the unnest's answer type at the walk's erased currency, `Nested.unnest` being `Any => A` by construction |

No hand-added row: `Eval` changes one line, on the abandonment walk and on no evaluation path (F8), so no hot-path cost sits outside the script's classes, and no
member with a partial default exists for a claim to cover.

## Evidence

Verification, on the tip with C:

| what | result |
|---|---|
| `kyo-kernelJVM/clean` then `compile`, batch | clean |
| `kyo-kernelJVM/Jmh/compile` | clean |
| `kyo-kernelJVM/test` | 1836 passed, 0 failed, 5 canceled (the `DebuggerTest` sessions, which cancel when the debugger is compiled out, as before); 1510 at the base plus the 320 cells, the five cases of edit 11 and the case of edit 12 |
| `kyo-preludeJVM/test` and `kyo-coreJVM/test` | 2665 passed, 0 failed, on the tip with edit 21, `ChoiceTest` 33 passed |
| `EvalShapeTest` | 320 cells, all green; the multi-shot cells hang at the base |
| `kyo-netJVM/testOnly RearmSurvivorsTest` | 2 passed with edits 23 and 24, on this machine and in the linux-arm64 CI container (`scripts/build.sh --env podman-ci --arch arm`, the environment the failure came from), the ordering assertion included |
| `testKyo --dry-run --phase compile-test --modules kyo-kernelJVM,kyo-dataJVM JVM` | the pass reads `kyo-dataJVM/Test/compile; kyo-kernelJVM/Test/compile; kyo-kernelJVM/Jmh/compile`, the benchmark compile for the module that has one and not for the one that does not |
| `SpanTest` | 237 passed on each of JVM, JS, Wasm and Native after edit 22 |
| `kyo-kernelJVM/testOnly EvalTest BracketTest` | 233 passed on the tip with edits 25 to 27; the reproduction fails before edit 26 with `Nested@... was not the same instance as Kyo(...)`. The three suites on this tip: `kyo-kernelJVM/test` 1837 passed, `kyo-preludeJVM/test` 844 passed, `kyo-coreJVM/test` 2665 passed and 0 failed; `kyo-kernelJVM/compile` and `kyo-kernelJVM/doc` green on this tip, the scaladoc of edit 8 and the comment of edit 25 included |

Benchmarks. `KernelBench`, 49 rows, is the class; `package-check.sh` confirms it references
`kyo.kernel`. The rows the fix reaches by name, before running: every row answering through
`handleCont`, since the arm gained the `repeated` test, with `escaping` read only when it holds. The
rows C reaches: `handleLoopAnswersInPlace`,
`handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, and every fusion row, since the tails
are inside the fused templates.

A first round, the base (`dcadee780d`, the base plus edit 19 alone, so the class compiles) against
the fix alone, same session, back to back, `-f 1`, all 49 rows: `bench/compare-base-vs-A.md`. No
`handleCont` row outside the 5% band. Three rows outside it, none on a path the fix touches:

| row | -f 1 | -f 3 |
|---|---|---|
| `collectOverCollection` | -8.8% | +1.8%, inside error |
| `nestedPayloadsUnwrapInMaps` | -6.0% | +0.1%, inside error |
| `pureIterationViaArrow` | +9.4% | -8.1%, errors of 10 and 15 percent on the score |

`-f 3` on those three, both legs back to back: `bench/compare-base-vs-A-f3.md`, zero suspects.

Three legs in one session, back to back, `-f 1`, 49 rows each: the base (`dcadee780d`, the base
plus edit 19 so the class compiles), the fix alone (A, the fix as its first draft wrapped the
continuation in `Eval`'s cont arm), and that draft with C (edits 13 to 18 added). The handler-side
shape of edits 7 to 10 came after this session, and the whole class runs again on the tip as it
stands; that session is the one the table's pending cells name. The fix against the tip attributes C alone,
one variable (`bench/compare-A-vs-AC.md`): zero
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

**The tip's final session.** The base (`dcadee780d`) against the tip (`dafad6640a`, the
handler-side shape; the kernel's main sources at the package's tip differ from it by the scaladoc of
edit 8 alone), same session, back to back, `-f 1`, all 52 rows: `bench/compare-base-vs-tip.md`.
One suspect, `repeatedClausesPayReentry` at +589%, the regression the multi-shot table below
prices. Every `handleCont` row, every fusion row and the three rows C's tails sit in are inside the
band: `handleLoopAnswersInPlace` -1.0%, `handleLoopFusesContinuation` -1.6%,
`statefulAnswersPaySuccessor` +0.6%, `contextRegionsPayEntryExit` +1.3%,
`emittingClausesPayRegionRebuild` -1.1%, `bracketPerRound` +1.4%. Nine rows sit outside the band
at `-f 1`, each inside its own error bars, and `-f 3` on those nine and the suspect, both legs back to
back on an otherwise idle machine, is `bench/compare-base-vs-tip-f3.md`: seven inside the band, two
inside their errors, and the suspect confirmed at a tight 6.4 times:

| row | -f 1, base against tip | -f 3 |
|---|---|---|
| `pureIterationViaArrow` | -8.1%, errors of 48 and 39 percent | +13.5%, errors of 16 and 10 percent, noise on the row that is always noisy |
| `collectOverCollection` | -7.2%, error of 7 percent on the base | +5.6%, inside the combined error (14.08 ± 0.37 against 14.87 ± 0.64) |
| `foreignCrossingsPayRotation` | -6.2%, error of 39 percent on the base | +1.8% |
| `nestedPayloadsUnwrapInMaps` | -5.8%, error of 45 percent on the base | -2.5% |
| `suspensionBaselineAltEnv` | +5.2%, error of 20 percent on the tip | -0.6% |
| `trailingMapsStayLinear` | +7.1%, error of 9 percent on the tip | +2.5% |
| `repeatedRegionsPayEntry` | +16.7%, error of 59 percent on the tip | +3.7% (56.1 against 58.2 us) |
| `bracketAroundLoop` | +17.1%, error of 37 percent on the base | -1.9% |
| `continuationBodiesFuse` | +19.4%, error of 17 percent on the tip | +3.8% |
| `repeatedClausesPayReentry` | +589%, the suspect | +537%, 99.8 ± 0.3 against 636.1 ± 7.4 us: 54 ns per resumption, 6.4 times, the priced row |

**The multi-shot rows, and the regression they show.** Three rows added by edit 20 enter a
`handleContRepeated` region, which no row did before. Base against tip, one session, both legs
back to back on an idle machine, `-f 3 -prof gc`: time in `bench/compare-rows-base-vs-tip-clean.md`,
allocation from the `gc.alloc.rate.norm` lines of `bench/base-rows-4.log` and `bench/tip-rows-4.log`
(the earlier shape's session, `bench/compare-rows-base-vs-AC.md`, is kept for the derivation's
record; the first profiler session on this shape, `bench/compare-rows-base-vs-tip.md`, had its base
leg disturbed and is superseded by this one):

| row | base | tip | per unit |
|---|---|---|---|
| `repeatedRegionsPayEntry`, 1000 regions of one operation | 55.0 us, 88,104 B | 58.8 us, 104,120 B | +3.8 ns and 16 B per region: the re-entered handler |
| `repeatedRegionsPayEntryRecovering`, the same through the recovering overload | 58.0 us, 88,104 B | 59.2 us, 104,120 B | +1.1 ns and 16 B per region |
| `repeatedClausesPayReentry`, one region of 10,000 operations | 101.4 us, 480,121 B | 637.4 us, 720,140 B | +54 ns and 24 B per resumption: one region entered per application |

The third is a regression of 6.3 times on that row (6.4 in the full-class session's `-f 3` confirmation), and nobody has accepted it. The mechanism is
the design: every application of a repeated handler's continuation enters a region, and a region's
entry and exit is what `contextRegionsPayEntryExit` and `emittingClausesPayRegionRebuild` already
measure at 73 and 89 ns per region, so the row sits at the floor, not above it. No cheaper frame
would do, because what stops an inner occurrence from capturing the clause's pending work is a
handler on the stack above that work: a crossing packs every stack entry between an occurrence and
the handler that answers it into the continuation. The base was flat on this row because it was
wrong on the shape the matrix found. The tree's one consumer of the repeated handlers, `Choice.run`,
paid the delimiter by hand, wrapping each resumption in a fresh `Choice.run`; with the kernel
delimiting, that wrapper is a second region per resumption, and edit 21 removes it. What that nets
for Choice is the `ChoiceBench` comparison below; the decision on the price itself is open ruling 5.

`ChoiceBench` (kept in the package as `bench/src/ChoiceBench.scala`, removed from kyo-bench by the reviewer's ruling of 2026-09-12), ten sequential binary choice points, throughput, higher is better, `-f 3`, base
against tip in one session, `bench/compare-choice-base-vs-AC.md`:

| row | base | tip | |
|---|---|---|---|
| `run` | 1,273 ops/s | 4,063 ops/s | 3.2 times faster: the kernel's re-entered region replaces the base's inner `Choice.run` per resumption and its second flatten, and edit 21 drops that inner region |
| `runStream` | 5,208 ops/s | 5,042 ops/s | -3.2%, inside the combined error |

A first draft re-entered every repeated handler, `handleFirstRepeated` included. On that draft
`run` was already 2.2 times faster than the base before edit 20 (1,280 against 2,860 ops/s), and
`runStream` was 24% slower (4,936 against 3,721 ops/s), because every resumed computation carried a
re-entered region on top of the fresh `handleFirstRepeated` the stream's loop installs per iteration, a
region that could not prevent anything. That measurement is what moved the wrap into the two
handlers that resume inside their region, and `runStream` is back at the base with it.

**CI**, on `fwbrasil/kyo-ci-test`, the full matrix (linux-x64, linux-arm64, windows-x64; JVM, JS,
Native, Wasm). The first full run, 34672876184 on the gated-matrix commit, found every JS and Wasm
job dying in `kyo-data`'s `SpanTest`: the branch's ancestry adds an out-of-bounds case for
`Span.updated`, whose scaladoc promises `IndexOutOfBoundsException` while the code relied on the
JVM's array store; on JS the fatal undefined-behaviour error escapes the harness and node exits, on
Wasm the store traps with the same effect. Fixed by edit 22, reproduced locally before the fix (the
same run-terminated exception) and verified after it: `SpanTest` 237 passed on each of JVM, JS and
Wasm, the out-of-bounds case included. The run after that fix, 34676060392, per job:

| job | outcome | what it is |
|---|---|---|
| windows-x64 JS | passed | the Span fix on the third OS |
| linux-arm64 JVM | passed | without edit 23, so the kyo-net race is intermittent, as diagnosed; with edits 23 and 24 the leaf passes in the linux-arm64 CI container run locally, the order pinned |
| linux-x64 JVM | failed | `kyo-ui`'s `ReactiveUITeardownTest`, an `assertEventually` timing assertion; the module is identical to main, and upstream main's own runs failed the same test twice this week, on linux-arm64 and windows-arm64 |
| linux-x64 JS, linux-arm64 JS, linux-x64 Wasm, linux-arm64 Wasm | failed | `kyo-sql-postgres`'s `SqlClientInterruptTest`, the leaf "an interrupted connect strands no descriptor" stuck for two minutes, and its sibling "interrupting the statement's fiber stops it" passing only at the thirty-second query timeout: on JS an interrupt does not reach a fiber parked on the socket. Reproduced locally on JS at the tip and at the branch's base, so it predates this work; main's JS jobs pass it. A bisect between main and the base, eleven steps of the leaf on JS, ends at the merge of main into the branch (9f0b6d38b9, the three commits after it not building until the kyo-net compile fix): the leaf came from main's #1933 in that merge, the branch before the merge passes, main alone passes, and their combination hangs, which puts the defect in main's new close and interrupt paths meeting the branch's own kernel and core on JS. Open; the next step is the branch's interrupt delivery to a promise the JS driver holds, compared against main's |
| linux-x64 Native | failed | `kyo-ffi-it`'s `ItCallbackExceptionTest`, a `NoSuchElementException` from inside a C callback's exception report; `kyo-ffi` is identical to main, main's own commit passes this job on this fork, and the branch fails it on both runs, so it belongs to the branch's kernel line on Native; the base's Native job is running to date it |
| linux-arm64 Native | failed | `kyo-ffi-it`'s `ItStructPtrTest`, the same exception class, the same attribution |
| windows-x64 JVM | in progress at packaging | |

The run on the handler-side commit, 34683181614, on every job that concluded at packaging:

| job | outcome | what it is |
|---|---|---|
| windows-x64 JS | passed | |
| linux-x64 JS, linux-arm64 JS, linux-x64 Wasm, linux-arm64 Wasm | failed | the same `SqlClientInterruptTest` hang, unchanged |
| linux-x64 Native, linux-arm64 Native | failed | `kyo-ffi-it`'s `ItStructPtrTest` on both, the same `NoSuchElementException`; which of the two ffi tests fails first varies between runs. Locally on macOS arm64 the suite aborts the process at `ItCallbackExceptionTest`; with the callback reporter's `printStackTrace` removed both pass, and with the frames resolved and printed one by one before `printStackTrace` both pass as well, the trace stopping at `_isort` after eight frames. The base's Native job fails the same way and main's commit passes it on this fork. Open: Scala Native's trace printing for an exception captured inside a C callback frame, on this branch's stack; the next step is a reproduction without kyo |
| linux-x64 JVM | failed | `kyo-tasty`'s `CollectionInvariantsTest`, a `StackOverflowError` on a scheduler worker bouncing between the scheduler's boundary arrow and a fused suspension node; the suite passes locally on the tip (7 passed) and in the sweep (1706), so this is a CI-only symptom of the branch's core, recorded with its trace |
| linux-arm64 JVM | failed | `kyo-doctest`'s `CorpusTest`, one leaf stuck two minutes with a worker deep in `IOPromise.removeInterrupt`; the suite passes locally (144); the same attribution as the row above |
| windows-x64 JVM | in progress at packaging | |

**The sweep**, every sbt project's suite one at a time, `reviews/robustness/sweep/run.sh` over
`modules.txt` and `noncross.txt`, results in `reviews/robustness/sweep/results.tsv`. It ran on the
tree as the sweep started, which is the tip minus the handler-side shape of edits 7 to 10, the two
test cases of edits 11 and 12, the third benchmark row and the build runner's step; those are
verified on the tip by the suites in the table above, and the working copies of the files they
touch were pinned to the sweep's commit for its duration so it saw one tree throughout.

| | count |
|---|---|
| projects run | 83 |
| leaves passed | 34,211 |
| leaves failed | 39, all outside the kernel |

The eight projects that did not end green, each read to its cause:

| project | what happened |
|---|---|
| kyo-aeron | the C shim links against a staged libaeron this machine lacked; staged with `kyo-aeron/scripts/build-aeron.sh darwin-aarch64` and re-run: 143 passed, 0 failed |
| kyo-ai | 642 passed, 37 failed: 25 are the Codex CLI harness answering `systemError` on every turn, 3 are provider authentication against live endpoints, the rest are provider timeouts; live-service suites the CLI and keys on this machine let run, and CI's JVM jobs pass the module |
| kyo-pod | 992 passed, 2 failed, both `ContainerItTest` under the `[docker]` runtime scope with `ContainerMissingException`; the podman scope passes |
| kyo-test-sbt-publish | publishes every module and hit the kernel's doc build failing on the benchmark classes in the main class directory, the defect the `Jmh / classDirectory` change fixes. Rerun on the tip, `kyo-test-sbt-publish/test`: 3 passed, 0 failed, the kernel's scaladoc built (`sweep/rerun-kyo_test_sbt_publish_test.log`) |

| kyo-compat-tests | the sweep ran it without the Scala switch its build declares (`scalaVersion := scala3LTSVersion`), so its dependency's 3.8.4 TASTy was unreadable (`TASTy signature has wrong version`). Rerun as CI's runner does, `++3.3.8 kyo-compat-tests/test`: 270 passed, 0 failed (`sweep/rerun-kyo-compat-tests-lts.log`) |
| kyo-ffi-plugin | its tests publish the plugin and its docs locally and met the same doc failure, plus one source the formatter rejected. Rerun on the tip, `kyo-ffi-plugin/test`: 153 passed, 0 failed, the publish and its scaladoc included (`sweep/rerun-kyo_ffi_plugin_test.log`) |
| kyo-settings, kyo-website-bundle | not sbt projects; the lists carried two names that resolve to nothing |

Every other project passed, kyo-tasty and kyo-doctest among them (1706 and 144 leaves), the two
whose CI-only failures on the final-code run are in the CI table.

## Open rulings

The reviewer read the status report on 2026-09-12 and asked about each; the state after that exchange.

1. **The overload pair.** `Arrow.apply(v: A)` against `apply[S2](v: A < S2)`: at an erased input the
   static argument type picks the semantics. Evidence: the `Batch` regression, the two `PollTest`
   ascriptions, and the contract cases in `ArrowEffectTest`. The matrix in B does not reach the
   pair: its scenarios type their continuations. Declared, not touched. The reviewer asked whether
   the version that takes a plain value can be removed and what it would cost ("let's try and check
   perf"). A static analysis of every call site is in `lenses/fork1-apply-pair.md`; the experiment
   itself lives on the throwaway branch `robustness-noapply`, off this tip, and is outside this
   package's range. What it found so far, each with its evidence: the kernel compiles without the
   overload and its six overrides, with the pending overload keeping the settled body
   (`this.head(v, this.tail)`; with `this(v, Arrow.id)` instead, eleven `EffectTrace` cases fail,
   the trace losing the frames of steps applied through a composed cont). Eight sites must state
   the type of a computation handed to a continuation typed over it, with `Kyo.lift`
   (`Batch.flush`, six kernel test cases, IOTask's null answer as an ascription), because the lift's
   lint refuses a value whose type is already pending; the base delivered the same payload through
   the default body's own lift, so semantics are unchanged there. The `PollTest` ascriptions stay
   (ruling 2). The kernel suite passes in full (1836) and the prelude suite too (844); the core
   suite does not: the interrupt invariants of the semaphore and the rate limiter, `Signal`'s
   combinators, `pipeline.tryRun` and a leaked spin-loop fiber (`IOTaskTest`) fail on the
   experiment, and the tip passes all of them under the same load. The mechanism this points at:
   a direct application of an `Arrow.apply(f)` arrow ran `f` without polling the safepoint (the
   override), and through the fused arm it polls, so a pending stop now lands at every settled
   application, which is a change in when interrupts take effect, not a fast path lost. The
   measurement the reviewer asked for, `KernelBench` in full, tip against removal, same session,
   `-f 1` (`bench/compare-tip-vs-noapply.md`, 52 rows, four suspects) and `-f 3` on every row
   outside the band (`bench/compare-tip-vs-noapply-f3.md`): `effectfulIterationViaArrow` 118 us
   against 387 us, 3.3 times, the `recursive` arrow's direct self-application now polling the
   safepoint on every round so the budget is spent twice per round and the deferral lands at half
   the period, as the analysis predicted; `deferBindUnderTrailingMap` +15.8% and
   `deferBindUnderIdleHandler` +7.5%, `Effect.defer`'s direct application through the fused arm;
   `bracketPerRound` +13.8%, the same for the bracket's acquire and its `Ensure`;
   `trailingMapsStayLinear` and `fusionAfterSuspensionRunOnly` flat, so the head-and-tail default
   holds the composed-continuation rows as predicted; `pureIterationViaArrow` 102 against 87 us,
   faster, on the row that is always noisy. Every other row is inside the band. What the removal
   costs, then: a semantic change in interrupt delivery that the core suite rejects, and a direct
   application of a `recursive`, `Arrow.apply(f)`, `Effect.defer` or `Ensure` arrow paying the
   fused arm. What it buys: one application law and the eight sites stating a computation as
   data; not the `PollTest` ascriptions. Alone on an idle machine the experiment's core suite fails 158 leaves and two
   kyo-test leak checks (`kyo-coreJVM/test` on `robustness-noapply`), so the failures are the
   removal's, not load. The isolation probe (the experiment plus the `Arrow.apply(f)` override
   alone, branch `robustness-probe`) is running and its core result goes here; the decision is the
   reviewer's.
2. **The `PollTest` ascriptions** are workarounds under the ruling of 2026-08-28 and are left in
   place. The reviewer asked whether they come from the handler method's signature: no signature
   changed. Compiled without them, on the tip and on the overload-removal experiment, the compiler
   names the cause: `T` in that test is an `enum`, so `T.T2("zero")` widens to `T` and
   `Present(T.T2("zero"))` is a `Present[T]`, which is not a `Maybe[T.T2]`; the ascription is the
   expected type that keeps `T.T2` narrow. A plain parameter of type `Maybe[T.T2]` would propagate
   that expected type on its own; a continuation's parameter is a `Maybe[T.T2] < S2`, through which
   the lift does not carry it, and with the overload pair present the argument is typed with no
   expected type at all. Not fork 1's symptom: the removal experiment shows the ascription is still
   needed with the pair gone (the earlier attribution in this package was wrong and is corrected
   here).
3. **D**, the reference interpreter: dropped by the reviewer ("drop"). The derivation keeps the
   record of why it was not attempted; nothing else in the package depends on it.
4. **Fork 4, A over B.** The alternative, candidate B, is `done` per resumption with a changed
   `handleContRepeated` signature, the standard delimited reading. Edit 13's second case (1060, not
   4060) is the line that pins A; reversing it is a public-surface decision. B would not change the
   cost in ruling 5: it re-enters a region per resumption as well. The reviewer asked how the kernel
   and prelude suites passed at the base if the base is wrong: no case in either suite had a repeated
   clause resuming twice over two or more occurrences with pending work between the resumptions, and
   the tree's one consumer, `Choice.run`, re-entered a region by hand around every resumption, which
   is exactly the delimiting the kernel now does. Edit 13's cases and the matrix in B are that shape,
   and the base hangs or blows up on them. A stands unless the reviewer rules B.
5. **The per-resumption region is the price of a kernel that delimits.** Accepting A means every
   multi-shot resumption enters a region, 54 ns and 24 bytes, and a clause that resumes exactly once
   under `handleContRepeated` pays it where the base ran flat; such a clause belongs under
   `handleCont`, and no consumer in the tree has that shape. The alternative is the base's contract,
   documented rather than enforced: a repeated clause with pending work between resumptions must
   re-enter a region itself, as `Choice.run` did, and one that does not hangs. Recommendation: A,
   with edit 21, because the kernel is then correct by construction for the shape the matrix found
   and the one consumer pays less than it paid before: `Choice.run` 3.2 times faster than the base,
   `runStream` unchanged. The reviewer's position: a necessary price is acceptable, to be discussed
   live; the benchmark section above is the case for its necessity, and the region-entry floor
   (`contextRegionsPayEntryExit`, `emittingClausesPayRegionRebuild`) is the lever that would lower it
   for every handler, not a lever this change has.
