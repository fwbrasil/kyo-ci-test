# Lens report: kernel-discipline

FAIL

Range judged: `cdefdc9e60..95cac78133`, paths `kyo-kernel/{shared,jvm-native,js-wasm}/src/main/scala`.
`kyo-kernel/.claude/skills/kernel/SKILL.md` is not present in the tree (the directory holds `flags.sh`,
`package-check.sh`, `rulings.md` only), so the cast ladder used is the one restated in the brief.

## Enumeration

I re-ran the enumeration on the 80 added lines of the `-U0` diff, independently of `flags.sh`, with both
word-boundary and plain-substring passes over the brief's pattern list.

Constructs found, 9:

| pattern | count | sites |
|---|---|---|
| `asInstanceOf` | 4 | Handler.scala 386, 413, 478, 556 |
| `@unchecked` | 1 | Handler.scala 109 |
| `new <Type>` | 4 | ArrowEffect.scala 213, 274, 990; Handler.scala 105 |
| `.erased`, `Any`, `Nothing`, `Null`, `null`, `var`, `while`, new `class`/`trait`/`object`/`enum`/`type`, `drive`/`drives`/`driving`/`after`, `TODO`/`FIXME`/`???` | 0 | none |

Two near-matches are not constructs: `end new` at Handler.scala:111 is a closing marker, and the word
"type" at Handler.scala:102 is inside a comment about `Arrow`'s type member, not a declaration. No named
class, trait, object, enum, or type alias is added; the four `new` sites are the anonymous classes.

**9 constructs enumerated, plus the 2 hand-added rows, against 11 rows in the table.** Every enumerated
construct has a row, and every row's cited line text matches an added line byte for byte. No `D-missing-n`
finding. Rule 1 is clean. The FAIL comes entirely from rule 2 and rule 3.

## Findings

**D-verdict-F1** (F1, ArrowEffect.scala:213). The verdict is "justified:" followed by an argument about how
often the object is built. It is not a category from the closed set, not a measurement (no number, and no
explicit "measurement pending" naming rows), not a `moved` provenance (the code is new, not relocated), and
not `REMOVE`. It is the shape rule 2 names verbatim: "justified" with no category or number behind it. The
claim itself is checkable and looks right (ArrowEffect.scala:212 is `override val resumed`, a `val` built
with the handler), but a true rationalisation is still a rationalisation. Note that the ladder's closed set
has no category for an allocation, so an allocation row's only countable verdicts are a number, a `moved`
provenance, or `REMOVE`; F1 has none of the three.

**D-verdict-F2** (F2, ArrowEffect.scala:274). "justified: as F1, for the recovering overload" inherits F1's
shape and fails rule 2 the same way.

**D-verdict-F3** (F3, ArrowEffect.scala:990). "justified: as F1" plus detail about `escaping` and `repeated`
carrying over. The added detail is structural, not numeric. Fails rule 2 as F1 does.

**D-verdict-F4** (F4, Handler.scala:105). The verdict opens "justified:" and closes "Number: the benchmark
section of the package, every row, base against tip". That closing clause names a row set but transcribes no
number and does not say "measurement pending". Rule 2 accepts a number with the row it came from, or an
explicit pending naming the rows; this is neither. Ranked below D-verdict-F1 through F3, because a number
source does exist and resolves (`reviews/robustness/bench/compare-base-vs-A.md` is present), and because F10
already reports measured numbers for the same `Eval` cont arm this allocation sits in. One clause fixes it:
cite F10's figures, or mark the row pending. As written, three of the four allocation rows point at nothing
and the fourth points without quoting, which is an inconsistency visible inside the table alone.

**D-verdict-F11** (F11, Handler.scala:93). The row's whole evidence is its reach enumeration, and the count
is wrong. F11 states "the three handlers that override `repeated` all override `resumed`". At the tip, six
sites override `repeated`, all in ArrowEffect.scala: 211, 217, 272, 278, 988, 995. Three are the region
handlers; the other three are the `resumed` twins this change introduces, which set `repeated = true`
themselves. The conclusion survives, because all six also override `resumed` (212, 218, 273, 279, 989, 996,
the twins with `= this`), so `bug` stays unreachable and a re-entry through a twin terminates on `this`
without allocating. But the row understates its own reach by exactly the three objects the change adds, and
rule 3 asks whether the claim is one the table can make. It is, once the count is corrected to six with the
twins named.

