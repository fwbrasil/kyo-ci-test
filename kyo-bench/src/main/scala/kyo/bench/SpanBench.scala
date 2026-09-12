package kyo.bench

import kyo.*
import org.openjdk.jmh.annotations.*

/** `Span.updated` over a sixteen-element span: the copy, plus the index check that raises the documented exception on every platform.
  */
class SpanBench extends BaseBench:

    private val span: Span[Int] = Span(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private var i               = 0

    @Benchmark
    def updated: Span[Int] =
        i = (i + 1) & 15
        span.updated(i, i)

end SpanBench
