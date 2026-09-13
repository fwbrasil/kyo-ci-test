package java.util.concurrent.locks

// The signatures match the JDK's exactly, `Unit` results included, for the reason `VarHandle` gives: a caller
// recompiled alone links against the JDK's `void` result.
object LockSupport:
    private def fail =
        throw new UnsupportedOperationException("fiber.block is not supported in ScalaJS")
    def park(o: Object): Unit               = fail
    def parkNanos(o: Object, l: Long): Unit = fail
    def unpark(t: Thread): Unit             = fail
end LockSupport
