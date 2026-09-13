# The region handler protocol, and what a finalizer needs from it

Every function below is declared in `Handler.scala` and called by the evaluator at a fixed moment.
The moments are what matter: none of them is a step, so nothing schedulable can land between the
event and the call. A finalizer that lives entirely in these calls needs nothing else from the
machine.

## The declarations

Common to every region (`Handler`):

| function | meaning |
|---|---|
| `tag` | the effect this region answers, or the binding it provides |
| `repeated` | the clause may resume the cont it is given more than once; the regions dumped into that cont are held, so each discharges once, where this handler ends |
| `escaping` | the clause hands the cont out as a value; the debt moves to the scope below, drained at that scope's exit if the remainder never resumes |
| `bound(ctx, state)` / `unbound(ctx)` | what entering the region contributes to the context and what leaving takes back |

A region that answers operations (`ArrowHandler`: cont, masking, loop, loop-with-state):

| function | meaning |
|---|---|
| `run(input, cont)` and its variants | answers one operation; reached through `answering` or `answers`, which attach the effect trace to a throw |
| `done(state, value)` | the body's value settled with this region on top; returns the region's result |
| `recover(state, ex)` | a throw is unwinding through the region; `Present` continues from the recovery value, `Absent` lets it pass |

A region that binds a value (`ContextHandler`):

| function | meaning |
|---|---|
| `derive(outer)` | the state at entry, from the enclosing binding of the same tag or none |
| `fork(parent)` / `join(parent, forked, child)` | the state a child fiber takes across a crossing, and what comes back |
| `done(state)` | the extent ran to its end; no value, since a crossing wraps a forked result |
| `release(state, ex)` | the extent was unwound by a throw, or abandoned |
| `discharge(state, ex)` | the owner of a held region ended normally; the recorded outcome is acted on |
| `borrow(state)` | custody taken, because a repeated handler dumped this region into a cont it may resume again |
| `defers(state)` | under custody, so resumption does not hand the region its own answerability back |
| `reenter(state)` | a parked region is being reinstalled |

## When the evaluator calls each

1. **Entry.** A `HandleContext` node: `derive(ctx.get(tag))`, then `bound`, then push. A `HandleArrow`
   node: push, then `bound`.

2. **The body's value settles with the region on top and nothing composed after it.** An arrow
   region: `done(state, value)`, then `arrowExit` (pop; owed dumps are drained, or moved to the scope
   below when `escaping`; `unbound`). A context region: `contextExit` (`done(state)`; pop; drain;
   `unbound`). The value is in hand when `done` is called, and the call is made from the settled arm
   directly: no deferral, no poll, no arrow between.

3. **An operation dispatched to a region below the top.** The regions above it are dumped into the
   continuation as entries with their states (`dumped`). If the answering handler is `repeated`, each
   context region gets `borrow(state)` (`held`).

4. **A park.** `park` takes every region off the stack into the `Park`'s entries with its state and
   its owed dumps. No hook runs: the obligations travel with the remainder.

5. **A park resumes.** `installed` calls `reenter(state)` on each context region (a throw here
   releases the park and rethrows), consults `defers`, then pushes each region back with `bound`.

6. **A throw unwinds.** `recovered`, innermost first. A context region: pop, drain what it owed,
   `release(state, ex)`. An arrow region: `recover(state, ex)`; `Present` resumes the evaluation from
   the recovery, `Absent` pops and continues unwinding. A fatal is offered to nobody.

7. **A remainder is abandoned.** `Eval.release` collects every context region the remainder holds:
   from `Park` entries, from `HandleContext` nodes not yet entered (with `derive(Absent)` for a state
   that never existed), and from owed dumps. Then `release(state, ex)` on each, innermost first,
   each call guarded so a throwing release suppresses into the signal.

8. **A dumped cont is dropped by an owner ending normally.** `drainDiscarded`: `discharge(state,
   signal)` on each held region.

## What a finalizer needs, hook by hook

| need | hook | already there |
|---|---|---|
| a place to hold the resource from the start | the cell region's `derive`: an empty cell | yes, `Bracket.region` |
| the resource's arrival with nothing between | an arrow region's `done(state, value)` around the acquire, filling the cell | the hook exists; `Bracket` never used it, it used an arrow |
| release on unwind | the cell region's `release` | yes |
| release on abandonment | the cell region's `release`, from the walk's collection | yes |
| release once under a multi-shot clause | `borrow`, `defers`, `discharge` | yes |
| release at the normal end | the cell region's `done(state)` | yes |
| not crossing into a child fiber | `fork` and `join` | yes, whatever `Bracket.region` declares |

So a bracket is two regions the kernel already knows how to run: the cell region on the outside,
holding the cell from `derive` on, and an acquire region on the inside whose `done` fills the cell
and returns `use(value)`. A stop inside the acquire parks with the cell empty, and the walk releases
nothing, because nothing is owned. A stop after `done` parks with the cell full, and the walk
releases it. There is no window, because the only transition, empty to full, happens in `done`.

The same two hooks serve every caller that reached for `ensureMap`:

- `Scope.acquireRelease`: an acquire region whose `done` calls the scope's `ensureUnsafe`. No cell:
  the scope owns the value from `done` on.
- `Async.timeout`'s spawn and the `Exchange` reader spawn: an acquire region whose `done` stores the
  handle, inside a cell region whose release interrupts it.
- `Topic`'s poll: a cell region over the token, an acquire region over the poll whose `done` hands
  the token to the transport on `Done`.

## What the protocol does not have, and does not need

An arrow region that answers no operation. `ArrowHandler` is sealed with four shapes, each built to
answer something; the acquire region answers nothing and exists for its `done`. That is the one
addition: a shape of `ArrowHandler` with `done` and `recover` only, and a tag nothing raises. It is
not a new node kind: it is pushed and popped by the existing `HandleArrow` arm, and its `done` is
called by the existing settled arm. `Arrow.Ensure` and `ensureMap` go, and with them the walk's
`ensuring` and `leftmost`, the reporter, the marker node, and the stopped resumption.

## What changes in expectations

An acquire that has not reached `done` owns nothing, even if the value it was waiting for is sitting
in a promise. Whoever produced that value owns it until a handoff the owner registered before
waiting. The two strand leaves and the two crossing-delivery leaves in the branch assert the
opposite and are rewritten to that shape; the pool already uses it.
