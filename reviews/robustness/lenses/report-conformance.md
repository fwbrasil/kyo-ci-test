# Lens report: kernel-conformance, round 4

PASS

Scope note first, because it decides the verdict. I judged the committed tree at `cdefdc9e60..HEAD`
against `reviews/robustness/derivation.md` as it stands on disk, which is what the dispatch names as
current. The disk copy is one paragraph ahead of the copy committed at HEAD, and that paragraph is
exactly what declares two of the code changes in the range. Against the disk copy the surface is
fully declared; against the copy committed inside the range it is not. C16 records that gap.
Everything else below holds under either copy.

I read the derivation, the diff of `kyo-kernel/shared/src/main`, `kyo-kernel/jvm/src/jmh`,
`kyo-kernel/CONTRIBUTING.md`, `kyo-kernel/shared/src/test`, and the further declared paths
(`build.sbt`, `project/TestKyo.scala`, `kyo-prelude/.../Choice.scala`, `kyo-data/.../Span.scala`,
`kyo-bench/.../ChoiceBench.scala`, `kyo-bench/.../SpanBench.scala`,
`kyo-net/.../RearmSurvivorsTest.scala`), and the files each hunk sits in, at the tip. No transcript,
no summary, no test or benchmark output.

## Findings

### C16. The declarations of the `build.sbt` setting and the `EffectTrace` link exist only in the working tree

`reviews/robustness/derivation.md`, disk lines 26 to 35, absent at HEAD, where the paragraph ends
four sentences earlier: "of a sentence to remember (R3). The kernel skill would be the other home for
the rule, but it is not tracked in this tree."

The range changes `build.sbt` at three sites (805, 1399, 3207) and
`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:21`, in the commits titled
"the Jmh configuration compiles into its own class directory" and "EffectTrace's doc links splice
through the companion, where it lives". A reviewer who checks out the tip and opens the derivation
that ships with it finds a build setting in three projects and a scaladoc edit in kernel source with
no declaration anywhere in the document.

The disk declaration itself checks out, so the substance is settled and only the commit is missing:
three projects enable `JmhPlugin` (`build.sbt` 819, 1390, 3191), all three and only those three carry
`Jmh / classDirectory := crossTarget.value / "jmh-classes"`, which is the spelling the disk paragraph
quotes; and `EffectTrace`'s change is the single link `[[splice]]` becoming `[[EffectTrace.splice]]`
with the paragraph rewrapped, nothing else in the file.

### C17. "The four pieces" heads five subsections, and a sixth piece is declared under a different heading

`reviews/robustness/derivation.md:11`: "## The four pieces".

Under it a reviewer counts five: A at 13, B at 37, C at 82, E at 125, D at 134. Piece F is declared
at 296, under "### What A costs, measured, and piece F" at 274, so the document's own enumeration of
what this change is made of gives six letters where its heading gives four, and a reader walking the
pieces by the heading stops one short of E and never reaches F.

### C18. "kyo-net is identical to main on this branch" is contradicted by the edit the same paragraph declares

`reviews/robustness/derivation.md:162`: "kyo-net is identical to main on this branch."

`git diff origin/main..HEAD -- kyo-net` returns exactly the file the three preceding sentences
declare, `RearmSurvivorsTest.scala`, 12 insertions and 3 deletions. The claim holds at `cdefdc9e60`,
where that diff is empty, and it is the reason the arm64 failure is attributable to the leaf rather
than to branch work, but it is written in the present tense about a tip where it no longer holds.

### C19. The rule the re-entered region imposes on brackets is pinned by a test and stated nowhere on the public surface

`reviews/robustness/derivation.md:240` to `244`: "The re-entered handler repeats, as the outer one
does: a continuation captured inside a re-entered region is resumed by the same clause more than
once, so what that region owes is held across every application and discharged where the re-entered
region ends, after the last of them, which a case in `BracketTest` pins."

