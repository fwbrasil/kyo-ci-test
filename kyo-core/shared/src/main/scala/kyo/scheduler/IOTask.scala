package kyo.scheduler

import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Effect
import kyo.kernel.Isolate
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
import kyo.scheduler.IOTask.*
import scala.annotation.tailrec

sealed abstract private[kyo] class IOTask[E, A, S2] extends IOPromise[E, A < S2] with Task:

    /** This fiber's computation, wrapped in the boundary below with nothing composed between the region and
      * the body's first operation. A member rather than a function, so the spawn's captures live on the task
      * instead of a closure beside it.
      */
    protected def prepared: Unit < Any

    /** The remainder of this fiber.
      *
      * Filled by `start`, not here: `prepared` reads subclass fields assigned after this constructor runs.
      * Built once rather than per slice, since the region carries what a handler accumulates.
      */
    private var curr: Unit < Any = cleared

    /** Who owns this task, and whether it is still alive. Five states, never two at once:
      *
      *   - `Idle`: owned by nobody, between slices. `curr` holds what a resumption runs.
      *   - `Thread`: a worker is inside a slice, on that thread. Stops are delivered per thread, so this is
      *     how a preemption or interrupt reaches a slice in flight.
      *   - `IOPromise`: parked on that promise, not to be rescheduled; naming it lets the wakeup be
      *     registered and later unlinked.
      *   - `Result.Error`: interrupted with that error, the remainder not yet released. The promise stays
      *     pending until it is, so a fiber's result is available once its finalizers have run. The error is
      *     the one the interrupt carried, held as it is.
      *   - `Done`: terminal, and what the remainder held has been released.
      *
      * Invariant: `Idle` and the error are the only states another thread may take this task out of; every
      * other transition is made by the owner, and each one out of a slice is a CAS, since an interrupt may
      * have taken the word meanwhile. An interrupt lands under any owner: over `Idle` it takes the task and
      * schedules a run, which claims the error and releases; over the thread or the promise it takes the
      * word and leaves the release to the owner, who is still inside the slice and finds the error at its
      * end. So a run is never scheduled for a slice in flight, and the two claims, out of `Idle` and out of
      * the error, are the only contended ones, which makes a redundant schedule free.
      *
      * `AnyRef` rather than `Status` because the platform handle must name the field's erased type.
      */
    @volatile private var status: AnyRef = Idle

    private def interrupted: Boolean = status.isInstanceOf[Result.Error[?]]

    /** Claims this task for a thread that does not own it yet.
      *
      * An absent handle means a single-threaded runtime, where the read-modify-write is already atomic.
      */
    private def casStatus(curr: Status, next: Status): Boolean =
        IOTaskPlatformSpecific.statusHandle match
            case Absent =>
                (status eq curr) && {
                    status = next
                    true
                }
            case Present(handle) =>
                handle.compareAndSet(this, curr, next)
    end casStatus

    /** The frame of the join a park stopped at, for the unlink the wakeup performs.
      *
      * `run` arms the wakeup but only the boundary holds the join's frame, so the boundary leaves it here.
      * A plain var next to a volatile one: written and read by the same thread within one slice.
      */
    private var joinFrame: Frame = Frame.internal

    /** The fiber boundary: one region answering everything the scheduler is responsible for.
      *
      * `Async.Join` and `Abort` share one entry through a tag that is the union of the two, and every abort
      * reaches it whatever its error type, since each `Abort[E]` is an `Abort[Nothing]`.
      *
      * Here rather than in `Fiber` because none of its decisions are effect interpretation: an abort
      * completes this promise, a ready join resumes in place, a pending one parks this task.
      */
    protected def boundary[P](v: P < (Abort[E] & Async))(complete: P => Unit): Unit < Any =
        // Typed at Unit: a fiber answers with its promise, so every exit completes this task or hands the
        // continuation to something that will.
        //
        // Erasure-forced: constructors are `Any` and the union tag is cast onto the region. A region is
        // contravariant in its input constructor, so one standing under two families with unrelated inputs
        // could only be `Nothing`, leaving the clause holding an uninhabited type. The cast is confined to
        // the tag, so the region answers those two families and nothing else; the match below recovers which
        // one arrived.
        //
        // `Abort[E] & Async` rides in the region's `S` and is dropped from the row after: a row is
        // contravariant, while `Abort[E]` is an `Abort[Nothing]` and `Async` is opaque outside its package.
        ArrowEffect.handleCont[[X] =>> Any, [X] =>> Any, ArrowEffect[[X] =>> Any, [X] =>> Any], P, Unit, Abort[E] & Async, Any](
            Tag[Async.Join & Abort[Any]].asInstanceOf[Tag[ArrowEffect[[X] =>> Any, [X] =>> Any]]],
            v
        )(
            [C] =>
                (input, cont) =>
                    // one clause for two families, discriminated by what the operation carries: an abort's
                    // input is its error, a join's is the thunk that hands over the promise
                    input match
                        case error: Result.Error[E] @unchecked =>
                            // Answering without applying the continuation discards the rest of the
                            // computation, so no stop is needed. The answer is never read: the done lane
                            // below checks whether this task is still pending, and this arm settled it.
                            // An interrupt that landed first owns the ending, so it is not settled here.
                            if !interrupted then completeDiscard(error)
                            null.asInstanceOf[P]
                        case joinInput: Async.JoinInput[C] @unchecked =>
                            // invoking it registers the interrupt cascade on this task before the promise's
                            // state is read, so an interrupt landing in between still reaches what is awaited
                            val promise = joinInput(this)
                            promise.poll() match
                                case null =>
                                    cont(null)
                                case Present(r) =>
                                    // already complete when the thunk ran, so drop the link it pre-registered
                                    // rather than letting it accumulate
                                    removeInterrupt(promise)(using joinInput.frame)
                                    cont(r)
                                case Absent =>
                                    // Waiting. The operation is left unanswered and raised again behind a
                                    // deferral, with a stop requested, so the eval parks in front of it.
                                    // Answering without applying the continuation would tell the region the
                                    // computation is over, draining finalizers a resumption still needs:
                                    // parking is the only exit that carries owed releases with the remainder.
                                    //
                                    // The wakeup is armed by `run`, not here: the remainder does not exist
                                    // until the eval finishes unwinding, and arming early would let a second
                                    // worker restore the same park and re-enter a spent scope.
                                    parkOn(promise, joinInput.frame)
                                    discard(Safepoint.stop(Thread.currentThread(), this))
                                    // Under the join's own frame, carried by the input: a clause is never
                                    // handed the frame of what it answers, and the scheduler's own would
                                    // lose where the fiber stopped.
                                    ArrowEffect.suspendWith[C](using joinInput.frame)(Tag[Async.Join], joinInput)(r => cont(r))
                            end match
                        case other =>
                            bug(s"fiber boundary received an operation it does not answer: $other")
            ,
            // Guarded because the abort arm above settles the task itself and answers with a placeholder, and
            // because an interrupt that landed on this slice owns the ending: the promise completes with it,
            // once the remainder is released, not with what the body produced after it.
            p => if isPending() && !interrupted then complete(p) else ()
            // No call site to name: what a parked fiber reports comes from the operation it stopped at.
        )(using Frame.internal).asInstanceOf[Unit < Any]
    end boundary

    /** Puts the prepared computation in place, once the spawn that built this task is fully constructed. */
    private def install(): Unit =
        curr = prepared

    /** Records that this slice has decided to park, and on what.
      *
      * A method rather than two writes at the site: the site is inside a lambda, and a field a lambda touches
      * is promoted and renamed, while the platform handle finds `status` by name.
      */
    private def parkOn(promise: IOPromise[?, ?], frame: Frame): Unit =
        // A CAS rather than a store: an interrupt that landed on this slice holds the word, and the park is
        // then released at the slice's end rather than armed.
        discard(casStatus(Thread.currentThread(), promise))
        joinFrame = frame
    end parkOn

    private def stopSlice(): Unit =
        status match
            // Addressed to this task: the read and the sentinel landing are two steps, and the slice can end
            // between them. The addressee lets the slot refuse a late delivery; see `Safepoint.stop`.
            case thread: Thread => discard(Safepoint.stop(thread, this))
            case _              => ()
    end stopSlice

    final override def onComplete() =
        doPreempt()
        resetRuntime()
    end onComplete

    final override def doPreempt(): Unit =
        super.doPreempt()
        stopSlice()

    /** Takes an interrupt: the word holds the error until the remainder is released, and the promise stays
      * pending until then, so the result is available once the finalizers have run.
      *
      * Over `Idle` nobody owns the task, so this schedules the run that claims the error and releases. Over
      * the thread the slice is in flight: it is stopped, and its owner finds the error at the slice's end.
      * Over the promise the slice is still unwinding from its park, with a stop already requested, and the
      * same owner finds it. First to land wins; a later one finds the error, or `Done`, through
      * `preInterrupt` and is refused, as it would be by a completed promise.
      */
    final override protected def interrupt(p: IOPromise.Pending[E, A < S2], error: Result.Error[E]): Boolean =
        @tailrec def loop(): Boolean =
            status match
                case _: Idle.type =>
                    (casStatus(Idle, error) && {
                        taken()
                        Scheduler.get.schedule(this)
                        true
                    }) || loop()
                case thread: Thread =>
                    (casStatus(thread, error) && {
                        // Addressed to this task; see `stopSlice`.
                        discard(Safepoint.stop(thread, this))
                        taken()
                        true
                    }) || loop()
                case promise: IOPromise[?, ?] =>
                    (casStatus(promise, error) && {
                        taken()
                        true
                    }) || loop()
                case _ =>
                    false
        loop()
    end interrupt

    // Runs promptly from here: the release is what frees the worker and the finalizers, and the runtime this
    // task accumulated would otherwise deprioritize it.
    private def taken(): Unit =
        Scheduler.get.notifyInterrupt()
        resetRuntime()

    final override def preInterrupt(): Boolean =
        status match
            case _: Result.Error[?] | _: Done.type => false
            case _                                 => true

    final override def needsInterrupt(): Boolean =
        interrupted || !isPending()

    /** Where this fiber currently stands, as one rendered frame, or empty where there is none.
      *
      * A diagnostic read from other threads while this one runs, so it touches only fields already in hand
      * and never anything the evaluator would have run.
      */
    final override def fiberTrace(): String =
        try
            currentFrame(curr) match
                case Present(f) => render(f)
                case Absent     => ""
        catch case _: Throwable => ""

    /** The frame of the operation this fiber stands at, where it stands at one.
      *
      * A deferral's payload is a value rather than a body, so this runs none of the fiber's computation.
      */
    private def currentFrame(v: Unit < Any): Maybe[Frame] =
        @tailrec def loop(x: Any, fuel: Int): Maybe[Frame] =
            if fuel == 0 then Absent
            else
                x match
                    case s: Pending.Suspend[?, ?, ?, ?] =>
                        val f = s.frame
                        if f.eq(Frame.internal) then Absent else Present(f)
                    case h: Pending.Handle[?, ?, ?, ?] => loop(h.value, fuel - 1)
                    case p: Pending.Park[?, ?]         => loop(p.value, fuel - 1)
                    case d: Pending.Defer[?, ?, ?, ?]  =>
                        // the deferral's applying arrow names the site that built it (a `Sync.defer` body's
                        // own file:line); the chained continuation is next, and only then the payload
                        val fa = d.contA.frame
                        if !fa.eq(Frame.internal) then Present(fa)
                        else
                            val fb = d.contB.frame
                            if !fb.eq(Frame.internal) then Present(fb)
                            else loop(d.value, fuel - 1)
                        end if
                    case _ => Absent
        loop(v, 16)
    end currentFrame

    private def render(f: Frame): String =
        val at = StackTraceElement(
            s"${f.snippetShort} @ ${f.className}",
            f.callerName,
            f.position.fileName,
            f.position.lineNumber
        )
        s"at $at"
    end render

    final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        val thread = Thread.currentThread()
        if !casStatus(Idle, thread) then
            // Owned by somebody else, who finishes or releases it, unless the word holds an interrupt taken
            // while the task was idle: this is the run that interrupt scheduled, and it releases.
            release()
            Task.Done
        else if !isPending() then
            // Completed between slices without an interrupt, so no claim was made on its behalf and this
            // one releases the remainder.
            abandon(Absent)
            Task.Done
        else
            val previous = IOTask.current.get()
            IOTask.current.set(this)
            // Records the slice for the stop channel: the slot honors a stop only while this record stands,
            // so a delivery racing the slice boundary cannot stop whatever runs next.
            val slot          = Safepoint.get()
            val previousSlice = Safepoint.beginSlice(slot, this)
            // The slice deadline. On js-wasm it is the preemption source; on jvm-native stops carry
            // preemption and this inlines to nothing.
            Safepoint.deadline(deadline)
            val next =
                try
                    try Eval.partial(curr)
                    finally
                        IOTask.current.set(previous)
                        Safepoint.endSlice(slot, previousSlice)
                catch
                    case ex =>
                        // Completed here because the failure unwound past the boundary. Constructed rather
                        // than through `Result.panic`, which refuses to hold a fatal.
                        completeDiscard(new Result.Panic(ex))
                        curr = cleared
                        if IsFatal(ex) then
                            // A fatal skips the arms below that release ownership, and ownership never given
                            // up is never reclaimed. Nothing is left to release, so mark it terminal.
                            status = Done
                            throw ex
                        end if
                        cleared
            // Every exit from the slice is a CAS out of the state this owner left the word in: one that
            // fails found an interrupt there, and the owner releases on its behalf.
            status match
                case promise: IOPromise[?, ?] =>
                    // `next` is the park, carrying the regions above it and the releases they owe, kept
                    // uncomposed so `abandon` can find it. Order matters: store the remainder, clear the
                    // status, then arm. Arming publishes the task, so everything a resuming worker reads
                    // must already be written.
                    curr = next
                    // Read out before the wakeup closes over it; see `parkOn`.
                    val frame = joinFrame
                    if casStatus(promise, Idle) then
                        promise.onComplete { _ =>
                            removeInterrupt(promise)(using frame)
                            Scheduler.get.schedule(this)
                        }
                        // Completed while this slice unwound, without an interrupt: no run was scheduled on
                        // its behalf, and the wakeup may never come, so claim it here.
                        if !isPending() && casStatus(Idle, Done) then abandon(Absent)
                    else release()
                    end if
                    Task.Done
                case _: Result.Error[?] =>
                    // An interrupt landed on this slice. What the stop left is the remainder, unless the body
                    // ran to its end first, in which case the interrupt still owns the ending.
                    curr = if next.evalNow.isDefined then cleared else next
                    release()
                    Task.Done
                case _ =>
                    // Stored before ownership is released: once idle, another thread may claim this task.
                    if next.evalNow.isDefined then
                        // The boundary completed the fiber on the way here.
                        curr = cleared
                        if !casStatus(thread, Done) then release()
                        Task.Done
                    else
                        curr = next
                        if !isPending() then
                            // Completed mid-slice, and nobody will resume the remainder.
                            abandon(Absent)
                            Task.Done
                        else if casStatus(thread, Idle) then
                            Task.Preempted
                        else
                            release()
                            Task.Done
                        end if
                    end if
            end match
        end if
    end run

    /** Releases on behalf of the interrupt the word holds: claims it, releases the remainder, and completes
      * the promise with it. The claim is what keeps two runs from releasing the same remainder.
      */
    private def release(): Unit =
        status match
            case error: Result.Error[E] @unchecked =>
                if casStatus(error, Done) then abandon(Present(error))
            case _ => ()

    /** Releases what an abandoned remainder still holds, links what it stands waiting on, and completes the
      * promise with the interrupt taken, when one was.
      *
      * A parked computation carries its owed releases rather than running them, and this fiber will not
      * resume, so they are run here. The link comes first: an interrupt arriving as the fiber reached its
      * join can find a remainder standing at one whose promise is not yet tied to this fiber. A join the
      * remainder has not reached is not linked, since nothing under a step that never ran is waited on yet,
      * and the release runs no step of the remainder to find one. The completion comes last: the cascade to
      * what this fiber linked, and every observer of its result, run only once its finalizers have.
      *
      * Only reached by a thread owning the task, so the release happens once. `Done` keeps a later schedule
      * from resuming what was just released.
      */
    private def abandon(interruption: Maybe[Result.Error[E]]): Unit =
        val remainder = curr
        curr = cleared
        status = Done
        if !isNull(remainder) then
            // Invoking the input registers the link, the same call the boundary makes.
            Eval.release(remainder, new KyoException("fiber abandoned")(using Frame.internal), Tag[Async.Join]) {
                [C] => input => discard(input(this))
            }
        end if
        interruption.foreach(error => discard(settleInterrupt(error)))
    end abandon

    // Drops the reference so a finished task does not retain the computation it ran. Never a signal: what a
    // slice produced is said by `evalNow` and `status`.
    private inline def cleared = null.asInstanceOf[Unit < Any]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, status = $status, curr = $curr)"

