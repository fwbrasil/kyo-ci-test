FAIL

Tip judged: `17bf04b446` (no source under the declared surface differs from the current tip). Range: `bc6a48a2aa..HEAD`.

Recorded by the coordinator from the lens's reply: the harness blocks a subagent from writing a report
file, so `conformance-5b` and `lens-conformance-5-2` returned the findings as text. The lens did the
full walk; only the write failed. Everything else conformed: all eight rules of the release
derivation, the full piece table, every "As built" deviation present, every declared removal except
C5-3, no partial reference interpreter, and all 189 `sequence-5.json` fragments verbatim at the tip.

## Findings

### C5-1: the fiber boundary and abandonment are in the range and in no item

`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:101` (the `boundary` scaladoc), `:113`
(`ArrowEffect.handleLoop` in place of `ArrowEffect.handleCont`), `:422` (`abandon`). The range rewrites
the boundary from a continuation-taking region into a loop region, turns the abort arm into
`Loop.done(())`, turns the waiting join into `Loop.continue(ArrowEffect.suspend(...))`, and replaces
`abandon`'s resume-then-release with a link-then-release walk. Items 1 to 17 do not name `IOTask`, the
boundary, or abandonment. `sequence-5.json` carries five edits for the file and `provenance.md` lists
`5a46dfda1c` among the fifth walk's commits, so the walk's data treats the file as in scope while the
section does not present it. Designed in `redesign.md` section 7, so not undesigned, undeclared.

### C5-2: the reporter overload of `Eval.release` is added where the derivation removes it

`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:733`, pinned at `EvalTest.scala`.
`redesign.md` section 4 lists "the reporter overload of `release`" among the removals; every other name
on that list is gone at the tip, this one is present (added by `5a46dfda1c`). `derivation-releases.md`
rule 7 requires exactly its behavior. The two derivation documents disagree, and "As built" records
neither the keep nor the disagreement.

### C5-3: `maskedRead` is kept where the derivation deletes it

`Eval.scala:398`. `redesign.md` section 3 lists `maskedRead` (and `maskedEntries`, `Context.Masked`)
among the context removal's deletions. Seven of the eight names are gone; `maskedRead` is kept, with no
record of the reconsideration.

### C5-4: the context removal is the range's first commit and no item presents it

`Context.scala` deleted, `Eval.scala:89-104` and `262-275` (the stack-resolved read). The section says
"the walk starts from the context removal's tip, `bc6a48a2aa`", but `bc6a48a2aa` still has `Context`;
the removal is `d0f19b8f89`, the range's first source commit, presented by no item.

### C5-5: `redesign.md`'s `floor` has no counterpart

`Handler.scala:249,265`, `Stack.scala:86,90,217`. `redesign.md` section 4 designs a `floor` carried
through the outcome dispatcher. The build has no floor; the gap (`Handler.Gap`, `Hidden`,
`Stack.hide`/`hidden`) does the job. Derived in `derivation-releases.md`, never recorded as superseding
the floor.

### C5-6: package files change in the range with no item, and the evidence claim is stale

`kyo-kernel/.claude/skills/kernel/rulings.md` and `package-check.sh` change in the range with no item.
The evidence paragraph's claim that the commits after `8927f43e83` touch only `reviews/` and the
package check is contradicted by `275f6792b5`, which changed `rulings.md` under `kyo-kernel/.claude`.
