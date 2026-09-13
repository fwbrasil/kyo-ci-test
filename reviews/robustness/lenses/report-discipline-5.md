FAIL

tip judged: `17bf04b446dea0fb78d4f7decee26a029aaaf1ef`
range: `bc6a48a2aa..17bf04b446`
paths: `kyo-kernel/shared/src/main`, `kyo-kernel/jvm/src/main`, `kyo-kernel/js-wasm/src/main`, `kyo-kernel/jvm-native/src/main`
table: `reviews/robustness/flags-5.md`

HEAD advanced from `46de11690d` to `17bf04b446` while this walk ran. The enumeration was re-run
pinned to `17bf04b446` and is byte-identical to the earlier one. The only kernel main source touched
after `1d3402adc6` is the js-wasm `Safepoint.scala`, so every site in the table re-derives unchanged
at the tip; no line in the table has moved.

`kyo-kernel/.claude/skills/kernel/SKILL.md` does not exist in this tree, at that path or anywhere
else in the repository, and it is not tracked. The cast ladder applied here is therefore the one
restated in `reviews/robustness/lenses/kernel-discipline.md`, as that lens directs when the skill
file is absent. `rulings.md` is present and was read in full.

## Counts

| quantity | value |
|---|---|
| constructs the script emits at the tip | 164 |
| rows in the table | 164 |
| matched one to one by row id, file, line and class | 164 |
| constructs with no row | 0 |
| rows with no construct | 0 |
| rows whose verdict I accept | 108 |
| rows whose verdict I do not accept | 56 |

The script was run as dispatched over all four path roots. Its 164 rows and the table's 164 rows
agree on every row id, file, line number and construct class, with no residue on either side. The
enumeration was also repeated independently with a looser pattern set than the script's, admitting
lowercase type and class keywords, `new` before a lowercase name, and `Any` inside a longer
identifier. That pass produced three extra sites, all of them prose: `Eval.scala:736` and
`Stack.scala:208` match only because a scaladoc sentence contains the word "type" before a word, and
`Stack.scala:294` matches only because a scaladoc sentence names `Span[AnyRef]`. The two code sites
that do use `AnyRef`, `Eval.scala:311` and `Stack.scala:189`, both carry rows. So no construct of
concern is missing from the table.

The js-wasm change emits no row, confirmed rather than assumed. `Safepoint.scala` contributes four
added lines in this range: three comment lines and `if depth.isArmed && (stopRequested || expired())
then depth = depth.drained`. None carries a cast, a carrier, a mutable local, a new type, a banned
term, a placeholder or an allocation, so the absence of a Safepoint row is correct. The jvm
`ConsoleDebugger.scala` likewise contributes four added lines and correctly emits none.

Verdict kinds across the 164 rows:

| kind | rows |
|---|---|
| justified | 55 |
| moved | 44 |
| erasure-forced | 24 |
| ruled | 21 |
| comment | 14 |
| representation | 5 |
| reference-identity | 1 |
| measurement | 0 |
| REMOVE | 0 |

The table's own preamble declares its closed set as a cast ladder category, a measurement, a `moved`
provenance, `ruled`, `comment`, or `REMOVE`. `justified` is not in that declared set, and 55 of the
164 rows carry it. Thirty-two of those 55 reduce to something the set admits, by naming a ladder
category directly, by referring to a row that names one, or by naming the base construct they came
from. The other 23 do not, and they are the bulk of the findings below.

All 14 `comment` rows were checked against the tip and every one cites a genuine scaladoc or comment
line. All 21 `ruled` rows cite the correct line at the tip. The two checks the lens names that are
vacuous for this walk are recorded here so their absence is not mistaken for an omission: the table
carries no hand-added rows, so there is no H1 or H2 to check, and no added line carries a comment
asserting a tail call. The four `@tailrec` sites on added lines (`Eval.scala:78`, `:436`, `:576`,
`:752`) are compiler-checked annotations, not claims in prose, so they are not findings.

## Findings

### D5-1: the reporting overload of `Eval.release` is new, and all five of its rows say `moved`

Rows F100, F101, F102, F107, F108. Sites at the tip: `Eval.scala:737` (F100 and F101),
`Eval.scala:739` (F102), `Eval.scala:777` (F107), `Eval.scala:778` (F108).

