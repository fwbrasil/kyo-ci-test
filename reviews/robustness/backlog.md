# Autonomous queue: the redesign

Status: started 2026-09-13 on `robustness` at bc6a48a2aa. Tree clean. Kernel JVM green on the tip except
the six leaves that assert the fused delivery (to be rewritten by item 5). Working rules in force: one
verified commit per item; never edit sources while a run is in flight; commit before any bracket; a
rejected experiment becomes a `parked/` branch; no bisection; complete and correct, no scope cuts.

User's instruction, verbatim: "work on it to completion in an isolated worktree. Be diligent to simplify
things and ensure correctness by construction. Read the kernel skill again to remember. I'll go to bed
and expect you to work fully autonomously without any excuses. Don't stop, I expect the design fully
validated by morning and all that can be cleaned properly cleaned"

Design: `reviews/robustness/analysis/redesign.md`, `region-protocol.md`, `bracket-node.md`.

1. [ ] `Context` removed. Sites: `Eval.loop` (the `ctx` parameter, the read arm, `contextExit`,
   `arrowExit`, `installed`, `rebound`, `rebuilt`, `maskedEntries`, `maskedRead`), `Handler` (`bound`,
   `unbound`), `Context.scala` (deleted), `Stack.find` for reads, `derive(outer)` through the same lookup,
   masking by handler kind. Acceptance: `kyo-kernelJVM/test` green; commit.
2. [ ] Releases in stack entries. Sites: `Stack` (`releases` array, root list, pop runs the list, dump
   moves lists, escaping forwards), `Handler` (`release(state, outcome)`, `escaping`; remove `repeated`,
   `borrow`, `defers`, `reenter`, `discharge`, valueless `done`), `Eval` (`held`, `expandOwed`, `drainOwed`,
   `drainDiscarded` removed; `release` runs lists; the refusal on re-push from the release's ran flag),
   `Bracket` (`Cell` replaced by the release closure carrying ran and ended). Acceptance: kernel JVM green
   with `BracketTest`'s multi-shot and peel leaves; commit.
3. [ ] `done(state, value)` with an outcome on region handlers. Sites: `Handler.ContextHandler.done`,
   `Eval` settled arm (continue keeps the region, done pops it). Acceptance: kernel JVM green; commit.
4. [ ] `Bracket` as one `Finalize` region: body is the acquire, first `done` stores and continues with
   `use`, second `done` ends, `release` at the pop. `Scope.acquireRelease` on it. `Arrow.Ensure`,
   `ensureMap`, `Pending.Fused`, `Effect.fused`, `Eval.stopped` removed; `crossing` back to a plain
   deferral; `IOTask.abandon` links and releases. Callers: `Async._timeout`, `Exchange`, `Topic`.
   Acceptance: kernel JVM green, core JVM interrupt suites green, strand leaves rewritten to the handoff,
   fused-delivery leaves removed, bracket law leaves added; commit.
5. [ ] In-place answering: loop handlers and the boundary keep the inner regions on the stack; the floor
   for a clause's own suspensions, carried through the outcome dispatcher and the park; `done` pops the
   entries above with the discard signal; the boundary parks the whole stack. Continuation-taking
   handlers keep the dump and `crossing`. Acceptance: kernel JVM green with the new floor leaves; core
   JVM green; commit.
6. [ ] Full verification: kernel JS and Native; core JS; sql JVM containers and JS; aeron JVM; net TLS
   suites JVM and JS; the KernelBench rows base vs tip in a throwaway worktree. Commit the numbers.
7. [ ] CI: push, dispatch the full matrix, monitor, fix what is red.
8. [ ] Package: derivation, flags, sequence, review.md for the fifth walk; the report files updated
   (issues.md closed items, test-plan.md, coverage.md); rulings recorded; `.dev/kernel-robustness.md`
   in the primary tree.

Deferred, and why: the scheduler Native crash on macOS (main, not CI); the unnamed scalafmt failure
(main); both listed in issues.md.
