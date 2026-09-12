# Live review: kernel robustness

Base `cdefdc9e60`, branch `robustness`, worktree `.claude/worktrees/robustness`. Range and tip are
re-derived by `package-check.sh` at packaging; the walk below is `sequence.json`, verified against
the tip by `sequence.py --verify`. Twenty-two edits, applied one at a time with the Edit tool, in
the order given here: seventeen in the kernel and the build's test runner, two in `kyo-prelude` and
`kyo-bench` for the consumer of the multi-shot fix, one in `kyo-data` and two in `kyo-net` that the
branch's CI matrix required.

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

The fix keeps every signature and today's `done` behaviour, and it lives in the handler that
declares it repeats: the two `handleContRepeated` overloads wrap the continuation they hand the
clause, in `run`, so that each application re-enters a fresh region under the handler's re-entered
form, the same clause with `done` as identity. Entering the fresh region stores the registers as
that region's continuation, so the enclosing clause's pending work sits outside the body again and
a later occurrence captures body maps only. `done` still runs once, at the outer region's end.
`Eval` and `ContHandler` are unchanged, so the single-shot path pays nothing, and
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
   mechanical form of what item 13 says and is the step that would have caught edit 16.

**B. The matrix**

4. `EvalShapeTest.scala`, new: ten scenarios, every handler kind with every arm it has, each
   stating only what its clause does to one occurrence; the fusion law (`law`, `lawState`, `runs`,
   a fold of that one occurrence over n) derives the value and the clause-run count for n in 0 to
   3, and the at-top law (an inert `ContextEffect` binding or an inert `handleCont` region above the
   handler changes nothing) and the suspension law (a clause that first performs an effect handled
   outside the region answers the same) assert it across eight configurations, 320 cells, every one
   asserting a concrete value and none written by hand.

**The fix**

5. `Handler.scala`: two helpers join the `Handler` object beside `attachReentry`. `reentered(outer)`
   is the handler a re-entered region runs under, `outer` with `done` as identity so the re-entered
   region yields the body's value and `outer`'s `done` still runs once, and `repeated` as `outer`
   is, so what a re-entered region owes is held across the clause's resumptions and released where
   that region ends. `reentering(k, reentered)` is the continuation wrapped so that each application
   re-enters, `Pending.handle(k(x), reentered, ())`, an `Arrow.Step` deferring on a pending input in
   the same arm shape as `Arrow.apply` and unnesting a settled one.
6. `ArrowEffect.scala`, `handleContRepeated`: the handler builds its re-entered form once, as a
   `val`, and wraps the continuation it hands the clause in `run`, so the re-entry lives with the
   handler that declares it repeats and nothing in `Eval` or `ContHandler` changes.
7. `ArrowEffect.scala`, the recovering overload: the same, and its re-entered form carries no
   `recover`, since `recover` yields the region's `B` where the re-entered region yields the body's
   `A`, so a throw inside a re-entered region unwinds to this handler's `recover`.
8. `ArrowEffectTest.scala`: five cases pin the fix, a clause resuming twice over two occurrences
   (60), `done` running once at the outer end and not per resumption (1060, not 4060), three
   occurrences (180), a throw after a second resumption reaching the outer `recover` (4), and a
   hundred thousand sequential operations under the repeated handler, which now nests a region per
   resumption, stack safe like every sibling handler's depth case.
9. `BracketTest.scala`: a bracket acquired inside one resumption and captured by an inner
   occurrence's continuation is released once, where the re-entered region ends, before the outer
   clause resumes again, which is what `reentered`'s `repeated` buys and where the release moved to.

**C. One re-entry path**

10. `Handler.scala`: `attachReentryToPending(reentry, outcome)` is the tail the four loop sites
    spelled, a pending outcome gets the cont attached through `attachReentry`, a settled one passes
    through without building the arrow, `inline` so the fused walks expand it as the branch they
    carried. C is item 4 of the robustness list, one re-entry path rather than four spellings of it;
    it is not part of the fix and travels with it because the tails are where a loop region's
    re-entry is attached, the same rule the fix applies to a repeated region.
11. `Handler.scala`: `attachReentryToPending2`, the same over the state-carrying outcome.
12. `Handler.scala`, `LoopHandler.answers`: the tail becomes the call.
13. `Handler.scala`, `LoopStateHandler.answers`: the tail becomes the call.
14. `Handler.scala`, `answersLoop`: the tail becomes the call; the `k.asInstanceOf` on the line is
    the one the site already carried.
