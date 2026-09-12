# Lens report: kernel-conformance, round 6

FAIL

Round 6. Tip `ee0007fb03af87c04cf764b5a15e62997dda11d3`, base `cdefdc9e60`.

The substitution check passes, and nothing in the code moved since round 5: `git diff
b167efef69..HEAD -- . ':!reviews'` is empty, so the four pieces, the multi-shot fix and the two
edits outside the kernel are the same values I walked last round, and I walked them again against
the derivation at this tip. The verdict is FAIL on the surface check alone, and on one class of
defect: the tree the derivation describes is not the tree in front of me. Piece G, written this
round to declare the tooling, rests on a file that is in neither the tree nor the range, and it
declares three files whose directory an ancestor of the base removed from git on purpose, without
saying so. The same removal covers 6,912 lines the range tracks that no sentence declares at all.

I read `reviews/robustness/derivation.md` at the tip, the diff over the declared paths, the diff
`--stat` over the whole range, the kernel files each hunk sits in at the tip, and the three files
piece G adds. I ran `sequence.py --verify cdefdc9e60 HEAD` (VERIFIED, 19 files) and
`flags.sh cdefdc9e60..HEAD -- kyo-kernel/shared/src/main/scala` (9 rows, against 9 adjudicated in
`flags.md`). No transcript, no summary, no test or benchmark output. The derivation on disk is
byte-identical to the derivation at HEAD.

## Findings

### C26. Piece G's subject, `SKILL.md`, is in neither the tree nor the range

`reviews/robustness/derivation.md:168`: "`kyo-kernel/.claude/skills/kernel/SKILL.md` describes a
pipeline whose three companion files did not exist in this tree", and `derivation.md:176`: "Surface:
those three files and nothing in `SKILL.md`."

`ls kyo-kernel/.claude/skills/kernel/` at the tip lists `flags.sh`, `package-check.sh` and
`rulings.md`, and nothing else. `git ls-tree -r HEAD -- kyo-kernel/.claude` lists the same three.
There is no `SKILL.md` at that path, tracked or untracked, and `find . -name SKILL.md` finds only
the seven readme skills under the root `.claude/`. The file was last tracked before `76c675ea94`,
which deleted it.

So both sentences assert a fact about this tree that this tree denies. The first is the whole
justification for adding 346 lines of tooling to the module under review: the reader is told the
three files fill a gap a present document names, and cannot open that document to check either that
it names them or that it describes the pipeline the files implement. The second reads as a statement
that `SKILL.md` was present and left alone, where in fact nothing was left alone because nothing was
there. The three added files inherit the same defect in the code: `flags.sh:4`, `flags.sh:18` and
`package-check.sh:4` each send the reader to `SKILL.md, "Preparing a live review"` for what the
script is for, and the repository has no such file.

### C27. `kyo-kernel/.claude/` was removed from git on purpose before the base, and G declares the re-add without declaring the reversal

`reviews/robustness/derivation.md:39` to `41`: "The kernel skill's `SKILL.md` names three files
beside it that did not exist, `flags.sh`, `package-check.sh` and `rulings.md`; this change writes
them (piece G) as the pipeline's tooling".

`76c675ea94`, "[repo] development artifacts move to .dev, which git ignores", dated 2026-09-10 and
an ancestor of the base, removed 525 files including "`kyo-kernel/.claude/` with the kernel skill
and its bench harness", naming as its subject "the agent tooling that produced them", and stating
the rule it leaves behind: "What stayed: every module source, `kyo-kernel/CONTRIBUTING.md`, and the
README and CONTRIBUTING files. The rule is that those are the only markdown the repository carries."
`.gitignore` at the tip carries the other half: "Local development artifacts: design notes, review
packages, benchmark output, agent tooling." routing them to `.dev/`. That commit is on this branch
only; `origin/main` has never had `kyo-kernel/.claude`.

Commit `f6e9fb0041` puts the three files back at that path, one of them (`rulings.md`, 121 lines)
markdown, and its subject line records the act as "skill scripts tracked in the worktree". The path
is excluded in `.git/info/exclude`, so committing them took an override. Piece G says the change
writes three files; it does not say that the directory it writes them into is one a commit two days
before the base emptied deliberately, nor why that decision is being reversed for three of its
files and not the rest. A reviewer who wrote `76c675ea94` reads G and finds his own rule reversed
with no argument against it, which is the shape `rulings.md:49` itself names: "changes stay inside
the derivation's declared surface. An improvement outside it is still a finding, because nobody
agreed to it."

