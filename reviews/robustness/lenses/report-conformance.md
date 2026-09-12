# Lens report: kernel-conformance, round 5

FAIL

Round 5. Tip `b167efef697bdb3ab7e8d5fcbeadb6f1519a4188`, base `cdefdc9e60`.

The substitution check passes: every piece the derivation names (A, B, C, E, F, H, candidate A) is in
the code as the composition the derivation writes, the forks take the ruled side and only that side,
and no reference interpreter is in the diff. The verdict is FAIL on the surface check alone. Two
changes in the range are declared nowhere in the derivation, one of them 330 lines of new tracked
files under `kyo-kernel/` whose existence a derivation sentence denies, and the other a paragraph on
a public scaladoc.

I read `reviews/robustness/derivation.md` at the tip, the diff over the declared paths, the diff
`--stat` over the whole range, and the kernel files each hunk sits in, at the tip. I ran
`sequence.py --verify cdefdc9e60 HEAD` (VERIFIED, 34 edits) and `flags.sh cdefdc9e60..HEAD --
kyo-kernel/shared/src/main/scala` (9 rows). No transcript, no summary, no test or benchmark output.
The derivation on disk is byte-identical to the derivation at HEAD this round, so round 4's scope
note does not recur.

## Findings

### C21. Three new tracked files under `kyo-kernel/.claude/skills/kernel/` are declared nowhere, and the derivation says they are not there

`reviews/robustness/derivation.md:37` to `38`: "The kernel skill would be the other home for the
rule, but it is not tracked in this tree."

The range adds `kyo-kernel/.claude/skills/kernel/flags.sh` (78 lines),
`package-check.sh` (131 lines) and `rulings.md` (121 lines), all three in commit `f6e9fb0041`, none
of them present at `cdefdc9e60` (`git ls-tree -r cdefdc9e60 -- kyo-kernel/.claude` is empty). No
piece declares them. `package-check.sh` is named once, at `derivation.md:220`, in the evidence list,
as a tool the package runs, which declares an activity rather than a file added to the module under
review. `flags.sh` and `rulings.md` are not named at all.

The quoted sentence is the reason piece A gives for putting the verification rule in
`CONTRIBUTING.md` rather than in the skill, so it is load-bearing for a decision, not a passing
remark. At the tip the skill's directory carries three tracked files and only its `SKILL.md` remains
untracked. A reviewer who reads the derivation and then the `--stat` sees 330 lines of agent tooling
entering the `kyo-kernel` module with no declaration, one of them (`rulings.md`) a rules document
that quotes the reviewer verbatim, and a sentence telling them not to expect any of it.

### C22. The new scaladoc paragraph on `handleContRepeated` is a public-surface change no sentence declares

`reviews/robustness/derivation.md:254` to `255`: "Confined to the two `handleContRepeated`
overloads: their handler wraps the continuation it hands the clause, in `run`, so that each
application re-enters".

`kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:189` to `193` adds a five-line
paragraph to the public scaladoc of `handleContRepeated`, stating the re-entry rule and where a
bracket acquired inside a resumption is released. Candidate A declares a change to the two overloads'
`run`, and `derivation.md:262` to `265` gives the rule's written home as a test: "what that region
owes is held across every application and discharged where the re-entered region ends, after the last
of them, which a case in `BracketTest` pins." The only scaladoc change the document declares anywhere
is `EffectTrace`'s doc link, at `derivation.md:35` to `37`. So the change that answers round 4's C19
is in the code with nothing in the derivation saying it was made, which is the same shape as round
4's C16 one level up: a reviewer diffing the public documentation of a public combinator finds a new
paragraph and no declaration to check it against.

The paragraph itself matches the code and the pinned test: `reentering` at `Handler.scala:348` enters
a fresh region per application, and `BracketTest.scala:1205` pins the release of a bracket acquired
in the first resumption before the outer clause's second application.

### C23. The piece enumeration does not account for H

`reviews/robustness/derivation.md:13` to `14`: "A to D are the list's items; E and F were added as
the work found them, E under this heading and F under "What A costs", beside the measurement that
motivated it."

