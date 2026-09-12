# Lens report: kernel-conformance

PASS

Round 2, over `cdefdc9e60..1b1373db43`. The findings below are numbered from C9: round 1 used C1 to C8
on this lens, and reusing those ids for different content would make them unstable. Both findings are
against the derivation's text, not against the code: every piece the derivation names is implemented as
the composition it states, every declared item is present, nothing in the diff is undeclared, each fork is
taken on its ruled side only, and no reference interpreter is in the tree.

## Findings

### C9. The cont arm's equation is stated twice in one paragraph, in two forms, and the code is the second

`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:118`

The derivation states the arm as: "so that `Eval`'s cont arm is one line, `if handler.repeated then
handler.reentering(raw) else raw`". The line reads:

    val continuation = if handler.repeated && !handler.escaping then handler.reentering(raw) else raw

The conjunct is declared four sentences earlier in the same paragraph ("the arm re-enters when `repeated
&& !escaping`", with `handleFirstRepeated` as the handler it excludes and a measured reason for excluding
it), so the design is on the record and the code implements it. What is not on the record is the quoted
one-line form, which is the earlier draft of the same line. A reviewer who reads the derivation's stated
equation and then greps the arm sees a predicate with a second term and has to find the other sentence to
learn it was ruled.

### C10. The "Surface, exactly" list for piece C names members that do not exist

`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Handler.scala:229`, `:300`

The derivation's surface list says: "`Handler.LoopHandler.answers`: the `case o =>` tail becomes
`reenter(k, o)`" and "`Handler.LoopStateHandler.answers`: the `case o2 =>` tail becomes `reenter2(k,
o2)`". The tails call `attachReentryUnlessSettled` and `attachReentryUnlessSettled2`. The rename is
declared in the prose two paragraphs above the list, with its reason ("The name is not `reenter`, the
working name this section first used"), and the final names appear there, so the code follows the
derivation. The list itself was not carried forward. A reviewer walking the surface list, which is the
part of the section that says "exactly", finds two names with no definition in the tree.

## The surface I enumerated

The diff over the brief's paths plus the two paths the dispatch added (`kyo-prelude/.../Choice.scala`,
`kyo-bench/.../ChoiceBench.scala`) and the two declared under "Also on the branch, outside the kernel"
(`kyo-data/.../Span.scala`, `kyo-net/.../RearmSurvivorsTest.scala`) is eleven files. Line numbers are at
the tip.

### Declared and present

**A, the verification rule.** `kyo-kernel/CONTRIBUTING.md:441`, checklist item 13. Two sentences added and
nothing else on the line: the first requires `kyo-preludeJVM/test` and `kyo-coreJVM/test` for a change to
the evaluator, the handlers or the representation, with `Batch.run` as the example consumer; the second
requires `kyo-kernelJVM/Jmh/compile` for a public signature change, with the reason that neither `test`
nor CI's test action compiles the benchmark sources. Both sentences are the ones the derivation declares,
in that order.

**B, the shape matrix.** New file `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalShapeTest.scala`,
231 lines, prefix `Eval` for the source beside it. Members: the effect under test `Ask` and `ask`; the
outside-answered `Say` and `say`; the two interloper tags `Cfg` and `Idle`; `record` (the
`handleLoopState` log that counts clause runs); `prog(n)` (n consecutive occurrences); `cfgAbove` (a
`ContextEffect.handleInheritable` binding never read); `idleAbove` (a `handleCont` region never
performed); `innerAbove` (an inner handler for the same tag, answering 1000); the three laws `law`,
`lawState` and `runs`; the `Scenario` record and the `counter` done function; `scenarios`, ten entries;
and the loop that runs each scenario for n in 0, 1, 2, 3 under eight cells.

The ten scenarios are the derivation's axes restricted to the arms that exist: `handleCont` resuming once
and never; `handleContRepeated` resuming once, twice and never; `handleLoop` continuing and ending from
the clause; `handleLoopState` threading a counter and ending from the clause; and the single `Mask`
scenario tunnelling a `handleCont` past an inner handler for the same tag. The eight cells are the
derivation's eight: base, a binding above, a region above, the three suspending variants of those, and two
with an inner handler for the same tag. Ten times four times eight is the declared 320 cells, and every
cell asserts an equality against a value the laws produce. No expected value of `prog(n)` is written by
hand: `expected` is `law(...)` or `lawState(...)` per scenario, `inner` likewise, `says` is `runs`, and
`counter` is passed both to the handler and to `lawState`. `law`'s body is the fold the derivation states
(each resumption's value once per path below it, plus the rest's total, and `ends` for an empty
`resumes`).

**C, one re-entry path.** `Handler.scala`. `attachReentryUnlessSettled` at `:382` and
`attachReentryUnlessSettled2` at `:409`, both `private[kyo] inline`, both placed immediately after the
`attachReentry` and `attachReentry2` they specialise, each body the pending test, the `attachReentry` call
and the settled cast, unchanged from what the four sites spelled. The four tails, and only the tails:
`LoopHandler.answers:229`, `LoopStateHandler.answers:300`, `answersLoop:479`, `answersLoopState:557`. The
two unfused `Continue` arms above the tails at `:221-227` and `:292-298` are untouched, as the section
requires. A grep for `attachReentry` returns exactly these four helper call sites, the two helper bodies,
the two originals, and the two direct `Eval` calls.

**D, the reference interpreter.** Absent. The only test file added is `EvalShapeTest.scala`; no direct
evaluator, no differential harness, no partial version of either.

**E, the benchmark class compiles.** `KernelBench.scala:468`, `:469`, `:470`, `:481`. Four
`ContextEffect.handle(Tag[X])(...)` calls become `ContextEffect.handle(Tag[X], ...)`, which is the
signature at `ContextEffect.scala:269`. Four calls, that file only, no other edit in those methods.

**F, `Choice.run`.** `kyo-prelude/shared/src/main/scala/kyo/Choice.scala:99`. The clause is
`Kyo.foreach(Chunk.from(input))(v => cont(v)).map(_.flattenChunk)`: one flatten, no inner `Choice.run`,
which is the equation the derivation gives. That method only; `runStream` is not in the diff.
`kyo-bench/src/main/scala/kyo/bench/ChoiceBench.scala` is new, 27 lines, with the two rows declared, `run`
and `runStream`, over ten sequential binary choice points.

**The multi-shot re-entry fix, candidate A.** Two members added to `ContHandler` and no more:
`resumed` at `Handler.scala:94`, defaulting to `bug`, and `reentering` at `:104`, `private[kyo]`. The
import of `kyo.bug` at `:7` is what the default needs. `reentering` composes the derivation's equation out
of existing values: a pending input defers through `Effect.defer(p, this, cont2)`, which is the arm
`Arrow.apply` uses at `Arrow.scala:171`, and a settled one is unnested, applied to the raw continuation,
and handed to `Pending.handle(..., twin, ())`, whose settled case is `done` and whose pending case builds
a `HandleArrow` region node. `twin` is read once per suspension, not per resumption.

The two `handleContRepeated` overloads define `resumed` as a `val`, so one twin per region entry:
`ArrowEffect.scala:212` for the plain overload and `:273` for the recovering one, each with an `outer =>`
self alias at `:206` and `:266` so the twin's `run` delegates to the outer clause. Each twin has `done` as
identity, `repeated = true`, and `resumed = this`. Neither twin declares `recover`, which is what the
derivation states for the recovering overload. `handleFirstRepeated` at `:986-987` is untouched and
defines no twin, which is consistent with the arm excluding an escaping handler.

`Eval.scala:113-118`: the cont arm's `continuation` becomes `raw`, two comment lines are added, and the
new `continuation` is the branch discussed in C9. The arm is the only part of `Eval` in the diff.

`ArrowEffectTest.scala:177-222`: a new `"handleContRepeated"` block, four cases, appended and displacing
nothing. Three pin the fix (two consecutive occurrences at 60, three at 180, and `done` once at the outer
end at 1060 against the 4060 a per-resumption `done` would give) and the fourth pins a throw after a
second resumption reaching the outer `recover`. Those are the three named cases plus the recover case the
derivation declares in the candidate A paragraph.

`KernelBench.scala:564` and `:573`: the two rows declared, under the declared names.
`repeatedClausesPayReentry` is `suspensionBaseline`'s program, `Depth` being 10000, under
`handleContRepeated` with a clause resuming once. `repeatedRegionsPayEntry` enters a region per operation
at `NarrowDepth`, which is `contextRegionsPayEntryExit`'s shape.

**Outside the kernel.** `kyo-data/shared/src/main/scala/kyo/Span.scala:981-983`: an index check in
`updated` raising `IndexOutOfBoundsException`, with the message text and shape of `Chunk.scala:174`.
`kyo-net/jvm-native/src/test/scala/kyo/net/internal/posix/RearmSurvivorsTest.scala:46-54`: the first leaf
arms write before read, with the ordering reason in place; the second leaf and the rest of the file are
untouched, and kyo-net at the base is byte-identical to `origin/main`, which is the reading under which
the derivation's sentence about kyo-net holds.

### Declared and not present

None. Every item the derivation declares as changing is in the diff, and D is declared as not attempted
and is not there.

### Present and not declared

None in the brief's paths. The range also adds twenty files under `reviews/robustness/`, which is the
review package the derivation itself lives in and which the brief's diff does not cover, so I did not
judge them.

### "Must not change", checked

- `attachReentry` (`Handler.scala:357-373`) and `attachReentry2` (`:390-406`): bodies identical to the
  base, now reached only through the two helpers and the two `Eval` sites.
- `clauseDispatch`: not in the diff.
- `Suspend.crossing`: `PendingInternal.scala` is not in the diff.
- The two `Eval` not-at-top arms: `Eval.scala:188` and `:256` still call `attachReentry` and
  `attachReentry2` directly on a value known to be pending, unchanged.
- Node classes: none in the diff.
- Public surface: `handleContRepeated`'s two signatures are unchanged, including the `done` arity, and so
  is `Choice.run`'s. The added members sit on `Handler.ContHandler`, inside a `private[kernel]` object
  whose enclosing class is `private[kernel]`, and `reentering` is `private[kyo]`. The one public behaviour
  change in the range is `Span.updated`, which is declared in its own section.

### Forks, checked

1. **The overload pair.** Untouched. `Arrow.scala` is not in the diff, and the existing `ArrowEffectTest`
   contract cases are unedited; the file's only change is the appended block.
2. **The `PollTest` ascriptions.** Left in place. `PollTest` is not in the diff.
3. **Whether D ships.** Not attempted, and nothing partial of it is in the tree.
4. **Candidate A against candidate B.** A only. B would have changed `handleContRepeated`'s signature and
   made `done` run per resumption; the signature is unchanged and the 1060 case pins `done` once.
5. **The per-resumption region.** A with F, which is the recommendation the section records: the arm
   re-enters, and `Choice.run` drops its hand-rolled delimiter while `runStream` keeps its shape.
