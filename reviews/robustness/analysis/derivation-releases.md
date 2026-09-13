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

## Not touched

`IOTask` (the boundary is a loop handler already; `abandon` walks). `Safepoint`. The pool. The context
removal.
