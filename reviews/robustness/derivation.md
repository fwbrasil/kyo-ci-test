# Derivation: kernel robustness

Base `cdefdc9e60`. Branch `robustness`. The list this implements is `.dev/kernel-robustness.md`
(gitignored, on disk in the primary worktree); its items are restated here where they change code.

Two bugs in one day shared a shape the kernel suite never built: a clause that suspends, an effect
answered by a region other than the clause's own, at-top versus not-at-top. Each was invisible to
1513 kernel tests and surfaced in a consumer. This change makes that shape a first-class thing the
suite builds, and makes the one rule those bugs circled live in one place.

## The four pieces

### A. Downstream suites in the verification rule (CONTRIBUTING, not kernel source)

`kyo-kernel/CONTRIBUTING.md`, checklist item 13, gains one sentence: a change to the evaluator, the
handlers or the representation is not verified by the kernel suite alone; `kyo-preludeJVM/test` and
`kyo-coreJVM/test` run before it is called green, with `Batch.run` as the example of a consumer
composing the combinators in a shape the kernel suite does not. The kernel skill would be the other
home, but it is not tracked in this tree.

### B. The shape matrix, with the at-top law as its oracle (test only)

New file `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalShapeTest.scala`, the prefix
being the source it exercises.

The oracle problem: a hundred generated cells need a hundred expected values, and deriving each by
hand reintroduces the judgment that missed both bugs. So the expected value is derived once per
scenario, for its simplest configuration, and every other configuration is asserted equal to it by a
law the kernel already states:

- **at-top law** (skill: "a fast path must be observationally equivalent to the law it specialises"):
  a scenario run with the handler at the top of the stack equals the same scenario with an inert
  region pushed above the handler. An inert region is a `ContextEffect` binding for a tag the
  scenario never reads, and separately a `handleCont` region for an arrow effect the scenario never
  performs, so both region kinds are exercised as the interloper.
- **fusion law**: one occurrence answered equals three consecutive occurrences answered with the
  same clause, each answer independent. This is what puts the fused walks (`answersLoop`,
  `answersLoopState`) on the same footing as the unfused `answers`.
- **suspension law**: a clause that answers immediately equals the same clause that first performs an
  effect handled outside the region and then answers identically. This is the first bug's cell.

Scenario axes, generated:

    handler   in {handleCont, handleContRepeated, handleLoop, handleLoopState, Mask}
    outcome   in {continue, done-from-clause, done-from-body}   (where the handler has the arm)
    resume    in {once, twice, never}                           (handleCont and Repeated only)

Every cell asserts one concrete value, the hand-derived one for the base configuration, and the
laws multiply it across configurations rather than asking for new derivations.

Beyond the laws, the cells whose expected values are genuinely different are kept as hand-derived
geography and named for what they pin: an interior region with the *same* tag as the handler (the
`EvalTest` inner/outer cases, which the crossing rewrite tripped), and a peeled continuation resumed
with a computation, typed over the computation and typed over `Any` (the two cases from
`12074d8523`). Those are not generated; they are the reason the generator has the axes it has.

### C. One re-entry path (kernel source)

The rule "a loop clause's outcome re-enters its region through the region's continuation `k`" is
one function, currently spelled four times in `Handler.scala`:

    reenter(k, outcome) =
      outcome settled, Continue(ans), ans settled  ->  Loop.continue(k(ans))
      outcome settled, Continue(ans), ans pending  ->  Loop.continue(ans.map(k))
      outcome pending                               ->  attachReentry(k)(outcome)
      otherwise                                     ->  outcome

Each piece already exists: `k(ans)` is `Arrow.apply`, `ans.map(k)` is the pending combinator,
`attachReentry` is the deferred form of the first two, and pass-through is the identity. Nothing new
is introduced; the four spellings become four calls to one `private[kyo] inline def reenter` on
`Handler`, so the decision lives once and a wrong arm is wrong in one place.

`inline` because the fused templates are inlined at every `handleLoop`/`handleLoopState` site and
their bytecode size is a design property (the 68-to-25-byte history in the skill). The expansion must
be the same shape it is today; the benchmark comparison is what proves that rather than the argument.

Surface, exactly:

- `Handler.LoopHandler.answers`: the `run(input) match` arms become `reenter(k, run(input))`.
- `Handler.LoopStateHandler.answers`: likewise with `reenter2`, the state-carrying twin.
- `Handler.answersLoop`: the `case o => if o.isInstanceOf[Pending] ... attachReentry ...` block.
- `Handler.answersLoopState`: likewise with `attachReentry2`.

Must not change: `attachReentry` and `attachReentry2` themselves (they become callees, not callers),
`clauseDispatch`, `Suspend.crossing` (its contract was just written on it and is what B pins), the
two `Eval` not-at-top arms (they already make a single direct `attachReentry` call on a value known
to be pending; folding them in would be a change to `Eval` with no duplication to remove), every node
class, and the public surface.

### D. A reference interpreter (test only, time-boxed)

A direct-style evaluator over the node types with no fusion, no stack machine and no safepoint,
under `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/`, and a differential run of the
matrix's scenarios through both. Its value is catching shapes nobody enumerated; its risk is being a
second interpreter with its own bugs. The decision rule: it ships only if it passes B's matrix
independently, on the strength of its own reading of the combinators. If it does not, it is written
up as a design note with what diverged, and B stands as the robustness measure.

## What is not in this change

- The `Arrow.apply(v: A)` / `apply[S2](v: A < S2)` overload pair. At an erased input the static
  argument type picks the semantics; `Batch` fell into it and two `PollTest` sites needed an
  ascription. Both are consequences of the pair. Whether the pair stays is the user's, and the
  ascriptions are a workaround by the standing ruling of 2026-08-28 ("You cant workaround real
  issues, even if they're inference issues"). Declared, not touched: see forks.
- `Eval`'s at-top/not-at-top split itself. B asserts the two paths agree; C does not merge them.

## Forks

1. **The overload pair.** Keep both `apply`s, or collapse to one and give the as-data delivery an
   explicit public spelling. Evidence for the ruling: the `Batch` regression, the two `PollTest`
   ascriptions at value-typed sites, and the contract cases in `ArrowEffectTest` showing the two
   readings. Recommendation withheld until the matrix in B is in hand, since it will say how many
   scenarios the pair's choice actually reaches.
2. **The `PollTest` ascriptions** (`4c64b3e6fb`) are workarounds under the 2026-08-28 ruling. They
   are left in place in this change because their root cause is fork 1, and the fix belongs with
   that ruling rather than as a third spelling.
3. **Whether D ships.** Decided by the rule in D, and reported either way.

## Evidence the package will carry

- clean batch build of `kyo-kernelJVM`
- kernel suite, kyo-prelude suite, kyo-core suite, on the tip, per A
- `KernelBench` in full on base and tip, back to back, `-f 1`, then `-f 3` on any row outside the
  drift band; the rows C reaches by name before running: `handleLoopAnswersInPlace`,
  `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, and every fusion row
- `package-check.sh` confirming each benchmark class named references `kyo.kernel`
- the flags table, every row adjudicated
- the three lenses' reports, dispatched as general-purpose agents with the sub-skill briefs inline,
  since the sub-skills are not registered in this tree
- the CI matrix on `fwbrasil/kyo-ci-test` for the branch, all platforms and OSes
