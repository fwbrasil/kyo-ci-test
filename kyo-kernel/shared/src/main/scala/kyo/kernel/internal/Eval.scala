package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.IsFatal
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Tag
import kyo.bug
import kyo.discard
import kyo.kernel.<
import kyo.kernel.Arrow
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import kyo.kernel.Loop
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import language.implicitConversions
import scala.annotation.publicInBinary
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/** The evaluator: unfolds a computation's nodes until it produces a value, or until there is nothing further it can do without an answer.
  *
  * A computation is a value built by composition, and this is an accelerator for running such values, not the definition of what they mean.
  * Every branch below is the operational reading of an equation expressible in the public combinators, which is why a gap here is a missing
  * value rather than a missing instruction.
  *
  * `loop` is the whole machine. It carries the node in hand and two continuations, and each arm either reduces the node and loops, or pushes
  * a region, or hands an answer to a handler. It is a tail-recursive loop rather than a recursive walk, which is where stack safety comes
  * from: depth in the computation costs heap, not call frames.
  *
  * One thing lives beside it. [[Stack]] holds the regions installed around the node in hand, bindings included: a context read finds
  * the innermost region of its tag the way an operation finds its handler, so a binding is visible exactly while its region is installed.
  * The stack is mutable and borrowed for one evaluation.
  *
  * The loop is far too large to inline and every effect in the program passes through it, so its dispatch is megamorphic. That is the reason
  * the combinators fuse at their own call sites and reach the loop only when they must, and the reason cold work here is kept out of line
  * rather than written into the arms.
  */
