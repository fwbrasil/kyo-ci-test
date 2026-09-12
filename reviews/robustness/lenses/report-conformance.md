# Lens report: kernel-conformance

FAIL

Range `cdefdc9e60..94bd4447e9`. Read: `reviews/robustness/derivation.md` as it stands on disk (it
carries one uncommitted edit against HEAD, to the Span paragraph only), the diff over the six
declared kernel paths, and the touched kernel sources at the tip.

## Findings

### C1. A fourth code edit, in benchmark source, that no piece declares

`kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala:468`, `:469`, `:470`, `:481`.

Three `ContextEffect.handle` call sites change from two parameter groups to one, for example from
`ContextEffect.handle(Tag[Cfg3])(1, x => x, x => x, (p, _, _) => p)` to
`ContextEffect.handle(Tag[Cfg3], 1, x => x, x => x, (p, _, _) => p)`.

The derivation names the benchmarks twice, both times under "Evidence the package will carry":
"`KernelBench` in full on base and tip, back to back, `-f 1`" and "`package-check.sh` confirming each
benchmark class named references `kyo.kernel`". Neither is a licence to edit benchmark source, and
none of A, B, C, D or Candidate A lists this file. `ContextEffect.scala` is unchanged across the
range, so the call shape being repaired was already wrong at the base commit.

A reviewer would see a fourth edited source file in a change whose surface section says "Surface,
exactly" and then lists four `Handler.scala` members.

### C2. Checklist item 13 gains two sentences where one was declared

`kyo-kernel/CONTRIBUTING.md:441`.

Derivation A: "`kyo-kernel/CONTRIBUTING.md`, checklist item 13, gains one sentence: a change to the
evaluator, the handlers or the representation is not verified by the kernel suite alone".

The item gains that sentence and a second one: "A change to a public signature also runs
`kyo-kernelJVM/Jmh/compile`: the benchmark sources under `jvm/src/jmh` are compiled by neither `test`
nor CI's test action, and a signature they call can move without anything noticing until the next
measurement fails to build."

That is a separate rule, about benchmark compilation rather than about downstream suites, and it is
the rule that would have caught C1. A reviewer would see a contributor obligation added that the
derivation does not propose.

### C3. The fusion law is not in the code; hand-derived closed forms took its place

`kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalShapeTest.scala:56`, `:58`, `:66`, `:73`,
`:87`, `:90`, `:96`, `:103`, `:120`, `:130`, and the loop at `:137`.

Derivation B: "**fusion law**: one occurrence answered equals three consecutive occurrences answered
with the same clause, each answer independent. This is what puts the fused walks (`answersLoop`,
`answersLoopState`) on the same footing as the unfused `answers`."

No law of that form is in the file. The occurrence count is an axis `List(0, 1, 2, 3)`, and every
cell asserts `s.expected(n)` and `s.says(n)`, which are hand-written closed forms supplied per
scenario: `n => if n == 0 then 0 else n * (1 << (n - 1)) * 15`, `says = n => (1 << n) - 1`,
`n => (0 until n).map(i => 107 + i).sum * 1000 + (100 + n)`, `says = n => math.min(n, 1)`. Nothing
asserts that the three-occurrence result follows from the one-occurrence result.

This contradicts the oracle argument the same section opens with: "a hundred generated cells need a
hundred expected values, and deriving each by hand reintroduces the judgment that missed both bugs.
So the expected value is derived once per scenario, for its simplest configuration, and every other
configuration is asserted equal to it by a law the kernel already states". Along the occurrence axis
the code does the opposite. A reviewer would see the judgment the oracle was built to remove
re-entered as a formula, once per scenario, for both the value and the side-effect count.

### C4. The axes are a hand-written list, not a cross product

`EvalShapeTest.scala:61` through `:133`.

Derivation B: "Scenario axes, generated: handler in {handleCont, handleContRepeated, handleLoop,
handleLoopState, Mask}; outcome in {continue, done-from-clause, done-from-body} (where the handler
has the arm); resume in {once, twice, never} (handleCont and Repeated only)."

`scenarios` is a literal `List` of seven `Scenario` values. All five handlers appear, but the other
two axes are not crossed with them. `handleLoopState` appears only in the continue outcome
(`:107`), with no done-from-clause cell, though `handleLoop` at `:100` shows the handler family has
that arm. `handleContRepeated` appears only at resume twice (`:78`), with no once and no never cell,
so the resume axis is spread across two different handlers rather than crossed against either.

A reviewer would see the word "generated" describing seven values typed out by hand, and would ask
which cells of the declared matrix were never built.

### C5. One of the two named geography items is missing from the file that declares both

