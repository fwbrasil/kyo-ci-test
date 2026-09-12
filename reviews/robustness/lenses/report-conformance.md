# Report: kernel-conformance, round 3

FAIL

Range `cdefdc9e60..HEAD`, judged against `reviews/robustness/derivation.md` as it stands at the tip.

Substitution and forks are clean. Every piece the derivation names is present, and each one composes the
values the derivation states it composes rather than new ones. The four re-entry tails of piece C become
the two named helpers and nothing else moves with them; the multi-shot fix is the handler-side Candidate A
exactly as written, wrapping the continuation in `run` through `Handler.reentered` and `Handler.reentering`,
with `Eval` and `ContHandler` untouched; the matrix of piece B derives every cell from the three laws with
no hand-written value of `prog(n)`; pieces A, E and F are the edits their sentences describe. Every "must
not change" member is unchanged. No fork has the losing side half-present: `Arrow.apply`'s overload pair is
untouched (fork 1), the `PollTest` ascriptions are untouched (fork 2), no reference interpreter is in the
diff (fork 3, piece D), Candidate B's signature change is absent (fork 4), and the abandoned first two
drafts of the fix left no residue (`Eval.scala` is not in the range, there is no `resumed` member on
`ContHandler`, and no `repeated`/`escaping` gate was added to the evaluator's cont arm).

The verdict is FAIL on the surface axis alone. Three code additions in the range have no declaring sentence
anywhere in the derivation, and one added assertion contradicts the derivation sentence that describes the
edit it sits in. Nothing declared is missing.

## Findings

### C11. A third `KernelBench` row, `repeatedRegionsPayEntryRecovering`, is undeclared

`kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala:580-589`.

The derivation says, under "What A costs, measured, and piece F":

> "Two rows added to `KernelBench` enter a `handleContRepeated` region, which no row did before:
> `repeatedRegionsPayEntry` (a region per operation) and `repeatedClausesPayReentry` (`suspensionBaseline`'s
> program under a repeated handler, ten thousand operations each resumed once)."

Three rows were added, not two: `repeatedClausesPayReentry` at 564, `repeatedRegionsPayEntry` at 573, and
`repeatedRegionsPayEntryRecovering` at 582, the third running the same shape through the recovering
overload. A reviewer reading the derivation's cost section and then the benchmark class finds a measured
row that the design never asked for and whose numbers the derivation's cost paragraph does not account for.

### C12. `SpanBench.scala` is a new file with no sentence declaring it

`kyo-bench/src/main/scala/kyo/bench/SpanBench.scala:1-18`, added whole in the range.

The derivation closes its out-of-kernel section with:

> "Neither is a kernel piece; they are the last two edits of the live-review walk, in their own group, so
> the range's surface is fully declared and fully applied."

The Span group is declared as one edit, the index check in `Span.updated`. A second file joins the range
for it, a benchmark class, and the derivation names no benchmark for the Span piece anywhere; the string
`SpanBench` does not occur in it. A reviewer would also read the new class's own scaladoc, "the index check
that raises the documented exception on every platform", against its body, which walks `i` over `0` to `15`
on a sixteen-element span and so never reaches the throw the sentence points at.

### C13. The `handleContRepeated` block in `ArrowEffectTest` has five cases where four are declared

`kyo-kernel/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:177-230`, the case at 223.

The derivation declares this block in two places. Fork 4:

> "Three named cases in `ArrowEffectTest` pin the fix, one of them pinning `done` once at the outer end
> against per-resumption (1060, not 4060), and the matrix runs the shape across every configuration."

and Candidate A:

> "a case in `ArrowEffectTest` pins that a throw after a second resumption reaches the outer `recover`."

The block holds five: the three that pin the fix (181, 191, 201), the recover case (211), and
"deep sequential operations are stack safe" (223), which pins a property the derivation never claims for
the re-entry and which its cost paragraph does not mention. The case follows the file's existing
per-handler convention, the same name appearing at 975, 1263, 2155 and 2352 for other handler kinds, so
its merit is not what is at issue; a reviewer holding the derivation still counts one case more than the
design authorises.

### C14. The kyo-net leaf now pins the arming order the derivation says it does not

`kyo-net/jvm-native/src/test/scala/kyo/net/internal/posix/RearmSurvivorsTest.scala:76-80`.

The derivation says:

> "The leaf now arms write first, which orders the two registrations; nothing it pins depends on the order."

The edit does more than reorder the two `await` calls. It adds an assertion that the `registerWrite` entry
precedes the `registerRead` entry in the driver's call log, which makes the arming order a property the
leaf now pins, and which is the one thing the declaring sentence says the leaf does not do. A reviewer
comparing the two sees a test that fails if the driver ever applies registrations out of arming order, a
contract the derivation does not claim the leaf is there to hold.

### C15. `KernelBench` calls the re-entered handler a "twin"

`kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala:580`, the scaladoc reading
"through the recovering overload, whose handler and twin are their own classes".

The derivation names this thing once and consistently, in Candidate A:

> "`reentered` is the same handler with `done` as identity, built once with the handler so the re-entered
> region yields the body's `A` rather than applying `done` a second time"

The word "twin" appears nowhere in the derivation and nowhere in the kernel sources; the member is
`Handler.reentered` and the derivation and the kernel scaladoc both say "the re-entered handler". This is
the last occurrence of the older name in the range's own added text. A reviewer looking up "twin" against
the design finds no such concept.

## The surface enumerated

Thirteen non-package files change in the range. The package under `reviews/robustness/` is excluded as the
review's own artefacts.

**Piece A, declared.**
- `kyo-kernel/CONTRIBUTING.md:441`, checklist item 13, gains exactly the two sentences the derivation
  describes: downstream `kyo-preludeJVM/test` and `kyo-coreJVM/test` with `Batch.run` as the example, and
  the benchmark sources compiled by CI's compile-test phase with `kyo-kernelJVM/Jmh/compile` before a push.
  No other item changes.
- `project/TestKyo.scala:348` and `:364-379`: `tasks` now appends `jmhCompileTasks(state, a.phase, modules)`,
  and the new private `jmhCompileTasks` returns nothing outside the `compile-test` phase and otherwise finds
  each selected module's project ref and emits `<name>/Jmh/compile` when its `ivyConfigurations` carry one
  named `jmh`. Found from the build rather than listed, as declared.

**Piece B, declared.**
- `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalShapeTest.scala`, new, 231 lines, package
  `kyo.kernel.internal`, extending `kyo.Test`. Ten scenarios, matching the derivation's enumeration one for
  one: `handleCont` resuming once and never, `handleContRepeated` resuming once, twice and never,
  `handleLoop` continuing and ending from the clause, `handleLoopState` threading a counter and ending from
  the clause, and one `Mask` tunnelling past an inner handler. Laws `law`, `lawState` and `runs`, and
  helpers `prog`, `record`, `cfgAbove` (a `ContextEffect` binding never read), `idleAbove` (a `handleCont`
  region never performed) and `innerAbove` (a handler for the same tag). Four values of n, zero to three,
  under eight configurations: base, a binding above, a region above, the three suspending variants, and the
  two inner-handler cells, so 320 cells. Every `expected` and `inner` field is a law application; no value
  of `prog(n)` is written by hand.

**Piece C, declared, four sites plus two helpers.**
- `Handler.scala:203`, `LoopHandler.answers`, `case o` tail is now
  `attachReentryToPending[I, O, E, A, B, S, X](k, o)`.
- `Handler.scala:274`, `LoopStateHandler.answers`, `case o2` tail is now
  `attachReentryToPending2[State, I, O, E, A, B, S, X](k, o2)`.
- `Handler.scala:491`, `answersLoop`, `result = attachReentryToPending[...](k.asInstanceOf[...], o)`.
- `Handler.scala:569`, `answersLoopState`, `result = attachReentryToPending2[...](k.asInstanceOf[...], o2)`.
- `Handler.scala:394` and `:421`, new `private[kyo] inline def attachReentryToPending` and
  `attachReentryToPending2`, each the `isInstanceOf[Pending]` test with `attachReentry`/`attachReentry2` on
  one arm and the pass-through cast on the other. `inline`, as declared.
- The two unfused `Continue` arms at `Handler.scala:195-202` and `:265-273` are unchanged, which the
  derivation requires after correcting its earlier draft.

**The multi-shot fix, Candidate A, declared.**
- `ArrowEffect.scala:209-211` and `:264-266`, the two `handleContRepeated` overloads only. Each anonymous
  `ContHandler` gains `val reentered = Handler.reentered(this)`, built once with the handler, and `run`
  passes `Handler.reentering[I, O, E, A, S & S2, X](next, reentered)` to `handle` in place of `next`.
  Signatures, `done`, `recover` and `repeated` are unchanged. `handleCont` at `:165` and the other
  `ContHandler` sites at `:319`, `:693`, `:921` and `handleFirstRepeated` at `:951-988` are untouched, so
  the holding handler is untouched by construction rather than by a condition, as declared.
- `Handler.scala:330-345`, new `private[kyo] def reentered(outer)`: `tag` and `run` delegate to `outer`,
  `done` is identity, `repeated` is true. It carries no `recover`, as the derivation requires of the
  recovering overload's re-entered handler.
- `Handler.scala:348-362`, new `private[kyo] def reentering(k, reentered)`: an `Arrow.Step` whose two
  argument `apply` defers a pending input through `Effect.defer(p, this, cont2)`, the same arm
  `Arrow.apply` uses at `Arrow.scala:171-172`, and on a settled input applies
  `Pending.handle[Unit, E, A, A, S](k(Nested.unnest(v)), reentered, ())`, which is the derivation's
  `k(x) = Pending.handle(bodyRest(x), reentered, ())`.
- `kyo-kernel/shared/src/test/scala/kyo/kernel/BracketTest.scala:1202-1224`, one case, the release inside a
  re-entered region landing where that region ends and before the next outer resumption, which is the case
  the derivation says pins the held debt.
- `kyo-kernel/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:177-230`, a new
  `"handleContRepeated"` block. Declared: the value case at 181 (60), the `done`-once case at 191 (1060,
  not 4060), the three-occurrence case at 201 (180), the recover case at 211. Undeclared: the case at 223,
  finding C13.

**Piece E, declared.**
- `KernelBench.scala:468-470` and `:481`, four `ContextEffect.handle(Tag[X])(...)` calls become
  `ContextEffect.handle(Tag[X], ...)`. Exactly four, and no other call in the file changes.

**Piece F, declared.**
- `kyo-prelude/shared/src/main/scala/kyo/Choice.scala:99`, `Choice.run`'s clause becomes
  `Kyo.foreach(Chunk.from(input))(v => cont(v)).map(_.flattenChunk)`, one flatten and no inner `run`.
  `run` is the only member that changes; `runStream` at `:113-133` keeps its `handleFirstRepeated` shape.
- `kyo-bench/src/main/scala/kyo/bench/ChoiceBench.scala`, new, 27 lines, the two rows `run` and
  `runStream` over ten sequential binary choice points.

**Outside the kernel, declared.**
- `kyo-data/shared/src/main/scala/kyo/Span.scala:981-982`, `Span.updated` gains the index check throwing
  `IndexOutOfBoundsException`. The message is byte for byte the one `Chunk.scala:174` uses, which is the
  derivation's "follows `Chunk`'s shape and message". The scaladoc's `@throws` at `:977-978` already
  promised it.
- `kyo-net/jvm-native/src/test/scala/kyo/net/internal/posix/RearmSurvivorsTest.scala:46-54`, the write
  registration is armed before the read. Also `:76-80`, an added order assertion, finding C14.

**Undeclared additions.**
- `KernelBench.scala:580-589`, `repeatedRegionsPayEntryRecovering`, finding C11.
- `kyo-bench/src/main/scala/kyo/bench/SpanBench.scala`, finding C12.
- `ArrowEffectTest.scala:223-229`, finding C13.

**Confirmed unchanged, against the derivation's "must not change" list.**
- `Handler.attachReentry` at `:369` and `attachReentry2` at `:402`, bodies untouched, now callees only.
- `clauseDispatch` on both loop handlers, `Handler.scala:158-175` and `:235-252`.
- `Eval.scala`, not in the range at all, so `Suspend.crossing`, the two not-at-top arms and the held and
  escaping machinery are untouched.
- Every node class, and every public signature in `ArrowEffect`. The only `ArrowEffect` edits are inside
  the two anonymous handler bodies.
- `ArrowEffectBytecodeTest` and `PendingBytecodeTest`, not in the range, so no pinned number was rewritten.
- `Arrow.scala`, not in the range, so the `apply` overload pair of fork 1 is untouched, and `PollTest`,
  not in the range, so the fork 2 ascriptions stand.
