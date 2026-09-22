# Live review: `handleCont` single-shot, `handleContRepeated` for replays

Branch `ci-green-followup2` against your tip `84d9274beb`. Kernel commits: `5f56e0af2a` (the change), with `f80f3c3366` (SyncTest) as
its consumer fix. Status: proposed, not opened.

## Derivation

**The defect.** `Scope.run` closes from its bracket release. Under any `handleCont` handler outside it (`Path.run`, `Abort.run`'s catching,
`Check.run`, ...), the kernel dumped the regions above the handler as held: their releases moved to the handler's entry and ran at the
handler's end. A scope opened inside `Path.run { Scope.run { Path.temp() } ; exists }` therefore closed after `exists`. The cause is that
every `ContHandler` was treated as one whose clause may resume more than once, which is what holding is for; a clause that resumes once,
which is every `handleCont` in the tree, does not need it and is wrong under it.

**The equation.** Two shapes the kernel already has:

- single-shot: the peel's ownership (`handleFirst`). The dump is escaping: releases travel in the snapshot and each region closes at its own
  end where the continuation resumes; the handler's region owes the remainder and drains it at its exit if the clause never resumed
  (settled or drained, never both). In `Eval` this is the `FirstHandler` arm's `escaping = !repeated` and `oweRemainderBelow`, moved one
  region up: the cont clause runs inside its region, so the remainder is owed to the region itself (`oweRemainders(idx, ...)`), not below it.
- repeated: the robustness branch's `handleContRepeated` (`110ad76d11`): the dump is held (unchanged arm), and the continuation is wrapped so
  each application re-enters a fresh region of the same handler with `onDone` as identity:
  `cont'(v) = Pending.handle(k(unnest(v)), reentered, ())`. Without the wrapper a re-entrant repeated clause loops without bound, because the
  continuation captured at a later occurrence carries the clause's next resumption (pinned by BracketTest "a bracket inside a re-entered
  region ...", which spun for 16 minutes before the port).

**Foreign-stack resumption (ruled).** A single-shot remainder resumed inside a nested eval, or on another thread, ends its regions there and
the owner drains it again: the owed lane lives on the resuming stack. Two kernel mechanisms were built and undone at your direction (a
mark on the snapshot: snapshots are immutable; a per-thread stack chain: more machinery than the branch ever had). The ruling is the
robustness branch's: once across stacks is the region's guard, Bracket's cell; the raw `ContextEffect.handle` release hook is once per
evaluation, stated in its doc. Only Bracket implements `release` in the tree.

**Surface.** `Handler.scala`, `Eval.scala` (cont arm, `drainRemainders`), `ArrowEffect.scala`, `ContextEffect.scala` (doc). Consumers:
`Choice.run` (repeated), `Scope.run` (release-only close), `SyncTest` (two sites). Not touched: `Stack.scala`, `FirstHandler`, `LoopHandler`,
`Isolate`, the representation.

**Fork left open (row 3 of the backlog).** Eight kernel test files apply a `handleCont` continuation twice with no guarded region; they pass
and are not converted. Convert for consistency with the scaladoc, or leave as pins that a bare double resumption still works.

## The edits, in the order they will be applied

Each with the sentence that goes with it.

1. `Handler.scala`, `ContHandler`: the scaladoc and `private[kyo] def repeated: Boolean = false`. *The evaluator has to know at the dump
   site whether to hold or to hand out, and the handler is the only thing that knows which clause it serves.*
2. `Handler.scala`, after `ContextHandler`: `reentered` and `reentering`. *The robustness branch's wrapper, verbatim in shape: a fresh region
   per application, identity `onDone`, the outer `onDone` still once at the outer region's end.*
3. `Eval.scala`, cont arm: `escaping = !repeated`, `oweRemainders(idx, Chunk(entries))` for single-shot. *The peel's ownership for a
   single-shot clause; the held shape only when the handler says it repeats.*
4. `Eval.scala`, `drainRemainders`: newest first. *Every other drain runs newest first; a lane holding two remainders released the older
   one first.*
5. `ArrowEffect.scala`, `handleCont` scaladoc. *Single-shot is now the contract; the second application is refused at the first guarded
   region it re-enters.*