`EvalShapeTest.scala`, whole file.

Derivation B: "Beyond the laws, the cells whose expected values are genuinely different are kept as
hand-derived geography and named for what they pin: an interior region with the *same* tag as the
handler (the `EvalTest` inner/outer cases, which the crossing rewrite tripped), and a peeled
continuation resumed with a computation, typed over the computation and typed over `Any` (the two
cases from `12074d8523`). Those are not generated; they are the reason the generator has the axes it
has."

The first item is present: `innerAbove` at `:41` and the two cells at `:167` and `:171`. The second
is absent. The file has no `handleFirst`, no `handleFirstRepeated` and no peel scenario at all, so
the axes the derivation says those cases motivate never meet them. The two cases live in
`kyo-kernel/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala`, added by `12074d8523`, which is
an ancestor of the base commit, and the range's only edit to that file is the new
`handleContRepeated` block at `:177` through `:210`.

A reviewer would see a declared component of B missing, and would note that the peel shape is the one
the CONTRIBUTING sentence added in A names as the consumer shape the kernel suite does not build.

### C6. The recovering twin drops `recover` as well as `done`

`kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:273` through `:279`, against the
outer handler's `:272`.

Derivation, Candidate A: "`resumed` is the same handler with `done` as identity, built once per
region so the re-entered region yields the body's `A` rather than applying `done` a second time".

The twin built inside the recovering `handleContRepeated` overload differs from its outer in two
members, not one. `done` becomes identity, as declared, and the outer's
`override def recover(state: Unit, ex: Throwable) = onRecover(ex)` at `:272` is not carried over, so
a re-entered region has no recover arm. The other two twins are faithful by comparison: the twin at
`:212` matches an outer that sets nothing but `repeated`, and the twin at `:989` carries both
`escaping` and `repeated` from its outer at `:987` and `:988`.

Nothing in the derivation and no comment at the site says recover is meant to be dropped, or what a
throwable raised inside a re-entered region is then answered by. A reviewer would see a second
semantic change riding inside a member declared as a one-member change.

### C7. Two members are added to `ContHandler` where one is declared

`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Handler.scala:93` and `:103`.

Derivation, Candidate A: "Every piece exists: `Pending.handle`, `Arrow.Step`, the `repeated` flag;
`resumed` is a member added to `ContHandler`."

`resumed` is added at `:93`. So is `private[kyo] def reentering[V](k: Arrow[O[V], A, E & S])` at
`:103`, which is where the declared equation `k(x) = Pending.handle(bodyRest(x), handler.resumed, ())`
is actually composed, and which carries its own deferral arm for a pending argument at `:108` and its
own `Nested.unnest` concession at `:109`. The derivation's equation places the wrapping at the
continuation's construction site in `Eval`; the code places it on the handler and has `Eval` call it.

A reviewer would see the change's load-bearing new member named nowhere in the derivation, and would
have to take the pending-argument arm and the unnest on the code's own word.

### C8. The single-shot path is edited in a change whose text says it is untouched

`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:113` through `:118`.

Derivation, Candidate A: "Confined to `repeated` handlers, so the single-shot `handleCont` hot path
is untouched." And: "Cost: one arrow per repeated suspension and one node per resumption, on
repeated handlers only."

The `ContHandler` arm, which every `handleCont`, `handleContRepeated`, `handleFirst` and
`handleFirstRepeated` suspension passes through, now reads
`val continuation = if handler.repeated then handler.reentering(raw) else raw`. The single-shot path
pays a virtual call to `repeated` and a branch at every suspension. That cost is not in the declared
cost line, and the line above it says the path is untouched.

A reviewer would see the hot path changed and the derivation asserting it was not.

## The surface I enumerated

Every file and member the range changes, across all paths, with the declaration each one traces to.

### Declared and present

