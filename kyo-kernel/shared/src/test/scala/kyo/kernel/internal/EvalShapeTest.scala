package kyo.kernel.internal

import kyo.*
import kyo.kernel.*
import kyo.kernel.ArrowEffect.Mask

/** The shapes the evaluator has to agree on, generated rather than picked.
  *
  * Every scenario derives one value by hand, for its simplest configuration, and the laws below multiply that value across every
  * configuration the evaluator distinguishes. A cell that disagrees with its scenario's base value is a path the evaluator takes for that
  * configuration that diverges from the law it specialises.
  */
class EvalShapeTest extends Test:

    // the effect under test, answered by the handler each scenario installs
    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    // an effect a clause may perform, answered outside every region under test
    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    // interlopers: a binding never read and a region never answered, pushed above the handler under test
    sealed trait Cfg  extends ContextEffect[Int]
    sealed trait Idle extends ArrowEffect[Const[Unit], Const[Unit]]

    def record[A](v: A < Say): (List[String], A) < Any =
        ArrowEffect.handleLoopState(Tag[Say], List.empty[String], v)(
            [C] => (log, s) => Loop.continue(log :+ s, ()),
            (log, a) => (log, a)
        )

    // n occurrences of the effect, consecutive, summed; zero occurrences settles without it
    def prog(n: Int): Int < Ask =
        if n == 0 then 0 else ask.map(a => prog(n - 1).map(_ + a))

    def cfgAbove[A, S](v: A < S): A < S  = ContextEffect.handleInheritable(Tag[Cfg], 0)(v)
    def idleAbove[A, S](v: A < S): A < S = ArrowEffect.handleCont(Tag[Idle], v)([C] => (_, k) => k(()))

    // an inner handler for the same tag: the innermost region answers, and the scenario's own never runs
    def innerAbove[A, S](v: A < (Ask & S)): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, k) => k(1000))

    /** One handler under test.
      *
      * `run` installs it with a clause that answers at once; `suspending` installs the same handler with a clause that performs `say("s")`
      * and then answers identically, so the two must agree on the value and differ only in the log. `expected(n)` is the hand-derived value
      * for `prog(n)`, `inner(n)` the value when an inner region for the same tag sits between the handler and the program, and `says(n)`
      * how many times the suspending clause runs for `prog(n)`: once per occurrence unless the clause ends the region or resumes more
      * than once.
      */
    final case class Scenario(
        name: String,
        run: (Int < (Ask & Say)) => Int < Say,
        suspending: (Int < (Ask & Say)) => Int < Say,
        expected: Int => Int,
        inner: Int => Int,
        says: Int => Int = n => n
    )

    val scenarios: List[Scenario] = List(
        Scenario(
            "handleCont resuming once",
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, k) => k(7)),
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, k) => say("s").map(_ => k(7))),
            n => 7 * n,
            n => 1000 * n
        ),
        Scenario(
            "handleCont never resuming",
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1),
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => say("s").map(_ => -1)),
            n => if n == 0 then 0 else -1,
            n => 1000 * n,
            // the first occurrence ends the region, so the clause runs once at most
            says = n => math.min(n, 1)
        ),
        Scenario(
            "handleContRepeated resuming twice",
            v => ArrowEffect.handleContRepeated(Tag[Ask], v)([C] => (_, k) => k(7).map(a => k(8).map(b => a + b)), a => a),
            v =>
                ArrowEffect.handleContRepeated(Tag[Ask], v)(
                    [C] => (_, k) => say("s").map(_ => k(7).map(a => k(8).map(b => a + b))),
                    a => a
                ),
            // every path through n binary choices of 7 or 8, summed: each position contributes 2^(n-1) * (7 + 8)
            n => if n == 0 then 0 else n * (1 << (n - 1)) * 15,
            n => 1000 * n,
            // occurrence k is reached once per path through the k-1 choices before it: 1 + 2 + ... + 2^(n-1)
            says = n => (1 << n) - 1
        ),
        Scenario(
            "handleLoop continuing",
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(7)),
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => say("s").map(_ => Loop.continue(7))),
            n => 7 * n,
            n => 1000 * n
        ),
        Scenario(
            "handleLoop ending from the clause",
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a),
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => say("s").map(_ => Loop.done(-1)), a => a),
            n => if n == 0 then 0 else -1,
            n => 1000 * n,
            says = n => math.min(n, 1)
        ),
        Scenario(
            "handleLoopState threading a counter",
            v =>
                ArrowEffect.handleLoopState(Tag[Ask], 100, v)(
                    [C] => (st, _) => Loop.continue(st + 1, 7 + st),
                    (st, a) => a * 1000 + st
                ),
            v =>
                ArrowEffect.handleLoopState(Tag[Ask], 100, v)(
                    [C] => (st, _) => say("s").map(_ => Loop.continue(st + 1, 7 + st)),
                    (st, a) => a * 1000 + st
                ),
            // answers 107, 108, ... 106 + n; then the done arm sees their sum and the final counter
            n => (0 until n).map(i => 107 + i).sum * 1000 + (100 + n),
            n => 1000 * n * 1000 + 100
        ),
        Scenario(
            "Mask tunnelling past an inner handler",
            v => ArrowEffect.handleCont(Tag[Ask], Mask.run[Ask](innerAbove(Mask[Ask](v))))([C] => (_, k) => k(7)),
            v =>
                ArrowEffect.handleCont(Tag[Ask], Mask.run[Ask](innerAbove(Mask[Ask](v))))(
                    [C] => (_, k) => say("s").map(_ => k(7))
                ),
            n => 7 * n,
            n => 1000 * n
        )
    )

    for s <- scenarios do
        s.name - {
            for n <- List(0, 1, 2, 3) do
                s"$n occurrences" - {

                    "base" in {
                        assert(record(s.run(prog(n))).eval == ((Nil, s.expected(n))))
                    }

                    // at-top law: an inert region above the handler changes nothing, whichever kind it is
                    "a binding above" in {
                        assert(record(s.run(cfgAbove(prog(n)))).eval == ((Nil, s.expected(n))))
                    }

                    "a region above" in {
                        assert(record(s.run(idleAbove(prog(n)))).eval == ((Nil, s.expected(n))))
                    }

                    // suspension law: a clause that performs an outer effect and then answers equals one that answers at once
                    "the clause suspends first" in {
                        assert(record(s.suspending(prog(n))).eval == ((List.fill(s.says(n))("s"), s.expected(n))))
                    }

                    "the clause suspends first, with a binding above" in {
                        assert(record(s.suspending(cfgAbove(prog(n)))).eval == ((List.fill(s.says(n))("s"), s.expected(n))))
                    }

                    "the clause suspends first, with a region above" in {
                        assert(record(s.suspending(idleAbove(prog(n)))).eval == ((List.fill(s.says(n))("s"), s.expected(n))))
                    }

                    // geography: an inner handler for the same tag answers, and this handler's clause never runs
                    "an inner handler for the same tag" in {
                        assert(record(s.run(innerAbove(prog(n)))).eval == ((Nil, s.inner(n))))
                    }

                    "an inner handler for the same tag, the clause suspending" in {
                        assert(record(s.suspending(innerAbove(prog(n)))).eval == ((Nil, s.inner(n))))
                    }
                }
        }
    end for

end EvalShapeTest