Under `## The pieces` a reviewer counts six subsections: A at 16, B at 40, C at 85, E at 128, D at
137, H at 146. The sentence that tells the reader which letters exist and where each lives names A,
B, C, D, E and F, and never mentions H, which is a kernel source change with its own reproduction.
The letter G is unused, so a reader who notices H's letter has nothing to resolve it against either.
This is round 4's C17 in its next form: the heading no longer undercounts, and the enumerating
sentence now does.

### C24. "`Eval` and `ContHandler` are unchanged" is contradicted by the piece four sections later

`reviews/robustness/derivation.md:274`: "`Eval` and `ContHandler` are unchanged, so the single-shot
path pays nothing, and".

`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:646` changes, and its comment at
`631` to `635` with it. Piece H declares that edit at `derivation.md:159` to `160` ("Surface:
`Eval.release`'s `ensuring` and `EvalTest`; nothing on any evaluation path"), so the substance is
settled and the two sentences are about different pieces. The sentence at 274 carries no such scope:
it is a bare claim about the file, in the present tense, about a tip where the file has moved. This
is round 4's C18 in a new place, and the fix that closed C18 (naming what the claim covers, "kyo-net's
sources are identical to main on this branch") is the shape this one is missing.

### C25. The flags table's verdicts for F1 and F2 cite an evidence file that does not exist

`reviews/robustness/derivation.md:221`: "- the flags table, every row adjudicated".

`reviews/robustness/flags.md`, rows F1 and F2, each end their verdict by deferring the time cells to
`bench/compare-rows-base-vs-tip-clean.md` ("The clean rerun, `bench/compare-rows-base-vs-tip-clean.md`,
replaces the time cells"). No such file is in the range and none is in the working tree;
`reviews/robustness/bench/` holds `compare-rows-base-vs-tip.md` and `compare-rows-base-vs-AC.md` and
no `-clean` file at all. So the two rows carrying the allocation and time numbers that piece A's cost
section quotes point at evidence a reviewer cannot open.

This one is scoped: `reviews/robustness/**` is the review's own instrument rather than a code
surface, and I raise no other finding on those files. It is here because the derivation names the
flags table as evidence the package carries, because the defect is a file-existence fact rather than
a judgement about any number, and because it is exactly the class `package-check.sh` was written to
catch.

## The surface I enumerated

Every path in `git diff cdefdc9e60..HEAD --stat`, with the sentence that declares it.

| Path | Change | Declared by |
|---|---|---|
| `kyo-kernel/CONTRIBUTING.md` | item 13 gains two sentences | A, 18 to 28 |
| `project/TestKyo.scala` | `jmhCompileTasks` and its call in the compile-test branch | A, 26 to 29 |
| `build.sbt` | `Jmh / classDirectory` in the three JMH projects | A, 33 to 35 |
| `kyo-kernel/.../internal/EffectTrace.scala` | one doc link through the companion | A, 35 to 37 |
| `kyo-kernel/.../test/.../internal/EvalShapeTest.scala` | new, the shape matrix | B, 42 |
| `kyo-kernel/.../internal/Handler.scala` | four re-entry tails, two new helpers | C, 117 to 120 |
| `kyo-kernel/.../internal/Handler.scala` | `reentered`, `reentering` | Candidate A, 283 to 286 |
| `kyo-kernel/.../ArrowEffect.scala` | the two overloads wrap their continuation | Candidate A, 254 to 256 |
| `kyo-kernel/.../ArrowEffect.scala` | a paragraph on `handleContRepeated`'s scaladoc | nothing (C22) |
| `kyo-kernel/.../internal/Eval.scala` | `ensuring` unnests, and the walk's comment | H, 158 to 161 |
| `kyo-kernel/.../test/.../internal/EvalTest.scala` | one case, the boxed resource | H, 155 to 158 |
| `kyo-kernel/.../test/.../ArrowEffectTest.scala` | five cases in a new `handleContRepeated` block | Fork 4, 341 to 345 |
| `kyo-kernel/.../test/.../BracketTest.scala` | one case in `multi-shot clauses` | Candidate A, 264 to 265 |
| `kyo-kernel/jvm/src/jmh/.../KernelBench.scala` | four `ContextEffect.handle` calls | E, 130 to 132 |
| `kyo-kernel/jvm/src/jmh/.../KernelBench.scala` | three new rows | What A costs, 297 to 302 |
| `kyo-prelude/.../Choice.scala` | `Choice.run`'s clause | F, 317 to 318 |
| `kyo-bench/.../ChoiceBench.scala` | new, `run` and `runStream` | F, 322 |
| `kyo-data/.../Span.scala` | `updated` checks its index | Also on the branch, 165 to 170 |
| `kyo-bench/.../SpanBench.scala` | new, `updated` | Also on the branch, 172 to 173 |
| `kyo-net/.../RearmSurvivorsTest.scala` | write armed first, the order asserted | Also on the branch, 175 to 183 |
| `kyo-kernel/.claude/skills/kernel/flags.sh` | new, 78 lines | nothing (C21) |
| `kyo-kernel/.claude/skills/kernel/package-check.sh` | new, 131 lines | nothing (C21) |
| `kyo-kernel/.claude/skills/kernel/rulings.md` | new, 121 lines | nothing (C21) |
| `reviews/robustness/**` | the review package | the review's own instrument |

Per piece, what I checked at the tip.

- **A.** CONTRIBUTING item 13 gains exactly two sentences and nothing else in the file moves; the
  first names `kyo-preludeJVM/test` and `kyo-coreJVM/test` with `Batch.run` as the example, the
  second names the compile-test phase and `kyo-kernelJVM/Jmh/compile`. `jmhCompileTasks`
  (`TestKyo.scala:369`) is gated on `phase != "compile-test"`, finds projects through
  `allProjectRefs` and `ivyConfigurations` rather than from a list, and emits `<module>/Jmh/compile`.
  `Jmh / classDirectory := crossTarget.value / "jmh-classes"` appears at `build.sbt:805`, `1399` and
  `3207`, which are the three projects that enable `JmhPlugin`, and nowhere else. `EffectTrace`'s
  change is the single link `[[splice]]` becoming `[[EffectTrace.splice]]` with the paragraph
  rewrapped.
- **B.** `EvalShapeTest.scala` at the declared path, prefix matching `internal/Eval.scala`. Ten
  scenarios on exactly the declared axes: `handleCont` once and never, `handleContRepeated` once,
  twice and never, `handleLoop` continue and done-from-clause, `handleLoopState` the same two, and
  one Mask scenario tunnelling a `handleCont` past an inner handler for the same tag. `n` runs over
  0 to 3 and eight configurations per cell (base, a binding above, a region above, the three
  suspending variants, two inner-handler variants), which is the 320 the derivation states. The
  inert regions are `ContextEffect.handleInheritable` for a tag never read and a `handleCont` for an
  effect never performed. No expected value is written by hand: every cell reads `law`, `lawState`
  or `runs`, and `law(List(7,8))(2)` folds to the 60 the derivation quotes.
- **C.** Exactly the four declared sites change, to `attachReentryToPending` at `Handler.scala:203`
  and `491` and `attachReentryToPending2` at `274` and `569`. Both helpers are `private[kyo] inline`
  (at `394` and `421`), each beside the `attachReentry` it is the settled fast path of, and each is
  the derivation's equation verbatim: pending goes to `attachReentry`, anything else passes through
  cast. The two unfused `Continue` arms are untouched. Must-not-change holds: `attachReentry` and
  `attachReentry2` are unchanged and are now callees only, `clauseDispatch`, `Suspend.crossing` and
  every node class are absent from the diff, `Eval`'s two not-at-top arms are unchanged, and no
  public signature moves.
- **D.** Not attempted, as fork 3 rules. `EvalShapeTest.scala` is the only new file under
  `kyo-kernel/shared/src/test`, and it contains no evaluator of its own. No partial interpreter is in
  the diff.
- **E.** Four `ContextEffect.handle` calls change from two argument lists to one, three in
  `contextReadsUnderBindings` and one in `contextRegionsPayEntryExit`. Those are the only four calls
  the file makes, and none is left in the old shape.
- **F.** `Choice.run`'s clause becomes
  `Kyo.foreach(Chunk.from(input))(v => cont(v)).map(_.flattenChunk)`, one flatten and no inner `run`,
  and `Choice.scala` changes in that method only. `runStream` keeps its shape. `ChoiceBench` carries
  both rows over ten sequential binary choice points.
- **H.** `Eval.scala:646` is `collect(step(Nested.unnest[Any](v)), Arrow.id, 0)`, which is the
  derivation's equation. The claim it rests on checks out in the sources: `Arrow.Ensure`'s
  two-argument apply delivers `apply(Nested.unnest(v))` on its settled arm (`Arrow.scala:257`), and
  `Nested.unnest` removes one layer or is identity (`Nested.scala:21`), so the walk cannot lose a
  value that was never boxed. `[Any]` is the spelling `answersLoop`'s settled arm already uses
  (`Handler.scala:452`). `ensuring` is reached only with settled values, from `collect`'s two settled
  arms. The `EvalTest` case carries the name the derivation quotes, goes through the budgeted
  three-argument `Eval.release`, and asserts identity with the resource rather than equality. Nothing
  on an evaluation path changed.
- **Candidate A.** The wrap is in the handler, in `run`, in both `handleContRepeated` overloads and
  nowhere else (`ArrowEffect.scala:217` and `272`). `reentered` is built once as a `val` on each
  handler, is `outer` with `done` as identity and `repeated` true, and carries no `recover` on the
  recovering overload. `reentering` is an `Arrow.Step` that defers a pending input in the same arm as
  `Arrow.apply` and, on a settled one, applies `Pending.handle(k(unnest(v)), reentered, ())`, which
  is the declared equation. `handleFirstRepeated` is absent from the diff. Neither rejected draft
  leaves a trace: `Handler.scala` has no `resumed` member, the new path carries no `escaping` gate,
  and `Eval`'s repeated arm is unchanged.
- **Outside the kernel.** `Span.updated` raises before allocating, with `Chunk.updated`'s check shape
  and message. `SpanBench.updated` walks a sixteen-element span with `(i + 1) & 15`, so no row
  reaches the throw. `RearmSurvivorsTest` arms write before read and asserts the order in the log;
  `git diff origin/main..HEAD -- kyo-net` is that one test file and `kyo-net`'s main sources are
  empty in that diff, so `derivation.md:182` now holds as written.

## Forks

Fork 1 and fork 2 are declared as not touched: `Arrow.scala`'s `apply` overload pair is absent from
the diff, and no `PollTest` file appears in it. Fork 3 is ruled not attempted and no interpreter is
present. Fork 4 is ruled A and only A is implemented: `handleContRepeated`'s signatures are
byte-identical to the base apart from the `run` bodies, and `done` still runs once, which the 1060
case pins against candidate B's 4060. Fork 5 is left open for the user and needs no code.

## Round 4's findings

- **C16**, the `build.sbt` setting and the `EffectTrace` link declared only in the working tree:
  **resolved**. Both declarations are committed at HEAD (`derivation.md:33` to `37`), and the
  derivation on disk is identical to the derivation at HEAD.
- **C17**, "The four pieces" heading five subsections: **partly resolved**. The heading is now
  "## The pieces" and the added sentence at 13 to 14 places E and F. The same sentence omits H, which
  is C23.
- **C18**, "kyo-net is identical to main on this branch": **resolved**. The claim is now scoped to
  sources and paired with the leaf ("kyo-net's sources are identical to main on this branch; this
  leaf is the branch's one kyo-net change"), and that is what the diff against `origin/main` shows.
  The same claim shape recurs for `Eval` at 274, which is C24.
- **C19**, the re-entry rule pinned by a test and stated nowhere on the public surface: **resolved in
  the code, undeclared in the document**. `ArrowEffect.scala:189` to `193` now states it on
  `handleContRepeated`'s scaladoc, and it agrees with `Handler.reentered`'s doc and with
  `BracketTest.scala:1205`. The derivation still declares no scaladoc change there, which is C22.
- **C20**, "the second row" not naming the row whose cost is quoted: **resolved**. The sentence at
  302 to 304 now names `repeatedClausesPayReentry` and says "slower than the base leg".
