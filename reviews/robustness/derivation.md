# Derivation: kernel robustness

Base `cdefdc9e60`. Branch `robustness`. The list this implements is `.dev/kernel-robustness.md`
(gitignored, on disk in the primary worktree); its items are restated here where they change code.

Two bugs in one day shared a shape the kernel suite never built: a clause that suspends, an effect
answered by a region other than the clause's own, at-top versus not-at-top. Each was invisible to
1510 kernel tests and surfaced in a consumer. This change makes that shape a first-class thing the
suite builds, and makes the one rule those bugs circled live in one place.

## The pieces

A to D are the list's items; E, F, G and H were added as the work found them: E under this heading,
F under "What A costs" beside the measurement that motivated it, G (the pipeline's tooling) and H
(a defect found by reading) under this heading.

### A. Downstream suites in the verification rule (CONTRIBUTING, not kernel source)

`kyo-kernel/CONTRIBUTING.md`, checklist item 13, gains two sentences. The first: a change to the
evaluator, the handlers or the representation is not verified by the kernel suite alone;
`kyo-preludeJVM/test` and `kyo-coreJVM/test` run before it is called green, with `Batch.run` as the
example of a consumer composing the combinators in a shape the kernel suite does not. The second:
the benchmark sources, which no test task compiles, are compiled by CI's compile-test phase, and a
change to a public signature runs `kyo-kernelJVM/Jmh/compile` before it is pushed. The second
sentence was added when the first was applied: `KernelBench` no longer compiled against
`ContextEffect.handle`'s two parameter groups, a signature change from before the base that nothing
had noticed, which is piece E below. The CI step is `project/TestKyo.scala`: in the compile-test
phase, `testKyo` adds `Jmh/compile` for every selected module whose project carries the `jmh`
configuration, found from the build rather than listed, which the rehearsal lens asked for in place
of a sentence to remember (R3). That step made a latent defect ordinary: the JMH plugin compiled
the benchmark classes into the main class directory, and the kernel's scaladoc, which reads every
TASTy file there, failed on a cross-framework benchmark whose dependency is only on the jmh
classpath, so any sequence of compile-test then doc in one workspace broke (the sweep's publish
module found it). `build.sbt` gives the `Jmh` configuration its own class directory in the three
JMH projects (`Jmh / classDirectory := crossTarget.value / "jmh-classes"`); verified by a clean
build, `Jmh/compile`, a one-fork `Jmh/run` and `doc` in sequence. One doc link in
`EffectTrace`'s scaladoc, `[[splice]]`, resolved to nothing because `splice` lives on the companion;
it now reads `[[EffectTrace.splice]]`, the only warning that build emitted for the kernel. The
kernel skill's `SKILL.md` names three files beside it that did not exist, `flags.sh`,
`package-check.sh` and `rulings.md`; this change writes them (piece G) as the pipeline's tooling,
and the rule itself stays in the module guide, where a contributor reads it.

### B. The shape matrix, with the at-top law as its oracle (test only)

New file `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalShapeTest.scala`, the prefix
being the source it exercises.

The oracle problem: a few hundred cells need a few hundred expected values, and deriving each by
hand reintroduces the judgment that missed both bugs. So a scenario states only what its clause does
to one occurrence, and three laws the kernel already states derive every cell from that:

- **fusion law**: n consecutive occurrences answered by a clause are n independent answers. In the
  file this is `law(resumes, ends)`, a fold of one occurrence over the rest (each resumption
  contributes its value once per path below it, plus the rest's total; an empty `resumes` ends the
  region with `ends`), and `lawState` is the same fold for a clause that threads state. The number of
  times a clause runs is the same fold, `runs`. No value of `prog(n)` is written by hand; the
  scenario supplies the one-occurrence answer and the law supplies n. This is what puts the fused
  walks (`answersLoop`, `answersLoopState`) on the same footing as the unfused `answers`.
- **at-top law** (skill: "a fast path must be observationally equivalent to the law it specialises"):
  a scenario run with the handler at the top of the stack equals the same scenario with an inert
  region pushed above the handler. An inert region is a `ContextEffect` binding for a tag the
  scenario never reads, and separately a `handleCont` region for an arrow effect the scenario never
  performs, so both region kinds are exercised as the interloper.
- **suspension law**: a clause that answers immediately equals the same clause that first performs an
  effect handled outside the region and then answers identically. This is the first bug's cell.

Scenarios, enumerated: every handler kind with every arm it has, which is the cross product of the
axes below restricted to the arms that exist.

    handler   in {handleCont, handleContRepeated, handleLoop, handleLoopState, Mask}
    resume    in {once, never}            for handleCont; {once, twice, never} for handleContRepeated
    outcome   in {continue, done-from-clause}   for handleLoop and handleLoopState
    Mask      one scenario: a handleCont tunnelled past an inner handler for the same tag

Ten scenarios. The combinations absent are absent because the arm does not exist: a single-shot
`handleCont` cannot resume twice, `handleLoop` and `handleLoopState` have no resumption count, and
`Mask` has no clause of its own. Each scenario runs for n in 0 to 3 under eight configurations (the
base, the two inert regions above, the three suspending variants, and two with an inner handler for
the same tag between the handler and the program), 320 cells, every one asserting a concrete value.

The inner-handler configuration is the geography the `EvalTest` inner/outer cases pin (the cells the
crossing rewrite tripped), carried into the matrix as a configuration of every scenario. The other
geography the two bugs left behind, a peeled continuation resumed with a computation typed over the
computation and over `Any`, stays where `12074d8523` put it, in `ArrowEffectTest`, and is not
duplicated here: it is a contract of `Arrow.apply`'s overload pair (fork 1), not a shape of the
evaluator.

### C. One re-entry path (kernel source)

A loop clause's outcome, once it is not a `Continue` the caller has already handled, re-enters its
region by one rule, currently spelled four times in `Handler.scala`:

    reenter(k, outcome) =
      outcome pending  ->  attachReentry(k)(outcome)
      otherwise        ->  outcome

That tail is the shared piece. The unfused `answers` on `LoopHandler` and `LoopStateHandler` match
`run(input)` and handle a settled `Continue` inline (`Loop.continue(k(ans))` for a settled answer,
`Loop.continue(ans.map(k))` for a pending one) before reaching the tail; the fused walks
`answersLoop` and `answersLoopState` handle their `Continue` inside the walk itself and reach the
tail only for what the walk could not consume. So the four sites share the tail exactly and nothing
else, and the tail is what becomes one `private[kyo] inline def attachReentryToPending` (and
`attachReentryToPending2` for the state-carrying outcome), beside the `attachReentry` it is the
settled fast path of. Each piece already exists: `attachReentry`, the `Pending` test, pass-through.
The name is not `reenter`, the working name this section first used: `LoopStateHandler.reenter(state)`
already exists as the lifecycle hook a region receives on re-entry, and one name for two things is
the new-terminology rule broken from the other side. An uncurried overload of `attachReentry` itself
was the other candidate and is rejected because `attachReentry(k)(o)` in `Eval` and
`attachReentry(k, o)` in `Handler` would differ by a comma.
The two unfused `Continue` arms stay as they are, which the earlier draft of this section got wrong
by folding them in; the fused walks cannot take a `reenter` that covers `Continue` without being
restructured, and restructuring a fused template is not a consolidation.

`inline` because the fused templates are inlined at every `handleLoop`/`handleLoopState` site and
their bytecode size is a design property (the 68-to-25-byte history in the skill). The expansion must
be the same shape it is today; the benchmark comparison is what proves that rather than the argument.

Surface, exactly:

- `Handler.LoopHandler.answers`: the `case o =>` tail becomes `attachReentryToPending(k, o)`.
- `Handler.LoopStateHandler.answers`: the `case o2 =>` tail becomes `attachReentryToPending2(k, o2)`.
- `Handler.answersLoop`: the `case o => result = if o.isInstanceOf[Pending] ... attachReentry ...` tail.
- `Handler.answersLoopState`: likewise with `attachReentry2`.

Must not change: `attachReentry` and `attachReentry2` themselves (they become callees, not callers),
`clauseDispatch`, `Suspend.crossing` (its contract was just written on it and is what B pins), the
two `Eval` not-at-top arms (they already make a single direct `attachReentry` call on a value known
to be pending; folding them in would be a change to `Eval` with no duplication to remove), every node
class, and the public surface.

### E. The benchmark class compiles (`KernelBench.scala`)

Four `ContextEffect.handle(Tag[X])(...)` calls in `kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala`
become `ContextEffect.handle(Tag[X], ...)`, the two-group signature every context handler has had
since before the base; the benchmark sources had not been compiled since, which A's second sentence
now prevents. No measurement changes. Surface: that file only, and only those four calls. Found by
applying A, and added to the change because the evidence below needs the class to build on both
legs.

### D. A reference interpreter (test only, time-boxed)

A direct-style evaluator over the node types with no fusion, no stack machine and no safepoint,
under `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/`, and a differential run of the
matrix's scenarios through both. Its value is catching shapes nobody enumerated; its risk is being a
second interpreter with its own bugs. The decision rule: it ships only if it passes B's matrix
independently, on the strength of its own reading of the combinators. If it does not, it is written
up as a design note with what diverged, and B stands as the robustness measure.

### H. The release walk delivers the payload (kernel source)

Found by reading, during the fork 1 analysis of `Eval.release` (`lenses/fork1-apply-pair.md`,
section 3), not by a test. The abandonment walk carries a cont down to the `Arrow.Ensure` waiting on
a value that has already settled and offers it that value, `collect(step(v), Arrow.id, 0)`, with `v`
the settled value as the walk found it, union-represented. Every settled arm delivers through
`Nested.unnest` (`Ensure`'s own two-argument apply, `map`'s strict arm), because a value that is
itself a computation is carried boxed once settled; the walk skipped that. So a bracket whose
resource is a computation, `Bracket(Kyo.lift(resource))(use)(release)`, abandoned through the
budgeted release (the entry a fiber abandonment uses) handed its release the box. Reproduced first:
the `EvalTest` case "a release for a resource that is itself a computation receives the computation,
not its box" fails on the tip before this piece with `Nested@... was not the same instance as
Kyo(...)`. Equation: the walk's delivery equals the arm's, `step(Nested.unnest(v))`, one line, and
the comment above it says why. Surface: `Eval.release`'s `ensuring` and `EvalTest`; nothing on any
evaluation path. The `[Any]` on the unnest is the walk's erased currency, `Arrow[Any, Any, Any]`,
the spelling `answersLoop`'s settled arm already uses.

### G. The kernel skill's tooling (not kernel source)

The kernel skill (`SKILL.md`, the document this work ran under) is not in this tree: the repository
untracked `kyo-kernel/.claude/` and every other agent artifact in `76c675ea94`, which moved them to
`.dev/`, gitignored, and set the rule that only module sources, READMEs and CONTRIBUTING files are
tracked; the primary worktree's copy of the skill is gone since. Its pipeline section names three
companion files that did not exist anywhere: `flags.sh`, which emits one row per construct of concern on a diff's added lines
(casts, `Any` carriers, `@unchecked`, allocations, terminology), the skeleton `flags.md` adjudicates;
`package-check.sh`, which re-derives every mechanical claim a package makes (tip, commit count,
surface, clean tree, the flag count against the table, the walk reproducing the tip, each benchmark
class named referencing the package under review) as OK, CHECK or STALE lines; and `rulings.md`, the
reviewer's objections verbatim and dated, the rehearsal lens's rubric, carrying the 2026-09-12 entry
from this change's status report. This change writes the three, whole, as new files. On this branch they are tracked, force-added
against that rule, so that a session boundary cannot lose them; that is preservation on a working
branch and nothing else. At the live review they are written under
`.dev/kyo-kernel/.claude/skills/kernel/` in the user's tree, where the repository's rule puts the
kernel skill's artifacts, untracked. Surface: those three files; no equation, they are tooling, and
the package's own checks (`package-check.sh` over this package, the flags table) are their first run.

## Also on the branch, outside the kernel

`kyo-data/shared/src/main/scala/kyo/Span.scala`, `Span.updated`: an explicit index check raising the
`IndexOutOfBoundsException` its scaladoc already promised. The CI matrix for the branch found it: the
JVM's array store raised the exception, Scala.js treats an out-of-bounds store as undefined behaviour,
and on the Wasm backend it traps and kills the node process, which ended `kyo-dataWasm`'s test run.
The `SpanTest` case that reached it is on the branch's ancestry (`c52e4bd8fa`), not on main, so the
contract was untested off the JVM until now. The check follows `Chunk`'s shape and message. On the
JVM the array store already checked the index, so `updated` now checks twice on that platform;
`SpanBench.updated` in `kyo-bench`, every index in bounds, prices that check on a sixteen-element
span, base against tip.

`kyo-net/jvm-native/src/test/scala/kyo/net/internal/posix/RearmSurvivorsTest.scala`: the leaf that
asserts no rearm under edge-triggered registration armed read before write, and the poller driver
applies registrations in command order on its poll fiber, so the EOF read event could be dispatched
and the driver closed by the test before the write registration was applied; the linux-arm64 JVM
job reported the log without `registerWrite`. The leaf now arms write first, which orders the two
registrations, and asserts that order in the log, so the property the reorder rests on is pinned
rather than assumed; what the leaf is there to pin, no rearm under edge-triggered registration, does
not depend on which direction is armed first. kyo-net's sources are identical to main on this
branch; this leaf is the branch's one kyo-net change.

Neither is a kernel piece; they are two edits of the live-review walk, in their own group, so the
range's surface is fully declared and fully applied.

Tracking on this branch, declared because the range shows it: `reviews/robustness/`, this package,
is tracked on the branch (35 files, force-added), and so are piece G's three files, both against
the repository's rule in `76c675ea94` that such artifacts live under `.dev/`, gitignored. The
reason is preservation across sessions on a working branch that is never merged as it stands: the
walk is what crosses to the user's tree, and nothing under `reviews/` is part of it. The package
itself belongs under `.dev/reviews/robustness/` in the user's tree, untracked, once the review has
run.

## What is not in this change

- The `Arrow.apply(v: A)` / `apply[S2](v: A < S2)` overload pair. At an erased input the static
  argument type picks the semantics; `Batch` fell into it and two `PollTest` sites needed an
  ascription. Both are consequences of the pair. Whether the pair stays is the user's, and the
  ascriptions are a workaround by the standing ruling of 2026-08-28 ("You cant workaround real
  issues, even if they're inference issues"). Declared, not touched: see forks.
- `Eval`'s at-top/not-at-top split itself. B asserts the two paths agree; C does not merge them.

## Forks

1. **The overload pair.** Keep both `apply`s, or collapse to one and give the as-data delivery an
   explicit public spelling. Evidence for the ruling: the `Batch` regression, the two `PollTest`
   ascriptions at value-typed sites, and the contract cases in `ArrowEffectTest` showing the two
   readings. Recommendation withheld until the matrix in B is in hand, since it will say how many
   scenarios the pair's choice actually reaches.
2. **The `PollTest` ascriptions** (`4c64b3e6fb`) are workarounds under the 2026-08-28 ruling. They
   are left in place in this change because their root cause is fork 1, and the fix belongs with
   that ruling rather than as a third spelling.
3. **Whether D ships.** Decided by the rule in D, and reported either way. Outcome: not attempted this
   round. B found a real defect on its first run and the night went to reproducing, fixing and
   measuring it, which is the better use of the hours than a second interpreter whose trust would
   itself need establishing. D stays on the robustness list as the long-run item, with B's matrix
   as the concrete subset it would generalise.

## Evidence the package will carry

- clean batch build of `kyo-kernelJVM`
- kernel suite, kyo-prelude suite, kyo-core suite, on the tip, per A
- `KernelBench` in full on base and tip, back to back, `-f 1`, then `-f 3` on any row outside the
  drift band; the rows C reaches by name before running: `handleLoopAnswersInPlace`,
  `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, and every fusion row
- `package-check.sh` confirming each benchmark class named references `kyo.kernel`
- the flags table, every row adjudicated
- the three lenses' reports, dispatched as general-purpose agents with the sub-skill briefs inline,
  since the sub-skills are not registered in this tree
- the CI matrix on `fwbrasil/kyo-ci-test` for the branch, all platforms and OSes

## Finding: multi-shot re-entry is not delimited (found by B on its first run)

`handleContRepeated` with a clause that resumes twice, over two or more consecutive occurrences in
one region, does not terminate. Four leaves of work exhaust a 4 GB heap. Minimal shape:

    handleContRepeated(Tag[Ask], ask.map(a => ask.map(b => a + b)))(
        [C] => (_, k) => k(7).map(x => k(8).map(y => x + y)),
        a => a
    )                                                        // should be 60; hangs

Mechanism, traced by hand against `Eval`'s at-top `ContHandler` arm. The continuation handed to a
clause is `kyo.cont.chain(contA.chain(contB))`: the suspension's own continuation chained with the
loop's registers. At the first occurrence the registers hold body maps, which belong in `k`. But the
clause's result is evaluated by the same loop inside the region, with its own pending maps in those
registers, so when its first resumption `k(7)` reaches the second occurrence, the registers hold
`x => k(8).map(...)`, the enclosing clause's second resumption, and it is captured into the inner
continuation `k2`. `k2` is applied twice by the inner clause, so the outer clause's remaining work
runs twice; each run re-suspends at the second occurrence with a fresh clause whose `k2'` captures
the same pending work again. Geometric, unbounded. Single-shot never shows it because the captured
pending work runs once either way, and the README's multi-shot example has one occurrence.

In the delimited reading a continuation is the rest of the body up to the region's boundary, and the
clause's own maps are handler code outside it. The evaluator has that boundary for loop handlers,
whose `Loop.continue` rebuilds the region as a fresh `Handle` value ("resumption equals entry"). A
`handleCont` continuation has no such re-entry: it is the raw chain.

### Candidate A: a repeated continuation re-enters through a fresh region, types unchanged

Confined to the two `handleContRepeated` overloads: their handler wraps the continuation it hands
the clause, in `run`, so that each application re-enters:
`k(x) = Pending.handle(bodyRest(x), reentered, ())`, where `bodyRest` is today's chain (or the
crossing, not at top) and `reentered` is the same handler with `done` as identity, built once with
the handler so the re-entered region yields the body's `A` rather than applying `done` a second
time; the outer region still applies `done` once at its end, which is today's behaviour. Entering
the fresh region stores the registers as its stack continuation, so the enclosing clause's pending
work sits outside the body again and a later occurrence captures body maps only. The re-entered
handler repeats, as the outer one does: a continuation captured inside a re-entered region is
resumed by the same clause more than once, so what that region owes is held across every
application and discharged where the re-entered region ends, after the last of them, which a case
in `BracketTest` pins.

