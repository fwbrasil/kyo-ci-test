# Why the finalizer holes keep appearing, and how to test the kernel and core so they stop

## Summary

Every hole this campaign found is the same invariant broken at a different site: a release must be
registered in the step that produces the resource, and nothing schedulable may land between the two.
The invariant was never written down as one statement, so it was rediscovered one symptom at a time,
and every pin written along the way pinned the symptom's shape and the mechanism that fixed it rather
than the invariant. The suites are large (13k lines in the kernel alone) and example-based: they
sample one point on each axis of the shape space, and the holes live at the points nobody sampled,
which is where a value is a computation, an operation crossed a region, or the stop landed one poll
earlier. The scheduler's interleavings are hidden behind timing on the JVM, so the container suites
were green by luck and the JS scheduler's fixed order was the only thing that exposed them.

The fix for coverage is to make the invariant executable over the whole shape space instead of adding
pins per symptom: a resource ledger as the oracle, a generated grammar of computation shapes, every
stop placement enumerated rather than raced, one interpreter for abandonment so one property covers
it, and a manual scheduler in core so fiber interleavings are enumerated the same way. The rest of this
file gives the evidence for the diagnosis and the concrete shape of each piece.

## Evidence: the holes are one invariant

| found by | site where the value and its registration were split | shape axis nobody had sampled |
|---|---|---|
| JS SQL close 30 s | the walk's budget ran a deferral and took a permit nothing released | a deferral under the walk |
| SyncTest first slice | `Sync.ensure` installed its region a step after the body began | region installed after the first poll |
| ScopeTest #1928 | `Scope.close` registered in a later step than the claim | registration behind a suspension |
| ScopeTest #1820 | an `Ensure` waiting on a settled value under a deferral | settled value, `Ensure` head |
| ScopeInterruptTest strand pin | a join's answer arrived while parked, the fiber abandoned | answer in the promise, fiber not resumed |
| JS SQL again, after piece P | the answer was the isolate's restore, a computation | computation-valued answer |
| this report, Issue 1 | the crossing parks the answer as a deferral, the deferral arm polls | operation under a region, stop at resumption |
| this report, Issue 2 | a body ending with its value under a taken interrupt | value that is a resource |
| this report, Issue 3 | resource produced on one fiber, registered by another | cross-fiber ownership |
| CI, AeronTransportTest | an interrupt on a completed add, the token in the value | the same as Issue 2, in a consumer |

Ten sites, one rule. Each pin written for one row passes on that row and says nothing about the next,
because each was written as "this shape releases" rather than "every shape releases".

## Why it happened

**The invariant lived in comments, spread over five files.** `Arrow.ensure`'s scaladoc says why a
poll between a value and its registration loses the resource. `Scope.acquireRelease`'s comment says
the same for `map` versus `ensureMap`. `Eval.release`'s comment says a deferral is walked, not run.
`IOTask`'s status word explains what an interrupt owns. `Pending.Suspend.crossing`'s comment says an
answer parks as a deferral. Nobody had written the one sentence that ties them: which evaluator arms
are step boundaries, and that a delivery is not one. Without that sentence, each site was reasoned
about alone, and the crossing's deferral, the clause-answer poll and the deferral arm's poll were
each individually defensible and jointly wrong.

**Pins captured mechanisms, not properties.** The strand pin joined a `Promise` and passed because a
promise's result is a settled `Int`; a fiber's result is a computation, and the same pin with a fiber
fails on every platform. The three delivery pins hand the answer in through the reporter overload
and check that an `ensureMap` saw a settled `Int`: they pin the walk's delivery as built, and the
walk's delivery is the thing that is wrong. A pin that varies the answer's shape (settled, nested,
computation, failure) would have failed before piece P was committed.

**The symptom-first loop converges on the reported shapes, never on the space.** Red CI, reproduce
that shape, fix that site, pin that shape, green. Every iteration adds a point; none adds an axis.
Ten iterations later the sampled points are exactly the ones that were reported, and the space between
them is untested by construction.

