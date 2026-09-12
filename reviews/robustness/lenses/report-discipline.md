# Report: kernel-discipline, round 5

PASS

Round 5. Tip judged: `eed8a05ac3d9469f641ae11b21cb7d6780de8848`, branch `robustness`, range
`cdefdc9e60..HEAD` over `kyo-kernel/shared/src/main/scala`, `kyo-kernel/jvm-native/src/main/scala`
and `kyo-kernel/js-wasm/src/main/scala`.

**Constructs enumerated: 9. Rows in the table: 9.** One to one by site.

No findings. Round 4's four findings are all closed against this tip, verified number by number
against the files the rows now cite rather than against the author's account of the fix. The kernel
sources are byte-identical to the tip round 4 judged (`git diff b167efef69..HEAD` over the three
main-source paths is empty), so rules 1, 3 and 4 were re-run from scratch and reach the same result
on the same evidence; rule 2 is the rule that changed, and it is the one I re-derived in full.

## Findings

None.

## Status of round 4's findings

### D-verdict-F1 (F1, Handler.scala:333, rule 2, the measurement class): RESOLVED

F1 no longer names `bench/compare-rows-base-vs-tip-clean.md`, and no longer withdraws the cell it
prints. It now prints a number that exists, from a file that exists, and names the queued rerun as
queued rather than as a citation: "The recovering row's time is in `bench/compare-rows-base-vs-tip.md`
only, 59.0 us against 60.5 us, +2.6%, whose base leg ran beside a compile; a clean rerun of the three
rows is queued and will replace that one cell."

`bench/compare-rows-base-vs-tip.md` carries `repeatedRegionsPayEntryRecovering` at 58.963 +/- 2.864
against 60.481 +/- 3.628, +2.6%. That is the printed cell. The row it applies to and the cell the
rerun replaces are both named, so this is a measurement with a caveat rather than a pending, which
is stronger than what O1 asked for.

The contamination behind the caveat is real, and the run's own error bars are what show it rather
than the attribution the row gives. `base-rows-3.log` reports `repeatedClausesPayReentry` at
151.282 +/- 59.533 us/op, a 39% error band on a row the same-session `-f 3` confirmation puts at
99.780 +/- 0.281. A leg that scores 52% high with an error two orders of magnitude wider than the
clean leg's was disturbed by something. The attribution to a compile specifically is not pinned by
the timestamps; see O8.

The split the row makes is the right one either way. The allocation half is read off the same
disturbed run and is unaffected by the disturbance, `gc.alloc.rate.norm` being bytes per operation
rather than wall clock, and its error bands say so: 480121.051 +/- 0.413 B/op on the leg whose time
carries +/- 59.533 us/op. Only the time cell is caveated.

### D-verdict-F2 (F2, Handler.scala:352, rule 2): RESOLVED, both halves

The absent file is gone from F2 as well. The cross-session ratio is gone with it: F2 now reads
"Time, same session both legs, `-f 3`, `bench/compare-base-vs-tip-f3.md`: 99.8 +/- 0.3 us against
636.1 +/- 7.4 us, 54 ns per operation, 6.4 times."

That file carries `repeatedClausesPayReentry` at 99.780 +/- 0.281 against 636.051 +/- 7.425. Both
printed cells are exact. 636.051 over 99.780 is 6.374, so 6.4 times. The difference, 536.271 us over
`Depth` = 10,000 operations, is 53.6 ns, so 54 ns per operation. This is the figure round 4 said
`review.md:323` carried and the table did not; the table now carries it, and the two agree.

"Same session both legs" is true and checkable. `base-3-f3.log` completed 12:16:24 after 316 s, so it
ran 12:11:08 to 12:16:24; `tip-3-f3.log` completed 12:21:52 after 315 s, so it ran 12:16:37 to
12:21:52. Back to back, no overlap, and no other run in the package's logs falls in either window.
Both are `-f 3` (`# Fork: 1 of 3`, `Cnt 15`).

### D-cite-F1 (F1, allocation pointer): RESOLVED