### C28. The range tracks 6,912 lines of review package that no sentence declares, under the same removed path

`reviews/robustness/derivation.md:202`: "so the range's surface is fully declared and fully applied."

`git diff cdefdc9e60..HEAD --numstat -- reviews` is 35 files and 6,912 added lines, among them
`sweep/rerun-kyo_ffi_plugin_test.log` at 2,387 lines, `bench/base-rows-3.log` and
`bench/tip-rows-3.log` at 467 each, and `sweep/rerun-kyo_test_sbt_publish_test.log` at 691.
`git ls-tree -r cdefdc9e60 -- reviews` is empty, and so is the same listing on `origin/main`:
`76c675ea94` removed `reviews/` by name in the same sweep as `kyo-kernel/.claude`, and the
`.gitignore` line quoted in C27 names "review packages" among what belongs under `.dev/`.

No sentence in the derivation declares the package as part of the change's surface. The evidence
section at 229 to 240 lists what "the package will carry", which declares contents, not a decision
to commit the package to a branch that goes to main. Round 5 recorded this row as "the review's own
instrument" and raised nothing on it; with `76c675ea94` in hand that framing no longer covers it,
because the instrument being present in the worktree, which the live-review model needs, is a
different fact from the instrument being tracked on the branch, which the ancestor commit forbade.
This finding is about the tracking only. I raise nothing about the package's content, and the lens
reports live there by the reviewer's own design.

## The surface I enumerated

Every path in `git diff cdefdc9e60..HEAD --stat`, with the sentence that declares it.

| Path | Change | Declared by |
|---|---|---|
| `kyo-kernel/CONTRIBUTING.md` | item 13 gains two sentences | A, 19 to 29 |
| `project/TestKyo.scala` | `jmhCompileTasks` and its call in the compile-test branch | A, 27 to 30 |
| `build.sbt` | `Jmh / classDirectory` in the three JMH projects | A, 34 to 36 |
| `kyo-kernel/.../internal/EffectTrace.scala` | one doc link through the companion | A, 36 to 38 |
| `kyo-kernel/.../test/.../internal/EvalShapeTest.scala` | new, the shape matrix | B, 45 |
| `kyo-kernel/.../internal/Handler.scala` | four re-entry tails, two new helpers | C, 120 to 123 |
| `kyo-kernel/.../internal/Handler.scala` | `reentered`, `reentering` | Candidate A, 303 to 306 |
| `kyo-kernel/.../ArrowEffect.scala` | the two overloads wrap their continuation | Candidate A, 270 to 272 |
| `kyo-kernel/.../ArrowEffect.scala` | a paragraph on `handleContRepeated`'s scaladoc | Candidate A cost, 291 to 294 |
| `kyo-kernel/.../internal/Eval.scala` | `ensuring` unnests, and the walk's comment | H, 161 to 163 |
| `kyo-kernel/.../test/.../internal/EvalTest.scala` | one case, the boxed resource | H, 158 to 160 |
| `kyo-kernel/.../test/.../ArrowEffectTest.scala` | five cases in a new `handleContRepeated` block | Fork 4, 361 to 365 |
| `kyo-kernel/.../test/.../BracketTest.scala` | one case in `multi-shot clauses` | Candidate A, 280 to 281 |
| `kyo-kernel/jvm/src/jmh/.../KernelBench.scala` | four `ContextEffect.handle` calls | E, 133 to 135 |
| `kyo-kernel/jvm/src/jmh/.../KernelBench.scala` | three new rows | What A costs, 317 to 322 |
| `kyo-prelude/.../Choice.scala` | `Choice.run`'s clause | F, 337 to 339 |
| `kyo-bench/.../ChoiceBench.scala` | new, `run` and `runStream` | F, 342 |
| `kyo-data/.../Span.scala` | `updated` checks its index | Also on the branch, 181 to 189 |
| `kyo-bench/.../SpanBench.scala` | new, `updated` | Also on the branch, 188 to 189 |
| `kyo-net/.../RearmSurvivorsTest.scala` | write armed first, the order asserted | Also on the branch, 191 to 199 |
| `kyo-kernel/.claude/skills/kernel/flags.sh` | new, 78 lines | G, 166 to 177 (C26, C27) |
| `kyo-kernel/.claude/skills/kernel/package-check.sh` | new, 131 lines | G, 166 to 177 (C26, C27) |
| `kyo-kernel/.claude/skills/kernel/rulings.md` | new, 121 lines | G, 166 to 177 (C26, C27) |
| `reviews/robustness/**` | 35 files, 6,912 lines | nothing (C28) |