@publicInBinary private[kyo] object Eval:

    /** Evaluates until a value is produced, with no preemption. */
    def apply[A, S](v: A < S): A < S =
        apply(v, armed = false)

    /** Evaluates until a value is produced or the safepoint says to stop, answering what is left as a computation to resume later.
      *
      * This is what a scheduler runs a fiber with: the stop flag is what turns a run into a slice, and the remainder that comes back is a
      * complete value, valid anywhere, so another thread may pick it up.
      */
    def partial[A](v: A < Any): A < Any =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            try apply(v, armed = true)
            finally discard(Safepoint.consumeStopped(slot))
        end if
    end partial

    // `armed` is a parameter rather than a test inside the loop: it is constant for the whole evaluation, so the
    // stop check folds away entirely for a run that cannot be preempted.
    private def apply[A, S](v: A < S, armed: Boolean): A < S =
        // one stack and one safepoint slot per evaluation
        val stack = Stack.borrow()
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        if armed then Safepoint.arm(slot)

        // A clause's answer the stop is not honored on: a parked slice, which is a crossing delivering the answer
        // under the regions it crosses into and polls on its own inside. A re-raised operation does not, so the
        // stop parks in front of it.
        inline def crossing(result: Any): Boolean = result.isInstanceOf[Pending.Park[?, ?]]

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                // a deferral: unfold it, its two continuations going in front of ours
                case kyo: Pending.Defer[?, ?, T, S2] @unchecked =>
                    if armed && Safepoint.stopped(slot) then park(v, contA, contB)
                    else loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)))

                case kyo: Pending.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
                        // a context read: answered by the innermost binding of its tag, straight from the stack
                        case kyo: Pending.SuspendContext[VX, CX, T, CX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx >= 0 && stack.handler(idx).isInstanceOf[Handler.MaskingHandler[?, ?, ?, ?]] then
                                // A masking region shadows the binding this read would have answered from, so it
                                // dispatches there by the route an arrow operation takes.
                                val entries = if idx == stack.depth - 1 then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                val result  = maskedRead(kyo, entries, contA.chain(contB))
                                if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                else loop(result, Arrow.id, Arrow.id)
                            else
                                // Erasure-forced: the binding's state is stored at the storage boundary's type.
                                val state =
                                    if idx >= 0 then stack.state(idx).asInstanceOf[VX]
                                    else kyo.default.getOrElse(unhandled(kyo, stack))
                                Debugger.onContext(kyo, state)
                                loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id)
                            end if

                        // an operation: the innermost region for its tag answers, by the shape of its handler
                        case kyo: Pending.SuspendArrow[IX, OX, EX, VX, T, EX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx < 0 then
                                unhandled(kyo, stack)
                            else
                                Debugger.onHandle(kyo, stack.handler(idx), stack.state(idx))
                                val atTop = idx == stack.depth - 1
                                if !atTop then Debugger.onForeign(kyo, stack.handler(stack.depth - 1))
                                stack.handler(idx) match
                                    // a cont clause: handed the continuation, with the regions above dumped into it
                                    case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.input, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        // The stop is honored on the clause's answer: one that re-raises the
                                        // operation would otherwise dispatch straight back here with no deferral to
                                        // park at. A crossing is entered instead; see `crossing`.
                                        if armed && Safepoint.stopped(slot) && !crossing(result) then
                                            park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id)
                                    // a masking clause: the same, handed the operation re-raised instead of its input
                                    case handler: Handler.MaskingHandler[EX, C, Y, S2] @unchecked =>
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.reraise, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        if armed && Safepoint.stopped(slot) && !crossing(result) then
                                            park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id)
                                    // a loop clause at the top: answered in place, the region staying installed
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            // continued: the answer carries on inside the region
                                            case e: Loop.Continue[C < (EX & S2)] @unchecked =>
                                                loop(e._1, Arrow.id, Arrow.id)
                                            // suspended: the clause's own computation runs under a gap hiding the region
                                            case pending: Pending[Outcome[C < (EX & S2), Y < S2], S2] @unchecked =>
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                stack.hide(idx)
                                                loop[OutT, OutT, OutT, S2](pending, Arrow.id, Arrow.id)
                                            // done: the region ends with its value
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome[Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                arrowExit(handler)
                                                loop(result, next, Arrow.id)
                                        end match
                                    // a loop clause with regions above it: the same three outcomes, the answer flowing into the
                                    // body under those regions
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.running(kyo.input, kyo, stack, idx)
                                        // Load-bearing despite nothing reading it: see `Stack.sink`.
                                        stack.sink = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue[OX[VX] < (EX & S2)] @unchecked =>
                                                val ans = outcome._1
                                                // A stop landing as the clause answers with a computation parks in front of
                                                // it, with every region in place: a boundary re-raising a pending join asks
                                                // for exactly this.
                                                if armed && ans.isInstanceOf[Pending[?, ?]] && Safepoint.stopped(slot) then
                                                    park(ans, kyo.cont, contA.chain(contB))
                                                else loop(ans, kyo.cont, contA.chain(contB))
                                            case pending: Pending[Outcome[OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                val k        = kyo.cont.chain(contA.chain(contB))
                                                val answered = Handler.attachReentry[IX, OX, EX, C, Y, S2, VX](k)(pending)
                                                stack.hide(idx)
                                                loop[OutT, OutT, OutT, S2](answered, Arrow.id, Arrow.id)
                                            case outcome =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome[
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                discarded(idx, handler)
                                                loop(result, next, Arrow.id)
                                        end match
                                    // the stateful loop clause at the top: as the loop clause, with the state in the region's slot
                                    case handler: Handler.LoopStateHandler[VX, IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(stack.state(idx).asInstanceOf[VX], kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            case e: Loop.Continue2[VX, C < (EX & S2)] @unchecked =>
                                                stack.setState(idx, e._1)
                                                loop(e._2, Arrow.id, Arrow.id)
                                            case pending: Pending[Outcome2[VX, C < (EX & S2), Y < S2], S2] @unchecked =>
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                stack.hide(idx)
                                                loop[OutT, OutT, OutT, S2](pending, Arrow.id, Arrow.id)
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome2[VX, Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                arrowExit(handler)
                                                loop(result, next, Arrow.id)
                                        end match
                                    // the stateful loop clause with regions above it: as the loop clause, with the state
                                    case handler: Handler.LoopStateHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.running(stack.state(idx).asInstanceOf[VX], kyo.input, kyo, stack, idx)
                                        // Load-bearing despite nothing reading it: see `Stack.sink`.
                                        stack.sink = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                stack.setState(idx, outcome._1)
                                                val ans = outcome._2
                                                if armed && ans.isInstanceOf[Pending[?, ?]] && Safepoint.stopped(slot) then
                                                    park(ans, kyo.cont, contA.chain(contB))
                                                else loop(ans, kyo.cont, contA.chain(contB))
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                val k        = kyo.cont.chain(contA.chain(contB))
                                                val answered = Handler.attachReentry2[VX, IX, OX, EX, C, Y, S2, VX](k)(pending)
                                                stack.hide(idx)
                                                loop[OutT, OutT, OutT, S2](answered, Arrow.id, Arrow.id)
                                            case outcome =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome2[
                                                        VX,
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                discarded(idx, handler)
                                                loop(result, next, Arrow.id)
                                        end match
                                    case handler =>
                                        unanswerable(handler)
                                end match
                            end if

                // entering a region: push it, then run what is inside
                case kyo: Pending.HandleArrow[?, ?, ?, ?, T, S2] @unchecked =>
                    Debugger.onRegionEnter(kyo.handler, kyo.state)
                    stack.push(kyo.handler, kyo.state, kyo.cont.chain(contA.chain(contB)))
                    loop(kyo.value, Arrow.id, Arrow.id)

                // entering a binding: derive its value from the enclosing one on the stack, push it
                case kyo: Pending.HandleContext[VX, CX, T, S2] @unchecked =>
                    val handler  = kyo.handler
                    val outerIdx = stack.find(handler.tag)
                    // a masking region found first shadows whatever is bound outside it
                    val outer =
                        if outerIdx >= 0 && !stack.handler(outerIdx).isInstanceOf[Handler.MaskingHandler[?, ?, ?, ?]] then
                            Maybe(stack.state(outerIdx).asInstanceOf[VX])
                        else Maybe.empty
                    val newState = handler.derive(outer)
                    Debugger.onContext(kyo, newState)
                    Debugger.onRegionEnter(handler, newState)
                    stack.push(handler, newState, contA.chain(contB))
                    // what the region owes goes into its own entry, to run when whatever entry holds it by then pops
                    handler.release(newState) match
                        case Present(r) => stack.owe(stack.depth - 1, r)
                        case Absent     => ()
                    loop(kyo.value, Arrow.id, Arrow.id)

                // a parked slice with nothing to reinstall: take on what it holds and continue in place
                case kyo: Pending.Park[?, ?] if kyo.entries.isEmpty =>
                    stack.oweBelow(stack.depth, kyo.releases)
                    loop(kyo.value.asInstanceOf[T < S2], contA, contB)

                // a parked slice: reinstall the regions it carries, then continue inside them
                case kyo: Pending.Park[?, ?] =>
                    installed(kyo, contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]])
                    loop(kyo.value, Arrow.id, Arrow.id)

                case kyo: Pending.Snapshot[T, S2] @unchecked =>
                    loop(kyo.cont(stack, contA.chain(contB)), Arrow.id, Arrow.id)

                // a settled value
                case res =>
                    // nothing composed after it: it is the result of the region on top, or of the whole evaluation
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then
                            res.asInstanceOf[A < S]
                        else
                            val top  = stack.depth - 1
                            val next = stack.continuation(top).asInstanceOf[Arrow[Y, Any, Any]]
                            stack.handler(top) match
                                case hc: Handler.ContextHandler[VX, CX, AX, ?] @unchecked =>
                                    val state   = stack.state(top).asInstanceOf[VX]
                                    val outcome = hc.done(state, res.asInstanceOf[AX < Any])
                                    outcome match
                                        // continued: the region stays installed over more body, with the state it names
                                        // and whatever that state owes
                                        case c: Loop.Continue2[VX, AX < Any] @unchecked =>
                                            Debugger.onResult(c)
                                            if c._1.asInstanceOf[AnyRef] ne state.asInstanceOf[AnyRef] then
                                                stack.setState(top, c._1)
                                                hc.release(c._1) match
                                                    case Present(r) => stack.owe(top, r)
                                                    case Absent     => ()
                                            end if
                                            loop(c._2, Arrow.id, Arrow.id)
                                        case _ =>
                                            val result = Loop.unnest(outcome.asInstanceOf[Outcome2[VX, Any, AX < Any]])
                                            Debugger.onRegionExit(hc, result)
                                            contextExit(top)
                                            loop(result.asInstanceOf[Y < Any], next, Arrow.id)
                                    end match
                                // a gap: the outcome of a loop clause's own computation, the regions it hid still in place
                                case _: Handler.Gap.type =>
                                    val h = top - stack.hidden(top)
                                    res match
                                        case c: Loop.Continue[Y < Any] @unchecked =>
                                            lifted(top, h)
                                            loop(c._1, Arrow.id, Arrow.id)
                                        case c: Loop.Continue2[Any, Y < Any] @unchecked =>
                                            stack.setState(h, c._1)
                                            lifted(top, h)
                                            loop(c._2, Arrow.id, Arrow.id)
                                        case done =>
                                            val handler = stack.handler(h).asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                            val result  = Nested.unnest[Y < Any](Loop.unnest(done.asInstanceOf[Outcome[Any, Y < Any]]))
                                            Debugger.onRegionExit(handler, result)
                                            val after = stack.continuation(h).asInstanceOf[Arrow[Y, Any, Any]]
                                            lifted(top, h)
                                            discarded(h, handler)
                                            loop(result, after, Arrow.id)
                                    end match
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                    val result  = handler.done(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
                                    Debugger.onRegionExit(handler, result)
                                    arrowExit(handler)
                                    loop(result, next, Arrow.id)
                            end match
                    else
                        // something composed after it: apply the head, keep the tail
                        contA match
                            case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB))
                            case _ =>
                                loop(contA(res, contB), Arrow.id, Arrow.id)
            end match
        end loop

        /** Stops the evaluation and answers what is left as a value that can be resumed anywhere.
          *
          * The node in hand and its two continuations fold back into one computation, and every region still installed comes with it as a
          * snapshot, so resuming reinstalls exactly what was here. An empty stack with no debt is the cheap case: the remainder is the
          * computation itself, with no `Park` node built for it.
          */
        def park[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val parked: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then v.asInstanceOf[Any < Any]
                else Effect.defer(v, contA, contB).asInstanceOf[Any < Any]
            val held = stack.takeEvalReleases()
            if stack.isEmpty then
                if held eq null then parked.asInstanceOf[A < S]
                else Pending.Park[A, S](parked, Stack.Snapshot.empty, held)
            else
                Debugger.whenEnabled {
                    var j = stack.depth - 1
                    while j >= 0 do
                        Debugger.onRegionExit(stack.handler(j), parked)
                        j -= 1
                }
                Pending.Park[A, S](parked, stack.takeAll(), held)
            end if
        end park

        // Out of line to keep `loop` small. Dumping the regions between pops the stack down to the masking
        // region, so it is at the top by the time this looks for it.
        def maskedRead[VX2, CX2 <: ContextEffect[VX2], T2, Y, S3](
            kyo: Pending.SuspendContext[VX2, CX2, T2, CX2 & S3],
            entries: Stack.Snapshot,
            resume: Arrow[T2, Y, S3]
        ): Y < (CX2 & S3) =
            val handler = stack.handler(stack.depth - 1).asInstanceOf[Handler.MaskingHandler[CX2, Y, Any, S3]]
            val continuation =
                if entries.isEmpty then kyo.cont.chain(resume)
                else kyo.crossing(entries, resume)
            val result = handler.answering(kyo.reraise, continuation, kyo, stack)
            Debugger.onResult(result)
            result
        end maskedRead

        def installed(kyo: Pending.Park[?, ?], resume: Arrow[Any, Any, Any]): Unit =
            val entries = kyo.entries
            // A region whose release ran refuses to be reinstalled, with its own explanation; what the park holds
            // is released before the refusal propagates.
            var ri = 0
            while ri < entries.regions do
                entries.handler(ri) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        hc.release(entries.state(ri).asInstanceOf[VX]) match
                            case Present(r) =>
                                try r.reenter()
                                catch
                                    case ex if !IsFatal(ex) =>
                                        release(kyo, ex)
                                        throw ex
                                end try
                            case Absent => ()
                        end match
                    case _ => ()
                end match
                ri += 1
            end while
            stack.oweBelow(stack.depth, kyo.releases)

            @tailrec def install(i: Int): Unit =
                if i < entries.regions then
                    val stored = entries.continuation(i).asInstanceOf[Arrow[Y, Any, Any]]
                    val cont =
                        if i == 0 then stored.chain(resume)
                        else stored
                    val handler = entries.handler(i).asInstanceOf[Handler[EX, Y, Any]]
                    val st      = entries.state(i)
                    Debugger.onRegionEnter(handler, st)
                    stack.push(handler, st, cont)
                    stack.oweAll(stack.depth - 1, entries.releases(i))
                    install(i + 1)
            install(0)
        end installed

        // Pops the entry at `top` and runs what it held, told how the entry ended.
        def popped(top: Int, outcome: Maybe[Throwable]): Unit =
            val held = stack.takeReleases(top)
            stack.pop()
            released(held, outcome)
        end popped

        // Pops the entry at `top` for an unwind: what it held is told the failure, and so is the region's own
        // release when a dump moved it elsewhere, since the failure is the extent's whichever entry holds it.
        def unwound(top: Int, ex: Throwable): Unit =
            val own =
                stack.handler(top) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked => hc.release(stack.state(top).asInstanceOf[VX])
                    case _                                                   => Absent
            popped(top, Present(ex))
            own match
                case Present(r) => releasing(r, Present(ex))
                case Absent     => ()
        end unwound

        def contextExit(top: Int): Unit = popped(top, Absent)

        // An escaping region has not finished with what it holds: it moves to the entry below, to run when that one ends.
        def arrowExit(handler: Handler.ArrowHandler[?, ?, ?, ?, ?]): Unit =
            val top = stack.depth - 1
            if handler.escaping then
                val held = stack.takeReleases(top)
                stack.pop()
                stack.oweBelow(stack.depth, held)
            else popped(top, Absent)
            end if
        end arrowExit

        // Lifts the gap at `top`: what escaped to it goes below the region it hid, which is back in dispatch by the pop alone.
        def lifted(top: Int, h: Int): Unit =
            val held = stack.takeReleases(top)
            stack.pop()
            stack.oweBelow(h, held)
        end lifted

        // Ends the region at `h` from its clause: the regions above it are discarded, their extents never having ended,
        // which is what their releases are told, and then the region itself.
        def discarded(h: Int, handler: Handler.ArrowHandler[?, ?, ?, ?, ?]): Unit =
            while stack.depth - 1 > h do
                Debugger.onRegionExit(stack.handler(stack.depth - 1), handler)
                popped(stack.depth - 1, Absent)
            arrowExit(handler)
        end discarded

        /** Unwinds the stack for a throwable, offering it to each region's recover arm from the innermost outward.
          *
          * A region that answers stops the unwind and the evaluation continues from there. One that declines is popped, discharging what it
          * owes and releasing its state, and the throwable carries on outward. Reaching an empty stack re-raises it with the effect trace
          * spliced in.
          *
          * A context region has no recover arm, so it is always popped. A fatal throwable is offered to nothing: every region is unwound and
          * released, and it propagates.
          */
        @tailrec def recovered(ex: Throwable): A < S =
            if stack.isEmpty then
                released(stack.takeEvalReleases(), Present(ex))
                EffectTrace.splice(ex)
                throw ex
            else
                val top   = stack.depth - 1
                val state = stack.state(top)
                stack.handler(top) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        Debugger.onRegionExit(hc, ex)
                        unwound(top, ex)
                        recovered(ex)
                    // a gap: the throw came from a loop clause's own computation, outside the regions the gap hides,
                    // which are released with it and not offered it, since it is not the body's
                    case _: Handler.Gap.type =>
                        var n = stack.hidden(top)
                        popped(top, Present(ex))
                        while n > 0 do
                            Debugger.onRegionExit(stack.handler(stack.depth - 1), ex)
                            unwound(stack.depth - 1, ex)
                            n -= 1
                        end while
                        recovered(ex)
                    case handler0 =>
                        val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                        val outcome =
                            try if IsFatal(ex) then Absent else handler.recover(state.asInstanceOf[VX], ex)
                            catch
                                case ex2 if !IsFatal(ex2) =>
                                    Debugger.onRegionExit(handler, ex2)
                                    popped(top, Present(ex2))
                                    EffectTrace.attach(ex2, stack)
                                    EffectTrace.splice(ex2)
                                    return recovered(ex2)
                        outcome match
                            case Present(r) =>
                                Debugger.onRecover(handler, ex)
                                Debugger.onRegionExit(handler, r)
                                stack.continuation(top).asInstanceOf[Arrow[Y, A, S]](r)
                            case Absent =>
                                Debugger.onRegionExit(handler, ex)
                                popped(top, Present(ex))
                                recovered(ex)
                        end match
                end match

        /** Runs the loop and catches what it throws, so a recover arm can resume the evaluation rather than only observe the failure.
          *
          * A region that recovers answers with a computation, which has to be evaluated from a loop that is itself still guarded, hence the
          * re-entry here rather than a return into the loop that just unwound.
          */
        @tailrec def guarded(curr: A < S): A < S =
            val res =
                try loop(curr, Arrow.id, Arrow.id)
                catch
                    case failure =>

                        Safepoint.reset(slot)

                        EffectTrace.attach(failure, stack)
                        EffectTrace.splice(failure)
                        val resumed = recovered(failure)
                        popped(stack.depth - 1, Present(failure))
                        return guarded(resumed)
            res match
                case susp: Pending.Suspend[?, ?, ?, ?] =>

                    bug(s"unhandled suspension: $susp")
                case res => res
            end match
        end guarded

        try
            val out = guarded(v)
            released(stack.takeEvalReleases(), Absent)
            out
        finally
            Safepoint.restore(slot, saved)
            Stack.release(stack)
        end try
    end apply

    private def unhandled(kyo: Pending[?, ?], stack: Stack): Nothing =
        try bug(s"unhandled suspension: $kyo")
        catch
            case ex =>
                EffectTrace.attach(ex, kyo, stack)
                throw ex

    private def unanswerable(handler: Handler[?, ?, ?]): Nothing = bug(s"unhandled: $handler")

    private[kernel] def dumped(stack: Stack, idx: Int, kyo: Pending.Suspend[?, ?, ?, ?]): Stack.Snapshot =
        val entries = stack.dump(idx + 1)
        Debugger.whenEnabled {
            var i = entries.regions - 1
            while i >= 0 do
                Debugger.onRegionExit(entries.handler(i), kyo)
                i -= 1
        }
        entries
    end dumped

    /** Runs what an entry held, last added first, telling each release how the entry ended.
      *
      * A release that throws at a normal end is reported, since nothing is unwinding to carry it; at an unwind or an abandonment the throw
      * is attached to the outcome as suppressed.
      */
    private[kernel] def released(held: Stack.Releases, outcome: Maybe[Throwable]): Unit =
        if held ne null then
            held match
                case r: Release => releasing(r, outcome)
                case c: Chunk[Release] @unchecked =>
                    val indexed = c.toIndexed
                    var i       = indexed.length - 1
                    while i >= 0 do
                        releasing(indexed(i), outcome)
                        i -= 1
            end match

    private def releasing(release: Release, outcome: Maybe[Throwable]): Unit =
        if !release.ran then
            Debugger.onRelease(release, outcome)
            try release(outcome)
            catch
                case t if !IsFatal(t) =>
                    outcome match
                        case Present(ex) => if ex ne t then ex.addSuppressed(t)
                        case Absent      => Report.unhandled(t)
            end try
    end releasing

    /** Releases what `v` still holds, running nothing.
      *
      * What a remainder holds is in the parks in it: the regions installed when the slice stopped, each with its own release and what
      * its entry held for others, and what the evaluation held below them. A region node not yet entered owns what its derived state
      * owes: nothing for a bracket whose acquire never ran, the release it was handed for an ensuring. A deferral is walked rather than
      * evaluated, so what it holds stays unreached: a caller releasing a cont it has just refused would otherwise run the very thing the
      * refusal exists to stop, and a caller abandoning a computation whole would otherwise run a step of it after the interrupt,
      * acquiring what nothing will release. What a deferral has not run has acquired nothing, so there is nothing under it to release.
      */
    def release[A, S](v: A < S, ex: Throwable): Unit =
        release(v, ex, Absent, _ => ())

    /** Releases what `v` still holds, and hands `f` the input of the first operation under it that `effectTag`
      * answers, so a caller that owes something to an operation the computation stands at can settle it
      * without walking the computation a second time. `f` runs before anything is released, and nothing is
      * delivered to the operation: a fiber links the promise its remainder waits on, and that is all. An
      * operation under a deferral does not exist yet, and neither does anything it would have waited on, so it
      * is not reported: as in [[release]] above, a deferral is walked, not evaluated.
      */
    def release[I[_], O[_], E <: ArrowEffect[I, O], A, S](v: A < S, ex: Throwable, effectTag: Tag[E])(
        f: [C] => I[C] => Unit
    ): Unit =
        // Erasure-forced: the operation's state type is existential here, and `f` takes it back at that type.
        release(v, ex, Present(effectTag.erased), input => f[Any](input.asInstanceOf[I[Any]]))

    private def release[A, S](v: A < S, ex: Throwable, effectTag: Maybe[Tag[Any]], f: Any => Unit): Unit =
        // outermost first as collected, so running it backwards releases innermost first; a region's own release
        // goes in before what its entry held for the regions above it, so those run first
        val held = ArrayBuffer.empty[Stack.Releases]

        def owned(handler: Handler[?, ?, ?], state: Any): Unit =
            handler match
                case hc: Handler.ContextHandler[Any, ?, ?, ?] @unchecked =>
                    hc.release(state) match
                        case Present(r) => held += r
                        case Absent     => ()
                case _ => ()

        @tailrec def collect(v: Any): Unit =
            v match
                case p: Pending[?, ?] =>
                    p match
                        // walked, not run: what a deferral has not run has acquired nothing
                        case kyo: Pending.Defer[?, ?, ?, ?] =>
                            collect(kyo.value)
                        // a region not yet entered owns what its derived state owes
                        case kyo: Pending.HandleContext[VX, CX, ?, ?] @unchecked =>
                            val hc = kyo.handler
                            owned(hc, hc.derive(Maybe.empty))
                            collect(kyo.value)
                        case kyo: Pending.Handle[?, ?, ?, ?] =>
                            collect(kyo.value)
                        case kyo: Pending.Park[?, ?] =>
                            held += kyo.releases
                            val entries = kyo.entries
                            var i       = 0
                            while i < entries.regions do
                                owned(entries.handler(i), entries.state(i))
                                held += entries.releases(i)
                                i += 1
                            end while
                            collect(kyo.value)
                        // the operation the remainder stands at is reported, and left as it stands
                        case kyo: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked =>
                            effectTag.foreach(t => if t <:< kyo.tag.erased then f(kyo.input))
                        case _: Pending.Suspend[?, ?, ?, ?] => ()
                        case _: Pending.Snapshot[?, ?]      => ()
                case _ => ()
        collect(v)
        var i = held.length - 1
        while i >= 0 do
            released(held(i), Present(ex))
            i -= 1
        end while
    end release

    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type VX
    type CX <: ContextEffect[VX]
    type AX
    type Y
    type IY[_]
    type OY[_]
    type EY <: ArrowEffect[IY, OY]
    type VY
end Eval