| Location | Change | Declared by |
|---|---|---|
| `Handler.scala:228` | `LoopHandler.answers`, `case o =>` tail becomes `attachReentryUnlessSettled[I, O, E, A, B, S, X](k, o)` | C, surface bullet 1 |
| `Handler.scala:299` | `LoopStateHandler.answers`, `case o2 =>` tail becomes `attachReentryUnlessSettled2[...](k, o2)` | C, surface bullet 2 |
| `Handler.scala:478` | `answersLoop`, `case o =>` tail becomes the same helper | C, surface bullet 3 |
| `Handler.scala:556` | `answersLoopState`, `case o2 =>` tail becomes the state helper | C, surface bullet 4 |
| `Handler.scala:381` | new `private[kyo] inline def attachReentryUnlessSettled`, placed after `attachReentry` at `:356` | C, "becomes one `private[kyo] inline def attachReentryUnlessSettled`" |
| `Handler.scala:408` | new `private[kyo] inline def attachReentryUnlessSettled2`, placed after `attachReentry2` at `:389` | C, same sentence |
| `Handler.scala:93` | new `ContHandler.resumed`, defaulting to `bug` | Candidate A, "`resumed` is a member added to `ContHandler`" |
| `ArrowEffect.scala:206`, `:212-218` | `handleContRepeated` without recover: `outer =>` self alias, `override val resumed` twin | Candidate A |
| `ArrowEffect.scala:266`, `:273-279` | `handleContRepeated` with recover: same, see C6 | Candidate A |
| `ArrowEffect.scala:977`, `:989-996` | `handleFirstRepeated`: same | Candidate A, "`handleFirstRepeated` is also `repeated` and gets the same treatment" |
| `Eval.scala:113-118` | `continuation` renamed `raw`, new `continuation` wraps when `handler.repeated`, see C8 | Candidate A |
| `Handler.scala:7` | `import kyo.bug` added, for the `resumed` default | implied by `resumed` |
| `ArrowEffectTest.scala:177-210` | new `"handleContRepeated"` block, three cases, asserting 60, 1060 and 180 | Fork 4, "Three named cases in `ArrowEffectTest` pin the fix, one of them pinning `done` once at the outer end against per-resumption (1060, not 4060)" |
| `EvalShapeTest.scala` | new file, 178 lines, package `kyo.kernel.internal` | B, "New file `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalShapeTest.scala`" |
| `CONTRIBUTING.md:441` | first added sentence, downstream suites | A |
| `Span.scala` | `Span.updated` index check | "Also on the branch, outside the kernel" |

### Present and not declared

| Location | Change | Finding |
|---|---|---|
| `KernelBench.scala:468`, `:469`, `:470`, `:481` | three `ContextEffect.handle` call sites reshaped | C1 |
| `CONTRIBUTING.md:441` | second added sentence, `Jmh/compile` | C2 |
| `Handler.scala:103` | new `ContHandler.reentering` | C7 |

### Declared and not present

| Declaration | Finding |
|---|---|
| B's fusion law | C3 |
| B's generated outcome and resume axes | C4 |
| B's peeled-continuation geography cells | C5 |

### "Must not change", checked

C's list holds. `attachReentry` at `Handler.scala:356` and `attachReentry2` at `:389` are unchanged
and are now callees only. `clauseDispatch` is unchanged. `Suspend.crossing` at
`PendingInternal.scala:83` is unchanged, and `PendingInternal.scala` is not in the range's diff at
all. The two `Eval` not-at-top arms that call `attachReentry` directly are at `Eval.scala:187` and
`:255`, both unchanged; the edited hunk at `:113` is the `ContHandler` arm, a different site. No node
class changed. No public signature changed: the diff of `ArrowEffect.scala` touches no `def` or
`inline def` declaration line, and the three added `resumed` members sit inside anonymous instances
of `Handler.ContHandler`, which is nested in the `private[kernel] object Handler` whose enclosing
`Handler` class is `sealed abstract private[kernel]`.

### Forks, checked

Fork 1, the `Arrow.apply` overload pair. Not touched. `kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala`
is unchanged across the range. Matches "Declared, not touched".

Fork 2, the `PollTest` ascriptions. Left in place. `kyo-prelude` and `kyo-core` have no diff in the
range at all.

Fork 3, whether D ships. Ruled not attempted, and no partial interpreter is present. The only file
added under `kyo-kernel` in the whole range is `EvalShapeTest.scala`, and it contains no evaluator:
it builds computations out of the public `ArrowEffect` and `ContextEffect` combinators and calls
`.eval`. No differential run against a second interpreter exists.

Fork 4, A over B. A is implemented and only A. `handleContRepeated`'s signature is unchanged, its
clause still returns `A < (E & S & S2 & Region.NoEscape)` rather than the region's output, and the
test at `ArrowEffectTest.scala:186` pins `done` once at the outer end with 1060 against the 4060 that
per-resumption `done` would give. No half of B is present.

### Coverage note on `resumed`'s `bug` default

`Handler.scala:93` defaults `resumed` to `bug(...)`, reachable only from `reentering`, which
`Eval.scala:118` calls only when `handler.repeated`. Every construction of a `ContHandler` in the
repository is in `ArrowEffect.scala`, at `:165`, `:205`, `:265`, `:329`, `:703`, `:931` and `:976`
plus the three twins, and exactly the three that set `repeated = true` override `resumed`. The
default is unreachable today. This is not a finding, it is the check behind the claim that C6 is the
only twin divergence.