The re-entered handler of the recovering overload carries no `recover`: `recover` yields the
region's output `B`, and the re-entered handler's output is the body's `A`, so the types do not
admit it. A throwable raised inside a re-entered region unwinds through it, which answers nothing,
to the outer region, whose `recover` is the one in effect, as before; a case in `ArrowEffectTest`
pins that a throw after a second resumption reaches the outer `recover`.

Cost: one arrow per suspension such a handler answers and one region per resumption, nowhere else.
`Eval` and `ContHandler` are unchanged by the fix (piece H changes one line of `Eval.release`, on
no evaluation path), so the single-shot path pays nothing; the scaladoc of `handleContRepeated`
gains the paragraph that states this rule, each application re-entering the region and a bracket
acquired inside one resumption released where that resumption's region ends, which the recovering
overload's scaladoc inherits by reference; and
`handleFirstRepeated` is untouched by construction rather than by a condition: a holding handler
runs its clause at `done`, after the region has exited, hands the continuation out, and its holder
re-establishes the region before applying it, as `Choice.runStream` does per iteration. Two drafts
preceded this shape. The first wrapped the continuation in `Eval`'s cont arm under
`handler.repeated`, which re-entered a region a holding handler cannot use and cost `runStream` 24%.
The second gated that on `!escaping`, which left a condition on the evaluator's hot arm and a
partial member on `ContHandler` (`resumed`, defaulting to `bug`) that an enumeration in the flags
table kept honest; the rehearsal lens put the wrap where the knowledge is, in the handler that
declares it repeats, and both went away. Every piece exists: `Pending.handle`, `Arrow.Step`, the
`repeated` flag. Two helpers join the `Handler` object beside `attachReentry`: `reentered(outer)`,
the re-entered handler, and `reentering(k, reentered)`, the wrapped continuation, an `Arrow.Step`
deferring on a pending input in the same arm as `Arrow.apply` and unnesting a settled one.