end IOTask

object IOTask:

    /** The two states of a task's status word that name no thread, no promise and no error.
      *
      * Objects rather than an enum over the whole word: the three carrying states hold a reference that is
      * already allocated, so naming all five as cases would put an allocation on every slice and every
      * interrupt.
      */
    private[scheduler] case object Idle
    private[scheduler] case object Done

    /** Who owns a task, and whether it is still alive. See the field's documentation for the machine. */
    private[scheduler] type Status = Thread | IOPromise[?, ?] | Idle.type | Done.type | Result.Error[?]

    /** Compare-and-set on a task's `status` field, without an atomic wrapper around it.
      *
      * The same shape as `IOPromise.StateHandle` and for the same reason: a boxed atomic would be a second
      * object per fiber, where a field the platform updates in place costs nothing.
      */
    abstract class StatusHandle:
        def compareAndSet(task: IOTask[?, ?, ?], curr: Status, next: Status): Boolean

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    // Install the scheduler's Diagnostics dumper at kyo-core's first touch of the scheduler: this object initializes when the first
    // fiber task is created, so a leaf that later hangs has the scheduler's live worker state in its Diagnostics.dumpAll() instead of blank.
    SchedulerDiagnostics.init()

    /** The fiber running on this thread, or null where none is.
      *
      * The boundary is built before the fiber exists, so it asks here rather than closing over one, and a
      * spawning fiber reads it to link its children.
      */
    private[kyo] val current: ThreadLocal[IOTask[?, ?, ?]] = new ThreadLocal[IOTask[?, ?, ?]]

    private[kyo] def currentTask(): Maybe[IOTask[?, ?, ?]] = Maybe(current.get())

    /** When `parent` is present it is linked to interrupt the new task BEFORE the task is scheduled, which
      * closes the window where a parent interrupted while children are still launching orphans one that
      * started but was not yet registered. The caller reads the parent once and passes it, so this does not
      * read the thread local per child. Detached creators pass `Absent`.
      */
    def apply[E, A, S, S2](isolate: Isolate[S, Abort[E] & Async, S2])(
        state: isolate.State,
        body: A < (Abort[E] & Async & S),
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A, S2] =
        start(
            new IOTask[E, A, S2]:
                protected def prepared =
                    boundary(isolate.isolate(state, body))(t => completeDiscard(Result.succeed(isolate.restore(t))))
            ,
            parent,
            runtime
        )

    /** Spawns a fiber detached from its caller, crossing nothing: what it hands over carries no effects of
      * its own, so there is no state to capture and nothing to restore.
      */
    def detached[E, A](
        body: A < (Abort[E] & Async),
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A, Any] =
        start(
            new IOTask[E, A, Any]:
                protected def prepared = boundary(body)(a => completeDiscard(Result.succeed(a)))
            ,
            parent,
            runtime
        )

    private def start[E, A, S2](task: IOTask[E, A, S2], parent: Maybe[IOPromise[?, ?]], runtime: Int): IOTask[E, A, S2] =
        // after the subclass is constructed, so `prepared` reads fields that are assigned
        task.install()
        task.addRuntime(runtime)
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end start

end IOTask