**A second interpreter of the same nodes.** `Eval.release` walks `Defer`, `Handle`, `HandleContext`,
`Park`, `Suspend`, `Snapshot` with its own rules, and `Eval.loop` evaluates the same nodes with others.
Piece P added a third set, a hand-rolled delivery inside the walk that re-derived "the step fused with
an answer" without the stack, and so could not answer a context read or a stack read. Every rule the
evaluator has must be re-derived in the walk, and each rule not re-derived is a hole. Nothing checks
that the two agree on what has been acquired.

**Fiber interleavings are hidden by timing.** On the JVM the child completes long before the
interrupt lands, so `kyo-sql-postgresJVM` passed 82 suites against a real backend with the hole in
place. The JS scheduler is a FIFO queue and pins one interleaving, the one that fails. There is no
way in core to say "interrupt after the third scheduler step" and enumerate the rest, so the
interleaving space is sampled by racing 200 rounds, which finds the wide windows and misses the
narrow ones.

**The interrupt's contract was decided inside `IOTask`.** What `interrupt` returning `true` promises,
whether a body's value survives a taken interrupt, and who owns a value produced on another fiber were
settled by the implementation and documented after. Consumers wrote their own pins against their own
reading: the pool assumed the permit reaches the parent, aeron assumed a completed add keeps its
token. Those pins are now the specification, found one CI run at a time.

## The invariant, stated once

A **step** is the work between two `Pending` nodes the evaluator unfolds. A **step boundary** is a
poll: the evaluator honors a stop there and nowhere else. A **delivery** is an operation's answer
reaching the operation's own continuation, and it includes producing the answer when the answer is a
computation the evaluator answers without running anyone's code: a context read, a stack read, an
isolate's restore. **The rule:** a delivery is never a step boundary; the first arrow of the
operation's continuation runs as the answer arrives, under the regions the operation was issued
under, and the first poll comes after it. **The consequence for finalizers:** a release registered
by that first arrow, `ensureMap` at a `Scope.acquireRelease`, is registered whenever the value exists,
whether the fiber resumes, is abandoned with the answer in hand, or is stopped at the next poll.

Everything else follows: the walk runs nothing because a deferral is a step boundary and what is
behind one has not acquired anything; abandonment of a remainder standing at an operation whose
answer arrived is a resumption with the stop pending, which runs exactly the delivery; a body that
ends with a value has delivered it to its promise, and the promise's consumer is the only one who can
release it.

## How to cover it

### 1. A resource ledger as the oracle, in the kernel suite

One helper for every finalizer test: `Ledger` with `acquire(name)` and `release(name)`, and a single
check, `balanced`, that every acquired name was released exactly once and no release ran for a name
never acquired. Every pin in `BracketTest`, `EvalTest`'s release block and the core interrupt suites
currently keeps its own `var released` or `AtomicInt`; the ledger replaces them and makes the
assertion the same everywhere. A pin that checks `balanced` cannot pass by releasing the wrong thing
or by releasing nothing when nothing was acquired.

### 2. Shapes generated, not chosen

The kernel suite uses ScalaTest without a property library, and adding one is not needed: the shape
space is finite and small enough to enumerate. A shape is a term in a grammar the evaluator's arms
define, built over the ledger:

| axis | values |
|---|---|
| the acquiring value | settled; a deferral; a suspension at the top; a suspension under one region; under two; a defaulted context read; a bound context read; a nested computation |
| the registration | `ensureMap` at the value; `Bracket` at the value; a deferral after the value (the negative case, which must not register) |
| the continuation after it | a `map`; a deferral; another suspension; the region's end |
| the region between clause and operation | none; a `Bracket`; a context region; a handler region; two |