That rule is now observable through the public combinator: `BracketTest.scala:1205` asserts a bracket
acquired inside one resumption releases before the outer clause resumes again. Its only written home
is `Handler.scala:323` to `329`, on a `private[kyo]` helper, and two comments inside the overloads at
`ArrowEffect.scala:207` and `262`. The scaladoc of `handleContRepeated`
(`ArrowEffect.scala:182` to `192`, unchanged in the range) still describes only the outer region
holding what it dumps into the continuation, which is a different obligation from a bracket acquired
after a resumption, and the derivation lists that scaladoc neither as changed nor as must-not-change.
A reviewer reading the public API sees no statement of the rule the new test pins.

### C20. "the second row" does not name the row whose cost is quoted

`reviews/robustness/derivation.md:282`: "the re-entry costs 64 bytes and 61 ns per resumption, which
makes the second row 7.2 times slower than the base."

The rows are enumerated immediately above, at 276 to 280, in the order `repeatedRegionsPayEntry`,
`repeatedRegionsPayEntryRecovering`, `repeatedClausesPayReentry`. By that order the second row is the
recovering region-entry row, which enters one region per operation and resumes each once, while the
cost quoted is per resumption, which is what the third row measures: it is `suspensionBaseline`'s
program, `Depth` being 10000 in `KernelBench`, with one resumption per operation. "the base" in the
same sentence is unanchored too, between the base leg of the run and the `suspensionBaseline` row the
third row is built from. A reviewer cannot tell which row carries the 7.2.

## The surface I enumerated

Code, with the piece that declares it.

| Path | Change | Declared by |
|---|---|---|
| `kyo-kernel/CONTRIBUTING.md` | item 13 gains two sentences, downstream suites and the benchmark compile | A |
| `project/TestKyo.scala` | `jmhCompileTasks`, and its call in the compile-test branch | A |
| `build.sbt` | `Jmh / classDirectory` in the three JMH projects | A, on disk only (C16) |
| `kyo-kernel/.../internal/EffectTrace.scala` | one doc link through the companion | A, on disk only (C16) |
| `kyo-kernel/.../test/.../internal/EvalShapeTest.scala` | new, the shape matrix | B |
| `kyo-kernel/.../internal/Handler.scala` | four re-entry tails, two new helpers | C |
| `kyo-kernel/.../internal/Handler.scala` | `reentered`, `reentering` | Finding, candidate A |
| `kyo-kernel/.../ArrowEffect.scala` | the two `handleContRepeated` overloads wrap their continuation | Finding, candidate A |
| `kyo-kernel/.../test/.../ArrowEffectTest.scala` | five cases in a new `handleContRepeated` block | Fork 4 |
| `kyo-kernel/.../test/.../BracketTest.scala` | one case in the existing `multi-shot clauses` block | Candidate A |
| `kyo-kernel/jvm/src/jmh/.../KernelBench.scala` | four `ContextEffect.handle` calls | E |
| `kyo-kernel/jvm/src/jmh/.../KernelBench.scala` | three new rows | What A costs |
| `kyo-prelude/.../Choice.scala` | `Choice.run`'s clause | F |
| `kyo-bench/.../ChoiceBench.scala` | new, `run` and `runStream` | F |
| `kyo-data/.../Span.scala` | `updated` checks its index | Also on the branch |
| `kyo-bench/.../SpanBench.scala` | new, `updated` | Also on the branch |
| `kyo-net/.../RearmSurvivorsTest.scala` | write armed first, the order asserted | Also on the branch |

Also in the range: `reviews/robustness/**`, the review package itself, which is the document I judge
against and its evidence rather than a code surface. The derivation's sentence at 165, "so the
range's surface is fully declared and fully applied", reads over those files; they are self-evidently
the review's own instrument and I raise no finding on them.

Per piece.

- **A.** The CONTRIBUTING line gains exactly two sentences and nothing else in the file moves. The
  first names `kyo-preludeJVM/test` and `kyo-coreJVM/test` with `Batch.run` as the example; the second
  names the compile-test phase and `kyo-kernelJVM/Jmh/compile`. `jmhCompileTasks` is gated on
  `phase != "compile-test"`, finds projects through `allProjectRefs` and `ivyConfigurations` rather
  than from a list, and emits `<module>/Jmh/compile`, which is what the derivation says the rehearsal
  lens asked for.