**D-prose-1** (the unflagged trailing note, adjudicating the `inline` on Handler.scala:381 and 408). The note
closes "Number: the benchmark section of the package, the fix tip against the tip with C, every row." No such
comparison exists. `reviews/robustness/bench/` holds `compare-base-vs-A.md` and `compare-base-vs-A-f3.md`
only, both with tip `a61fbf0ca6`, which predates item C (`d502756c44`, the commit that created the two
`inline` helpers). The number this note points at has not been taken, and the note does not say so. I
checked filenames only and read no benchmark contents. This sits outside the table rather than in a row, so
it is not a `D-verdict-Fn`; the `inline` modifier matches no rule 1 pattern and needs no row. It is reported
because the note adjudicates two added lines with a pointer that resolves to nothing, which is the failure
rule 2 exists to catch, moved out of the table.

## Checks that passed

**Rule 3, F10.** The diff contains the cited line: `val continuation = if handler.repeated then
handler.reentering(raw) else raw`, at Eval.scala:117, inside the `ContHandler` arm of the suspension match
(Eval.scala:110 binds `handler` at `Handler.ContHandler`), so "Eval.scala, cont arm" names the site
correctly even though it is the one row giving no line number. The row carries numbers, not a pointer: the
base and tip SHAs, the 5 percent band, three named out-of-band rows with their percentages, the `-f 3`
re-run with its percentages and error bars, and both files those came from. Both files exist. The base
`dcadee780d` is a baseline copy of the benchmark source change `52095e058f`, not the parent of
`a61fbf0ca6`, which makes the two sides share benchmark sources and isolates the kernel change; that is a
sound comparison for this line, not a discrepancy.

**Rule 3, F11.** The diff contains the cited line at Handler.scala:93. The two reachability clauses hold:
`resumed` has exactly one call site in the repository, Handler.scala:104 inside `reentering`, and
`reentering` has exactly one call site, Eval.scala:117, guarded by `handler.repeated`. Only the count is
wrong, which is D-verdict-F11.

**Rule 2, F5 through F9.** These five pass.

- F5 leads with `erasure-forced`, a category from the closed set. Its trailing consistency clause would not
  count alone, but the category carries the row.
- F6 gives both a `moved` provenance and the category `representation assertion`. The provenance verifies:
  the removed hunks at base Handler.scala 203 to 205 and 435 to 438 each carried
  `o.asInstanceOf[Outcome[A < (E & S), B < S] < S]`.
- F7 verifies the same way against the removed hunks at base 276 to 278 and 516 to 519.
- F8 and F9 give a `moved` provenance plus `erasure-forced`. Base lines 437 and 518 carry the identical
  `k.asInstanceOf[Arrow[O[C], A, E & S]]` at the same sites.

The `moved` claims on F6 and F7 are precise in a way worth recording: the helpers are `inline` and called
from two sites each (228 and 478; 299 and 556), which is the same two sites that wrote the cast before, so
the expansion count is unchanged.

**Rule 4, tail-call claims.** No trigger. The string "tail" does not occur on any added line, so no added
comment asserts a tail call anywhere, under a cast or otherwise. The nearest thing is the scaladoc at
Handler.scala:379 and the trailing note, which claim the `inline` avoids adding a call inside each
expansion. That is a claim about a call not being introduced, not an assertion that a call is in tail
position, so it does not meet rule 4.

## What would turn this PASS

Four of the six findings are one clause each. F1, F2, and F3 need a number or an explicit pending naming the
rows that will carry it, the form F4 reaches for. F4 needs its number quoted rather than pointed at, which
F10 already has for the same cont arm. F11 needs its count corrected from three to six with the twins named.
D-prose-1 needs either the tip-with-C comparison run, or the note marked as a measurement not yet taken.
