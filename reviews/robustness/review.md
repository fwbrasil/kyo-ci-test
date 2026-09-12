# Live review: kernel robustness

Base `cdefdc9e60`, branch `robustness`, worktree `.claude/worktrees/robustness`. Range and tip are
re-derived by `package-check.sh` at packaging; the walk below is `sequence.json`, verified against
the tip by `sequence.py --verify`. Seventeen edits, applied one at a time with the Edit tool, in the
order given here: sixteen in the kernel and one in `kyo-data` that the branch's CI matrix required.

## What this change is

Two bugs in one day shared a shape the kernel suite never built: a clause that suspends, an effect
answered by a region other than the clause's own, at-top against not-at-top. Each was invisible to
1513 kernel tests and surfaced in a consumer. The robustness list written after them has six items;
this change carries the four that are code or process, and the matrix it adds found a third defect on
its first run, which is fixed here as well.

| piece | what | where |
|---|---|---|
| A | downstream suites in the verification rule | `kyo-kernel/CONTRIBUTING.md`, item 13 |
| B | the shape matrix, with the at-top law as its oracle | `EvalShapeTest.scala`, 224 cells |
| fix | multi-shot re-entry is not delimited, found by B | `Handler`, `ArrowEffect`, `Eval`, `ArrowEffectTest` |
| C | one re-entry path for the four loop tails | `Handler` |
| bench | `KernelBench` follows the `ContextEffect.handle` signature | `KernelBench.scala` |

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

The fix keeps every signature and today's `done` behaviour: when `handler.repeated`, the continuation
handed to the clause is an arrow whose application re-enters a fresh region through the handler's
`resumed` twin, the same clause with `done` as identity. Entering the fresh region stores the
registers as that region's continuation, so the enclosing clause's pending work sits outside the body
again and a later occurrence captures body maps only. `done` still runs once, at the outer region's
end. Confined to `repeated` handlers; the single-shot `handleCont` path is untouched. Candidate B, the
delimited reading with `done` per resumption and a changed `handleContRepeated` signature, is
recorded in the derivation as the alternative and is fork 4 below.

## The walk

One sentence per edit, the sentence to say when applying it.

**A. The rule**

1. `CONTRIBUTING.md`, item 13: a change to the evaluator, the handlers or the representation is
   verified by the kernel suite, `kyo-preludeJVM/test` and `kyo-coreJVM/test` together, with
   `Batch.run` as the consumer that composes the combinators in a shape the kernel suite does not, and
   a public-signature change also runs `kyo-kernelJVM/Jmh/compile`, since the benchmark sources are
   compiled by neither `test` nor CI.

**B. The matrix**

2. `EvalShapeTest.scala`, new: seven scenarios over the five handler kinds, each hand-derived once
   for its base configuration and then asserted equal across eight configurations by the three laws
   the kernel already states (at-top: an inert `ContextEffect` binding or an inert `handleCont`
   region above the handler changes nothing; fusion: n consecutive occurrences answer independently;
   suspension: a clause that first performs an effect handled outside the region answers the same),
   for n in 0 to 3, 224 cells, every one with a concrete expected value.

**The fix**

3. `Handler.scala`: `import kyo.bug`, for the next edit.
4. `Handler.scala`, `ContHandler`: `resumed` is the handler a repeated continuation re-enters through,
   this handler with `done` as identity, defined only by handlers that repeat and `bug` otherwise;
   `reentering(k)` wraps a continuation so each application re-enters the region through
   `Pending.handle(k(x), resumed, ())`, an `Arrow.Step` deferring on a pending input in the same arm
   shape as `Arrow.apply`.
5. `ArrowEffect.scala`, `handleContRepeated`: the handler defines its `resumed` twin as a `val`, a
   `ContHandler` whose `run` delegates to the handler's own through the `outer` self alias and whose
   `done` is identity, built once at region entry.
