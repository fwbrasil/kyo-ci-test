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
module found it). `build.sbt` gives the `Jmh` configuration its own class directory on kyo-kernel
(`Jmh / classDirectory := crossTarget.value / "jmh-classes"`), and on kyo-kernel alone: the same setting
was first applied to kyo-ffi-bench and kyo-bench for consistency and found, live in the review, to
break their benchmark discovery, because their benchmarks live under `src/main` and only the
generated list moves (rulings.md, 2026-09-12); verified on kyo-kernel by a clean
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
`SpanBench.updated` (kept in the package as `bench/src/SpanBench.scala`, not in kyo-bench, by the reviewer's ruling of 2026-09-12), every index in bounds, prices that check on a sixteen-element
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
changes for it. `ChoiceBench` measured both rows, base against tip; the class is kept in the package (`bench/src/ChoiceBench.scala`), not in kyo-bench, by the reviewer's ruling of 2026-09-12.

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

## Second round: what the full-matrix CI run surfaced

Run 34703603537 on tip 8b6055f0d4 failed on every Linux job and passed on Windows: `SqlClientInterruptTest`
on JS and Wasm (both arches), `TypeAdtFidelity2Test` on x64 JVM, `ReactiveUITeardownTest` on arm64 JVM,
`ItStructPtrTest` on both Native jobs. Each was reproduced or traced to a mechanism before anything
changed, and each has a pin. Pieces continue the lettering above.

### I. The release walk runs nothing (kernel source)

The equation. Releasing an abandoned computation releases what it holds: the regions installed around
the node in hand, the debts those regions carry, and an `Ensure` that already has its value. A deferral
holds its body in an arrow and has not run it, so it holds nothing to release, and a join under it is not
waited on yet, so there is nothing to link:

    release(defer(thunk))          = ()
    release(defer(v, ensure))      = ensure(v)                  v settled: the obligation has its value
    release(Handle(h, body))       = release(body); release(h)
    release(Park(v, entries, owed)) = release(v); release(owed); release(entries)

The base spent a budget of sixteen steps running deferrals to reach an operation under them (5b629308cb,
"reach a fiber's join through the deferrals above it"). A step is the caller's code. One such step was
`Channel.take`'s deferral: kyo-sql's `takeSlot` wraps the take in `Async.timeoutWithError`, which spawns a
fiber; interrupting the query before that fiber's first slice abandoned it, the walk ran its first
deferral, which polled the permit out of the slot channel, and stopped, with the continuation that
registers the permit's return never applied. The pool then waited its whole close grace for a permit
nobody held, which is the 30 second `SqlClientInterruptTest` hang on JS and Wasm, where the runtime is
single-threaded and the interrupt always lands before that fiber's first slice. On the JVM the fiber
usually runs first, which is why the suite was green there.

Why the suites did not catch it: the commit that added the budget recorded a `MeterTest` permit leak as
red and undiagnosed, and `SyncTest`'s "runs its finalizer for a fiber abandoned before its first slice"
says in its own comment that the walk stops at a deferral. The four `EvalTest` pins that asserted stepping
were written against the budget: three park in front of an acquire that is an unrun thunk and demand the
walk run it to obtain a value to release, one asserts a deferral is run to reach the operation behind it.

Surface: `Eval.release`, the private walk and the two public overloads' documentation. The step arm, the
budget parameter and the safepoint save around the walk go; `ensuring` stays, since an `Ensure` whose
value is in hand is a release owed. `IOTask.abandon`'s documentation follows: the join it stands at is
linked, one it has not reached is not.

Pins: `EvalTest` "a release waiting on a value that already arrived is found on abandonment", "a release
the abandoned Ensure installs rather than registers is still run", and "a release for a resource that is
itself a computation receives the computation, not its box" take the settled shape the walk serves,
built with `Effect.defer(value, Arrow.ensure(...))`; "an acquire the park stopped in front of is neither
run nor released on abandonment" is the negative; "does not run a deferral to reach the operation behind
it" replaces the two budget pins. `BracketTest` "releases a region dumped into the continuation of a park
at a region below it" pins the debt path the boundary's join park relies on, which was the first
hypothesis and holds at the base. Evidence: `kyo-kernelJVM/test` 1838 green, kernel JS pins green,
`kyo-sql-postgresJS` `SqlClientInterruptTest` green, close in 3 ms where it was 30 008 ms.

Every "spawn or claim, then register in a later step" shape in kyo-core is exposed by this, since the
budget used to run the later step for free. `Async.timeoutWithError` already registers in the step that
spawns (its own comment says so); `Sync.ensure` (K) and `Scope.Finalizer.close` (L) did not.

Two `ScopeInterruptTest` leaves were written against the budget as well: "an interrupt landing while the
acquire's last step runs" requested the interrupt one step before the value and expected that last step
to still run under abandonment, which is the stepping. Under the honest walk the acquire never produces
and nothing leaks, so their `> 0` premise cannot hold. They now request the interrupt from inside the
step that produces the value, the one shape in which the interrupt lands as the value arrives, and a
third leaf pins the multi-step shape: an acquire interrupted a step before its value produces nothing
and releases nothing.

### J. A stale stop is superseded by the running slice's own (kernel jvm-native)

`Safepoint.stop` on jvm-native answered a request for a thread that already had a pending stop with
`true` and no replacement. A stop addressed to a slice that has ended can land after the next slice began
on that thread, and it then sits in the slot unhonored, since `honored` checks the slice. The fiber
boundary requests a stop for its own slice as it parks on a join; that request found the stale one, was
answered `true`, and the evaluator's check saw a stop it could not honor, so the join was re-raised and
dispatched straight back to the boundary, every round nesting the previous re-raise's continuation
inside the next. The rounds continued until the promise completed, and delivering the value then
recursed through every level: the `StackOverflowError` on a scheduler worker in `TypeAdtFidelity2Test`,
1068 frames of `IOTask$$anon$2.apply` over `SuspendArrowWith.apply` in the CI log, capped by the JVM's
trace depth.

Equation: a request from the slice's owner for its own slice supersedes a pending stop that names another
slice, and a wildcard supersedes any addressed one; a pending wildcard, or one naming the same slice,
already answers the request. Surface: the `pending: Stop` arm of `Safepoint.stop`. Pins: `SafepointTest`
"a stop for the running slice supersedes a stale one left by a departed slice" and "a wildcard stop
supersedes a stale one left by a departed slice".

Open on the replacement's scope: a stopper on another thread cannot tell a stale pending stop from the
running slice's own, since `slices` is owner-only, so a late delivery from another thread must not
replace; the owner can tell, because only one slice runs on its thread. The replacement is restricted to
the owner (`thread eq Thread.currentThread()`); a request from another thread against a pending stop
keeps the base's answer. That restriction is applied after the scope run below completes, since a
kernel edit under a running build is not a state to measure.

### K. `Bracket.ensuringWith`, and `Sync.ensure` on it (kernel and core)

With I in place, `SyncTest` "runs its finalizer for a fiber abandoned before its first slice" timed out:
`Sync.ensure` built its region inside `Sync.Unsafe.defer`, to make the slot its finalizer reads per run,
so the region sat under a deferral and a fiber abandoned before its first slice never reached it. Its own
comment claims the region is a node from the start, which the deferral broke; the budget used to step
through.

The piece with no counterpart: a region installed from the start whose release is owed a value the body
produces per run, which the kernel's `Maybe[Throwable]` release channel does not carry. `Bracket.apply`
has per-run state but installs its region only when the acquire settles; `ensuring` installs from the
start but has no state. `ensuringWith(init)(release)(body)` is the missing middle: the state is made in
`derive`, as the region is entered, and the body reads it back from the region as its first step, so
nothing sits between the region and what it owes. `Cell.Live` carries the state; `apply` passes the
acquired value through it, saving the closure it built per run; `ensuring` passes unit.

Surface: `Bracket.scala` (`Cell.Live[R]`, `apply`, `ensuring`, the new `ensuringWith`, `region`'s body row
now `Finalize & S`), `Sync.ensure`. Pins: `BracketTest` "ensuringWith" block, four cases: the state reaches
the body and the release, each run makes its own, a throw releases with the state, and an abandonment
before a step releases with a fresh state and runs nothing. Evidence: `SyncTest` 44 green, `FiberTest`
112 green, `MeterTest` 42 green, kernel pins 250 green.

Cast: `cell.asInstanceOf[Cell.Live[R]]` in the read is erasure-forced; the read is the first step under
the region that bound the cell it made with `init`.

### L. `Scope.Finalizer.close` registers its drain in the step that claims the backlog (core)

With K in place, `ScopeTest` "an interrupt racing the close does not stop the drain" (#1928) timed out in
two of six runs, with registered 1000 and released 998 polled forever. Traced with per-scope prints: in
every lost round the fiber's own close claimed the queue's backlog, the abandonment's close arrived
before the claim's promise completed, and no drain fiber was ever spawned. `close` was two steps:
`queue.close()` claims the backlog and completes the promise, then `.safe.onComplete`, which is
`Sync.Unsafe.defer(...)`, registers the drain as a step of its own. A stop landing between them parks
the fiber on the registration deferral; the abandonment then runs nothing, and the backlog sits
completed with nobody to drain it. The budget used to run that deferral during abandonment, which is
why the base passed. Same class as #1820: claim and registration must be one step.

Surface: `Finalizer.Unsafe.init`'s `close`: the unsafe `onComplete`, registered synchronously inside the
same `Sync.Unsafe.defer`, evaluating the continuation with `Sync.Unsafe.evalOrThrow` as the safe
`onComplete` does. The guard is the existing #1928 leaf's thousand rounds, which caught it; a
deterministic pin needs a stop delivered between the claim and the registration, and no seam exists
to place one there, so the rounds stay the pin.

### M. kyo-ffi Native transient callback registry (build definition and kyo-ffi)

`ItStructPtrTest` "struct with opaque field" failed with `NoSuchElementException` on both Native jobs,
the same class as the kyo-ffi Native abort traced in `analysis/ffi-native-trace.md`: a Scala Native
`ThreadLocal` lost an entry mid-call, on the same thread. The transient stacks are keyed by thread in a
`ConcurrentHashMap` instead, the trampoline's peek is inside its `try` so a missing entry is reported
rather than aborting the process, and `FfiGenErrors.reportCallbackFailed` cannot itself throw. Surface:
`project/CallbackShapesGen.scala`, `FfiGenErrors.scala`. Evidence: the kyo-ffi Native suite, 159 passed,
0 failed, 6 cancelled. Not yet rerun: `kyo-ffi-itNative`.

### N. `ReactiveUITeardownTest` reads the waiter count once it has settled (ui test)

"bound element replacement does not recursively subscribe to itself" read `ref.waiters` right after the
second render, inside the gap where the observer re-registers on the signal's fresh promise, and asserted
one. The observe loop is the repairing form, which re-reads `current` after every wake and reconciles a
missed wakeup within the repair interval, so the gap cannot lose an update; the read was the race. The
test now waits for the count to settle before reading it, as its first read already did; a recursive
subscription would hold the count above one and fail the wait. Not yet rerun locally.

### Not in this round

The deferred fiber completion the user directed (`Finalizing` status in `IOTask`, the promise completed
only after the remainder is released, `Fiber.interruptAwait`, `Fiber.init` without its `ended` promise)
is designed and not built; it follows this package.