15. `Handler.scala`, `answersLoopState`: the tail becomes the call.

**E. The benchmark class compiles**

16. `KernelBench.scala`: four `ContextEffect.handle(Tag[X])(...)` calls become
    `ContextEffect.handle(Tag[X], ...)`, the two-group signature every context handler has since
    `cdefdc9e60`; the benchmark sources had not been compiled since, which edits 2 and 3 now catch.
17. `KernelBench.scala`: three rows enter a multi-shot region, `repeatedClausesPayReentry` (the
    `suspensionBaseline` program under `handleContRepeated`, one region, ten thousand operations),
    `repeatedRegionsPayEntry` (a region per operation) and `repeatedRegionsPayEntryRecovering` (the
    same through the recovering overload), the rows that measure the re-entered handler built per
    region and the arrow built per operation, since no row entered such a region before.

**F. The consumer**

18. `Choice.scala`, `run`: the clause is `Kyo.foreach` over the alternatives applied to the
    continuation, flattened once; the inner `Choice.run(cont(v))` it wrapped each resumption in was
    the consumer re-entering a region by hand, which the continuation now does on every application,
    so keeping it would enter two regions per resumption.
19. `ChoiceBench.scala`, new in `kyo-bench`: `run` and `runStream` over ten sequential binary
    choice points, the rows that measure what Choice pays per resumption.

**Outside the kernel**

20. `Span.scala`, `updated`: an explicit index check raising the `IndexOutOfBoundsException` the
    scaladoc already promises, in `Chunk`'s shape and message; the JVM's array store delivered it,
    Scala.js treats the store as undefined behaviour and its fatal error ends the node process, and
    the Wasm backend traps with the same effect, which is how the branch's CI matrix found it, on
    every JS and Wasm job, through the `SpanTest` case on the branch's ancestry.
21. `RearmSurvivorsTest.scala`: the leaf arms write before read, so the write registration precedes
    the read registration in the poller driver's command order and is in the log by the time the
    read event can fire; armed the other way, the EOF event could be dispatched and the driver closed
    before the write registration was applied, which the linux-arm64 JVM job reported as a missing
    `registerWrite`. What the leaf pins, no rearm under edge-triggered registration, does not depend
    on the order.
22. `RearmSurvivorsTest.scala`: the leaf also asserts that the write registration precedes the read
    registration in the log, so the order it now rests on is pinned rather than assumed.

The name in edits 10 and 11 is not `reenter`, the derivation's working name: `LoopStateHandler.reenter(state)`
already exists as the lifecycle hook a region receives on re-entry, and an uncurried overload of
`attachReentry` itself would differ from the arrow form `Eval` applies by a comma. Nor is it
`attachReentryUnlessSettled`, the name it carried until the rehearsal read the "unless" as an edge
case: `attachReentryToPending` says what it does to a pending outcome, and a settled one passes
through.

## Adjudication

Every construct `flags.sh` enumerates on the diff's added lines, each with a verdict that is a
category from the cast ladder, a measurement, a `moved` provenance or `REMOVE`. The table is
`flags.md`; folded in here.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | Handler.scala:333 | `new ContHandler[I, O, E, A, A, S]:` | allocation | measured on the previous shape, pending on this one: the re-entered handler, built once per region entry. `repeatedRegionsPayEntry`, base against the earlier tip, `-f 3 -prof gc`: 16 bytes and 4.7 ns per region, +8.8% on a row that does nothing but enter and leave such regions (`bench/compare-rows-base-vs-AC.md`); the same object on this tip, remeasured with `repeatedRegionsPayEntryRecovering` in the tip's final session |
| F2 | Handler.scala:352 | `new Arrow.Step[O[X0], A, E & S]:` | allocation | measured on the previous shape, pending on this one, and the number is a regression on a row no consumer has: one arrow per suspension a repeated handler answers, and one region node per application. `repeatedClausesPayReentry`, base against the earlier tip, `-f 3 -prof gc`: 64 bytes and 61 ns per operation, +615% (`bench/compare-rows-base-vs-AC.md`); the same object on this tip, built in the handler's `run`, remeasured by `repeatedClausesPayReentry` in the final session. The mechanism, the consumer's numbers and the decision are in the benchmark section and open ruling 5 |
| F3 | Handler.scala:356 | `case p: Pending[O[X0], S3] @unchecked => Effect.defer(p, this, cont2)` | cast | erasure-forced: a typed pattern binding at the arm's type, the runtime test being `Pending` alone; the same arm as `Arrow.apply`'s and `Suspend.crossing`'s |
| F4 | Handler.scala:399 | `else outcome.asInstanceOf[Outcome[A < (E & S), B < S] < S]` | cast | moved: the pass-through cast `LoopHandler.answers` and `answersLoop` each carried at their tail, written once. Representation assertion: the two outcome types differ only in the `Continue` payload, and a settled outcome reaching the tail is not a `Continue` |
| F5 | Handler.scala:426 | `else outcome.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]` | cast | moved: as F4, for the state-carrying outcome |
| F6 | Handler.scala:491 | `result = attachReentryToPending[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o)` | cast | moved: the same `k.asInstanceOf` on the same site at the base; erasure-forced, the fused walk rebinding `k` per operation |
| F7 | Handler.scala:569 | `result = attachReentryToPending2[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o2)` | cast | moved: as F6 |