Verdicts as written: `moved: the base's reporting overload`; `moved: as F100`; `moved: the base's
erased reporter, answering nothing`; `moved: the base's reporting arm`; `moved: the base's tag test`.

At `bc6a48a2aa` the evaluator has exactly one `Eval.release`, declared at `Eval.scala:637` as
`def release[A, S](v: A < S, ex: Throwable): Unit`. It takes no effect tag and no reporter. Every
call site in the whole tree at the base passes two arguments. There is no polymorphic
`[C] => I[C] => Unit` parameter anywhere in the kernel's main sources at the base, no private
overload taking `f: Any => Unit`, and the word "reporting" does not appear. The base's release walk
matches `Pending.Defer`, `Pending.HandleContext`, `Pending.Suspend` and `Pending.Snapshot`, and has
no `Pending.SuspendArrow` arm; the only `SuspendArrow` in the base's `Eval.scala` is at line 117, in
the main dispatch loop, which is a different walk. The base's `Eval.scala` contains no `<:<` at all,
so it has no tag test for F108 to have moved.

A `moved` provenance counts because it names where the code came from and a reader can go look. Here
five rows name a source that does not exist, so none of the five is a provenance. A whole new
capability, the operation a released remainder stands at being reported back to its caller, enters
the kernel with every one of its constructs marked as having come from somewhere else. This is the
one finding in this report that would have changed what a reviewer looked at.

### D5-2: F7, an allocation per live cell carried by a `moved` verdict

Row F7, site `Bracket.scala:56`, `private val once = new AtomicBoolean(false)`. Verdict as written:
`moved: the base's exactly-once guard, a field rather than the superclass`.

At the base, `Bracket.Cell` is declared `sealed abstract private[kyo] class Cell extends
AtomicBoolean`, so the cell is its own guard and no second object exists. At the tip, `Cell extends
Release` and `Live` holds an `AtomicBoolean` as a field, which is one more object allocated per
acquisition than the base allocated. The guard mechanism did move, and the verdict's own second
clause says how it changed, but the construct the row flags is the allocation, and that allocation is
new. A provenance for the mechanism is not a provenance for the object. The closed set's slot for a
new allocation on a path a bracket takes on every acquisition is a measurement, and there is none.

### D5-3: F44, a provenance naming a symbol the base does not have

Row F44, site `Eval.scala:76`, `inline def crossing(result: Any): Boolean`. Verdict as written:
`moved: the base's `delivering`, with the fused arm gone`.

The base has no `delivering`. The word occurs twice at the base and both are prose, in a comment at
`Eval.scala:138` and in a scaladoc at `PendingInternal.scala:56`. The predicate the new `crossing`
carries was written inline at the base at `Eval.scala:140` and `Eval.scala:152`, and the separate
`Pending.Fused` test the verdict's second clause refers to was written inline at `Eval.scala:94`. So
the extraction into a named `inline def` is real and checkable, but the name the provenance hands the
reviewer resolves to nothing, which is what a provenance exists to prevent.

### D5-4: F77 and the eighteen rows that inherit it, `ruled` for an arm no ruling contains

Rows F77, F85, F91, F126, F130, F132, F139, F140, F141, F142, F143, F144, F145, F153, F155, F156,
F159, F160, F163. Nineteen rows. Sites at the tip run from `Eval.scala:383` through
`Stack.scala:313`. Verdict as written on the root: `ruled: the list shape, empty as `null``, with the
other eighteen reading `ruled: as F77`.