F1 now cites the profiler runs themselves: "from the profiler runs' `gc.alloc.rate.norm` lines
(`bench/base-rows-3.log`, `bench/tip-rows-3.log`, base against tip, `-f 3 -prof gc`)". Both files
exist, both are now committed by the tip commit rather than untracked, and both were invoked with
`-f 3 -prof gc` (their line 17 carries the literal `org.openjdk.jmh.Main -f 3 -prof gc`).

The numbers are in them, on the summary lines:

| row | base-rows-3.log | tip-rows-3.log |
|---|---|---|
| repeatedRegionsPayEntry | 88104.392 +/- 0.023 B/op | 104120.419 +/- 0.043 B/op |
| repeatedRegionsPayEntryRecovering | 88104.412 +/- 0.020 B/op | 104120.422 +/- 0.024 B/op |

F1 prints 88,104 against 104,120 on both rows, which is those four figures rounded. The derivation
reproduces: 16016.027 B over `NarrowDepth` = 1000 regions is 16.016 B, so 16 bytes per region.

The two counts the derivation divides by are the benchmark's own and I checked them at the source
rather than taking the row's word. `KernelBench.scala:596` is `inline def NarrowDepth = 1000` and
`repeatedRegionsPayEntry` (line 573) and `repeatedRegionsPayEntryRecovering` (line 582) each enter one
`handleContRepeated` region per iteration up to it, so 1000 regions per invocation. They go through
different overloads, the two-argument one and the three-argument recovering one, which is F1's
"through each overload".

### D-cite-F2 (F2, allocation pointer): RESOLVED

Same fix, same files. `base-rows-3.log` carries `repeatedClausesPayReentry:gc.alloc.rate.norm` at
480121.051 +/- 0.413 B/op and `tip-rows-3.log` at 720140.578 +/- 0.254 B/op, which are F2's 480,121
and 720,141. 240019.527 B over `Depth` = 10,000 operations is 24.002 B, so 24 bytes per operation.
`KernelBench.scala:595` is `inline def Depth = 10000`, and `repeatedClausesPayReentry` (line 564) is
`suspensionBaseline`'s loop under `handleContRepeated`, one `ask` per iteration, each resumed once,
which is F2's parenthetical exactly.

## Status of round 4's observations

- **O1** (F1's pending named two rows, F2's named none, and the two should read the same way):
  RESOLVED. Neither row is a pending now. Both print verified numbers with the files behind them, and
  the one caveated cell names its row and itself.
- **O2** (the inherited-member surface of the new handler): still accurate on this tip and re-checked
  at the current line numbers. `reentered` at Handler.scala:330 defines `tag`, `run`, `done` and
  `repeated`, and inherits `recover` and `unbound`. The defaults are `repeated = false` at
  Handler.scala:41, `unbound(ctx) = ctx` at 61 and `recover(state, ex) = Absent` at 76, each a total
  value, and the inherited `recover` is what the added comment at ArrowEffect.scala:268 accounts for.
  See O7 below for a defect in that comment.
- **O3** (F9 argues from construction rather than naming a category): unchanged. The tip commit
  rewrote F1 and F2 only, so F9 reads as it did. The observation stands as written and is still not a
  rule 2 failure.
- **O4** (the absent file was cited in `review.md` too, so the fix was shared): RESOLVED.
  `compare-rows-base-vs-tip-clean.md` now appears nowhere under `reviews/` except inside the round 3
  and round 4 lens reports that recorded the finding. `review.md:327` and `review.md:330` carry the
  same corrected citations the table does.

## Enumeration

I ran the enumeration myself over the added lines of

```
git diff -U0 cdefdc9e60..HEAD -- kyo-kernel/shared/src/main/scala kyo-kernel/jvm-native/src/main/scala kyo-kernel/js-wasm/src/main/scala
```

The range touches four main sources, `ArrowEffect.scala`, `EffectTrace.scala`, `Eval.scala` and
`Handler.scala`, with 85 added lines. The script numbers rows positionally and the table renumbers
them, so the correspondence is matched by site and line, not by id:

