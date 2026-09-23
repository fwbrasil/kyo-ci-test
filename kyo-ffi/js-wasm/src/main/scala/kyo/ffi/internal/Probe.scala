package kyo.ffi.internal

import scala.scalajs.js

/** Scratch instrumentation for the Windows JS investigation. Not for merge. */
object Probe:

    private val pid: Any = js.Dynamic.global.process.pid

    def log(msg: String): Unit =
        val _ = js.Dynamic.global.console.applyDynamic("error")(s"[probe t=${js.Date.now().toLong} pid=$pid] $msg")

    // Event-loop stall watchdog: a 50ms interval that reports any tick arriving more than 250ms late. unref'd so it never keeps
    // the process alive.
    private var last = js.Date.now()
    private val timer =
        js.Dynamic.global.setInterval(
            (() =>
                val now = js.Date.now()
                val gap = now - last
                if gap > 300 then log(s"event loop stalled ${gap - 50}ms")
                last = now
            ): js.Function0[Unit],
            50
        )
    locally { val _ = timer.applyDynamic("unref")() }

    def start(): Unit = ()
end Probe
