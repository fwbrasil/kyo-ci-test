# A bracket node instead of a fused arrow

The direction: ownership of a resource is a node the evaluator knows, `Pending.Bracket`, not a step
the evaluator is asked not to poll in front of, `Arrow.Ensure`. This file derives it.

## Why the fused arrow was the flawed decision

`ensureMap` promises that its function runs as the value arrives, with nothing schedulable between.
The kernel keeps that promise in exactly one place, the arrow's own `apply` when the input is already
settled:

```scala
final def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2) =
    v match
        case v: Pending[A, S2] @unchecked => Effect.defer(v, this, cont)          // pending: deferred behind it
        case _                            => cont.head(apply(Nested.unnest(v)), cont.tail)  // settled: applied now
```

Everywhere else the `Ensure` is an ordinary arrow in an ordinary continuation, and the evaluator has
no way to tell it apart from a `map`: the crossing parks the answer as a deferral in front of it, the
deferral arm polls before unfolding, the clause-answer check polls before entering, and the release
walk can only offer a settled value to an `Ensure` that happens to stand first. Every hole this
campaign found is one of those four places discovering that the arrow is just an arrow. Fixing them
one at a time is what I have been doing, and each fix teaches the evaluator to recognize the arrow at
one more site.

The pairing the arrow tries to express is acquire-with-release. The kernel already has a value for
that, `Bracket`, but it is built *on top of* the arrow:

```scala
def apply[A, S1](acquire: A < S1)[B, S2](use: A => B < S2)(release: (A, Maybe[Throwable]) => Unit) =
    val ensure = new Arrow.Ensure[A, B, S1 & S2]:
        override def apply(a: A) =
            val cell = new Cell.Live(a, release)
            region(cell, use(a))
    ensure(Effect.defer(acquire))
```

So the bracket's region exists only once an arrow has run, and whether that arrow runs is exactly
what the machine cannot guarantee.

## The equation

`Bracket(acquire)(use)(release)` is a region whose body is `acquire` and whose `done` moves the value
into the region's cell. The evaluator already runs a region's `done` in the settled arm with no poll
between the value and the call:

```scala
case handler0 =>
    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
    val result  = handler.done(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
```

That is the delivery the fused arrow was trying to be, and it is already a node, not a step. What
`Bracket` needs beyond a region is one law the region's body obeys:

**Inside an acquire, no stop is honored.** An acquire that has started runs to its value, or to the
suspension it cannot get past. Its value goes into the cell in the region's `done`, with nothing
between. Only then is the stop honored, and by then the walk finds the cell.

This is the bracket law every effect system states: the acquire is uninterruptible, the use is
interruptible, the release always runs. The kernel has been approximating it with an arrow that does
not poll at one site.

## What follows, case by case

- **A crossing answer.** The join inside the acquire crosses back into the bracket region. The
  crossing parks the answer as a deferral under the region; the deferral arm would poll; under the
  law it does not, because the region on top of the stack is a bracket in its acquire. The fold runs,
  the isolate's restore runs under the reinstalled regions, `done` stores the value. The stop parks at
  the first poll inside `use`.
- **A settled acquire.** `Sync.defer(open)` inside the acquire: the deferral is unfolded without a
  poll, the value is produced and stored. This is the first-slice case and the `Sync.acquireReleaseWith`
  leaves.
- **A stop landing before the acquire started.** The bracket node has not been entered; its region is
  not on the stack; nothing is owned; the walk releases nothing. "An acquire the park stopped in front
  of is neither run nor released" holds by construction: the poll before the bracket node parks.
- **Abandonment with the answer in the promise.** The remainder stands at the join, inside the
  acquire. Abandonment resumes the remainder with the stop pending: the join is answered by the
  boundary, the acquire finishes under the law, `done` stores the value, the first poll in `use` parks,
  and the walk releases the cell. This is the resumption the abandonment needs anyway; the law is
  what makes it deliver.
- **Abandonment with the promise still pending.** The acquire cannot finish. The boundary links the
  promise; the interrupt cascades to the child; the acquire's join ends with the interrupt; the
  region ends without a value; nothing to release. The child's own brackets release what it held.
- **A throw in the acquire.** Unwinds through the bracket region with no cell; nothing to release;
  the throw carries on. A throw in `use` unwinds through the region with the cell; released with the
  throwable. Both are the region's existing recover path.
- **Preemption during a long acquire.** Not honored until the acquire ends. That is the bracket law's
  price and every effect system pays it; acquires are meant to be short, and one that waits does so
  at a join, which is a park, not a poll.

## The node

A `Pending.Bracket` is a `HandleContext`-shaped node whose handler is the bracket's handler and whose
value is the acquire. It reuses `Cell` and the `Finalize` region already in `Bracket.scala`. The
evaluator needs to know two things about it: that its body is an acquire while the cell is empty, so
polls inside are not honored, and that `done` moves the value into the cell and starts `use`. Both
live in the handler, not in new evaluator arms: the handler's `done` is where the value lands, and
the poll sites ask the stack's top handler whether it is acquiring. One predicate on the handler,
read at the existing poll sites, replaces the marker node.

`Arrow.Ensure` and `ensureMap` are removed. Every caller becomes a bracket:

| caller | today | becomes |
|---|---|---|
| `Bracket.apply` | an `Ensure` installing the region | the node itself |
| `Scope.acquireRelease` | `Sync.defer(acquire).ensureMap(register)` | a bracket whose `use` registers with the scope and hands the cell over; release is the cell's until then |
| `Sync.ensure`, `Bracket.ensuring`, `ensuringWith` | region-from-the-start | unchanged in shape, already a node |
| `Async._timeout` | spawn `.ensureMap(wire; join)` | a bracket over the spawn: `use` wires and joins, release interrupts the child |
| `Exchange` reader spawn | spawn `.ensureMap(store handle)` | the same bracket shape |
| `Topic` poll flag | poll `.ensureMap(flip)` | a bracket over the token whose `use` polls and hands ownership to the transport on `Done` |

The pool keeps the handoff shape, which is a bracket over the take promise already.

## The surface

- `kyo-kernel`: `Pending.scala` (drop `ensureMap`), `Arrow.scala` (drop `Ensure`, `ensure`),
  `Bracket.scala` (the node, the handler's acquiring predicate, `done` storing the cell), `Eval.scala`
  (the deferral arm and the two clause-answer checks consult the predicate; `stopped` stays for the
  abandonment resume; `release` loses `ensuring` and `leftmost`, since ownership is in cells), and the
  crossing goes back to its plain deferral.
- `kyo-core`: `Scope.acquireRelease`, `Async._timeout`, `Exchange`, `IOTask.abandon` (resume, then
  release, as now).
- `kyo-aeron`: `Topic`'s two polls.
- Tests: `EvalTest`'s parking leaves are rewritten with a bracket around the operation instead of an
  `ensureMap` after it; the release block's leaves keep their shape; `BracketTest` gains the law
  ("a stop inside an acquire is honored at the first poll of the use"); `ScopeInterruptTest`'s strand
  leaves stay as written, since `Scope.acquireRelease` over a join becomes sound; the #1820
  settled-shape leaves go, since there is no `Ensure` waiting on a value.

## The fork

One law is being added to the evaluator: a stop is not honored while the region on top of the stack
is a bracket still in its acquire. Everything above follows from it and nothing else is new. If that
law is the ruling, I revert the marker-node commit, keep the pieces that stand on their own (the
pool's handoff shape, the safepoint merge, the walk's guard, the ending semantics), and build the
node in one change with the leaves as its acceptance.