6. `ArrowEffect.scala`, the recovering `handleContRepeated` overload: the same twin.
7. `ArrowEffect.scala`, `handleFirstRepeated`: the same twin over `A | First`, with `escaping` and
   `repeated` carried so the twin's region owes and holds as the original does.
8. `Eval.scala`, cont arm: the continuation handed to the clause is `handler.reentering(raw)` when
   `handler.repeated` and `raw` otherwise, `raw` being today's chain or crossing.
9. `ArrowEffectTest.scala`: three cases pin the fix, a clause resuming twice over two occurrences
   (60), `done` running once at the outer end and not per resumption (1060, not 4060), and three
   occurrences (180).

**C. One re-entry path**

10. `Handler.scala`: `attachReentryUnlessSettled(reentry, outcome)` is the tail the four loop sites
    spelled, a pending outcome gets the cont attached through `attachReentry`, a settled one passes
    through without building the arrow, `inline` so the fused walks expand it as the branch they
    carried.
11. `Handler.scala`: `attachReentryUnlessSettled2`, the same over the state-carrying outcome.
12. `Handler.scala`, `LoopHandler.answers`: the tail becomes the call.
13. `Handler.scala`, `LoopStateHandler.answers`: the tail becomes the call.
14. `Handler.scala`, `answersLoop`: the tail becomes the call; the `k.asInstanceOf` on the line is
    the one the site already carried.
15. `Handler.scala`, `answersLoopState`: the tail becomes the call.

**Bench**

16. `KernelBench.scala`: four `ContextEffect.handle(Tag[X])(...)` calls become
    `ContextEffect.handle(Tag[X], ...)`, the two-group signature every context handler has since
    `cdefdc9e60`; the benchmark sources had not been compiled since, which item 13 now prevents.

**Outside the kernel**

17. `Span.scala`, `updated`: an explicit index check raising the `IndexOutOfBoundsException` the
    scaladoc already promises, in `Chunk`'s shape and message; the JVM's array store delivered it,
    Scala.js treats the store as undefined behaviour, and the Wasm backend traps and kills node, which
    is how the branch's CI matrix found it through the `SpanTest` case on the branch's ancestry.

The name in edits 10 and 11 is not `reenter`, the derivation's working name: `LoopStateHandler.reenter(state)`
already exists as the lifecycle hook a region receives on re-entry, and an uncurried overload of
`attachReentry` itself would differ from the arrow form `Eval` applies by a comma.

## Adjudication

Every construct `flags.sh` enumerates on the diff's added lines, plus the two classes it cannot emit,
each with a verdict that is a category from the cast ladder, a measurement, a `moved` provenance or
`REMOVE`. The table is `flags.md`; folded in here.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | ArrowEffect.scala:213 | `new Handler.ContHandler[I, O, E, A, A, S & S2]:` | allocation | justified: the `resumed` twin, a `val` built once with the handler at region entry, never on an answer or a resumption; a repeated region already allocates its handler and its `HandleArrow`, this is one object beside them |
| F2 | ArrowEffect.scala:274 | `new Handler.ContHandler[I, O, E, A, A, S & S2]:` | allocation | justified: as F1, for the recovering overload |
| F3 | ArrowEffect.scala:990 | `new Handler.ContHandler[I, O, E, A \| First, A \| First, S & S2]:` | allocation | justified: as F1, for `handleFirstRepeated` |
| F4 | Handler.scala:105 | `new Arrow.Step[O[V], A, E & S]:` | allocation | justified: one arrow per operation answered by a repeated handler, built only when `handler.repeated`; number: the benchmark section, every row, base against the fix |
| F5 | Handler.scala:109 | `case p: Pending[O[V], S3] @unchecked => Effect.defer(p, this, cont2)` | cast | erasure-forced: a typed pattern binding at the arm's type, the runtime test being `Pending` alone; the same arm as `Arrow.apply`'s and `Suspend.crossing`'s |
| F6 | Handler.scala:386 | `else outcome.asInstanceOf[Outcome[A < (E & S), B < S] < S]` | cast | moved: the pass-through cast `LoopHandler.answers` and `answersLoop` each carried at their tail, written once. Representation assertion: the two outcome types differ only in the `Continue` payload, and a settled outcome reaching the tail is not a `Continue` |
| F7 | Handler.scala:413 | `else outcome.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]` | cast | moved: as F6, for the state-carrying outcome |
| F8 | Handler.scala:478 | `result = attachReentryUnlessSettled[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o)` | cast | moved: the same `k.asInstanceOf` on the same site at the base; erasure-forced, the fused walk rebinding `k` per operation |
| F9 | Handler.scala:556 | `result = attachReentryUnlessSettled2[...](k.asInstanceOf[Arrow[O[C], A, E & S]], o2)` | cast | moved: as F8 |
| F10 | Eval.scala, cont arm | `val continuation = if handler.repeated then handler.reentering(raw) else raw` | hot-path cost | measured, inside drift: `repeated` is a virtual `Boolean` on the arm every `handleCont` answer takes; no `handleCont` row outside the 5% band at `-f 1`, and the three rows outside it confirm as noise at `-f 3`; the numbers are in the benchmark section |
| F11 | Handler.scala:93 | `def resumed: ContHandler[I, O, E, A, A, S] = bug(...)` | claim | justified by construction, with the reach stated: only `reentering` calls `resumed`, `Eval` calls `reentering` only under `handler.repeated`, and the three handlers that override `repeated` all override `resumed` |