- **B.** `EvalShapeTest.scala` at the declared path, prefix matching `internal/Eval.scala`. Ten
  scenarios, exactly the declared axes: `handleCont` once and never, `handleContRepeated` once, twice
  and never, `handleLoop` continue and done-from-clause, `handleLoopState` the same two, and one Mask
  scenario tunnelling a `handleCont` past an inner handler. `n` runs over 0 to 3 and eight
  configurations per cell: base, a binding above, a region above, the three suspending variants, and
  two inner-handler variants, which is the 320 the derivation states. The inert regions are a
  `ContextEffect` binding for a tag never read and a `handleCont` for an effect never performed, both
  as declared. No expected value is written by hand: every cell reads `law`, `lawState` or `runs`,
  and `law(List(7,8))(2)` gives the 60 the derivation quotes for the two-occurrence shape.
- **C.** Exactly the four declared sites change, to `attachReentryToPending` at `Handler.scala:203`
  and `491` and `attachReentryToPending2` at `274` and `569`. Both helpers are `private[kyo] inline`,
  at `394` and `421`, each beside the `attachReentry` it is the settled fast path of. The two unfused
  `Continue` arms are untouched, as the derivation's correction of its own earlier draft requires.
  Must-not-change holds: `attachReentry` and `attachReentry2` are unchanged and are now callees only,
  `clauseDispatch`, `Suspend.crossing`, `Eval.scala` and every node class are absent from the diff,
  and no public signature moves.
- **D.** Not attempted, as fork 3 records. `EvalShapeTest.scala` is the only new file under
  `kyo-kernel/shared/src/test`, and it contains no evaluator of its own. No partial interpreter is in
  the diff.
- **E.** Four `ContextEffect.handle` calls change from two argument lists to one, three in
  `contextReadsUnderBindings` and one in `contextRegionsPayEntryExit`. Those are the only four calls
  the file makes, and none is left in the old shape.
- **F.** `Choice.run`'s clause becomes
  `Kyo.foreach(Chunk.from(input))(v => cont(v)).map(_.flattenChunk)`, one flatten and no inner `run`,
  and `Choice.scala` changes in that method only. `runStream` keeps its shape.
- **Candidate A.** The wrap is where the derivation puts it after the rehearsal lens moved it: in the
  handler, in `run`, in both `handleContRepeated` overloads and nowhere else. `reentered` is built
  once as a `val` on the handler, is `outer` with `done` as identity and `repeated` true, and carries
  no `recover` on the recovering overload. `reentering` is an `Arrow.Step` that defers a pending
  input and, on a settled one, applies `Pending.handle(k(unnest(v)), reentered, ())`, which is the
  declared equation. `Eval.scala` and the `ContHandler` trait are untouched, `handleFirstRepeated` at
  `ArrowEffect.scala:951` still sets `repeated` true at 977 and takes no wrap, and neither leftover of the two
  rejected drafts is present: `Handler.scala` has no `resumed` member and no `escaping` gate on the
  new path, and Eval's `repeated` arm at 548 is as it was.
- **Fork 4's five cases.** `ArrowEffectTest.scala:177` opens the only `handleContRepeated` block in
  the file and carries exactly the five declared cells: 60, 1060 against the 4060 a per-resumption
  `done` would give, 180, 4 from the outer `recover`, and 100000 sequential operations. The
  `BracketTest` case at 1205 asserts the release order the candidate describes.
- **Outside the kernel.** `Span.updated` raises before allocating, with `Chunk.updated`'s check shape
  and its message text verbatim modulo the index name. `SpanBench.updated` walks a sixteen-element
  span with `(i + 1) & 15`, so no row reaches the throw. `RearmSurvivorsTest` arms write before read
  and asserts the log order, and the leaf's own property is unchanged.

## Forks

Fork 1 and fork 2 are declared as not touched, and nothing in the diff touches the `Arrow.apply`
overload pair or the `PollTest` ascriptions. Fork 3 is ruled not attempted and no interpreter
appears. Fork 4 is ruled A and only A is implemented: candidate B would have changed
`handleContRepeated`'s signature and made `done` run per resumption, and neither is in the tree.
Fork 5 is left open for the user and needs no code, which is what the tree shows.