No file is deleted in the range. The files added outside `reviews/` are exactly six: the two
benchmark classes, `EvalShapeTest.scala`, and the three under `kyo-kernel/.claude`.

Per piece, what I checked at the tip.

- **A.** CONTRIBUTING item 13 is the only changed line in the file and gains exactly two sentences:
  the first names `kyo-preludeJVM/test` and `kyo-coreJVM/test` with `Batch.run` as the example, the
  second names the compile-test phase and `kyo-kernelJVM/Jmh/compile`. `jmhCompileTasks`
  (`TestKyo.scala:369`) returns `Nil` unless the phase is `compile-test`, finds projects through
  `allProjectRefs` and `ivyConfigurations` rather than from a list, and emits `<module>/Jmh/compile`.
  `Jmh / classDirectory := crossTarget.value / "jmh-classes"` appears at `build.sbt:805`, `1399` and
  `3207`, the three projects that enable the JMH plugin, and nowhere else. `EffectTrace`'s change is
  the single link `[[splice]]` becoming `[[EffectTrace.splice]]` with the paragraph rewrapped.
- **B.** `EvalShapeTest.scala` at the declared path, the prefix matching `internal/Eval.scala`. Ten
  scenarios on exactly the declared axes: `handleCont` once and never, `handleContRepeated` once,
  twice and never, `handleLoop` continue and done-from-clause, `handleLoopState` the same two, and
  one `Mask` scenario tunnelling a `handleCont` past an inner handler for the same tag. `n` runs over
  0 to 3 and eight configurations per cell (base, a binding above, a region above, the three
  suspending variants, two inner-handler variants), which is 320. The inert regions are
  `ContextEffect.handleInheritable` for a tag never read (`EvalShapeTest.scala:40`) and a
  `handleCont` for an effect never performed (`:41`). No expected value is written by hand: every
  cell reads `law`, `lawState` or `runs`.
- **C.** Exactly the four declared sites change, to `attachReentryToPending` at `Handler.scala:203`
  and `491` and `attachReentryToPending2` at `274` and `569`. Both helpers are `private[kyo] inline`
  (at `394` and `421`), each beside the `attachReentry` it is the settled fast path of (`369`,
  `402`), and each is the derivation's equation verbatim: pending goes to `attachReentry`, anything
  else passes through cast. The two unfused `Continue` arms are untouched. Must-not-change holds:
  `attachReentry` and `attachReentry2` are unchanged and are now callees only, and `clauseDispatch`,
  `Suspend.crossing`, every node class and every public signature are absent from the diff, the only
  occurrence of "crossing" on a changed line being a word in `reentering`'s scaladoc.
- **D.** Not attempted, as fork 3 rules. `EvalShapeTest.scala` is the only file added under
  `kyo-kernel/shared/src/test`, and it contains no evaluator of its own. No partial interpreter is in
  the diff.
- **E.** Four `ContextEffect.handle` calls change from two argument lists to one, three in
  `contextReadsUnderBindings` and one in `contextRegionsPayEntryExit`. Those are the only four the
  file makes, and none is left in the old shape.
- **F.** `Choice.run`'s clause becomes
  `Kyo.foreach(Chunk.from(input))(v => cont(v)).map(_.flattenChunk)`, one flatten and no inner `run`,
  and `Choice.scala` changes in that method only. `runStream` keeps its shape. `ChoiceBench` carries
  both rows over ten sequential binary choice points.
- **H.** `Eval.scala:646` is `collect(step(Nested.unnest[Any](v)), Arrow.id, 0)`, the derivation's
  equation, and the comment above it says why. `Arrow.Ensure`'s two-argument apply delivers
  `apply(Nested.unnest(v))` on its settled arm, so the walk now matches the arm it stands in for.
  The `EvalTest` case carries the name the derivation quotes, goes through the budgeted
  three-argument `Eval.release`, and asserts identity with the resource rather than equality.
  Nothing on an evaluation path changed.