The recorded ruling of 2026-09-13 is verbatim in `rulings.md`: "for releases make it Maybe[Throwable]
=> Unit | Chunk[Maybe[Throwable] => Unit] so we don't need to allocate for a single element." That is
a two arm union. It says nothing about an empty, and `null` appears nowhere in it. The code agrees
with the ruling and not with the row: `Stack.scala:274` declares `type Releases = Release |
Chunk[Release]`, two arms, no `Null`, while nineteen sites assign `null` into slots of that type and
test it with `eq null` and `ne null`. The table's preamble writes the shape as `null | Release |
Chunk[Release]`, which is a third arm the declared type does not carry.

The three arm shape appears only in the author's own write-ups, `analysis/redesign.md:8` and
`analysis/derivation-releases.md:10`, both of which state it as a conclusion in their own prose.
Neither quotes the reviewer. Against that, the standing consequence recorded under the 2026-08-29
rulings is "no `Any` or `Null` carrier in the evaluator", from "var res: any = null / why the fuck
are you doing this!?" and "FUCKING SAFE CODE!!! PROPERLY TYPED!!!". So the only recorded ruling that
speaks to a null carrier in the evaluator cuts against these nineteen rows rather than supporting
them. `ruled` is the strongest verdict in the set because it ends the argument; a row that claims it
for a shape the reviewer did not rule reduces to the author having found the shape acceptable, which
is the substance the lens names as not counting.

### D5-5: F127, `ruled` for a class the ruling does not describe

Row F127, site `Release.scala:15`, `abstract private[kyo] class Release extends (Maybe[Throwable] =>
Unit)`. Verdict as written: `ruled: the release the user specified, `Maybe[Throwable] => Unit`, with
the ran flag the refusal on re-push was ruled to use`.

The ruling specifies a function type. What the change adds is a new abstract class over that function
type, carrying `ran`, `reenter` and an implementation of `apply`. Half the verdict is a genuine
ruling, the arrow type, and half is not: no recorded ruling mentions a class, and no recorded ruling
mentions the ran flag. The derivation states both as its own conclusion at
`analysis/derivation-releases.md:10` and `analysis/redesign.md:29`, in the author's prose. Under the
2026-08-29 standing consequence a new type needs an argument that an existing one cannot serve, and
`ruled` is not that argument. The ran flag may well be the right design; the row's problem is that it
is presented as settled when the record settles only the arrow type beside it.

### D5-6: F74 and F75, a `justified` for a word a ruling bans by name

Rows F74 and F75, sites `Eval.scala:349` and `Eval.scala:352`. Verdict as written on F74:
`justified: the region's continuation, read before the entries it sits under are popped; `next` is
the name the arm above uses for the same slot read after the pop, and the two differ in that one
word`. F75 reads `justified: as F74`.

The 2026-08-29 ruling on vocabulary is verbatim: "Avoid new terminology: 'drive' is explicitly
banned, the correct is 'eval', don't use 'after' use 'cont', make sure naming is fully consistent."
`val after` at `Eval.scala:349` is the only identifier use of `after` in the kernel's main sources at
the tip, and it is used once, at `Eval.scala:352`. The verdict argues that the name is right, and its
own second clause concedes that `next` already names the same slot one arm above, which is the
inconsistency the same ruling's last clause asks to be closed. A verdict that argues against a
recorded ruling is not one of the four things a verdict may be.

### D5-7: F54 and its four dependents, the phrase the lens names verbatim

Rows F54, F56, F59, F65, F67. Sites `Eval.scala:305`, `:309`, `:319`, `:330`, `:337`. Verdict as
written on the root: `justified: the evaluator's currency at the region's hook`. The other four read
`justified: as F54`.

This is a house-style claim and nothing else. It names no ladder category, carries no number, names
no base construct, and cites no ruling. The lens lists "the evaluator is the engine room" as an
example of a verdict that does not count, and "the evaluator's currency" is that sentence with the
nouns changed. The construct underneath is an `Any` carrier in the evaluator, which is what the
2026-08-29 ruling names, so these five rows are the ones a reviewer would most want a real verdict
on. Note the contrast with F63 and F103, which flag the same shape and do give one: F63 says the
slot is erased, and F103 names the base's `collected` buffer, which is present at `Eval.scala:638` at
the base. Both of those are accepted here.

### D5-8: F114, `Any` explained as a meaning rather than a category

Row F114, site `Handler.scala:265`, `Handler[Hidden, Any, Any]`. Verdict as written: `justified: the
gap answers nothing and produces nothing; `Any` is the absence of both`.

Using `Any` to mean absence is a choice about the type, not a fact about erasure, a measurement, a
provenance or a ruling. The closed set has no category for it, and the kernel already has a type that
says absence, which is what the 2026-08-29 ruling asks for when a signature stops saying what the
method does. The row is a statement that the author found the shape acceptable.

### D5-9: four new types carried by design arguments

Rows F3 (`Bracket.scala:44`, `sealed abstract private[kyo] class Cell extends Release`), F4
(`Bracket.scala:48`, `final class Empty[R](...) extends Cell`), F111 (`Handler.scala:249`, `sealed
trait Hidden extends Effect`), F115 (`Handler.scala:265`, `object Gap`).

Verdicts as written, in order: `justified: the region's state is the release its entry holds; one
object plays both roles rather than a state plus a closure per bracket`; `justified: the region
before its acquire arrived owns nothing, and an empty cell says so by shape rather than by a flag on
the live one; `release(Empty)` is `Absent``; `justified: a stack entry is a handler and a handler has
a tag; the gap's is one nothing raises, so `find` never matches it`; `justified: the entry that hides
the region a loop clause serves, replacing the base's dump of the interior, its `clauseDispatch`
re-push and its crossing back; one object, no state of its own beyond the count in its entry`.

Each is an argument for why the shape is good. None names a ladder category, a number, a base type it
came from, or a ruling. The base has `Bracket.Cell` and `Cell.Live` and neither `Empty`, `Hidden` nor
`Gap`, so three of the four types are genuinely new, and the fourth changes its supertype. The
2026-08-29 ruling on `Region` sets the bar for a new type as an argument an existing one cannot
serve. These rows may clear that bar on the merits, but the table does not put them in a form a
reviewer can check without relitigating the design, which is what the closed set exists to avoid.
F115 is the closest to acceptable: it does name the base mechanisms it replaces, so a reviewer knows
where to look, but it names mechanisms rather than a type the object came from.

### D5-10: three carrier rows whose verdict restates the line

Rows F25 (`Bracket.scala:191`, `done[S2 <: S](state: Cell, value: Any < S2)`), F28
(`Bracket.scala:194`, `Nested.unnest[Any](value)`), F30 (`Bracket.scala:204`, `Loop.continue[Cell,
Any < (Finalize & S2), Any < S2](live, body)`).

Verdicts as written: `justified: the protocol's `done`, whose row is a parameter because the outcome
holds it invariantly`; `justified: the settled arm's single unnest, at the region's hook`;
`justified: the continue outcome naming the live cell as the region's state from here on`.

