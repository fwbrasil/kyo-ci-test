package java.lang.invoke

// The signatures match the JDK's exactly, `void` results included: a caller compiled in the same run as this
// object resolves to it, but one recompiled alone, as an incremental build does, resolves to the JDK's class
// and links against these forwarders by name and result type. A `Void` result linked only in the first case.
object VarHandle:
    def loadLoadFence(): Unit   = ()
    def storeStoreFence(): Unit = ()
