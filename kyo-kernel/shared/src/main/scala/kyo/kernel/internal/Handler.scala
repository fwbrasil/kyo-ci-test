package kyo.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.Arrow
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import kyo.kernel.Loop
import kyo.kernel.Loop.Continue
import kyo.kernel.Loop.Continue2
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.annotation.publicInBinary

/** What a region installs: the clause that answers an effect, plus what the evaluator has to know to run the region around it.
  *
  * One instance per region entry, pushed onto the [[Stack]] and matched by [[tag]] when a suspension looks for who answers it. The subclasses
  * below are the answering shapes the public API offers; everything they have in common is here, and it is information the evaluator needs
  * about a region rather than about the effect: whether the clause hands the continuation out.
  *
  * That is declared rather than inferred because the evaluator has to decide what to do with the releases a handler holds before the clause
  * has run, and by then it is too late to observe what the clause actually does.
  */
sealed abstract private[kernel] class Handler[E <: Effect, A, -S]:
    def tag: Tag[E]

    /** Whether this handler's clause hands the cont out of the clause as a value.
      *
      * Such a clause has not finished with what it owes when its answer settles: the remainder is still live in
      * whoever holds it. The releases this handler's entry holds are forwarded to the entry below at its normal end,
      * to run when that one ends, rather than run here.
      */
    def escaping: Boolean = false

    override def toString = s"Handler(${tag.show})"
end Handler

