# Derivation: releases in stack entries, the bracket as one region, the gap

What items 2 to 5 of the queue become in code, derived from the rules the design discussion settled
and from the mechanism as it stands (owed lanes, borrow/defers/discharge, Ensure/Fused, the reentry
arrows). Each section names the equation, the value the kernel already has for each piece, and the
surface.

## The rules

1. Every stack entry holds a release list: `null`, one `Release`, or a `Chunk[Release]`. A `Release`
   is a `Maybe[Throwable] => Unit` that remembers whether it ran. The root of the evaluation holds one
   more list, `evalReleases`, for what is forwarded past the outermost entry.
2. Popping an entry runs its list, last added first. `Absent` says the entry ended normally; `Present(ex)`
   says it was unwound by `ex` or abandoned with `ex`. A release maps what it is told onto its own extent:
   the bracket's cell answers `Absent` if its extent ended, else what it was told, or its own discard
   signal when told `Absent` by an owner that ended without the extent ever ending.
3. A region with a release adds it to its own entry at push. A binding region has none.
4. A dump into a continuation-taking handler (`handleCont`, masking) moves the dumped entries' lists into
   the handler's entry; the dumped entries carry empty lists. Reinstalling them, however many times, runs
   nothing at their pops: what was moved is not there. The handler's pop runs what it holds.
5. An escaping handler forwards its list to the entry below at a normal pop; at depth 0 to `evalReleases`.
   An unwind runs it.
6. A park carries every entry's list; reinstalling brings them back. Reinstalling an entry whose own release
   ran is refused, by the release, with its own explanation.
7. Abandonment (`Eval.release`) runs every list a remainder holds, innermost first, with the signal, and
   reports the first operation the remainder stands at. A region node not yet entered owns nothing.
8. An in-place handler (`handleLoop`, `handleLoopState`, the boundary) never dumps. A settled answer flows
   into the body under the regions above it. A pending answer is evaluated there too. A clause that
   suspends runs its own computation under a gap entry that hides the handler and the regions above it
   from dispatch; the outcome arriving at the gap lifts it (continue) or discards through it (done).

## The pieces

| piece | value the kernel has | change |
|---|---|---|
| the list | `Stack.owed` lanes of snapshots | `releases: Array[AnyRef]` of `Release | Chunk[Release]`; `evalReleases` |
| a release | `Bracket.Cell` | `Cell extends Release`; `ContextHandler.release(state): Maybe[Release]` |
| the bracket's transition | `Arrow.Ensure` fused with the acquire, then `region(cell, use(a))` | one `Finalize` region whose body is the acquire; `done(cell, a)` stores and continues with `use(a)`; the second `done` ends |
| a binding region's end | `ContextHandler.done(state): Unit` | `done(state, value): Outcome`; `Loop.continue` keeps the region, anything else ends it |
| the moved obligation | `Stack.dump` appends the snapshot to the lane below; `settle` removes it on reinstall; `held`/`borrow` for repeated | `dump` moves the lists; nothing to settle |
| the escaping obligation | `oweBelow` at `arrowExit` | forward the list |
| the clause outside its region | `dumped` + `stack.pop()` + `clauseDispatch` re-push + `attachReentry` fusing `k` | `Handler.Gap(count)` pushed with `k` as its continuation; `find` jumps over the hidden entries |
| the pending answer | `dumped` + `crossing` (a park reinstalling the entries) | evaluated in place, `park` under a stop |
| the crossing for a cont clause | `Park(Effect.fused(v, kc, resume), entries)` | `Park(Effect.defer(v, kc, resume), entries)` |
| refusal on re-entry | `ContextHandler.reenter(state)` | `Release.reenter()` on the release found through `release(state)` |
| the release protocol | `borrow`, `defers`, `reenter`, `release(state, ex)`, `discharge(state, ex)`, `repeated` | `release(state): Maybe[Release]`; `escaping` stays |

## The gap

A loop clause's own computation is at the row outside the region it serves, so it has to run with the
handler and the regions above it absent from dispatch. Today that is a dump, a pop, and a re-push through
`clauseDispatch`, with the answer crossing back through a park. The gap keeps everything where it is:

