# Lens report: kernel-discipline

FAIL

Round 2, judged from scratch. Range `cdefdc9e60..1b1373db43`, kernel main sources only. The kernel
skill file is not present in the tree at `kyo-kernel/.claude/skills/kernel/SKILL.md`, so the cast
ladder used here is the one restated in the brief.

## Enumeration

I ran the enumeration myself over the 73 added lines of the range's diff restricted to
`kyo-kernel/shared/src/main/scala`, `kyo-kernel/jvm-native/src/main/scala` and
`kyo-kernel/js-wasm/src/main/scala`. Only the shared tree changed; the other two are untouched.

| pattern | hits on added lines | rows covering them |
|---|---|---|
| `asInstanceOf` | 4 | F5, F6, F7, F8 |
| `@unchecked` | 1 | F4 |
| `new <Type>` | 3 | F1, F2, F3 |
| `.erased` | 0 | none needed |
| `Any`, `Nothing`, `Null`, `null` | 0 | none needed |
| `var`, `while` | 0 | none needed |
| new `class`, `trait`, `object`, `enum`, `type` declaration | 0 | none needed |
| `drive`, `drives`, `driving`, `after` | 0 | none needed |
| `TODO`, `FIXME`, `???` | 0 | none needed |

**Eight constructs enumerated against eight `F` rows**, one for one, plus the two hand rows H1 and H2
for the classes the script cannot emit. Ten rows in the table, ten constructs of concern. No
construct is unflagged, so there is no `D-missing-n` finding in this round.

Citation integrity: every row's quoted text appears verbatim on an added line of the diff, and every
`F` row's line number resolves to exactly that line in the tip file. H1's and H2's quoted lines are
both present. H2's line number is off by one, recorded below as `D-cite-H2`.

The failure in this round is on rule 2 and rule 3, not on coverage: two verdicts do not carry a form
the rules admit.

## Findings

### D-verdict-F2 (blocking)

Flag F2, `ArrowEffect.scala:274`, class `allocation`, verdict `measured: as F1, the same object for
the recovering overload; the row enters the plain overload, and the twin is the same anonymous shape
with the same fields`.

The verdict declares itself a measurement and carries no number for its own site. It borrows F1's
number and then states, correctly and in its own words, that the benchmark row those numbers come
from enters the plain overload rather than this one. So by the row's own account no measured row
exercises the construct it adjudicates.

The rule admits a measurement as `a number with the row it came from, or an explicit "measurement
pending" naming the rows that will carry it`. F2 is neither. What remains as its substance is an
argument from structural similarity, that the twin is the same anonymous shape with the same fields,
which is the `consistent with the existing code` shape the rules name as not counting. The shape
argument may well be sound for the 16 bytes, but soundness is not the test; the admitted forms are.

Discharge: either a row that enters the recovering overload with its own number, or a verdict of the
form `measurement pending`, naming the benchmark row that will carry it.

### D-verdict-H1 (blocking)

Flag H1, `Eval.scala`, cont arm, class `hot-path cost`, on the line
`val continuation = if handler.repeated && !handler.escaping then handler.reentering(raw) else raw`.

The line is present in the diff and the location is right. The row does carry numbers with the
benchmark files they came from, which is what rule 3 asks of a hot-path cost, and on form that part
is satisfied.

The defect is what the row says next. It states that the session those numbers come from measured
the arm before the `escaping` read was added, and then defers the covering measurement with `the
package's benchmark section carries the rerun on the tip`. That is a pointer to a location, not the
named benchmark rows rule 3 requires of a deferred measurement. Rule 3 is explicit that a
measurement pending on H1 is acceptable only if the row names the benchmark rows that will carry it,
and H1 names none.

Following the one citation the verdict makes, the benchmark section of the package carries the first
round, the three leg session, the multi shot rows, and the ChoiceBench comparison. It carries no
rerun identified as covering the arm after the `escaping` read was added. The row's pointer does not
resolve.

There is a second problem inside the same sentence. The benchmark section attributes the `escaping`
condition to edit 7 and places edits 3 to 8 in the leg the three leg session measured, which would
mean the session did include the `escaping` read and H1's caveat is wrong. The table and the section
it cites cannot both be right. Either reading leaves the row undischarged as written: on one, the
number does not cover the line and the rerun does not exist; on the other, the number does cover the
line and the caveat should be struck.

Discharge: strike the caveat if the cited session already includes the `escaping` read, or name the
benchmark rows carrying the rerun and their numbers.