Sixty-odd shapes, each a few lines through a builder. For each shape, four runs: `Eval.apply`
(balanced, the base line), `Eval.partial` with a stop at each poll (see 3), `Eval.release` of what
parked (balanced), and resume of what parked to the end (balanced). One `for` over the shapes, one
leaf per axis value so a failure names its cell. This is what makes "the crossing parks the answer
as a deferral" impossible to miss: the crossing is one cell, and the stop at its deferral is one run.

### 3. Every stop placement, enumerated

`requestStop()` stops at the next poll; the holes were one poll earlier or later than the one a pin
requested. A test-only counter on the safepoint, `stopAfter(n)`, arms the stop at the n-th poll of
the evaluation. A shape with k polls is then run k+1 times, and the ledger must balance after
`release` of what parked at each. The kernel already has the deadline on js-wasm and the addressed
stop on jvm-native; the counter is a third arming, honored by `stopped(slot)`, test scope only.
`ScopeInterruptTest`'s "an interrupt landing while the acquire's last step runs" family does this
today by racing 200 rounds; the counter does it once per poll and deterministically on every
platform.

### 4. One interpreter for abandonment

The coverage argument for the design question in issues.md: as long as `Eval.release` decides what
runs, every evaluator rule has to be tested twice, and the walk's copy is the one that was wrong three
times. If abandonment is a resumption with the stop pending followed by a release of what parked, the
shape enumeration in 2 and the stop enumeration in 3 cover abandonment with no separate pins, and the
walk is left with the one job the enumeration checks it for: releasing regions and running nothing.
The reporter overload and the `delivered` flag are the surface that a property cannot reach, because
they take an answer from outside the evaluator's rules.

### 5. A manual scheduler for the core interrupt suites

`IOTask` is a `Task`; its `run` can be driven by hand. A test scheduler with a queue and a `step()`
lets a core leaf spawn fibers, then say: run the child one slice, interrupt the parent, run the
parent's release, complete the promise, run the child. The five-state status word has a small
transition table (interrupt over `Idle`, over `Thread` by a self-interrupt, over the parked promise;
completion before, during, after), and the manual scheduler makes each row one deterministic leaf
instead of a race the JVM usually wins. The JS scheduler's FIFO order is one row of that table; the
others are the ones CI does not run. `kyo-scheduler`'s `TestExecutors` and `TestTask` are the
starting point; what is missing is a `Scheduler` the core tests can install for a leaf.

### 6. Cross-fiber ownership as one supported shape

A resource produced on one fiber and owned by another has exactly one sound shape: the producing
fiber registers the release in the step that produces the value, into the owner's scope, and a closed
owner's scope runs it detached. That shape gets one pin in core with the ledger, covering the three
windows (owner abandoned after the value, before the value with the child then producing, and the
value delivered on the child's own abandonment). The unsound shape, `Scope.acquireRelease` around
`Async.timeout` or around a `Fiber.get`, gets a line in `flags.sh` so a review sees it, and the pool,
aeron and net are checked against it. The three consumer pins that found holes (the pool's permit,
aeron's token, net's descriptor) stay where they are and become the integration layer over the one
core pin.

### 7. Verification rules for any finalizer change

- The JS leg of every interrupt suite runs locally before a commit claims the change verified: its
  FIFO order is the deterministic oracle the JVM does not give.
- A pin for a delivery varies the answer's shape: settled, nested, a computation, a failure.
- A pin for an interrupt varies where it lands: idle, running, parked, and before, during and after
  the value.
- A fix in `Eval.release` or `IOTask.abandon` runs the shape enumeration, not the pin that motivated
  it.
- A green container suite is evidence about the container, not about interleavings.

## Order of work

1. The ledger and the shape builder in the kernel suite, and the existing release and bracket pins
   rewritten over them, so the enumeration and the old pins agree before anything changes.
2. `stopAfter(n)` on the safepoint, test scope, all three platforms, and the stop enumeration over
   the shapes. This is where the crossing's cell goes red on the current tip.
3. The abandonment design from the advice, with the enumeration as its acceptance test.
4. The manual scheduler in core, and the status word's transition table as leaves over it.
5. The ownership pin and the flag line.