## Evidence

Verification, on the tip with C:

| what | result |
|---|---|
| `kyo-kernelJVM/clean` then `compile`, batch | clean |
| `kyo-kernelJVM/Jmh/compile` | clean |
| `kyo-kernelJVM/test` | 1737 passed, 0 failed, 5 canceled (the `DebuggerTest` sessions, which cancel when the debugger is compiled out, as before) |
| `kyo-preludeJVM/test` and `kyo-coreJVM/test` | 2665 passed, 0 failed |
| `EvalShapeTest` | 224 cells, all green; the multi-shot cells hang at the base |

Benchmarks. `KernelBench`, 49 rows, is the class; `package-check.sh` confirms it references
`kyo.kernel`. The rows the fix reaches by name, before running: every row answering through
`handleCont`, since the arm gained the `repeated` test. The rows C reaches: `handleLoopAnswersInPlace`,
`handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, and every fusion row, since the tails
are inside the fused templates.

Base against the fix, `dcadee780d` (base plus edit 16 alone, so the class compiles) against
`a61fbf0ca6`, same session, back to back, `-f 1`, all 49 rows: `bench/compare-base-vs-A.md`. No
`handleCont` row outside the 5% band. Three rows outside it, none on a path the fix touches:

| row | -f 1 | -f 3 |
|---|---|---|
| `collectOverCollection` | -8.8% | +1.8%, inside error |
| `nestedPayloadsUnwrapInMaps` | -6.0% | +0.1%, inside error |
| `pureIterationViaArrow` | +9.4% | -8.1%, errors of 10 and 15 percent on the score |

`-f 3` on those three, both legs back to back: `bench/compare-base-vs-A-f3.md`, zero suspects.

The fix against C (edits 10 to 15) is the leg that attributes C alone, one variable; it is reported in
this section as `bench/compare-A-vs-AC.md` once run.

CI, on `fwbrasil/kyo-ci-test`: the full matrix (linux-x64, linux-arm64, windows-x64; JVM, JS, Native,
Wasm) on the gated-matrix commit `bfd4351ff2` found `kyo-dataWasm`'s run dying in `SpanTest`: the
branch's ancestry adds an out-of-bounds case for `Span.updated`, whose scaladoc promises
`IndexOutOfBoundsException` while the code relied on the JVM's array store; on Wasm the store traps and
kills node. Fixed by edit 17. The matrix's final state is reported with the sweep below.

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
   reading. Edit 9's second case (1060, not 4060) is the line that pins the ruling; reversing it is a
   public-surface decision.