| site on HEAD | construct matched | script row | table row |
|---|---|---|---|
| Eval.scala:646 | `@unchecked` | F1 | F8 |
| Eval.scala:646 | `Any` carriers | F2 | F9 |
| Handler.scala:333 | `new <Type>`, the anonymous `ContHandler` | F3 | F1 |
| Handler.scala:352 | `new <Type>`, the anonymous `Arrow.Step` | F4 | F2 |
| Handler.scala:356 | `@unchecked` | F5 | F3 |
| Handler.scala:399 | `asInstanceOf` | F6 | F4 |
| Handler.scala:426 | `asInstanceOf` | F7 | F5 |
| Handler.scala:491 | `asInstanceOf` | F8 | F6 |
| Handler.scala:569 | `asInstanceOf` | F9 | F7 |

I opened the committed file at each of the eight distinct line numbers. Every one resolves to exactly
the text its row quotes, character for character after the leading indentation the script trims.
Eval.scala:646 carries two constructs and correctly has two rows.

Patterns with zero hits on the added lines: `.erased`, `Nothing`, `Null`, `null`, `var`, `while`,
`TODO`, `FIXME`, `???`, `drive`, `drives`, `driving`, `after`, and every named `class`, `trait`,
`object`, `enum` or `type` declaration. `Any` hits exactly one added line, Eval.scala:646. `new`
hits exactly two added lines, the two anonymous classes, which are the only new types in the range.
None of the three touched Scala sources contains `???` anywhere, not only on added lines.

Added lines that reach a flagged construct without naming one themselves need no row of their own
under rule 1, and the table names each in prose: `val reentered = Handler.reentered(this)` at
ArrowEffect.scala:215 and 270, and the `Handler.reentering[...](next, reentered)` argument at 217
and 272, which F1 and F2 name as the per region entry and the per suspension arrow. The enclosing
`new Handler.ContHandler[...]` at ArrowEffect.scala:211 and 266 are context lines in this range, not
added ones, so they are correctly absent.

`EffectTrace.scala` adds two lines, both scaladoc, replacing `[[splice]]` with
`[[EffectTrace.splice]]`. It carries no construct of concern and correctly has no row.

## Rule 2, the verdicts

All nine land in an allowed class, and on this tip all nine are sound in substance as well.

- **F1**, a measurement. Numbers with the rows and the files they came from, checked above. Its two
  structural claims are true at the source: `val reentered = Handler.reentered(this)` is a `val` on
  the anonymous handler (ArrowEffect.scala:215, 270), so one per region entry rather than one per
  suspension, which is what "built once per region entry and captured by the handler's `val`" says.
- **F2**, a measurement. Numbers with the row and the files, checked above. "Built in the handler's
  `run`" is true: `Handler.reentering(...)` is called inside `run` at ArrowEffect.scala:217 and 272,
  once per suspension. "One region entered per application" is true: `reentering`'s settled arm is
  `cont2(Pending.handle[Unit, E, A, A, S](k(Nested.unnest[O[X0]](v)), reentered, ()), Arrow.id)`, one
  `Pending.handle` per application. The row states plainly that its number is a regression and routes
  the decision to open ruling 5 instead of closing it here, which is the opposite of a verdict whose
  substance is that the author found the construct acceptable.
- **F3**, a category from the closed set, erasure-forced, with the reason stated. Both cross
  references corroborate and are true: `PendingInternal.scala:89`, inside `Suspend.crossing`, carries
  `case p: Pending[A, S3] @unchecked => Effect.defer(p, this, cont2)`; `Arrow.scala:254`, inside
  `Ensure.apply`, carries `case v: Pending[A, S2] @unchecked => Effect.defer(v, this, cont)`.
- **F4**, a `moved` provenance naming both origin sites, plus an explicit category, representation
  assertion, with the inhabitation argument behind it.
- **F5**, a `moved` provenance naming both origin sites, with the category carried by reference to F4.
- **F6**, a `moved` provenance naming the origin site, plus an explicit category, erasure-forced.
- **F7**, a `moved` provenance naming the origin site, with the category carried by reference to F6.
- **F8**, a category from the closed set, erasure-forced. Its claim that the `@unchecked` typed
  pattern is the base's line unchanged is true on the diff: the removed line is the same pattern,
  differing only in `step(v)` against `step(Nested.unnest[Any](v))`. Its cross reference is true:
  `answersLoop`'s settled arm at Handler.scala:452 carries the literal `Nested.unnest[Any](ans)`, as
  does `answersLoopState`'s at 530.