@publicInBinary private[kernel] object Handler:

    /** A region that answers operations, as opposed to one that only binds a value.
      *
      * `done` is what the region produces when the computation inside finishes without the clause ending it, and `recover` is offered a
      * throwable raised inside the region, answering with a replacement or declining so the unwind carries on.
      */
    sealed abstract class ArrowHandler[State, E <: Effect, A, B, -S] extends Handler[E, B, S]:

        def done(state: State, v: A): B < S
        def recover(state: State, ex: Throwable): Maybe[B < S] = Absent

    end ArrowHandler

    /** The region behind [[kyo.kernel.ArrowEffect.handleCont]]: the clause is handed the continuation and decides what to do with it.
      *
      * `run` answers at `E & S`, inside the region, so an operation the clause performs comes back to this same handler. The region carries
      * no state, hence `Unit`.
      */
    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:

        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

        /** Runs the clause, attaching the effect trace to anything it throws.
          *
          * The catch is here rather than around the evaluator's call so the suspension and the stack are still in hand: by the time a
          * throwable reaches the loop, the region it came from may already be off the stack.
          */
        private[kyo] def answering[X](input: I[X], cont: Arrow[O[X], A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(input, cont)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, cont, stack)
                    throw ex
    end ContHandler

    /** A region that masks its tag: every request for it, of either kind, reaches this clause as the request itself re-raised rather than as
      * an input, and no handler or binding it shadows sees it.
      *
      * An arrow operation arrives by the stack lookup. A context read arrives because entering the region masks the tag in the context, so
      * the read dispatches here instead of answering from the binding outside. That is why the clause takes an operation it cannot inspect:
      * the two kinds have nothing in common except being re-raisable.
      */
    abstract class MaskingHandler[E <: Effect, A, B, S] extends ArrowHandler[Unit, E, A, B, S]:

        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

        private[kyo] def answering[X](operation: X < E, next: Arrow[X, A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(operation, next)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, next, stack)
                    throw ex
    end MaskingHandler

    /** The region behind [[kyo.kernel.ArrowEffect.handleLoop]]: the clause is handed the input alone and answers with an outcome.
      *
      * `run` answers at `S` while the value it continues with is at `E & S`, which places the clause outside the region it serves. Only the
      * answer is region currency, which is why `Loop.done` leaves without passing through the region and why an effect the clause performs
      * goes to a handler further out.
      *
      * This region carries no state; [[LoopStateHandler]] is the same shape with state threaded through its `Outcome2`. They are separate
      * classes rather than one with an ignored state so that neither pays for the other's shape.
      */
    abstract class LoopHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B < S] < S

        private[kyo] def running[X](
            input: I[X],
            kyo: Pending.Suspend[?, ?, ?, ?],
            stack: Stack,
            idx: Int
        ): Outcome[O[X] < (E & S), B < S] < S =
            // The clause runs outside the regions between this one and the suspension, so a throw from it is not
            // the body's: the gap pushed before the rethrow has the unwind release this region and those above it
            // without offering the throw to them.
            try run(input)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, stack)
                    stack.hide(idx, Arrow.id)
                    throw ex

        /** Answers one occurrence for a region that is at the top of the stack, applying the continuation to the answer without leaving.
          *
          * This is the fused path, which is why it is separate from [[running]]: the region does not have to be exited and re-entered for an
          * occurrence it can answer in place, so the answer goes straight into `k`. A pending outcome is handed back as it is, its answer
          * unapplied: the gap the evaluator pushes over it applies `k` when the answer arrives.
          *
          * A throwable is turned into a deferred re-raise carried by `Loop.continue` rather than thrown from here, so it reaches the
          * evaluator as an ordinary computation and unwinds through the regions the continuation reinstalls, rather than from wherever this
          * clause happened to run.
          */
        def answers[X](
            input: I[X],
            k: Arrow[O[X], A, E & S],
            armed: Boolean,
            slot: Safepoint.Slot,
            frame: Frame
        ): Outcome[A < (E & S), B < S] < S =
            try
                run(input) match
                    case c: Continue[O[X] < (E & S)] @unchecked =>
                        val ans = c._1
                        ans match
                            case _: Pending[?, ?] =>
                                Loop.continue(ans.map(a => k(a))(using Frame.internal))
                            case _ =>
                                Loop.continue(k(Nested.unnest[O[X]](ans)))
                        end match
                    case o => attachReentryToPending[I, O, E, A, B, S, X](k, o)
                end match
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, k, frame)
                    Loop.continue(Effect.deferInline[A, E & S](throw ex)(using frame))
            end try
        end answers
    end LoopHandler

    /** [[LoopHandler]] with state threaded from one occurrence to the next through the outcome.
      *
      * The state lives in the stack slot for this region, so it survives a suspension without the clause holding it, and the clause reads it
      * as an argument and writes it by answering with it.
      */
    abstract class LoopStateHandler[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[State, E, A, B, S]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

        private[kyo] def running[X](
            state: State,
            input: I[X],
            kyo: Pending.Suspend[?, ?, ?, ?],
            stack: Stack,
            idx: Int
        ): Outcome2[State, O[X] < (E & S), B < S] < S =
            try run(state, input)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, stack)
                    stack.hide(idx, Arrow.id)
                    throw ex

        def answers[X](
            state: State,
            input: I[X],
            k: Arrow[O[X], A, E & S],
            armed: Boolean,
            slot: Safepoint.Slot,
            frame: Frame
        ): Outcome2[State, A < (E & S), B < S] < S =
            var st = state
            try
                run(state, input) match
                    case c: Continue2[State, O[X] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                Loop.continue(st, ans.map(a => k(a))(using Frame.internal))
                            case _ =>
                                Loop.continue(st, k(Nested.unnest[O[X]](ans)))
                        end match
                    case o2 => attachReentryToPending2[State, I, O, E, A, B, S, X](k, o2)
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    EffectTrace.attach(ex, k, frame)
                    Loop.continue(at, Effect.deferInline[A, E & S](throw ex)(using frame))
            end try
        end answers
    end LoopStateHandler

    /** A region that binds a value rather than answering operations.
      *
      * It has no clause: a read finds the region on the stack and takes its state, never reaching this handler. What is here is the
      * value's life instead: how it is derived on entry, what a fork takes and what a join puts back, and the lifecycle hooks below that let
      * a binding own something releasable.
      */
    abstract class ContextHandler[State, E <: ContextEffect[State], A, -S] extends Handler[E, A, S]:
        def derive(outer: Maybe[State]): State
        def fork(parent: State): State
        def join(parent: State, forked: State, child: State): State

        /** The body's value settled with this region on top.
          *
          * `Loop.continue(state, body)` keeps the region installed over more body, with the state it names, which is how
          * a bracket turns the acquire's value into its use under a cell that now owns it; anything else ends the region
          * with that as its value. The value arrives in union representation, and the default ends with it as it is.
          * The row is a parameter bounded by the region's because the outcome holds it invariantly.
          */
        private[kyo] def done[S2 <: S](state: State, value: A < S2): Outcome2[State, A < (E & S2), A < S2] < S2 =
            Loop.settled(value)

        /** The release this region owes for `state`, if any.
          *
          * It goes into the region's entry as the region is pushed and runs when the entry holding it pops, whichever
          * entry that is by then: a dump moves it to the handler that took the continuation, an escaping handler
          * forwards it below.
          */
        private[kyo] def release(state: State): Maybe[Release] = Absent
    end ContextHandler

    /** The effect nothing raises, the tag of [[Gap]]. */
    sealed trait Hidden extends Effect

    /** The entry pushed over a loop clause's own computation, hiding the handler and the regions above it from dispatch.
      *
      * A loop clause answers at the row outside its region, so what it runs must find neither that region nor the regions
      * between it and the suspension, while everything below stays in reach. Rather than taking those entries off the
      * stack, the evaluator pushes this over them, with the number of entries it hides as its state and the suspension's
      * continuation as its own, and [[Stack.find]] steps over what it hides. The outcome settling with the gap on top
      * lifts it: a continue hands the answer to the continuation under the regions the gap hid, and anything else
      * discards through them. An unwind releases what the gap hides without offering the throw to them, since it is not
      * the body's.
      *
      * A pending answer is the same shape one entry narrower: the answer is at the row outside the interior regions but
      * inside the handler's, so a gap over the interior alone hides them while it runs, and [[answered]] turns the value
      * it settles to into the continue the gap dispatches.
      */
    object Gap extends Handler[Hidden, Any, Any]:
        def tag               = Tag[Hidden]
        override def toString = "Gap"
    end Gap

    /** Turns a settled answer into the continue outcome a gap dispatches, so a pending answer evaluated under an answer gap
      * reaches the gap's settled arm the way a clause's own outcome does.
      */
    val answered: Arrow[Any, Outcome[Any, Any], Any] =
        new Arrow.Step[Any, Outcome[Any, Any], Any]:
            def frame = Frame.internal
            override def apply[D, S3](v: Any < S3, cont2: Arrow[Outcome[Any, Any], D, S3]) =
                v match
                    case p: Pending[Any, S3] @unchecked => Effect.defer(p, this, cont2)
                    case _                              => cont2(Loop.continue[Any, Any, Any](v), Arrow.id)

    /** The handler a re-entered region runs under: `outer` with `done` as identity, so the region a resumption
      * re-enters yields the body's value and `outer`'s `done` still runs once, at the outer region's end.
      */
    private[kyo] def reentered[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        outer: ContHandler[I, O, E, A, B, S]
    ): ContHandler[I, O, E, A, A, S] =
        new ContHandler[I, O, E, A, A, S]:
            def tag                                              = outer.tag
            def run[X](input: I[X], next: Arrow[O[X], A, E & S]) = outer.run(input, next)
            def done(state: Unit, v: A)                          = v

    /** Wraps the continuation a clause may resume more than once, so that each application re-enters the region,
      * through [[reentered]].
      *
      * Entering a region stores the loop's registers as that region's continuation, which keeps the clause's own
      * pending work out of what a later occurrence captures. Without that, the continuation captured at a later
      * occurrence carries the enclosing clause's next resumption, and every inner resumption re-triggers it, without
      * bound. A computation handed to the wrapped continuation runs at the clause's level first, as it does for a
      * crossing; only the settled answer re-enters.
      */
    private[kyo] def reentering[I[_], O[_], E <: ArrowEffect[I, O], A, S, X0](
        k: Arrow[O[X0], A, E & S],
        reentered: ContHandler[I, O, E, A, A, S]
    ): Arrow[O[X0], A, E & S] =
        new Arrow.Step[O[X0], A, E & S]:
            def frame = Frame.internal
            override def apply[D, S3](v: O[X0] < S3, cont2: Arrow[A, D, S3]) =
                v match
                    case p: Pending[O[X0], S3] @unchecked => Effect.defer(p, this, cont2)
                    case _ => cont2(Pending.handle[Unit, E, A, A, S](k(Nested.unnest[O[X0]](v)), reentered, ()), Arrow.id)
        end new
    end reentering

    /** Attaches a cont to an outcome whose clause has not settled yet, turning the clause's answer into the
      * region's remaining computation.
      *
      * The caller must pass the cont of the operation whose answer this outcome carries. That is the whole
      * obligation, and it is why the attachment happens here rather than where the outcome arrives: a walk
      * that fuses across a run of operations answers a different one on each turn, and only the walk knows
      * which. Applying it to an outcome that already carries a cont would apply two.
      *
      * The cont is applied where the outcome settles, under the gap that hides the region while the clause's
      * computation runs, so a throw from the body's first steps is deferred rather than thrown here: it then
      * unwinds through the region and what stands above it once the gap lifts, as the body's own failure.
      */
    private[kyo] def attachReentry[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S]
    ): Arrow[Outcome[O[X0] < (E & S), B < S], Outcome[A < (E & S), B < S], S] =
        type In  = Outcome[O[X0] < (E & S), B < S]
        type Out = Outcome[A < (E & S), B < S]
        new Arrow.Step[In, Out, S]:
            def frame = Frame.internal
            override def apply[D, S3](out: In < S3, cont2: Arrow[Out, D, S3]) =
                out match
                    case p: Pending[In, S3] @unchecked =>
                        Effect.defer(p, this, cont2)
                    case out: Continue[O[X0] < (E & S)] @unchecked =>
                        cont2(Loop.continue[A < (E & S), B < S, S](applying(reentry, out._1)))
                    case out =>
                        cont2(out.asInstanceOf[Out < S])
        end new
    end attachReentry

    // The body's next step runs as the cont is applied to a settled answer, still under the gap: a throw there is
    // the body's, so it is deferred and thrown once the gap has lifted and the regions are back in reach.
    private def applying[O, A, S](reentry: Arrow[O, A, S], ans: O < S): A < S =
        try reentry(ans)
        catch
            case ex if !kyo.IsFatal(ex) => Effect.deferInline[A, S](throw ex)(using Frame.internal)

    /** [[attachReentry]] for an outcome that may have settled already: a settled one passes through unchanged, a
      * pending one gets the cont attached.
      *
      * The caller has answered a settled `Continue` in place before reaching this, so a settled outcome arriving
      * here carries no answer to re-enter with; passing it through is that fact, and it is what lets the settled
      * path skip building the arrow. `inline` so the fused walks expand it as the branch they carry today.
      */
    private[kyo] inline def attachReentryToPending[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S],
        outcome: Outcome[O[X0] < (E & S), B < S] < S
    ): Outcome[A < (E & S), B < S] < S =
        if outcome.isInstanceOf[Pending[?, ?]] then attachReentry[I, O, E, A, B, S, X0](reentry)(outcome)
        else outcome.asInstanceOf[Outcome[A < (E & S), B < S] < S]

    /** [[attachReentry]] for a region that carries its state through the outcome. */
    private[kyo] def attachReentry2[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S]
    ): Arrow[Outcome2[State, O[X0] < (E & S), B < S], Outcome2[State, A < (E & S), B < S], S] =
        type In  = Outcome2[State, O[X0] < (E & S), B < S]
        type Out = Outcome2[State, A < (E & S), B < S]
        new Arrow.Step[In, Out, S]:
            def frame = Frame.internal
            override def apply[D, S3](out: In < S3, cont2: Arrow[Out, D, S3]) =
                out match
                    case p: Pending[In, S3] @unchecked =>
                        Effect.defer(p, this, cont2)
                    case out: Continue2[State, O[X0] < (E & S)] @unchecked =>
                        cont2(Loop.continue[State, A < (E & S), B < S](out._1, applying(reentry, out._2)))
                    case out =>
                        cont2(out.asInstanceOf[Out < S])
        end new
    end attachReentry2

    /** [[attachReentryToPending]] for a region that carries its state through the outcome. */
    private[kyo] inline def attachReentryToPending2[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S],
        outcome: Outcome2[State, O[X0] < (E & S), B < S] < S
    ): Outcome2[State, A < (E & S), B < S] < S =
        if outcome.isInstanceOf[Pending[?, ?]] then attachReentry2[State, I, O, E, A, B, S, X0](reentry)(outcome)
        else outcome.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]

    private[kyo] inline def answersLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => I[X] => Outcome[O[X] < (E & S), B < S] < S,
        _frame: Frame,
        input0: I[C],
        k0: Arrow[O[C], A, E & S],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome[A < (E & S), B < S] < S =
        // The walk fuses across operations of different types, so the input and cont in flight are erased.
        var in: Any                                 = input0
        var k: Arrow[Any, Any, Any]                 = k0.asInstanceOf[Arrow[Any, Any, Any]]
        var result: Outcome[A < (E & S), B < S] < S = null.asInstanceOf[Outcome[A < (E & S), B < S] < S]
        var running                                 = true
        while running do
            try
                handle[C](in.asInstanceOf[I[C]]) match
                    case c: Continue[O[C] < (E & S)] @unchecked =>
                        val ans = c._1
                        ans match
                            case _: Pending[?, ?] =>
                                result = Loop.continue(ans.map(a => k(a))(using _frame).asInstanceOf[A < (E & S)])
                                running = false
                            case _ =>
                                val next = k(Nested.unnest[Any](ans))
                                if armed && Safepoint.stopped(slot) then
                                    result = Loop.continue(Effect.defer(next, Arrow.id[Any]).asInstanceOf[A < (E & S)])
                                    running = false
                                else
                                    next match
                                        case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                            if sN.tag.erased =:= effectTag.erased =>
                                            in = sN.input
                                            k = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        case dN: Pending.Defer[Any, Any, Any, Any] @unchecked =>
                                            val v0      = dN.value
                                            var matched = false
                                            v0 match
                                                case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                                    if sN.tag.erased =:= effectTag.erased && dN.contB.isInstanceOf[Arrow.Id[?]] =>
                                                    val sc = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                    val ca = dN.contA.asInstanceOf[Arrow[Any, Any, Any]]
                                                    if sc.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = ca
                                                        matched = true
                                                    else if ca.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = sc
                                                        matched = true
                                                    end if
                                                case _ => ()
                                            end match
                                            if !matched then
                                                result = Loop.continue(Effect.defer(v0, dN.contA, dN.contB).asInstanceOf[A < (E & S)])
                                                running = false
                                        case _ =>
                                            result = Loop.continue(next.asInstanceOf[A < (E & S)])
                                            running = false
                                    end match
                                end if
                        end match
                    case o =>
                        result = attachReentryToPending[I, O, E, A, B, S, C](k.asInstanceOf[Arrow[O[C], A, E & S]], o)
                        running = false
                end match
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, k, _frame)
                    result = Loop.continue(Effect.deferInline[A, E & S](throw ex)(using _frame))
                    running = false
        end while
        result
    end answersLoop

    private[kyo] inline def answersLoopState[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => (State, I[X]) => Outcome2[State, O[X] < (E & S), B < (S)] < S,
        _frame: Frame,
        state0: State,
        input0: I[C],
        k0: Arrow[O[C], A, E & S],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome2[State, A < (E & S), B < S] < S =
        // The walk fuses across operations of different types, so the input and cont in flight are erased.
        var st: State                                       = state0
        var in: Any                                         = input0
        var k: Arrow[Any, Any, Any]                         = k0.asInstanceOf[Arrow[Any, Any, Any]]
        var result: Outcome2[State, A < (E & S), B < S] < S = null.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]
        var running                                         = true
        while running do
            try
                handle[C](st, in.asInstanceOf[I[C]]) match
                    case c: Continue2[State, O[C] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                result = Loop.continue(st, ans.map(a => k(a))(using _frame).asInstanceOf[A < (E & S)])
                                running = false
                            case _ =>
                                val next = k(Nested.unnest[Any](ans))
                                if armed && Safepoint.stopped(slot) then
                                    result = Loop.continue(st, Effect.defer(next, Arrow.id[Any]).asInstanceOf[A < (E & S)])
                                    running = false
                                else
                                    next match
                                        case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                            if sN.tag.erased =:= effectTag.erased =>
                                            in = sN.input
                                            k = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        case dN: Pending.Defer[Any, Any, Any, Any] @unchecked =>
                                            val v0      = dN.value
                                            var matched = false
                                            v0 match
                                                case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                                    if sN.tag.erased =:= effectTag.erased && dN.contB.isInstanceOf[Arrow.Id[?]] =>
                                                    val sc = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                    val ca = dN.contA.asInstanceOf[Arrow[Any, Any, Any]]
                                                    if sc.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = ca
                                                        matched = true
                                                    else if ca.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = sc
                                                        matched = true
                                                    end if
                                                case _ => ()
                                            end match
                                            if !matched then
                                                result = Loop.continue(st, Effect.defer(v0, dN.contA, dN.contB).asInstanceOf[A < (E & S)])
                                                running = false
                                        case _ =>
                                            result = Loop.continue(st, next.asInstanceOf[A < (E & S)])
                                            running = false
                                    end match
                                end if
                        end match
                    case o2 =>
                        result = attachReentryToPending2[State, I, O, E, A, B, S, C](k.asInstanceOf[Arrow[O[C], A, E & S]], o2)
                        running = false
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    EffectTrace.attach(ex, k, _frame)
                    result = Loop.continue(at, Effect.deferInline[A, E & S](throw ex)(using _frame))
                    running = false
        end while
        result
    end answersLoopState

end Handler
