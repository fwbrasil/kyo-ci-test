package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Absent
import kyo.Closed
import kyo.Frame
import kyo.KyoException
import kyo.Maybe
import kyo.Present
import kyo.Tag
import kyo.kernel.internal.*

/** Binds a resource for the extent of a use and guarantees its release runs, whichever way the extent ends.
  *
  * `Bracket(acquire)(use)(release)` enters a region, evaluates `acquire` under it, hands the value to the region as it arrives, runs
  * `use` on it under the same region, and runs `release` exactly once: when `use` completes, when it throws, or when a parked
  * remainder holding the region is abandoned. The value reaches the region through its own hook, with nothing schedulable between
  * the acquire's last step and the region owning what it produced; a stop landing inside the acquire parks a region that owns
  * nothing yet, and one landing inside the use parks a region that releases on abandonment.
  *
  * The release is told how the extent ended (`Absent` for a clean end, otherwise the failure the unwind carried or the discard
  * signal), not what the use produced: an extent a handler replays ends more than once, with more than one value. A caller needing
  * its own failures, such as `Sync.acquireReleaseWith` reifying an `Abort`, routes them itself. The release takes no effects and
  * its result is discarded: it runs where nothing is installed to answer for it.
  *
  * A bracket closes only with the scope that installed it. An isolated child, a spawned fiber included, gets an inert copy of the
  * region that owns nothing. A bracket inside a continuation a handler holds as a value belongs to that handler: it releases when
  * the handler ends, told a clean end if the extent ran to one under a resumption, and refuses a resumption after that.
  */
