# The redesign: regions own releases, handlers answer in place

The conclusions of the design discussion, as the list of changes. Each entry names what it adds,
what it removes, and what pins it.

## 1. `Stack`: releases live in entries

- Add a fourth parallel array, `releases`, typed `null | (Maybe[Throwable] => Unit) | Chunk[...]`,
  and a root list `evalReleases` for what an escaping clause bumps past the outermost region.
- A region with a release contributes `outcome => release(state, outcome)` to its own entry at push.
- Popping an entry runs its list: `Absent` at a normal end, the throwable on an unwind, the signal
  on abandonment; a bumped release gets the discard signal at a normal end.
- Remove `owed`, `evalOwed`, `owe`, `oweBelow`, `settle`, `takePopped`, `takeEvalOwed`; the
  snapshot's fourth slot becomes the release list.
- Pins: `StackTest` for push, pop, bump, and a park's snapshot carrying lists.

## 2. `Handler`: the protocol

- Keep: `tag`, `run`, `recover`, `derive`, `fork`, `join`.
- `done(state, value)` on every region handler, binding regions included, receives the body's value
  and answers with an outcome: continue under this region with more body, or end with a result.
  `Loop.continue` and `Loop.done` are the outcomes. This is the hook a bracket's acquire arrives at.
- Add: `release(state, outcome)` on regions with releasable state, called once by the entry's pop.
- Add: `escaping` as the one declaration on continuation-taking handlers: forward the held list to
  the outer region at the pop instead of running it.
- Remove: `repeated`, `bound`, `unbound`, `borrow`, `defers`, `reenter`, `discharge`, and the
  binding region's valueless `done`.
- A release remembers that it ran; the evaluator refuses to re-push a region whose release ran,
  with the bracket's current messages.

## 3. `Context` removed

- A read is `stack.find(tag)` then `stack.state(idx)`; a masking region found first dispatches the
  read to its clause; nothing found takes the default.
- `derive(outer)` uses the same lookup before the push.
- Remove `Context`, the `ctx` parameter of `loop`, `rebound`, `rebuilt`, `contextExit`'s unbind,
  `maskedEntries`, `maskedRead`, `Context.Masked`.
- Pins: `ContextEffectTest` shadowing, masking, defaulted reads, and `IsolateTest`'s join visible to
  the next read.

## 4. `Eval`: two kinds of handler

- In place, `handleLoop`, `handleLoopState`, and the boundary: the inner regions stay on the stack,
  the answer flows into the body under them. A clause's own suspensions dispatch from the handler's
  index outward: a `floor` carried through the outcome dispatcher, lifted on `continue`, nested by
  clauses, recorded in a park. `done` pops the entries above the handler with the discard signal,
  then the handler.
- Continuation-taking, `handleCont` and masking: dump the entries above into the continuation with
  empty lists, move their releases to the handler's entry, re-enter through `crossing` on each
  resumption; the snapshot carries the floor when a clause was in progress.
- Remove: the loop handler's interior dump for a suspending clause, `held`, `expandOwed`,
  `drainOwed`, `drainDiscarded`, the `delivering` check, `Eval.stopped`, and the reporter overload of
  `release`.
- `Eval.release` collects every entry a remainder holds and runs its list with the signal, guarded.
- Pins: `EvalTest` effectful-clause and own-tag blocks unchanged in expectation; new leaves for the
  floor after a park, the floor after a dump mid-clause, `done` through a clause in progress, and
  the release timing for once-resuming handlers.

## 5. `Pending`: nodes

- Remove `Pending.Fused` and `Effect.fused`; `crossing` goes back to `Effect.defer(v, kc, resume)`.
- Remove `Arrow.Ensure`, `Arrow.ensure`, `ensureMap`.
- Pins: the `#1820` settled-shape leaves go with `Ensure`; the parking leaves that assert fusion
  across a crossing go, since no in-place answer crosses.

## 6. `Bracket`: one region, the hooks do the work

- `Bracket(acquire)(use)(release)` is a `Finalize` region whose body is `acquire`. `derive` gives
  the state, an empty cell holding the release. The first `done` receives the acquire's value,
  stores it, and continues the region with `use(value)`; the second `done` receives `use`'s value
  and ends the region; `release` runs at the pop with the cell's contents. `fork` hands a child an
  inert copy, `join` keeps the parent's. No second region, no new handler shape.
- `Scope.acquireRelease` is the same region with a `use` that registers with the scope and hands
  the cell off. `ensuring` and `ensuringWith` keep their shape.
- Remove `Cell` and its hierarchy; the release closure carries the ran-once and extent-ended bits.
- Pins: `BracketTest` gains "a stop inside the acquire releases nothing", "a stop after done
  releases the cell", the multi-shot and peel leaves rewritten over lists.

## 7. `IOTask`: the boundary answers in place or parks

- The boundary becomes an in-place handler: a completed promise's result flows into the body; a
  pending one parks the whole stack with the floor. No `handleCont`, no crossing, no re-raise.
- `abandon`: link, then release; no resumption. Revert the `Eval.stopped` call.
- `finish`: keep the ending semantics from Issue 2 (a body's own ending stands; the interrupt owns a
  remainder and the blocked carrier's throw).
- Pins: `FiberTest` "completes with the value" stays; `ScopeInterruptTest` strand leaves rewritten
  to own the promise before waiting; the `IOTaskTest` transition leaves from the plan.

## 8. Callers of `ensureMap`

- `Scope.acquireRelease`: an acquire region whose `done` calls the scope's `ensureUnsafe`.
- `Async._timeout` and the `Exchange` reader spawn: a bracket over the spawn, `done` stores the
  handle, release interrupts it.
- `Topic`'s two polls: a bracket over the token, `done` hands ownership to the transport on `Done`.
- The pool keeps the handoff shape from the advice.

## 9. Kept from the branch as it stands

- `Safepoint.stop` merging two stops for one thread into a wildcard, with its pin.
- The ending semantics in `IOTask.finish` and the `run` catch guard, the `Fiber.interrupt` doc.
- The pool's `takeSlot` owning the take promise; the two ownership leaves.
- The Aeron leaf expecting the add's own ending.

## Order

1. Revert the marker-node commit to the pool-and-tests tip, keeping section 9.
2. Sections 1, 2, 3: stack lists, protocol, context removal. Kernel suites green.
3. Section 4 and 5: in-place answering with the floor, the dumps confined to continuation-taking
   handlers, `Ensure` removed. Kernel suites green.
4. Section 6: the bracket over the acquire region. `BracketTest` green.
5. Section 7 and 8: the boundary, abandonment, the callers. Core, sql, aeron, net suites on JVM and
   JS.
6. CI on the tip.