- **Candidate A.** The wrap is in the handler, in `run`, in both `handleContRepeated` overloads and
  nowhere else (`ArrowEffect.scala:217` and `272`). `reentered` is built once as a `val` on each
  handler (`:215`, `:270`), is `outer` with `done` as identity and `repeated` true
  (`Handler.scala:330`), and carries no `recover` on the recovering overload. `reentering`
  (`Handler.scala:348`) is an `Arrow.Step` that defers a pending input in the same arm as
  `Arrow.apply` and, on a settled one, applies `Pending.handle(k(unnest(v)), reentered, ())`, which
  is the declared equation. `handleFirstRepeated` is absent from the diff. Neither rejected draft
  leaves a trace: `Handler.scala` has no `resumed` member, the new path carries no `escaping` gate,
  and `Eval`'s repeated arm is unchanged. The scaladoc paragraph at `ArrowEffect.scala:189` to `193`
  states the rule the derivation now declares at 291 to 294, and the recovering overload's scaladoc
  does inherit it by reference (`:234`, "As [[handleContRepeated]], except a failure").
- **Outside the kernel.** `Span.updated` raises before allocating, with `Chunk.updated`'s check shape
  and message byte for byte (`Chunk.scala:173` to `174`, `Span.scala:981` to `982`).
  `SpanBench.updated` walks a sixteen-element span with `(i + 1) & 15`, so no row reaches the throw.
  `RearmSurvivorsTest` arms write before read and asserts the order in the log.

## Forks

Fork 1 and fork 2 are declared as not touched: `Arrow.scala`'s `apply` overload pair is absent from
the diff, and no `PollTest` file appears in it. Fork 3 is ruled not attempted and no interpreter is
present. Fork 4 is ruled A and only A is implemented: `handleContRepeated`'s signatures are
byte-identical to the base apart from the `run` bodies, and `done` still runs once, which the 1060
case pins against candidate B's 4060. All five cases the fork names are in the block, with the
values it quotes: 60, 1060, 180, 4, and a hundred thousand sequential operations. Fork 5 is left
open for the user and needs no code.

## Round 5's findings

- **C21**, three tracked files under `kyo-kernel/.claude/skills/kernel/` declared nowhere, and a
  derivation sentence denying they are there: **resolved as stated, superseded**. Piece G at 166 to
  177 declares all three, and the denying sentence is gone. What the declaration says about the tree
  is C26, and what it does not say about `76c675ea94` is C27.
- **C22**, the `handleContRepeated` scaladoc paragraph undeclared: **resolved**. The cost paragraph
  at `derivation.md:291` to `294` declares it, states its content, and names the inheritance by
  reference, all three of which the code bears out.
- **C23**, the piece enumeration not accounting for H: **resolved**. The sentence at 13 to 15 now
  names E, F, G and H and places each; A at 17, B at 43, C at 88, E at 131, D at 140, H at 149 and
  G at 166 are all under `## The pieces`, and F at 337 is under "What A costs" as stated.
- **C24**, "`Eval` and `ContHandler` are unchanged" contradicted four sections later: **resolved**.
  The claim at 290 is now scoped to the fix and names the exception in the same breath, which is the
  shape that closed C18.
- **C25**, F1 and F2 citing an evidence file that does not exist: **resolved**. Both rows now cite
  `bench/base-rows-3.log`, `bench/tip-rows-3.log`, `bench/compare-base-vs-tip-f3.md` and
  `bench/compare-rows-base-vs-tip.md`, all four present in the range, and each number I spot-checked
  is in the file it cites: 480,121 against 720,141 B/op and 88,104 against 104,120 B/op on the
  `gc.alloc.rate.norm` lines, and 99.780 against 636.051 us and 56.091 against 58.175 us in the
  `-f 3` table. The queued rerun is now named as queued rather than as a file.

## Round 4's findings

- **C16**, the `build.sbt` setting and the `EffectTrace` link declared only in the working tree:
  **resolved**, and still so at this tip. The derivation on disk is identical to the derivation at
  HEAD.
- **C17**, the heading undercounting the pieces: **resolved**. C23's fix completes it.
- **C18**, the unscoped "kyo-net is identical to main" claim: **resolved**.
- **C19**, the re-entry rule pinned by a test and stated nowhere on the public surface: **resolved**.
  Stated at `ArrowEffect.scala:189` to `193`, pinned at `BracketTest.scala:1205`, and now declared.
- **C20**, "the second row" not naming the row whose cost is quoted: **resolved**. The sentence at
  322 to 324 names `repeatedClausesPayReentry` and says "slower than the base leg".
