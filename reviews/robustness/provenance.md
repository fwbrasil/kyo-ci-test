# Provenance of the commits and benchmark classes this package names

dcadee780d: the base leg of every benchmark comparison, the base `cdefdc9e60` plus edit 21 alone so
`KernelBench` compiles, a local commit in the throwaway worktree `robustness-base`; outside the
range by construction and named as the base of the numbers, not as a date.

`KernelBench` (`kyo-kernel/jvm/src/jmh`) references `kyo.kernel` and is the evidence for every
kernel edit: base against the fix, the fix against the tip, and the two multi-shot rows.

`ChoiceBench` (`kyo-bench/src/main/scala/kyo/bench`) does not reference `kyo.kernel`. It is named
because piece F changes `kyo.Choice`, the tree's one consumer of the repeated handlers, and its rows
measure what that consumer pays per resumption before and after the kernel's re-entry and F's
simplification. They are evidence about F and about the cost of the kernel change to a consumer,
not about any kernel row, and the package presents them under that heading only.

`SpanBench` (`kyo-bench/src/main/scala/kyo/bench/SpanBench.scala`) does not reference `kyo.kernel`
either. It is named because edit 25 changes `kyo.Span.updated` in `kyo-data`, and its one row prices
the index check that method now makes on the JVM, base against tip. It is evidence about that edit
and nothing in the kernel.