No hand-added row: `Eval` is unchanged, so no hot-path cost sits outside the script's classes, and no
member with a partial default exists for a claim to cover.

## Evidence

Verification, on the tip with C:

| what | result |
|---|---|
| `kyo-kernelJVM/clean` then `compile`, batch | clean |
| `kyo-kernelJVM/Jmh/compile` | clean |
| `kyo-kernelJVM/test` | 1836 passed, 0 failed, 5 canceled (the `DebuggerTest` sessions, which cancel when the debugger is compiled out, as before); 1510 at the base plus the 320 cells, the five cases of edit 8 and the case of edit 9 |
| `kyo-preludeJVM/test` and `kyo-coreJVM/test` | 2665 passed, 0 failed, on the tip with edit 18, `ChoiceTest` 33 passed |
| `EvalShapeTest` | 320 cells, all green; the multi-shot cells hang at the base |
| `kyo-netJVM/testOnly RearmSurvivorsTest` | 2 passed with edits 21 and 22, on this machine and in the linux-arm64 CI container (`scripts/build.sh --env podman-ci --arch arm`, the environment the failure came from), the ordering assertion included |
| `testKyo --dry-run --phase compile-test --modules kyo-kernelJVM,kyo-dataJVM JVM` | the pass reads `kyo-dataJVM/Test/compile; kyo-kernelJVM/Test/compile; kyo-kernelJVM/Jmh/compile`, the benchmark compile for the module that has one and not for the one that does not |
| `SpanTest` | 237 passed on each of JVM, JS, Wasm and Native after edit 20 |