- **F9**, a `moved` provenance. See O3.

No verdict reads "needed for the types to work", "consistent with the existing code", "the evaluator
is the engine room", or "justified" with no category or number behind it.

I checked the four `moved` provenances against the base rather than taking the table's word, reading
`cdefdc9e60`'s `Handler.scala` directly. The diff removes exactly four tails and each removed hunk
carries the text its row names:

| base site | enclosing member at the base | text the row names |
|---|---|---|
| Handler.scala:203 | `LoopHandler.answers`, the class at 133 | `o.asInstanceOf[Outcome[A < (E & S), B < S] < S]` |
| Handler.scala:276 | `LoopStateHandler.answers`, the class at 220 | `o2.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]` |
| Handler.scala:435 | `answersLoop`, at 372 | the same pass-through, plus `k.asInstanceOf[Arrow[O[C], A, E & S]]` as `attachReentry`'s argument |
| Handler.scala:516 | `answersLoopState`, at 450 | likewise, with `attachReentry2` |

F4, F5, F6 and F7 name the right origins.

## Rule 3, hand-added rows

The table has no hand-added rows. The F series runs F1 to F9 with no gap and no H row, so rule 3 has
nothing to check on its own terms, and I state that rather than construct a check for rows that do
not exist.

What rule 3 exists to protect is satisfied elsewhere in this table. The hot-path cost the rule's H1
would carry is carried by F1 and F2, which are the allocation rows, and both now carry a number with
the benchmark files it came from rather than a pending. The script emits the allocation class itself,
so it is not one of the classes a hand row must supply.

The preamble's reason for their absence holds against the tree on both halves. `Eval` changes one code
line, `Eval.scala:646`, inside `ensuring` at 644, a local def of `private def release` at 627, which
is the abandonment walk and not an evaluation path; the other four changed `Eval` lines are the
comment above it. No member with a partial default exists: the three defaults an added handler can
inherit are each a total value, listed under O2 above.

## Rule 4, tail-call claims

No added line contains the word tail, in any case, and no added line contains a `try`. The range's
added comments assert no tail call anywhere, so there is nothing for this rule to catch. The `try` at
ArrowEffect.scala:262 is a context line of the recovering overload, not an added one.

## Work outside the declared surface

Nothing. All four touched main sources are declared in `derivation.md`:

- `ArrowEffect.scala` and the two new `Handler` helpers: the Candidate A section, "Confined to the
  two `handleContRepeated` overloads", which names both helpers, "`reentered(outer)`, the re-entered
  handler, and `reentering(k, reentered)`, the wrapped continuation, an `Arrow.Step`". The diff
  touches those two overloads and nothing else in the file.
- `Handler.scala`'s four tails: the piece C surface block, "Surface, exactly", which lists
  `LoopHandler.answers`, `LoopStateHandler.answers`, `answersLoop` and `answersLoopState` and nothing
  else, and declares the tail becoming `attachReentryToPending` and `attachReentryToPending2`. The
  diff changes exactly those four and adds exactly those two helpers plus the two the Candidate A
  section names.
- `Eval.scala`: piece H, "Surface: `Eval.release`'s `ensuring` and `EvalTest`; nothing on any
  evaluation path", including the `[Any]` spelling.
- `EffectTrace.scala`: the `[[splice]]` scaladoc link that resolved to nothing, "the only warning that
  build emitted for the kernel".

## The numbers the table cites that are not findings

Checked and present, so these are clear:

- `bench/compare-base-vs-tip-f3.md` carries `repeatedRegionsPayEntry` 56.091 +/- 2.290 against
  58.175 +/- 1.405, +3.7%, which is F1's 56.1 against 58.2. The derived 2.1 ns per region reproduces:
  2.084 us over 1000 regions.
- `bench/compare-rows-base-vs-tip.md` carries the recovering row at 58.963 against 60.481, +2.6%,
  which is F1's second cell.
- The not-flagged note on `repeated = true` is a behavioral claim with a test named rather than a
  number, which is the right form for it, and the test exists: `BracketTest.scala:1205`, "a bracket
  inside a re-entered region is released where that region ends, before the next resumption". The
  scaladoc it points at is on the added `reentered` at Handler.scala:325 to 329 and says what the
  note says it says.