object Bracket:

    // The region a bracket runs under; `Cell` is its state, the release it owes, and the exactly-once guard.
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    // The release is told how the extent ended, not what it produced. What it is told besides the ending is the
    // region's state: the acquired value for `apply`, a state made for the run for `ensuringWith`, nothing for
    // `ensuring`.
    //
    // Three shapes rather than one with flags: the empty cell a bracket starts with, waiting for its acquire and
    // owning nothing; the live cell that owns a value, born at the region's first `done` for `apply` and at entry
    // for `ensuring` and `ensuringWith`, so a continuation replaying the acquire makes a live cell per acquisition;
    // and the inert one handed to an isolated child, which owns nothing.
    sealed abstract private[kyo] class Cell extends Release

    private[kyo] object Cell:

        final class Empty[R](fin: (R, Maybe[Throwable]) => Unit, frame: Frame) extends Cell:
            private[kyo] def acquired(value: R): Live[R] = new Live(value, fin, frame)
            def ran: Boolean                             = false
            def apply(outcome: Maybe[Throwable]): Unit   = ()
            def reenter(): Unit                          = ()
        end Empty

        final class Live[R](val state: R, fin: (R, Maybe[Throwable]) => Unit, frame: Frame) extends Cell:
            private val once = new AtomicBoolean(false)
            // Whether the extent ran to an end: read by a release run elsewhere, and by a refused re-entry.
            @volatile private var ended = false

            private[kyo] def complete(): Unit = ended = true

            def ran: Boolean = once.get()

            // Told `Absent` by the entry holding it, the release answers for its own extent: a clean end if the
            // extent ended, the discard signal if it never did, which is what a remainder nobody resumed looks
            // like from the owner that released it. A failure wins over an ending that ran before it: the extent
            // is being abandoned, or a release that commits on success would commit over it.
            def apply(outcome: Maybe[Throwable]): Unit =
                if once.compareAndSet(false, true) then
                    val told =
                        outcome match
                            case Absent if ended => Absent
                            case Absent          => Present(new KyoException("remainder discarded")(using frame))
                            case told            => told
                    fin(state, told)

            def reenter(): Unit =
                if once.get() then
                    val why =
                        if ended then
                            "Its extent already ran to an end, which is what released it, and this is a later " +
                                "resumption of a continuation that re-enters it. A handler that resumes the same " +
                                "continuation more than once, as Choice does, has that effect whenever the bracket " +
                                "sits between the handler and the suspension it answers: the first resumption ends " +
                                "the extent and releases. Acquire inside the branch, so each resumption gets a " +
                                "resource of its own, or put the bracket outside the handler, so its extent is not " +
                                "what gets replayed."
                        else
                            "It was released when the scope that owned it ended, without its extent ever running to " +
                                "an end. That is what happens to a remainder handed out by a peel, such as " +
                                "Stream.splitAt, Emit.runFirst or Batch.capture, when it is consumed after the " +
                                "computation that peeled it has finished, on another fiber included: that scope " +
                                "cannot tell a remainder nobody will resume from one someone else still intends to " +
                                "resume, so it releases at its own exit. Consume the remainder inside the scope " +
                                "that peeled it, or use the confined form, Stream.splitAtWith, whose callback the " +
                                "remainder cannot escape."
                    throw new Closed("Bracket resource", frame, why)(using frame)
                end if
            end reenter
        end Live

        // Handed to an isolated child: no release, no recorded ending, no refusal, so one instance serves every crossing.
        val inert: Cell =
            new Cell:
                def ran: Boolean                           = false
                def apply(outcome: Maybe[Throwable]): Unit = ()
                def reenter(): Unit                        = ()
    end Cell

    /** Acquires a resource, runs `use` on it under a region that owns the release, and releases it exactly once.
      *
      * The region is entered before the acquire runs and owns the value from the moment it arrives: the acquire's value reaches the
      * region's own hook, with nothing schedulable in between, so an interrupt lands inside the acquire, where nothing is owned yet, or
      * inside the use, where the release runs on abandonment, and never between acquiring the resource and owing its release.
      *
      * A throw from `use` unwinds the region, so the release runs with it and the original failure is what the caller sees; a failure
      * from the release itself is attached to it as suppressed.
      *
      * @param acquire
      *   Produces the resource, evaluated when the computation runs
      * @param use
      *   The extent the resource is held for
      * @param release
      *   Runs once when that extent ends, told how it ended rather than what `use` produced
      */
    def apply[A, S1](acquire: A < S1)[B, S2](use: A => B < S2)(
        release: (A, Maybe[Throwable]) => Unit
    )(using _frame: Frame): B < (S1 & S2) =
        // Erasure-forced: the region's value changes at its first `done`, from the acquire's to the use's, so the
        // region is typed at `Any` and its result is the use's.
        region[S1 & S2](new Cell.Empty[A](release, _frame), acquire)(a => use(a.asInstanceOf[A])).asInstanceOf[B < (S1 & S2)]
    end apply

    /** Runs `release` when `body`'s extent ends, with nothing to acquire first.
      *
      * The region owns what it is told from the start, so a computation abandoned before it ran a step is released
      * all the same.
      */
    def ensuring[B, S](release: Maybe[Throwable] => Unit)(body: => B < S)(using _frame: Frame): B < S =
        // A throw while the body is being built is re-raised as the region's own body, so it unwinds with the region
        // installed and fires the release.
        val b =
            try body
            catch case ex => Effect.defer(throw ex)
        val fin: (Unit, Maybe[Throwable]) => Unit = (_, outcome) => release(outcome)
        // Erasure-forced: as in `apply`, the region is typed at `Any`.
        region[S](new Cell.Live((), fin, _frame), b)(a => a).asInstanceOf[B < S]
    end ensuring

    /** Runs `release` when `body`'s extent ends, told a state made for that run, with nothing to acquire first.
      *
      * The state is made as the region is entered, and `body` is handed the state as its first step under it. This
      * is for a release that is owed something the extent produced which the kernel does not carry: a typed failure
      * a handler inside the extent answered, as `Sync.ensure` records. Each run makes a state of its own, so a value
      * run twice, or by two fibers at once, shares nothing between the runs, and a computation abandoned before it
      * ran a step is released with a state nothing ever wrote.
      *
      * @param init
      *   Makes the state, once per run, as the region is entered
      * @param release
      *   Runs once when the extent ends, told the run's state and how the extent ended
      * @param body
      *   The extent, handed the run's state
      */
    def ensuringWith[R, B, S](init: => R)(release: (R, Maybe[Throwable]) => Unit)(body: R => B < S)(using _frame: Frame): B < S =
        // Erasure-forced: as in `apply`, the region is typed at `Any`.
        region[S](
            new Cell.Live(init, release, _frame),
            ContextEffect.suspendWith(Tag[Finalize]) { cell =>
                // Erasure-forced: the read is the first step under the region above, which bound the cell it made
                // with `init`, so this is that cell and `R` is what it holds.
                body(cell.asInstanceOf[Cell.Live[R]].state)
            }
        )(a => a).asInstanceOf[B < S]

    // The region: `body` runs under it, and its first `done` is where an empty cell takes the value as a live one
    // and continues the region with `use`; a live cell's `done` ends it. The live cell is the release the entry
    // holds, run when the entry ends, is unwound, or is abandoned, wherever a dump moved it by then.
    private def region[S](cell: => Cell, body: Any < (Finalize & S))(use: Any => Any < (Finalize & S))(using
        _frame: Frame
    ): Any < S =
        val h = new Handler.ContextHandler[Cell, Finalize, Any, S]:
            def tag                                       = Tag[Finalize]
            def derive(outer: Maybe[Cell])                = cell
            def fork(parent: Cell)                        = Cell.inert
            def join(parent: Cell, fk: Cell, child: Cell) = parent
            override private[kyo] def release(state: Cell): Maybe[Release] =
                state match
                    case live: Cell.Live[?] => Present(live)
                    case _                  => Absent
            override private[kyo] def done[S2 <: S](state: Cell, value: Any < S2) =
                state match
                    case empty: Cell.Empty[Any] @unchecked =>
                        val live = empty.acquired(Nested.unnest[Any](value))
                        Loop.continue[Cell, Any < (Finalize & S2), Any < S2](live, use(live.state))
                    case live: Cell.Live[?] =>
                        live.complete()
                        Loop.settled[Cell, Any < (Finalize & S2), Any, S2](value)
                    case _ =>
                        Loop.settled[Cell, Any < (Finalize & S2), Any, S2](value)
        new Pending.HandleContext[Cell, Finalize, Any, S]:
            override def frame = _frame
            def value          = body
            def handler        = h
        end new
    end region
end Bracket