### D-verdict-H2 (not blocking on its own)

Flag H2, `Handler.scala:93`, class `claim`, verdict opening `justified by construction, with the
reach stated`.

`justified` with no category or number behind it is named in the rules as not counting, and the row
names no category from the closed set and carries no number. On the general rule this is a defect of
form.

I am recording it as not blocking because rule 3 puts a different and more specific question to the
hand rows, whether the claim is one the table can make without a number, and H2's is. Its claim is a
static reach claim, and I checked every part of it against the tip:

- `resumed` is read at one site only, `Handler.scala:105`, inside `reentering`, as the row says.
- `reentering` is called at one site only, `Eval.scala:118`, the cont arm, under
  `handler.repeated && !handler.escaping`, as the row says.
- Five sites override `repeated` at the tip and all five are in `ArrowEffect.scala`, at lines 211,
  217, 272, 278 and 987. The row's account of them holds: 217 and 278 are the two twins and each
  also overrides `resumed` with `this`, at 218 and 279.
- The fifth, at 987, sits inside `handleFirstRepeated` and is preceded at 986 by
  `override def escaping = true`, so the arm's guard short circuits before it asks for `resumed`, as
  the row says.

So the substance is an exhaustive reach enumeration that verifies, not the banned pattern of a
verdict whose substance is that the author found it acceptable. The defect is the label. Naming the
category, `evidence-backed`, with the reach as the evidence, would close it.

### D-cite-H2 (not blocking)

H2 cites `Handler.scala:93`. At the tip, line 93 is the closing `*/` of the scaladoc; the line the
row quotes, `def resumed: ContHandler[I, O, E, A, A, S] = bug(...)`, is at line 94. The line itself
is present in the diff, so rule 3's presence test passes and only the number is wrong. Every other
line number in the table resolves exactly.

## Checks that passed

**Rule 4, tail-call claims.** No added line contains the string `tail`. No comment in the change
asserts a tail call anywhere, so there is nothing to test against a call under a cast, inside a
`try`, or crossing a method boundary. The word `tail` appears in the verdicts of F5 through F8, but
there it names a position in the base code, the tail of `answersLoop` and of `answersLoopState`, and
asserts nothing about a tail call.

**F1.** A measurement. Numbers with the benchmark row they came from, `repeatedRegionsPayEntry`, and
the file. Counts.

**F3.** A measurement. Numbers with the row, `repeatedClausesPayReentry`, and the file. Counts under
rule 2, and it is worth saying that a verdict reporting a regression of this size against itself is
the opposite of the failure this lens hunts. The row also states that it is not closed by this table
and hands the decision to the package's open ruling 5. That is a disposition question for the live
review, not a discipline finding, and the open ruling exists where the row says it does.

**F4.** `erasure-forced`, a category from the closed set, and the right one: the pattern binds at the
arm's type while the runtime test is `Pending` alone, which is what the annotation suppresses.

**F5.** A `moved` provenance naming both base sites, plus the category `representation assertion`.
The diff confirms the provenance: the base carried this cast at the tails the row names.

**F6.** A `moved` provenance naming the two state carrying tails it came from. Confirmed in the diff.

**F7.** A `moved` provenance naming the base site, plus the category `erasure-forced`. Confirmed in
the diff.

**F8.** A `moved` provenance naming the base site. Confirmed in the diff.

## One observation for the live review

The change removes six `asInstanceOf` occurrences and adds four, so on the cast count the range is
net negative by two. F5 through F8 are the four that remain, and all four are the same casts the base
already carried, now written once each in the shared helpers rather than twice at the walk tails.
The range introduces no cast that did not exist at the base. That is not a verdict and does not
discharge any row, but it is the context in which the four cast rows should be read.

## What a passing table needs

Two edits, both to verdict text rather than to code:

1. F2: a number from a row that enters the recovering overload, or `measurement pending` naming that
   row.
2. H1: either strike the caveat about the `escaping` read if the cited session already covers it, or
   name the benchmark rows and numbers of the rerun.

Two more that are worth taking with them:

3. H2: replace `justified by construction` with the category `evidence-backed`, the reach being the
   evidence. The reach itself verifies.
4. H2: the site is `Handler.scala:94`, not 93.

## Scope note

I judged the table against the diff, the cited line numbers in the tip sources, and the rules. Where
a verdict cites a location, I followed that citation and no further. I did not use the session
transcript, and no finding here rests on the contents of a benchmark output file.
