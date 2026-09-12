package kyo.bench

import kyo.*
import org.openjdk.jmh.annotations.*

/** `Span.updated` over a sixteen-element span, every index in bounds: the copy, plus the index check the method now makes before it,
  * so the row prices the check and never reaches the throw.
  */
class SpanBench extends BaseBench:

    private val span: Span[Int] = Span(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private var i               = 0

    @Benchmark
    def updated: Span[Int] =
        i = (i + 1) & 15
        span.updated(i, i)

end SpanBench
