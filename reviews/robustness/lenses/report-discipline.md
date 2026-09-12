# Report: kernel-discipline, round 3

PASS

## Findings

None. No construct of concern on an added line lacks a row, no verdict falls outside the allowed
classes, rule 3 has nothing to check, and rule 4 has nothing to fail.

## Enumeration

I ran the enumeration myself over the added lines of

```
git diff -U0 cdefdc9e60..HEAD -- kyo-kernel/shared/src/main/scala kyo-kernel/jvm-native/src/main/scala kyo-kernel/js-wasm/src/main/scala
```

The range touches two main sources, `ArrowEffect.scala` and `Handler.scala`, with 72 added lines.

**Constructs enumerated: 7. Rows in the table: 7.** The correspondence is one to one by site, not
only by count:

| site on HEAD | construct matched | row |
|---|---|---|
| Handler.scala:333 | `new <Type>`, the anonymous `ContHandler` | F1 |
| Handler.scala:352 | `new <Type>`, the anonymous `Arrow.Step` | F2 |
| Handler.scala:356 | `@unchecked` | F3 |
| Handler.scala:399 | `asInstanceOf` | F4 |
| Handler.scala:426 | `asInstanceOf` | F5 |
| Handler.scala:491 | `asInstanceOf` | F6 |
| Handler.scala:569 | `asInstanceOf` | F7 |

I opened the committed file at each of the seven cited line numbers. Every one resolves to exactly
the text its row quotes, character for character. No row cites a line the tree does not have, and no
row cites a line at the wrong number.

Patterns with zero hits on the added lines: `.erased`, `Any`, `Nothing`, `Null`, `null`, `var`,
`while`, `TODO`, `FIXME`, `???`, `drive`, `drives`, `driving`, `after`, and every named `class`,
`trait`, `object`, `enum` or `type` declaration. The two anonymous classes are the only new types.
The one further occurrence of `new` is the `end new` marker at Handler.scala:358, which closes the
class opened at 352, so it is F2's construct rather than a second one. The word `after` is worth a
line of its own because the scaladoc on `reentered` had every reason to reach for it and does not:
it says "once the last of them has run".

Four added lines in `ArrowEffect.scala` reach these two allocations without naming a listed
construct themselves, the `val reentered = Handler.reentered(this)` and the
`Handler.reentering[...](next, reentered)` argument in each of the two `handleContRepeated`
overloads, at ArrowEffect.scala:209 and 211 and at 264 and 266. They need no row of their own under
rule 1, and F1 and F2 name them in their prose as the per region entry and the per suspension arrow.
The enclosing `new Handler.ContHandler[...]` at ArrowEffect.scala:205 and 260 are context lines in
this range, not added ones, so they are correctly absent from the table.

## Rule 2, the verdicts

Each of the seven lands in an allowed class.

- **F1**, a measurement. Numbers with the benchmark row they came from, `repeatedRegionsPayEntry`,
  and the comparison file that holds them. It adds an explicit pending for the current shape and
  names the two rows that will carry the replacement.
- **F2**, a measurement. Numbers with the row they came from, `repeatedClausesPayReentry`, and the
  file. The row states plainly that the number is a regression and routes the decision to a named
  open ruling instead of closing it here. That is the opposite of a verdict whose substance is that
  the author found the construct acceptable.
- **F3**, a category from the closed set, erasure-forced, with the reason stated: a typed pattern
  binding at the arm's type whose runtime test is `Pending` alone. The trailing cross reference to
  the same arm elsewhere corroborates the category; it is not doing the work of the verdict.
- **F4**, a `moved` provenance naming both origin sites, plus an explicit category, representation
  assertion, with the inhabitation argument behind it.
- **F5**, a `moved` provenance naming both origin sites, with the category carried by reference to F4.
- **F6**, a `moved` provenance naming the origin site, plus an explicit category, erasure-forced.
- **F7**, a `moved` provenance naming the origin site, with the category carried by reference to F6.

No verdict reads "needed for the types to work", "consistent with the existing code", "the evaluator
is the engine room", or "justified" with no category or number behind it.

I checked the four `moved` provenances against the base rather than taking the table's word. At
cdefdc9e60 the class `LoopHandler` begins at line 133 and its `answers` at 186, carrying
`else o.asInstanceOf[Outcome[A < (E & S), B < S] < S]` at 205. `LoopStateHandler` begins at 220 with
its `answers` at 256, carrying the `Outcome2` form at 278. The fused walk `answersLoop` begins at
372 and `answersLoopState` at 450, each carrying both the outcome cast and the
`k.asInstanceOf[Arrow[O[C], A, E & S]]` argument to `attachReentry`. The diff removes exactly those
four tails and the two new inline helpers carry them once. F4, F5, F6 and F7 name the right origins.

## Rule 3, hand-added rows

The table has no hand-added rows. The F series runs F1 to F7 with no gap and no H row. Rule 3
therefore has nothing to check, and I state that rather than construct a check for rows that do not
exist. There is consequently no hot-path cost row whose number or pending-by-rows I could test.

The table's preamble gives a reason for their absence, and both halves of it hold against the tree.
`Eval` is unchanged in the range: the whole of kyo-kernel changes seven files, of which the only
main sources are `ArrowEffect.scala` and `Handler.scala`, and no `Eval` source is among them. No
member with a partial default exists: the defaults an added handler can inherit are
`repeated = false` at Handler.scala:41, `unbound(ctx) = ctx` at 61, and `recover(state, ex) = Absent`
at 76, each a total value, and the file contains no `???`.

## Rule 4, tail-call claims

No added line contains the word tail, and no added line contains a `try`. The range's added comments
assert no tail call anywhere, so there is nothing for this rule to catch. The one place the word
tail appears in the package is the flags note, where it names a code location rather than asserting
a call shape.

## Observations, not findings

**O1.** F1 and F2 both disclose that their numbers were taken on the previous shape of the code.
F1's pending clause names the two rows that will replace it. F2's says only that the tip's final
session replaces the cell, naming no row. Both rows satisfy the measurement class through its first
branch, a number with the row it came from, so neither is a rule 2 failure and neither is a finding.
The asymmetry is still worth one question at the live review: have F2 name the row that will carry
its replacement, as F1 does, so the two pendings read the same way.

**O2.** I checked the inherited-member surface of the newly added handler, since that is the class
of thing a hand-added row would have covered. The anonymous `ContHandler` at Handler.scala:333
defines `tag`, `run`, `done` and `repeated`, and inherits `recover` and `unbound`. The inherited
`recover` is accounted for: the added comment in the recovering `handleContRepeated` at
ArrowEffect.scala:262 says the re-entered region carries no recover and why a throw inside it
unwinds outward. The inherited `unbound` is the identity, which is what every `ContHandler` has,
the overrides at Handler.scala:114 and 297 belonging to `MaskingHandler` and `ContextHandler`, so
the re-entered handler matches its outer here and there is nothing unaccounted for. The table's
volunteered line on `override def repeated = true` is accurate and covers the fourth member.
