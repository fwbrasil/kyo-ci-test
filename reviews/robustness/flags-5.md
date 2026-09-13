# Flags on the fifth walk's kernel diff, `bc6a48a2aa..HEAD`

Every construct of concern `flags.sh` found on the added lines of the kernel's main sources
(`kyo-kernel/shared/src/main`, `kyo-kernel/jvm/src/main`), 162 rows, each with a verdict. Sites are
line numbers at the tip.

The kernel's main sources under the flagged paths last changed at `96f279c752` (the `after` to `next`
rename); `b3849fd99c` touched only `kyo-kernel/js-wasm`, which emits no row, so the sites hold across
the range's later commits. Run `PACKAGE_FLAGS=flags-5.md PACKAGE_SEQUENCE=sequence-5.json
kyo-kernel/.claude/skills/kernel/package-check.sh bc6a48a2aa..HEAD reviews/robustness kyo/kernel --
kyo-kernel/shared/src/main kyo-kernel/jvm/src/main` to re-derive the count.

The verdict set has two parts, because the diff carries two kinds of construct. A **cast** row takes a
cast-ladder category (erasure-forced, reference-identity, representation, evidence-backed,
macro-emitted), a measurement or a `moved` provenance, or `REMOVE`; that is the closed set
`kernel-discipline` enforces. A **concession** row (`new-type`, `mutability`, `allocation`) is not a
cast, and the kernel skill governs it by the concession shape rather than the cast ladder:
justification (a measurement or a structural proof) plus minimal scope plus a protective measure plus
a pinning test. Its verdict here is `moved` when the base or an earlier walk carried the same shape,
`ruled` when the user settled it, a `measurement`, or `justified`/`structural` carrying the concession
shape. The note below the table lists the concession rows that rest on the skill's framework rather
than on a cast category, which `kernel-discipline`, being cast-focused, flags; they are the reviewer's
to rule. `comment` marks a flag that fell on prose. The user's rulings on the list shape
(`Release | Chunk[Release]`, `null` the empty state, one element without a chunk) and on the protocol
(`release(state)`, `escaping` alone, no `repeated`) are in `analysis/redesign.md`,
`analysis/derivation-releases.md` and the rulings file.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | ArrowEffect.scala:121 | scaladoc: "refuses a resumption after that" | terminology | comment: `handleCont`'s contract, the refusal after the release ran |
| F2 | Bracket.scala:29 | scaladoc: the held-continuation rule | terminology | comment |
| F3 | Bracket.scala:44 | `sealed abstract private[kyo] class Cell extends Release` | new-type | ruled: the cell region the user specified on 2026-09-13 ("the cell region, holding the release from derive on, around an acquire region whose done fills the cell and returns use(a)"); one object is state and release because the user named it so |
| F4 | Bracket.scala:48 | `final class Empty[R](...) extends Cell` | new-type | ruled: the two states of that cell region; Empty owns nothing before the acquire arrives, Live owns the value from the first done, so `release(Empty)` is `Absent` by shape not by a flag |
| F5 | Bracket.scala:49 | `new Live(value, fin, frame)` | allocation | justified: one live cell per acquisition, at the region's first `done`, where the old `Ensure` allocated its `Cell.Live` |
| F6 | Bracket.scala:55 | `final class Live[R](...) extends Cell` | new-type | moved: the base's `Cell.Live`, now the `Release` itself |
| F7 | Bracket.scala:56 | `new AtomicBoolean(false)` | allocation | measurement: the bracket rows `bracketPerRound`, `bracketAroundLoop`, `bracketEnsuringOnly`, base vs tip in `bench/*-5*.json`; the field allocation is not a measured regression (bracketPerRound faster at the tip). See the benchmark section |
| F8 | Bracket.scala:58 | `@volatile private var ended = false` | mutability | moved: the base's `ended` |
| F9 | Bracket.scala:73 | `new KyoException("remainder discarded")` | allocation | justified: the discard signal, allocated only on the discard path, carrying the bracket's own frame where the base's drain carried `Frame.internal` |
| F10 | Bracket.scala:91 | the refusal message | terminology | moved: the base's message |
| F11 | Bracket.scala:97 | `throw new Closed(...)` | allocation | moved: the base's refusal |
| F12 | Bracket.scala:130 | comment: the region typed at `Any` | carrier | comment |
| F13 | Bracket.scala:131 | `use(a.asInstanceOf[A])`, `.asInstanceOf[B < ...]` | cast | erasure-forced: the region's value changes at its first `done`, from the acquire's `A` to the use's `B`, so the one region is typed at `Any` and its two ends are re-typed at the public boundary |
| F14 | Bracket.scala:131 | `new Cell.Empty[A](...)` | allocation | justified: one empty cell per entry, in place of the base's `Ensure` arrow and `Effect.defer` node |
| F15 | Bracket.scala:146 | comment | carrier | comment |
| F16 | Bracket.scala:147 | `.asInstanceOf[B < S]` | cast | erasure-forced: as F13 |
| F17 | Bracket.scala:147 | `new Cell.Live((), fin, _frame)` | allocation | moved: the base's `ensuring` cell |
| F18 | Bracket.scala:166 | comment | carrier | comment |
| F19 | Bracket.scala:168 | `new Cell.Live(init, release, _frame)` | allocation | moved: the base's `ensuringWith` cell |
| F20 | Bracket.scala:174 | `.asInstanceOf[B < S]` | cast | erasure-forced: as F13 |
| F21 | Bracket.scala:179 | `region[S](cell: => Cell, body: Any < (Finalize & S))(use: Any => Any < ...)` | carrier | erasure-forced: the region's value changes type at its first `done`, from the acquire's `A` to the use's `B`, so the one region is stored at `Any`; the same reason as F13 |
| F22 | Bracket.scala:181 | `): Any < S` | carrier | erasure-forced: as F21 |
| F23 | Bracket.scala:182 | `ContextHandler[Cell, Finalize, Any, S]` | carrier | erasure-forced: as F21 |
| F24 | Bracket.scala:182 | `new Handler.ContextHandler` | allocation | moved: the base's handler, one per bracket node |
| F25 | Bracket.scala:191 | `done[S2 <: S](state: Cell, value: Any < S2)` | carrier | erasure-forced: as the region row above, the region's erased value handed to `done` |
| F26 | Bracket.scala:193 | `case empty: Cell.Empty[Any] @unchecked` | cast | erasure-forced: `Cell` is stored unparameterised in the region's state slot; the typed pattern binds it back |
| F27 | Bracket.scala:193 | `Cell.Empty[Any]` | carrier | erasure-forced: as F21 |
| F28 | Bracket.scala:194 | `Nested.unnest[Any](value)` | carrier | erasure-forced: as the region row above; the hook unnests the erased region value once |
| F29 | Bracket.scala:195 | comment | mutability | comment |
| F30 | Bracket.scala:204 | `Loop.continue[Cell, Any < (Finalize & S2), Any < S2](live, body)` | carrier | erasure-forced: as the region row above, the live cell as the region's erased state |
| F31 | Bracket.scala:207 | `Loop.settled[Cell, Any < (Finalize & S2), Any, S2](value)` | carrier | representation: the value is already union currency; `Loop.settled` takes it without a lift |
| F32 | Bracket.scala:209 | `Loop.settled[...]` | carrier | representation: as F31, the inert cell's end |
| F33 | Bracket.scala:210 | `HandleContext[Cell, Finalize, Any, S]` | carrier | erasure-forced: as F21 |
| F34 | Bracket.scala:210 | `new Pending.HandleContext` | allocation | moved: the base's region node |
| F35 | Isolate.scala:291 | `new Arrow.Step[A, (Snapshot, Snapshot, A), S]` | allocation | measurement: the crossing rows `foreignCrossingsPayRotation` and `foreignCrossingsAnsweredInPlace`, base vs tip in `bench/*-5*.json`, faster at the tip; one step per crossing where the base composed two `map`s. See the benchmark section |
| F36 | Isolate.scala:295 | `case p: Pending[A, S2] @unchecked` | cast | representation: the canonical pending-first arm every standalone arrow spells |
| F37 | Isolate.scala:299 | `captured(v).asInstanceOf[Any < Any]` | cast | moved: the base's `inner.asInstanceOf[Any < Any]` into `Park` |
| F38 | Isolate.scala:299 | `Pending.Park[...]` | carrier | moved: the base's park around the isolated body |
| F39 | Loop.scala:159 | `new Done(v).asInstanceOf[Outcome2[St, A, O < S] < S]` | cast | representation: as `Loop.done`, the opaque outcome union spelled through a cast because the opaque type is not transparent here |
| F40 | Loop.scala:159 | `new Done(v)` | allocation | moved: `Loop.done`'s wrapping of an answer that is itself a continue, on that path only |
| F41 | Loop.scala:160 | `v.asInstanceOf[Outcome2[St, A, O < S] < S]` | cast | representation: the value is already union currency; the cast blocks the lift from wrapping it again |
| F42 | Debugger.scala:95 | `inline def onResult` | carrier | moved: the base's line, re-aligned by the formatter after `onRelease`'s signature grew |
| F43 | Eval.scala:37 | scaladoc | mutability | comment |
| F44 | Eval.scala:76 | `inline def crossing(result: Any): Boolean` | carrier | moved: the base's inline crossing test at `Eval.scala:94` and `:138` (the base had no `delivering` symbol; the word was only a comment), extracted to a named `inline def` |
| F45 | Eval.scala:101 | `stack.state(idx).asInstanceOf[VX]` | cast | erasure-forced: array element re-typing at the storage boundary |
| F46 | Eval.scala:184 | `type OutT = Outcome[...]` | new-type | moved: the base's local alias for the outcome in flight |
| F47 | Eval.scala:235 | `type OutT = Outcome2[...]` | new-type | moved: as F46 |
| F48 | Eval.scala:268 | `stack.state(outerIdx).asInstanceOf[VX]` | cast | erasure-forced: as F45 |
| F49 | Eval.scala:283 | `kyo.value.asInstanceOf[T < S2]` | cast | moved: the base's park arm |
| F50 | Eval.scala:287 | `contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]]` | cast | moved: the base's park arm |
| F51 | Eval.scala:287 | `Arrow[Any, Any, Any]` | carrier | moved: as F50 |
| F52 | Eval.scala:304 | `stack.state(top).asInstanceOf[VX]` | cast | erasure-forced: as F45 |
| F53 | Eval.scala:305 | `res.asInstanceOf[AX < Any]` | cast | representation: the settled value handed to `done` in union representation, the hook unnesting once |
| F54 | Eval.scala:305 | `AX < Any` | carrier | erasure-forced: `AX` is the region's erased answer type in the evaluator's union-currency position, as the cast on the line above |
| F55 | Eval.scala:309 | `case c: Loop.Continue2[VX, AX < Any] @unchecked` | cast | erasure-forced: the outcome's payload types are erased; the typed pattern binds them |
| F56 | Eval.scala:309 | `Continue2[VX, AX < Any]` | carrier | erasure-forced: as F54 |
| F57 | Eval.scala:311 | `c._1.asInstanceOf[AnyRef] ne state.asInstanceOf[AnyRef]` | cast | reference-identity: a state the hook did not replace owes nothing new; identity is the test, and `VX` is erased |
| F58 | Eval.scala:319 | `outcome.asInstanceOf[Outcome2[VX, Any, AX < Any]]` | cast | erasure-forced: `Loop.unnest` takes the outcome union at erased payloads |
| F59 | Eval.scala:319 | `Outcome2[VX, Any, AX < Any]` | carrier | erasure-forced: as F54 |
| F60 | Eval.scala:322 | `result.asInstanceOf[Y < Any]` | cast | moved: the base's `res.asInstanceOf[Y < Any]` into the region's continuation |
| F61 | Eval.scala:322 | `Y < Any` | carrier | moved: as F60 |
| F62 | Eval.scala:328 | `stack.continuation(top).asInstanceOf[Arrow[Any, Any, Any]]` | cast | erasure-forced: as F45, the gap's continuation slot |
| F63 | Eval.scala:328 | `Arrow[Any, Any, Any]` | carrier | justified: the gap's cont is erased, applied to a value whose type only the clause knows |
| F64 | Eval.scala:330 | `case c: Loop.Continue[Y < Any] @unchecked` | cast | erasure-forced: as F55 |
| F65 | Eval.scala:330 | `Continue[Y < Any]` | carrier | erasure-forced: as F54 |
| F66 | Eval.scala:337 | `case c: Loop.Continue2[Any, Y < Any] @unchecked` | cast | erasure-forced: as F55 |
| F67 | Eval.scala:337 | `Continue2[Any, Y < Any]` | carrier | erasure-forced: as F54 |
| F68 | Eval.scala:346 | `stack.handler(h).asInstanceOf[Handler.ArrowHandler[...]]` | cast | erasure-forced: as F45 |
| F69 | Eval.scala:346 | `ArrowHandler[VX, EX, AX, Y, Any]` | carrier | moved: the base's done arm |
| F70 | Eval.scala:347 | `done.asInstanceOf[Outcome[Any, Y < Any]]` | cast | moved: the base's done arm |
| F71 | Eval.scala:347 | `Nested.unnest[Y < Any]` | carrier | moved: the base's done arm |
| F72 | Eval.scala:349 | `stack.continuation(h).asInstanceOf[Arrow[Y, Any, Any]]` | cast | erasure-forced: as F45 |
| F73 | Eval.scala:349 | `Arrow[Y, Any, Any]` | carrier | moved: the base's `next` |
| F74 | Eval.scala:383 | `parked.asInstanceOf[A < S]` | cast | moved: the base's park |
| F75 | Eval.scala:383 | `held eq null` | carrier | ruled: the user specified the two-arm list type `Release | Chunk[Release]` (2026-09-13); its empty state is `null`, the same null-slot convention Stack's handler, state and continuation arrays already use, extended to the release-list array. Not a value carrier: a nullable reference to a list, tested and either run or skipped |
| F76 | Eval.scala:412 | `resume: Arrow[Any, Any, Any]` | carrier | moved: the base's `installed` |
| F77 | Eval.scala:416 | `var ri = 0` | mutability | moved: the base's reinstall loop |
| F78 | Eval.scala:420 | `entries.state(ri).asInstanceOf[VX]` | cast | erasure-forced: as F45 |
| F79 | Eval.scala:509 | `while stack.depth - 1 > h do` | mutability | justified: a local index over the stack the evaluator owns, never escaping, absence never null; the interpreter-mutability concession the kernel sanctions for the gap's pop loop, which is new with the gap (no base loop to move from) |
| F80 | Eval.scala:540 | `var n = stack.hidden(top)` | mutability | justified: as the gap pop loop above, the unwind through a gap; local, never escaping |
| F81 | Eval.scala:542 | `while n > 0 do` | mutability | justified: as the unwind-through-gap loop above |
| F82 | Eval.scala:546 | `end while` | mutability | justified: as the unwind-through-gap loop above |
| F83 | Eval.scala:633 | `held ne null` | carrier | ruled: as F75 |
| F84 | Eval.scala:637 | `case c: Chunk[Release] @unchecked` | cast | erasure-forced: the list union's chunk arm, its element type erased |
| F85 | Eval.scala:639 | `var failed = Maybe.empty[Throwable]` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F86 | Eval.scala:640 | `var i = indexed.length - 1` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F87 | Eval.scala:641 | `while i >= 0 do` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F88 | Eval.scala:644 | `end while` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F89 | Eval.scala:650 | `held ne null` | carrier | ruled: as F75 |
| F90 | Eval.scala:655 | `case c: Chunk[Release] @unchecked` | cast | erasure-forced: as F84 |
| F91 | Eval.scala:657 | `var failed = Maybe.empty[Throwable]` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F92 | Eval.scala:658 | `var i = ...` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F93 | Eval.scala:659 | `while i >= 0 do` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F94 | Eval.scala:668 | `end while` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F95 | Eval.scala:677 | comment: "carries the ones after it as suppressed" | terminology | comment |
| F96 | Eval.scala:691 | `new KyoException("remainder discarded")` | allocation | moved: the base's `drainDiscarded` signal, allocated only when a release threw at a discard |
| F97 | Eval.scala:720 | scaladoc | terminology | comment |
| F98 | Eval.scala:737 | `input.asInstanceOf[I[Any]]` | cast | moved: the fourth walk's reporting overload (`0beb288861`, `Eval.release(v, ex, effectTag, f)`), stripped at the range base `bc6a48a2aa` and restored here; declared in `provenance.md` |
| F99 | Eval.scala:737 | `f[Any]` | carrier | moved: as the reporting cast on this line, the fourth walk's reporter |
| F100 | Eval.scala:739 | `f: Any => Unit` | carrier | moved: the fourth walk's erased reporter parameter (`0beb288861`), answering nothing; `provenance.md` |
| F101 | Eval.scala:744 | `owned(handler: Handler[?, ?, ?], state: Any)` | carrier | justified: the walk's erased currency, as the base's `collected` buffer |
| F102 | Eval.scala:746 | `case hc: Handler.ContextHandler[Any, ?, ?, ?] @unchecked` | cast | erasure-forced: as F45, the state handed back at `Any` |
| F103 | Eval.scala:746 | `ContextHandler[Any, ?, ?, ?]` | carrier | justified: as F101 |
| F104 | Eval.scala:752 | `@tailrec def collect(v: Any): Unit` | carrier | moved: the base's walk, the cont parameter gone with `Ensure` |
| F105 | Eval.scala:777 | `case kyo: Pending.SuspendArrow[...] @unchecked` | cast | moved: the fourth walk's reporting arm (`0beb288861`, the `SuspendArrow` arm of the release walk); `provenance.md` |
| F106 | Eval.scala:778 | `t <:< kyo.tag.erased` | cast | moved: the fourth walk's tag test in that arm (`0beb288861`); `provenance.md` |
| F107 | Eval.scala:783 | `var i = held.length - 1` | mutability | moved: the base's `releaseCollected` loop walked with the same two locals (`bc6a48a2aa`, `Eval.scala:733`) |
| F108 | Handler.scala:38 | scaladoc | terminology | comment |
| F109 | Handler.scala:249 | `sealed trait Hidden extends Effect` | new-type | structural: a stack entry is a `Handler` carrying a `Tag`; the gap needs an entry whose tag `find` never matches, and no existing `Effect` is raised by nothing. Governed by the new-type concession, not the cast ladder (see the note below the table) |
| F110 | Handler.scala:254 | scaladoc | mutability | comment |
| F111 | Handler.scala:262 | scaladoc | mutability | comment |
| F112 | Handler.scala:265 | `Handler[Hidden, Any, Any]` | carrier | representation: the gap dispatches no clause (`find` never matches its tag), so `Handler`'s answer and value type arguments are unreachable; `Any` is the neutral element, as the Gap type row states |
| F113 | Handler.scala:265 | `object Gap` | new-type | structural: the entry that hides a loop clause's region while its own computation runs; it replaces the base's dump of the interior, its re-push and its crossing back, and carries no state beyond the count in its entry. New-type concession, not a cast (see the note below the table) |
| F114 | Handler.scala:273 | `val answered: Arrow[Any, Outcome[Any, Any], Any]` | carrier | justified: one erased step turning a pending answer's value into the gap's continue, applied to values whose types only the clause knows |
| F115 | Handler.scala:274 | `new Arrow.Step[Any, Outcome[Any, Any], Any]` | carrier | justified: as F114 |
| F116 | Handler.scala:274 | `new Arrow.Step` | allocation | justified: allocated once, a `val` on the companion |
| F117 | Handler.scala:276 | `apply[D, S3](v: Any < S3, cont2: ...)` | carrier | justified: as F114 |
| F118 | Handler.scala:278 | `case p: Pending[Any, S3] @unchecked` | cast | representation: the canonical pending-first arm |
| F119 | Handler.scala:278 | `Pending[Any, S3]` | carrier | justified: as F114 |
| F120 | Handler.scala:279 | `Loop.continue[Any, Any, Any](v)` | carrier | justified: as F114; the continue holds the value as it is, no lift |
| F121 | Handler.scala:322 | scaladoc | mutability | comment |
| F122 | PendingInternal.scala:94 | `Effect.defer(v, kc, resume).asInstanceOf[Any < Any]` | cast | moved: the base's crossing, `defer` in place of `fused` |
| F123 | PendingInternal.scala:94 | `Any < Any` | carrier | moved: as F122 |
| F124 | PendingInternal.scala:221 | `val releases: Stack.Releases = null` | carrier | ruled: as F75, the park's root list |
| F125 | Release.scala:15 | `abstract private[kyo] class Release extends (Maybe[Throwable] => Unit)` | new-type | ruled: the release the user specified is `Maybe[Throwable] => Unit`; the abstract class over it carries `ran` and `reenter`, the exactly-once refusal on re-push the redesign settled, which a bare function cannot express. Arrow type ruled; the class is a new-type concession (see the note below the table) |
| F126 | Stack.scala:29 | `private var releaseLists = new Array[Stack.Releases](0)` | mutability | moved: the base's fourth parallel array, lists in place of owed lanes |
| F127 | Stack.scala:29 | `new Array[...](0)` | allocation | moved: as F126 |
| F128 | Stack.scala:33 | `evalReleases: Stack.Releases = null` | carrier | ruled: as F75 |
| F129 | Stack.scala:33 | `private var evalReleases` | mutability | moved: the base's `evalOwed` |
| F130 | Stack.scala:78 | `releaseLists(size) = null` | carrier | ruled: as F75; clearing the popped slot, as the base's `pop` does for the other three |
| F131 | Stack.scala:81 | scaladoc | mutability | comment |
| F132 | Stack.scala:87 | `k.asInstanceOf[Arrow[Any, Any, Any]]` | cast | erasure-forced: the continuation slot's storage type |
| F133 | Stack.scala:87 | `Arrow[Any, Any, Any]` | carrier | erasure-forced: as F132 |
| F134 | Stack.scala:90 | `states(i).asInstanceOf[Int]` | cast | erasure-forced: the gap's count lives in the state slot, typed `Any` |
| F135 | Stack.scala:95 | `case hc: Handler.ContextHandler[Any, ?, ?, ?] @unchecked` | cast | erasure-forced: as F102 |
| F136 | Stack.scala:95 | `ContextHandler[Any, ?, ?, ?]` | carrier | justified: as F101 |
| F137 | Stack.scala:106 | `list ne null` | carrier | ruled: as F75 |
| F138 | Stack.scala:110 | `list ne null` | carrier | ruled: as F75 |
| F139 | Stack.scala:117 | `held ne null`, `= null` | carrier | ruled: as F75 |
| F140 | Stack.scala:123 | `held ne null`, `= null` | carrier | ruled: as F75 |
| F141 | Stack.scala:133 | `releaseLists(i) = null` | carrier | ruled: as F75 |
| F142 | Stack.scala:138 | `evalReleases = null` | carrier | ruled: as F75 |
| F143 | Stack.scala:154 | `releaseLists(i) = null` | carrier | ruled: as F75 |
| F144 | Stack.scala:170 | `var i = size - 1` | mutability | justified: `contextual`'s downward walk, so a gap can skip what it hides; the base walked upward with the same two locals |
| F145 | Stack.scala:171 | `while i >= 0 do` | mutability | justified: as F144 |
| F146 | Stack.scala:178 | `end while` | mutability | justified: as F144 |
| F147 | Stack.scala:180 | `var j = count * 4` | mutability | justified: as F144, filling from the end to keep the order |
| F148 | Stack.scala:182 | `while i >= 0 do` | mutability | justified: as F144 |
| F149 | Stack.scala:189 | `states(i).asInstanceOf[AnyRef]` | cast | moved: the base's snapshot fill |
| F150 | Stack.scala:190 | `Arrow.id[Any]` | carrier | moved: the base's snapshot fill |
| F151 | Stack.scala:191 | `out(j + 3) = null` | carrier | ruled: as F75 |
| F152 | Stack.scala:216 | `h.tag.erased <:< tag.erased` | cast | moved: the base's `find` test |
| F153 | Stack.scala:242 | `if kept then releaseLists(j) else null` | carrier | ruled: as F75; `kept` is the escaping dump keeping the remainder's lists |
| F154 | Stack.scala:247 | `releaseLists(j) = null` | carrier | ruled: as F75 |
| F155 | Stack.scala:259 | `new Array[Stack.Releases](capacity)` | allocation | moved: the base's growth of the fourth array |
| F156 | Stack.scala:274 | `type Releases = Release \| Chunk[Release]` | new-type | ruled: the user's list shape |
| F157 | Stack.scala:278 | `list eq null` | carrier | ruled: as F75 |
| F158 | Stack.scala:279 | `more eq null` | carrier | ruled: as F75 |
| F159 | Stack.scala:283 | `case c: Chunk[Release] @unchecked` | cast | erasure-forced: as F84 |
| F160 | Stack.scala:286 | `case c: Chunk[Release] @unchecked` | cast | erasure-forced: as F84 |
| F161 | Stack.scala:313 | `entries(count + 3) = null` | carrier | ruled: as F75, the builder's empty list |
| F162 | Stack.scala:329 | `self(i * 4 + 3).asInstanceOf[Releases]` | cast | erasure-forced: the snapshot's slot re-typed at the storage boundary, as the three beside it |

No row is `REMOVE`.

The concession rows that rest on the skill's concession and new-type framework rather than on a
cast-ladder category, which `kernel-discipline` (cast-focused) flags and which are the reviewer's to
rule:

- **F109 (`Hidden`) and F113 (`Gap`)**, new types. A stack entry is a `Handler` carrying a `Tag`; the
  gap needs an entry whose tag `find` never matches, and no existing effect is raised by nothing. The
  justification is structural (an argument an existing type cannot serve), the scope is the two
  declarations, the protection is that `find` skips the gap by tag, and the pins are `StackTest`'s
  gap cases and `EvalTest`'s loop-clause leaves.
- **F125 (`Release`)**, a new type over the ruled function type `Maybe[Throwable] => Unit`, carrying
  `ran` and `reenter` for the exactly-once refusal on re-push. The arrow type is ruled; the class is
  the structural carrier of the refusal, which a bare function cannot express. Pinned by
  `BracketTest`'s replayed-scope refusal leaves.
- **F79 to F82**, the gap's pop and unwind loops, new interpreter mutability with no base loop to
  move from. Local indices over the stack the evaluator owns, never escaping, absence never `null`;
  the interpreter-mutability concession the skill sanctions for the evaluator's own loops.

Every other row takes a cast-ladder category, a measurement, a `moved` provenance (the base or the
fourth walk carried the same shape), `ruled`, or `comment`. `provenance.md` declares `0beb288861`,
the fourth walk's tip, which the reporting-overload rows name as the source they were restored from.
