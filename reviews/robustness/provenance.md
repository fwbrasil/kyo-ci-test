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

a5b7d2a512: pieces P and Q as first committed, before the scheduler pin's repair joined the fourth
walk. History, not a date.

c3eb9caa24: the `SchedulerTest` repair as first committed, before the async-acquire pin was made to
poll. History, not a date.

0beb288861: the tip of the fourth walk's sources, pieces P and Q, the `SchedulerTest` repair, and the
async-acquire pin polling its counter. The commits after it in the range touch only `reviews/`, so
`sequence-4.json`, `flags-4.md` and the review's fourth-walk section name it as the tree they describe.
Named as the tip of the sources, not as a date.

8cdb379d4d: the tip `lenses/fork1-apply-pair.md` read, a static analysis of removing
`Arrow.apply(v: A)`, named as the tree it describes. The one kernel change since is piece H, the
unnest on the release walk that this analysis found and recommended; the sources it inventories are
otherwise those of the package's tip. History, not a date.

The fifth walk's commits, named by `backlog.md`, `analysis/issues.md`, `analysis/derivation-releases.md`
and `flags-5.md` as the history of the redesign, each one an item closed or a checkpoint taken. History,
not dates:

d0f19b8f89: `Context` removed, the read finding its binding on the stack.
5a46dfda1c: the fiber boundary as a loop handler, abandonment as a link-only walk, `finish` completing
whenever the promise is pending.
87b21a1ea1: the crossing's capture applied as the body's value arrives.
90aad2d8f1: releases in stack entries, the gap, `Bracket` as one region, the protocol's `done`.
726b853c53: the strand leaves and the replay leaf on the held-continuation rules.
1d3402adc6: an escaping dump keeps the remainder's releases, `runStream` replays under one region; the
tree `flags-5.md` was generated at, after which the kernel's main sources under the flagged paths did
not change (the one kernel commit since, b3849fd99c, touches `kyo-kernel/js-wasm`, which emits no flag).
4d2df91395: the pool's take release as a helper taking the unsafe evidence.
b3849fd99c: the js-wasm `Safepoint` draining an armed budget on a pending stop.
e3163ddbaf: the aeron add as a bracket's acquire, its token a nested bracket; also the tip leg of the
fifth walk's benchmarks, whose kernel, prelude and data sources equal the package's tip's. Named as
the tip of the numbers, not as a date. The base leg is `bc6a48a2aa` plus the one-line strict-equality
fix to `Eval.ensuring` that d0f19b8f89 carries (`Maybe` in place of `null`), applied uncommitted in the
throwaway worktree `robustness-bench-base` so the module compiles from a clean checkout.
8927f43e83: the kyo-core js-wasm shims' results matching the JDK's, issue 7.