Each says what the line does. Saying what a line does is not a verdict on why its `Any` is there.
F25 is the clearest case: the flagged construct is the `Any` in `Any < S2`, and the verdict explains
the `S2` type parameter instead, so it does not answer the flag at all. Note the contrast with F21
through F33's other rows, which point at F13 and therefore inherit `erasure-forced`; those are
accepted here.

### D5-11: thirteen mutability rows carried by a scope argument

Rows F81, F82, F83, F84 (roots and dependents at `Eval.scala:509`, `:540`, `:542`, `:546`) and F87,
F88, F89, F90, F93, F94, F95, F96, F109 (`Eval.scala:639`, `:640`, `:641`, `:644`, `:657`, `:658`,
`:659`, `:668`, `:783`).

Verdicts as written on the two roots: `justified: the pop loop over the hidden entries, a local index
over the stack the evaluator owns` and `justified: a local accumulator over one pop's releases;
absence as `Maybe`, never escaping`.

Both argue that the mutation is local and contained. That is a reasonable thing to believe and it is
still not one of the four things a verdict may be: it names no category, no number, no base
construct, and no ruling. The comparison that shows the gap is F146, which flags the same construct
class at `Stack.scala:170` and says "the base walked upward with the same two locals". That is a
provenance and it is accepted here, along with its four dependents. F81 and F87 could have been
written the same way if the base had the same loops, and the fact that they were not is the signal.

### D5-12: no row anywhere carries a number, while three make comparative cost claims

Row F35, site `Isolate.scala:291`, `new Arrow.Step[A, (Snapshot, Snapshot, A), S]`. Verdict as
written: `justified: one step per crossing where the base composed two `map`s, each allocating a
`DeferWith` under a pending body; fewer nodes on the crossing`.

"Fewer nodes on the crossing" is a claim about cost on the path every isolate crossing takes. The
closed set's answer to a cost claim is a measurement, a number with the row it came from, or an
explicit "measurement pending" naming the rows that will carry it. The table has neither, in this row
or in any of the other 163. F3 makes the same shape of claim ("one object plays both roles rather
than a state plus a closure per bracket") and F14 makes it again ("one empty cell per entry, in place
of the base's `Ensure` arrow and `Effect.defer` node"). Read together with D5-2, which adds one
`AtomicBoolean` per acquisition, the walk changes the allocation profile of the bracket and the
crossing in both directions and reports not one number about either. F35's provenance clause does
name the base site, which is why the row is counted as failing only on its comparative clause; F14 is
accepted on its provenance clause alone.
