# Provenance of the commits and benchmark classes this package names

dcadee780d: the base leg of every benchmark comparison, the base `cdefdc9e60` plus edit 19 alone so
dafad6640a: the tip leg of the final benchmark session, a local commit in the throwaway worktree
`robustness-A` carrying the handler-side shape; the kernel's main sources, the benchmark sources,
`Choice.scala` and `Span.scala` are those of the package's tip except the scaladoc paragraph of
edit 8, which no benchmark compiles differently. Named as the tip of the numbers, not as a date.

`KernelBench` compiles, a local commit in the throwaway worktree `robustness-base`; outside the
range by construction and named as the base of the numbers, not as a date.

`KernelBench` (`kyo-kernel/jvm/src/jmh`) references `kyo.kernel` and is the evidence for every
kernel edit: base against the fix, the fix against the tip, and the two multi-shot rows.

`ChoiceBench` (`reviews/robustness/bench/src/ChoiceBench.scala`, kept in the package rather than in kyo-bench by the ruling of 2026-09-12) does not reference `kyo.kernel`. It is named
because piece F changes `kyo.Choice`, the tree's one consumer of the repeated handlers, and its rows
measure what that consumer pays per resumption before and after the kernel's re-entry and F's
simplification. They are evidence about F and about the cost of the kernel change to a consumer,
not about any kernel row, and the package presents them under that heading only.

`SpanBench` (`reviews/robustness/bench/src/SpanBench.scala`, the same) does not reference `kyo.kernel`
either. It is named because edit 22 changes `kyo.Span.updated` in `kyo-data`, and its one row prices
the index check that method now makes on the JVM, base against tip. It is evidence about that edit
and nothing in the kernel.

7fedf93c73: the base of the second walk, the state the first walk left in the primary tree; the two
kyo-bench edits the reviewer dropped live are not in that tree and touch nothing the second walk
touches. History, not a date.

98bdc584b7: the tip of the second walk's sources. The commits after it in the range touch only
`reviews/`, so `sequence-2.json`, `flags-2.md` and the review's second-round section name it as the
tree they describe. Named as the tip of the sources, not as a date.

9d5c795077: the base of the third walk, the second walk's sources plus the package commits after
them, which touch only `reviews/`. History, not a date.

b9e522721b: the tip of the third walk's sources, piece O. The commits after it in the range touch
only `reviews/`, so `sequence-3.json`, `flags-3.md` and the review's third-walk section name it as
the tree they describe. Named as the tip of the sources, not as a date.

b0a9d4a666: the base of the fourth walk, the third walk's sources plus the package commit after them.
History, not a date.

a5b7d2a512: the tip of the fourth walk's sources, pieces P and Q. The commits after it in the range
touch only `reviews/`, so `sequence-4.json`, `flags-4.md` and the review's fourth-walk section name it
as the tree they describe. Named as the tip of the sources, not as a date.

8cdb379d4d: the tip `lenses/fork1-apply-pair.md` read, a static analysis of removing
`Arrow.apply(v: A)`, named as the tree it describes. The one kernel change since is piece H, the
unnest on the release walk that this analysis found and recommended; the sources it inventories are
otherwise those of the package's tip. History, not a date.