6. `ArrowEffect.scala`, the three `handleContRepeated` overloads. *Mirror `handleCont`'s three; `run` wraps `next` through `reentering`,
   `repeated` is true.*
7. `ContextEffect.scala`, `handle` scaladoc: once per evaluation. *States the ruling where a handler author reads it.*
8. Tests: `BracketTest` (multi-shot sites on `handleContRepeated`, two leaves re-enabled, three single-shot order pins: body, release,
   after), `ContextEffectTest` (two sites converted; the two nested-eval leaves release through `Bracket.ensuring`), `EvalTest` (the
   stop-before-capture leaf removed by your earlier ruling).
9. Consumers, outside the kernel: `Choice.run` on `handleContRepeated`; `Scope.run` release-only with `Finalizer.init` capturing the
   crossing and `awaitIfClosed`; `SyncTest` two sites.

## Adjudication of the added lines

| id | site | added line | class | verdict |
|---|---|---|---|---|
| F1 | Handler.scala `ContHandler` | `private[kyo] def repeated: Boolean = false` | new member | justified: the dump shape is decided in the evaluator, at `stack.handler(idx)`, before the clause runs; nothing but the handler can carry it. The robustness branch had no member because its `handleCont` was held too |
| F2 | Handler.scala `reentering` | `case p: Pending[O[X0], S3] @unchecked` | typed pattern | justified: erasure-forced, the same arm `crossing` uses |
| F3 | Handler.scala `reentering` | `Nested.unnest[O[X0]](v)` | representation | justified: delivery to a suspension continuation takes the raw payload, unnest exactly once |
| F4 | Handler.scala `reentering` | `Pending.handle[Unit, E, A, A, S](..., reentered, ())` | region rebuild | moved: `110ad76d11` |
| F5 | Eval.scala cont arm | `Chunk(entries)` | allocation | one `Chunk` per single-shot foreign dump; the peel arm already pays the same on `oweRemainderBelow`; not measured, see evidence owed |
| F6 | Eval.scala cont arm | `escaping = !repeated` | flag | justified: the one bit that selects between the two ownership shapes; both shapes pre-exist in `Stack.dump` |
| F7 | ArrowEffect.scala | `val reentered = Handler.reentered(this)` | allocation | one per repeated region, at region entry, not per resumption |
| F8 | ArrowEffect.scala | `Region.discharge(handle[X](input, Handler.reentering[...](next, reentered)))` | allocation | one wrapper per answered occurrence, one region per application; repeated handlers only |

No cast added. No `AnyRef`. No new node kind. No new type: `reentered` is a `ContHandler`, `reentering` an `Arrow.Step`.

## Evidence

- `kyo-kernelJVM/test`: 1819 passed, 0 failed (5 cancelled are `DebuggerTest` needing a system property).
- `ChoiceTest` 34/0 (incl. the bracket-around-the-choice-point pin: branch1, after1, branch2, after2, release); `ScopeTest` 88/0 with the
  three replay pins live; `ScopeInterruptTest` 15; `FiberTest` 122; `HubTest` 40; `PathTest` 210; `StreamSystemExtensionsTest` 17.
- kyo-core, kyo-prelude, kyo-system, kyo-combinators, kyo-jsonrpc JVM: 126 suites, 3763 passed, 0 failed. `SyncTest` 47/0 after its two
  conversions (the only `handleCont` users outside the kernel).
- After the merge of main: `checkClassNames JVM` no duplicates; the four kyo-test suites 168/84/142/73 passed.
- Whole tree JVM and CI run 35733969856 (four poles, four targets): in flight at the time of writing.

## Evidence owed before the review opens

- `KernelBench` rows that reach the cont arm, both variants, same session (`-f 1` screen, `-f 3` on any row outside drift): the
  `handleCont` rows and the two multi-shot rows from `a9d7ad0795`. F5 and F7/F8 are unmeasured until then.
- The three lenses (`kernel-conformance`, `kernel-discipline`, `kernel-rehearsal`) and `kernel-pulse` on this package, since the
  evaluator is touched.