Benchmarks. `KernelBench`, 49 rows, is the class; `package-check.sh` confirms it references
`kyo.kernel`. The rows the fix reaches by name, before running: every row answering through
`handleCont`, since the arm gained the `repeated` test, with `escaping` read only when it holds. The
rows C reaches: `handleLoopAnswersInPlace`,
`handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, and every fusion row, since the tails
are inside the fused templates.

A first round, the base (`dcadee780d`, the base plus edit 16 alone, so the class compiles) against
the fix alone, same session, back to back, `-f 1`, all 49 rows: `bench/compare-base-vs-A.md`. No
`handleCont` row outside the 5% band. Three rows outside it, none on a path the fix touches:

| row | -f 1 | -f 3 |
|---|---|---|
| `collectOverCollection` | -8.8% | +1.8%, inside error |
| `nestedPayloadsUnwrapInMaps` | -6.0% | +0.1%, inside error |
| `pureIterationViaArrow` | +9.4% | -8.1%, errors of 10 and 15 percent on the score |

`-f 3` on those three, both legs back to back: `bench/compare-base-vs-A-f3.md`, zero suspects.

Three legs in one session, back to back, `-f 1`, 49 rows each: the base (`dcadee780d`, the base
plus edit 16 so the class compiles), the fix alone (A, the fix as its first draft wrapped the
continuation in `Eval`'s cont arm), and that draft with C (edits 10 to 15 added). The handler-side
shape of edits 5 to 7 came after this session, and the whole class runs again on the tip as it
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

**The multi-shot rows, and the regression they show.** Three rows added by edit 17 enter a
`handleContRepeated` region, which no row did before. Base against tip, `-f 3 -prof gc`,
`bench/compare-rows-base-vs-AC.md`:

| row | base | tip | per unit |
|---|---|---|---|
| `repeatedRegionsPayEntry`, 1000 regions of one operation | 53.7 us, 88,104 B | 58.4 us, 104,120 B | +4.7 ns and 16 B per region: the re-entered handler |
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
delimiting, that wrapper is a second region per resumption, and edit 18 removes it. What that nets
for Choice is the `ChoiceBench` comparison below; the decision on the price itself is open ruling 5.

`ChoiceBench`, ten sequential binary choice points, throughput, higher is better, `-f 3`, base
against tip in one session, `bench/compare-choice-base-vs-AC.md`:

| row | base | tip | |
|---|---|---|---|
| `run` | 1,273 ops/s | 4,063 ops/s | 3.2 times faster: the kernel's re-entered region replaces the base's inner `Choice.run` per resumption and its second flatten, and edit 18 drops that inner region |
| `runStream` | 5,208 ops/s | 5,042 ops/s | -3.2%, inside the combined error |

A first draft re-entered every repeated handler, `handleFirstRepeated` included. On that draft
`run` was already 2.2 times faster than the base before edit 17 (1,280 against 2,860 ops/s), and
`runStream` was 24% slower (4,936 against 3,721 ops/s), because every resumed computation carried a
re-entered region on top of the fresh `handleFirstRepeated` the stream's loop installs per iteration, a
region that could not prevent anything. That measurement is what moved the wrap into the two
handlers that resume inside their region, and `runStream` is back at the base with it.

**CI**, on `fwbrasil/kyo-ci-test`, the full matrix (linux-x64, linux-arm64, windows-x64; JVM, JS,
Native, Wasm). The first full run, 34672876184 on the gated-matrix commit, found every JS and Wasm
job dying in `kyo-data`'s `SpanTest`: the branch's ancestry adds an out-of-bounds case for
`Span.updated`, whose scaladoc promises `IndexOutOfBoundsException` while the code relied on the
JVM's array store; on JS the fatal undefined-behaviour error escapes the harness and node exits, on
Wasm the store traps with the same effect. Fixed by edit 20, reproduced locally before the fix (the
same run-terminated exception) and verified after it: `SpanTest` 237 passed on each of JVM, JS and
Wasm, the out-of-bounds case included. The run after that fix, 34676060392, per job:

| job | outcome | what it is |
|---|---|---|
| windows-x64 JS | passed | the Span fix on the third OS |
| linux-arm64 JVM | passed | without edit 21, so the kyo-net race is intermittent, as diagnosed; with edits 21 and 22 the leaf passes in the linux-arm64 CI container run locally, the order pinned |
| linux-x64 JVM | failed | `kyo-ui`'s `ReactiveUITeardownTest`, an `assertEventually` timing assertion; the module is identical to main, and upstream main's own runs failed the same test twice this week, on linux-arm64 and windows-arm64 |
| linux-x64 JS, linux-arm64 JS, linux-x64 Wasm, linux-arm64 Wasm | failed | `kyo-sql-postgres`'s `SqlClientInterruptTest`, the leaf "an interrupted connect strands no descriptor" stuck for two minutes, and its sibling "interrupting the statement's fiber stops it" passing only at the thirty-second query timeout: on JS an interrupt does not reach a fiber parked on the socket. Reproduced locally on JS at the tip and at the branch's base, so it predates this work; main's JS jobs pass it. A bisect between main and the base, eleven steps of the leaf on JS, ends at the merge of main into the branch (9f0b6d38b9, the three commits after it not building until the kyo-net compile fix): the leaf came from main's #1933 in that merge, the branch before the merge passes, main alone passes, and their combination hangs, which puts the defect in main's new close and interrupt paths meeting the branch's own kernel and core on JS. Open; the next step is the branch's interrupt delivery to a promise the JS driver holds, compared against main's |
| linux-x64 Native | failed | `kyo-ffi-it`'s `ItCallbackExceptionTest`, a `NoSuchElementException` from inside a C callback's exception report; `kyo-ffi` is identical to main, main's own commit passes this job on this fork, and the branch fails it on both runs, so it belongs to the branch's kernel line on Native; the base's Native job is running to date it |
| linux-arm64 Native | failed | `kyo-ffi-it`'s `ItStructPtrTest`, the same exception class, the same attribution |
| windows-x64 JVM | in progress at packaging | |

The tip as it stands has not run through the matrix yet; the runs above are on the commit before
the handler-side shape, which changes kernel internals only.

**The sweep**, every sbt project's suite one at a time on the tree as of the sweep's start commit,
is reported in its own section below once it completes; the modules it touches through the kernel
are re-verified by the suites in the table above on the tip.

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
   with edit 18, because the kernel is then correct by construction for the shape the matrix found
   and the one consumer pays less than it paid before: `Choice.run` 3.2 times faster than the base,
   `runStream` unchanged.
