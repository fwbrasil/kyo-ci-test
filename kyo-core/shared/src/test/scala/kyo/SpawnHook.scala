package kyo

import kyo.kernel.ContextEffect
import kyo.scheduler.IOTask

/** A one-shot action fired on the spawning thread from inside the next fiber spawn that crosses a [[SpawnHook.probing]] region: after that
  * spawn's last safepoint poll and before the spawned fiber reaches its continuation.
  *
  * It places a stop inside a spawn window by construction. A window a few microseconds wide, between a spawn and the step that takes
  * ownership of what it produced, is below what a timer or a latch hand-off can land in, and sampling it with staggered offsets only
  * reaches it by chance.
  */
final private[kyo] class SpawnHook:
    private val pending = new java.util.concurrent.atomic.AtomicReference[Maybe[() => Unit]](Absent)

    def arm(f: () => Unit): Unit = pending.set(Present(f))

    /** Arms the hook to interrupt the fiber that performs the spawn, so that fiber's next step starts with the stop already pending. */
    def armInterrupt()(using Frame): Unit =
        arm(() => IOTask.currentTask().foreach(_.interruptDiscard(Result.Panic(Interrupted(summon[Frame])))))

    private def fire(): Unit = pending.getAndSet(Absent).foreach(_())
end SpawnHook

private[kyo] object SpawnHook:

    sealed trait Probe extends ContextEffect[SpawnHook]

    /** Runs `v` with `hook` installed: every spawn inside the region, and inside the fibers that inherit it, fires the hook if armed. */
    def probing[A, S](hook: SpawnHook)(v: A < (Probe & S))(using Frame): A < S =
        ContextEffect.handle(
            Tag[Probe],
            (_: Maybe[SpawnHook]) => hook,
            fork = (h: SpawnHook) =>
                h.fire()
                h
            ,
            join = (parent: SpawnHook, _: SpawnHook, _: SpawnHook) => parent
        )(v)
end SpawnHook
