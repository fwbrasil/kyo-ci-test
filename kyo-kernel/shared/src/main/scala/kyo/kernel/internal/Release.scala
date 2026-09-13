package kyo.kernel.internal

import kyo.Maybe

/** What a region owes when the stack entry holding it pops: one call, told how that entry ended.
  *
  * `Absent` says the entry ended normally; `Present(ex)` says it was unwound by `ex` or abandoned with `ex`. The release maps
  * that onto its own extent, which is not always the entry's: a release a dump moved to the handler's entry is told about the
  * handler's end, and answers for an extent that may have ended earlier, under a resumption, or never.
  *
  * A release runs once. The stack entry that pushed it holds it until a dump moves it to the entry of the handler that took the
  * continuation, or an escaping handler forwards it below; wherever it is when that entry pops, unwinds, or is abandoned, it runs
  * there. Reinstalling a region whose release ran is refused through [[reenter]], with the region's own explanation.
  */
abstract private[kyo] class Release extends (Maybe[Throwable] => Unit):

    /** Whether this release ran. */
    def ran: Boolean

    /** The region owing this release is being reinstalled from a park; refuses when the release ran. */
    def reenter(): Unit

end Release
