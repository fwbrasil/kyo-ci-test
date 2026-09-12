package kyo.bench

import kyo.*
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

/** `Choice.run` and `Choice.runStream` over a program with `depth` sequential binary choice points, so 2^depth leaves, each leaf
  * reached through `depth` resumptions of the handler's continuation.
  */
class ChoiceBench extends BaseBench:

    @Param(Array("10"))
    var depth: Int = uninitialized

    def prog(i: Int, acc: Int): Int < Choice =
        if i == 0 then acc
        else Choice.eval(1, 2).map(c => prog(i - 1, acc + c))

    @Benchmark
    def run: Int =
        Choice.run(prog(depth, 0)).eval.size

    @Benchmark
    def runStream: Int =
        Choice.runStream(prog(depth, 0)).run.eval.size

end ChoiceBench