### Candidate B: delimited semantics for `handleContRepeated`

Make the multi-shot clause live outside the region, as `handleLoop`'s does: `k` returns the region's
output `B` with `done` applied per resumption, and the clause returns `B < (S & S2)`. This is the
standard algebraic-effects reading and needs no re-entered handler, but it changes `handleContRepeated`'s
signature and makes `done` run per resumption rather than once, which is a public-surface decision.

### What A costs, measured, and piece F

Three rows added to `KernelBench` enter a `handleContRepeated` region, which no row did before:
`repeatedRegionsPayEntry` (a region per operation), `repeatedRegionsPayEntryRecovering` (the same
through the recovering overload, whose handler and re-entered handler are their own classes, so the
flags table has a number for that allocation site rather than a borrowed one) and
`repeatedClausesPayReentry` (`suspensionBaseline`'s program under a repeated handler, ten thousand
operations each resumed once). Base against tip with the allocation profiler: the re-entered handler costs 16 bytes and 3.8 ns per region
entry (1.1 ns through the recovering overload); the re-entry costs 24 bytes and 54 ns per resumption, which makes `repeatedClausesPayReentry` 6.3 times
slower than the base leg (the earlier shape, with the wrap in `Eval`, cost 64 bytes and 61 ns, 7.2 times). The mechanism is the design: every application of the continuation enters a
region, and a region's entry and exit is what the existing rows `contextRegionsPayEntryExit` and
`emittingClausesPayRegionRebuild` measure at 73 and 89 ns. There is no cheaper frame that would do:
what stops an inner occurrence from capturing the clause's pending work is a handler on the stack
above that work, because a crossing packs every stack entry between an occurrence and the handler
that answers it into the continuation.

