# Timing audit brief (read-only classification)

Worktree: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/gentle-purring-scroll`. Paths in your batch list are relative to it.

**First read `DETERMINISTIC_TESTS.md` at the repo root.** It is the standard you classify against.

## Task
For each file in your batch list, find every place the test touches the **real clock** and classify it. This is **read-only**: do NOT edit any file. Produce a structured findings list.

Real-clock touchpoints to examine:
- `System.currentTimeMillis()`, `System.nanoTime()` (and `.nanoTime()`).
- `Clock.now` / `Clock.nowMonotonic` used to measure a delta.
- `Thread.sleep(...)`.
- `Async.sleep` / `Clock.sleep` / `Async.delay` used **outside** `Clock.withTimeControl` where the pass/fail depends on it (a "settle then assert" or a delay used as a timeout gating an assertion).

## Classify each touchpoint as exactly one of:
- **CONVERT** — flaky real-clock dependence. The test's pass/fail can flip on a slow or fast runner. Examples: `assert(elapsed >= N)` / `assert(elapsed < N)`, `Thread.sleep(n); assert(happened)`, a bare `Async.sleep` before reading a state the sleep was meant to let happen. Give `file:line`, the current shape (one line), and the specific deterministic fix (which pattern from the doc: `withTimeControl` fork-and-advance, a barrier/latch, assert the terminal event instead of elapsed).
- **OK** — legitimately not flaky. Examples: sleeps/delays/timeouts **under** `Clock.withTimeControl`; `Duration` arithmetic (`5.millis.toMillis == 5`); `nanoTime`/`currentTimeMillis` used as a seed/id/label, not compared; a duration passed as a parameter, not measured; a real deadline used only as setup that is never asserted on; a generous ceiling / `Schedule.fixed(1.hour)` used only as a hang-canary where the assertion reads a state or event. Give `file:line` + one-line why.
- **DEVIATION** — the test genuinely needs the real clock and virtual time cannot cover it (it validates the platform clock itself, or a native/JS timer boundary with no virtual-time seam). Give `file:line`, why virtual time cannot cover it, and whether it still asserts a threshold a runner could flip (if so, that is a real problem to flag, not an accepted deviation).

## Output format (return this, keep it tight)
```
<relative/path>:
  CONVERT <line> — <current shape> -> <deterministic fix>
  OK <line> — <why>
  DEVIATION <line> — <why virtual time cannot cover it; safe or flag>
```
List only files that have at least one touchpoint. If a file's only touches are OK, still list them briefly. Be precise with line numbers. Do not edit anything.