- On a pending outcome the evaluator pushes `Gap(count = depth - idx)` with `k = kyo.cont.chain(contA.chain(contB))`
  as its continuation, and evaluates the outcome under it. `Stack.find` reaching a gap at `i` continues
  from `i - count - 1`. Regions the clause's computation pushes sit above the gap and are found first.
- The outcome settles with the gap on top. `Continue(ans)`: pop the gap, `loop(ans, k, id)`; the regions
  are back in dispatch by the pop alone. `Continue2(st, ans)`: the same after `setState(idx, st)`.
  Anything else: pop the gap, then the hidden entries above the handler with `Absent` (their extents are
  being discarded, which their releases report), then the handler; continue with the value on the
  handler's continuation.
- A throw from the clause's computation unwinds to the gap: the gap pops, the hidden entries are released
  with the throw without being offered it (the throw is not the body's), and the unwind continues below.
  A clause that throws synchronously gets the same treatment: `running` pushes the gap before rethrowing,
  instead of dumping.
- A park inside the clause's computation carries the gap as an entry; `count` is relative, so a reinstall
  at any depth keeps the hole where it was.

## Bracket

```
Bracket(acquire)(use)(release) =
  HandleContext(handler = Finalize over a fresh Cell(release), value = acquire)
    done(cell, a)  when cell is empty:  cell.acquired = a; Loop.continue(use(a))
    done(cell, b)  otherwise:           cell.ended = true; Loop.done(b)
    release(cell) = Present(cell)      run by the entry's pop, the owner's pop, an unwind, or abandonment
    fork(cell)    = inert              a child owns nothing
```

`ensuring` and `ensuringWith` are the same region with a cell that starts acquired. A throw from `use`
propagates out of `done`; the region on top is unwound with it and releases. `Scope.acquireRelease`,
`Async._timeout`, `Exchange`, and `Topic` are brackets whose `use` hands the value off.

## Surface

Kernel: `Stack`, `Handler`, `Eval`, `Bracket`, `PendingInternal` (Park's third field, `Fused`, `crossing`),
`Effect` (`fused`), `Arrow` (`Ensure`, `ensure`), `Pending` (`ensureMap`), `Loop` (an outcome for a value
already represented), `ArrowEffect` (`repeated` overrides, `handleFirstRepeated`), `Isolate` (the
`Forked` overrides). Callers: `Scope.acquireRelease`, `Async._timeout`, `Exchange`, `Topic`, `Choice`.
Tests: `StackTest` (lanes to lists), `EvalTest` (owed dumps, fused delivery, `Ensure` walk pins),
`BracketTest` (multi-shot and messages), `ChoiceTest` (a comment).

## Not touched by the release/gap/bracket pieces

This document derives the release-in-entries, gap and bracket pieces, whose base is `87b21a1ea1`: there
`IOTask`'s boundary is already a loop handler, `abandon` already walks, and `Context` is already removed.
The package range is wider, `bc6a48a2aa..HEAD`, opening with the `Context` removal (`d0f19b8f89`) and the
boundary and abandonment rework (`5a46dfda1c`); the package presents those as items 0a and 0b, in-range
and separately reasoned, rather than as untouched. `Safepoint` (beyond the js-wasm drain `b3849fd99c`)
and the pool are untouched here.

## As built (90aad2d8f1, 726b853c53): where the code settled differently from the rules above

`redesign.md` is an earlier plan; the build diverged from it in three ways `redesign.md` still states as
written, and the reconciliation is here:

- **The `floor` became the gap.** `redesign.md` section 4 planned a `floor` index carried through the
  outcome dispatcher to mark how far a loop clause's own computation may see. The build has no floor: the
  gap (`Handler.Gap` over `Hidden`, `Stack.hide`/`hidden`, `find` stepping over it) hides the region and
  the interior in place instead, which is the mechanism the rest of this document derives.
- **`maskedRead` is kept.** `redesign.md` section 3 listed `maskedRead` among the context removal's
  deletions. The build keeps it: a masking region still shadows a binding a read would answer from, and
  the `SuspendContext` arm dispatches there by the arrow route. `maskedEntries` and `Context.Masked` are
  gone; `maskedRead` is not.
- **The reporter overload of `Eval.release` is kept.** `redesign.md` section 4 listed it among the
  removals; rule 7 above requires it (abandonment reports the first operation the remainder stands at, so
  a join is linked). It is present, restored by `5a46dfda1c`, and item 0b presents it.

- **Rule 7, a region node not yet entered.** It owns what its derived state owes, as before: nothing for a
  bracket whose acquire never ran (an empty cell), the release it was handed for `ensuring` and
  `ensuringWith`. `Sync.ensure` on a fiber interrupted before its first step still runs its finalizer.
- **Rule 8, a pending answer.** The rows place a loop clause's *answer* inside the handler's region but
  outside the interior (`O[C] < (E & S)`, with `S` the row outside the handled computation), so a pending
  answer is evaluated under a second gap over the interior alone, and `Handler.answered` turns the value it
  settles to into the continue the gap dispatches. The gap carries the continuation the answer flows into
  (`hide(from, k)`); at the top, where there is no interior, the outcome already carries it and the gap's is
  the identity. A stop landing as a clause answers parks in front of the answer with the answer gap in place,
  which is the boundary's re-raised join.
- **A region's own release** stays in its own entry's list from the push (a cell born at the bracket's first
  `done` is added then). A dump moves lists only; a reinstalled region's normal pop runs nothing of its own.
  `Stack.owned` derives the region's release from handler and state so an unwind, a discard or an
  abandonment can tell it the failure wherever the dump left it held; `Release.ran` keeps a release found
  twice from running twice.
- **A release that throws.** At its own region's normal end it runs unguarded and fails the computation, as
  the extent's own failure would (`releasedAtEnd`); at an unwind or an abandonment the throw is attached
  to the failure as suppressed; at a discard, or for a moved release at its holder's normal end, the
  throws are gathered on a "remainder discarded" signal and reported once.
- **The bracket's `use` throwing while it builds its computation** releases the live cell with the throw
  before it propagates, inside `done`, since the cell owns the value from the hook on.
- **`Forked` regions** do not delegate `done`: the origin's runs once, at the join.
- **`ContextEffect.handle`** lost its `release` arm: a binding owns nothing to release; a region that does is
  a `Bracket`.
- **Multi-shot.** With one rule for every continuation-taking handler, a `handleCont` clause resuming twice
  runs both shots against the live resource; the refusal happens only after the release ran (a leaked
  continuation resumed after the handler ended, a park evaluated twice). `handleContRepeated` keeps the
  re-entry (each application under a fresh region of the handler); `handleFirstRepeated` is gone.
- **Rule 5, escaping, as the first full prelude run corrected it.** Forwarding the dumped releases below
  and nothing else keeps every resource of every resumed-and-completed remainder open until the scope
  enclosing the peel ends, one release per element in a streaming loop (`BatchTest` caught it as a
  `closes == 2` read before the enclosing scope ended). So an escaping handler's dump keeps each region's
  list in the snapshot as well (`Stack.dump(from, kept = true)`): the remainder carries what it holds and
  releases it at its own completion, and the copy the handler forwards below is the backstop for a
  remainder nobody resumes, once whichever comes first through `Release.ran`. That is the `handleFirst`
  contract as it was: a remainder resumed a second time is refused at the bracket it re-enters. The one
  caller that replayed a peeled token, `Choice.runStream`, now replays under `handleContRepeated` and
  emits each outcome as its branch completes: the outcomes stream in the order `run` collects them
  (depth first, where the loop emitted level by level), and a consumer that stops pulling stops the
  branches it never took (`ChoiceTest` "with incremental consumption and state" now sees the three taken
  branches' updates, not all five). A bracket around a streamed choice point is held across every branch
  and released once, as under `run`.
- **`Stack.truncate`** is gone: a loop handler's `done` pops the hidden entries one at a time through the
  gap, releasing each.