- The not-flagged note on `inline` reproduces in full. `bench/compare-A-vs-AC.md` reports 49 rows and
  zero suspects, with `handleLoopAnswersInPlace` +0.4%, `handleLoopFusesContinuation` +0.0%,
  `statefulAnswersPaySuccessor` -0.9%, `pureIterationViaArrow` -6.3% and `continuationBodiesFuse`
  -5.9%, each exactly as quoted. `bench/compare-A-vs-AC-f3.md` reports 4 rows and zero suspects, with
  those last two at +3.8% and +4.4%, inside the band.

## Observations, not findings

**O3 carried forward.** F9's verdict is a `moved` provenance for the part of the line that is the
base's, and for the part that is genuinely added, the `[Any]` on the unnest, it gives a construction
argument, "`Nested.unnest` being `Any => A` by construction", rather than a named category. The
argument is true and checkable, and the line's other row, F8, carries erasure-forced for the same
erased currency, so this is not a rule 2 failure. Naming the category outright on F9 would put it in
the closed set without relying on the neighbouring row.

**O5.** F1's word "only" is true only under its own `-f 3` framing. The sentence reads "The recovering
row's time is in `bench/compare-rows-base-vs-tip.md` only". Among the `-f 3` comparisons that is
right: `bench/compare-base-vs-tip-f3.md` does not carry the recovering row at all. But
`bench/compare-base-vs-tip.md:39` does carry it, at `-f 1`, 57.998 +/- 7.940 against 58.752 +/- 2.025,
+1.3%, from `base-3.json` and `tip-3.json` (`# Fork: 1 of 1`, `Cnt 5`). A reader who greps for the row
finds a second figure at half the magnitude of the printed one and no word in the table about it.
Writing "the only `-f 3` file carrying it" would close the gap in three words, and naming the `-f 1`
figure beside it would be better still, since the two bracket the cell the queued rerun will settle.

**O6.** F2's "(64 on the earlier shape, whose wrap lived in `Eval`)" is the one number in either
rewritten row with no file named. It is real and I located it: `bench/base-rows.log` carries
`repeatedClausesPayReentry:gc.alloc.rate.norm` at 480120.687 +/- 0.005 B/op and `bench/AC-rows.log`,
the same session, at 1120180.903 +/- 0.206 B/op. 640,060 B over 10,000 operations is 64.006 B, so the
64 reproduces exactly. This is not a rule 2 failure: the number names its row, the row being the one
the whole verdict is about, and it is a parenthetical contrast against a superseded shape rather than
the figure the decision rests on. It is worth citing anyway, because every other number in F1 and F2
now names its file and this one does not, and because the contrast is the strongest single argument
the row makes for shape C over the earlier one.

**O7.** The added comment at ArrowEffect.scala:269 ends mid-phrase: "so a throw inside it unwinds to
this handler's", with no noun after the possessive. The sentence is the one that accounts for the
re-entered handler inheriting `recover`, which is O2's subject, so the missing word is load bearing
for a reader working out why the recovering overload is safe. Not a discipline rule failure and not
mine to fix under this lens's constraints, but it is in the range's added lines and should not reach
the live review unnoticed.

**O8.** F1's reason for discounting the recovering row's time, "whose base leg ran beside a compile",
is not supported by the run logs, though the discounting itself is. `base-rows-3.log` completed at
11:38:38 after 103 s, so its leg ran 11:36:55 to 11:38:38. No compile in the package overlaps that
window: `base-rows-3.compile.log` took 3 s and finished at 11:36:38, before it started;
`tip-3-f3.compile.log` took 198 s and finished at 11:36:00, also before; and `tip-rows-3.compile.log`
took 2 s and finished at 11:38:54, sixteen seconds after it ended. The sbt startup and project
loading that precede a compile task are not counted in its reported time, so the tip compile's
process may well have been loading during the base leg's tail, but nothing in the logs records that.

The finding this does not become is the point: the cell is discounted on evidence that is stronger
than the stated reason, namely the leg's own error bands above. Replacing "ran beside a compile" with
what the run itself reports would make the caveat checkable from the file the row already cites.