The base leg's number on that row is flat because the base is wrong: a clause with pending work between
resumptions captured that work into every inner continuation. The tree's one consumer,
`Choice.run`, paid the delimiter by hand, wrapping each resumption in a fresh `Choice.run`, which is
why `kyo-prelude`'s suite passed at the base with sequential choices. With the kernel delimiting,
that wrapper is a second region per resumption, so piece F removes it:

**F.** `kyo-prelude/shared/src/main/scala/kyo/Choice.scala`, `Choice.run`: the clause becomes
`Kyo.foreach(alternatives)(v => cont(v)).map(_.flattenChunk)`, one flatten, no inner `run`. Surface:
that method only. `runStream` keeps its shape: it hands the peeled continuation out and evaluates
the results outside the clause, under a fresh `handleFirstRepeated` per iteration, which is the
holder re-establishing the region, and the arm does not re-enter for a holding handler, so nothing
changes for it. `ChoiceBench` in `kyo-bench` is added for both rows, base against tip.

### Fork 5: the per-resumption region is the price of a kernel that delimits

Open for the user. Accepting A means every multi-shot resumption enters a region, 54 ns and 24
bytes, and a clause that resumes exactly once under `handleContRepeated` pays it too, where the base
ran flat; such a clause belongs under `handleCont`, and no consumer in the tree has that shape. The
alternative is the base's contract, documented rather than enforced: a repeated clause with pending
work between resumptions must re-enter a region itself, as `Choice.run` did, and a clause that does
not hangs. Recommendation: A, with F, because the kernel is then correct by construction for the
shape the matrix found, and the one consumer pays what it paid before.

### Fork 4, ruled: A

The user granted full autonomy for the overnight work ("add tests to repro issues and do fix them.
All is in your scope"), so this is decided here rather than parked. A is implemented: it preserves
every signature and today's `done` behaviour, confines its cost to `repeated` handlers, and every
piece of it already existed. B is recorded above as the alternative weighed, with the one thing that
would motivate it, `done` per resumption being the standard delimited reading, left for a later
decision on the public surface. Five cases in `ArrowEffectTest`'s `handleContRepeated` block pin the
fix: the two-occurrence shape (60), `done` once at the outer end against per-resumption (1060, not
4060), three occurrences (180), a throw after a second resumption reaching the outer `recover` (4),
and a hundred thousand sequential operations, which every sibling handler's block carries and which
matters here because a repeated region now nests a region per resumption. The matrix runs the
shape across every configuration.
