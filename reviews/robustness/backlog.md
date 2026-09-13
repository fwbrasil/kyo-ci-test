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

0. [x] 2026-09-13 03:40 to 10:05 lost: a probe's scratch print referenced `status` inside the boundary's
   clause lambda, which promotes the field and breaks the platform handle that finds it by name
   (`parkOn`'s comment). The worker died, sbt hung. Rule: no field references in scratch prints inside
   lambdas of `IOTask`.
1. [x] `Context` removed, d0f19b8f89.
1b. [x] 5a46dfda1c, 87b21a1ea1. The boundary as a loop handler, abandonment as a link-only walk, the
   crossing's capture applied as the body's value arrives (a stop on the body's last step no longer
   strands the value).
2. [x] 90aad2d8f1. Releases in stack entries (`Stack.releases`, `evalReleases`, `owe`/`oweAll`/`oweBelow`,
   `takeReleases`, `owned`; dump moves lists; escaping forwards; parks carry lists), the protocol
   (`release(state): Maybe[Release]`, `escaping`; `repeated`, `borrow`, `defers`, `reenter`, `discharge`
   gone), the refusal through `Release.reenter`.
3. [x] 90aad2d8f1. `done(state, value)` answering with an `Outcome2` on region handlers.
4. [x] 90aad2d8f1, 726b853c53. `Bracket` as one `Finalize` region over the acquire; `Scope.acquireRelease`,
   `Async._timeout`, `Exchange`, `Topic` on it; `Arrow.Ensure`, `ensureMap`, `Pending.Fused`,
   `Effect.fused` gone; the strand leaves on the ownership rule.
5. [x] 90aad2d8f1. In place: the gap (`Handler.Gap`, `Stack.hide`/`hidden`, `find` stepping over it,
   `contextual` skipping what it hides), the answer gap with `Handler.answered`, `done` discarding through
   it, the boundary parking the whole stack with the gap. See `derivation-releases.md`, "As built".
6. [ ] Full verification. Done on JVM: kernel 1839 green, kernel doctest 56 blocks green, prelude 845
   green, core green (the orphaned-permit leaf rewritten to the handoff, then green), every JVM
   module's tests compiling (the pool's `takeSlot` had never compiled; its release is now a helper
   taking the unsafe evidence), sql JVM containers 139 suites green, the JS SQL interrupt suite green.
   Kernel JS 1780 and Native 1816 green after the js-wasm Safepoint fix (b3849fd99c: a pending stop
   drains the budget, as the jvm-native slot does). Aeron JVM green after e3163ddbaf: the add is a
   bracket's acquire, its token a nested bracket (the fourth walk's "ending stands" expectation
   restored to main's). Kernel JS and Native EvalTest 136 green, aeron JS and Native
   AeronTransportTest 34 green, net TLS suites JVM (all green, platform combinations cancelled) and JS
   (84 passed, 158 cancelled) green, core JS 1700 green in full after the js-wasm shim fix
   (8927f43e83, issue 7), core JVM doc green. In progress: KernelBench (-f 1, 53 rows) and ChoiceBench
   (-f 3) base (bc6a48a2aa) vs tip (e3163ddbaf, whose kernel and prelude sources equal the branch
   tip's) in the throwaway worktrees `robustness-bench-base` and `robustness-bench-tip`. Commit the
   numbers.
7. [ ] CI: push, dispatch the full matrix, monitor, fix what is red.
8. [ ] Package: derivation, flags, sequence, review.md for the fifth walk; the report files updated
   (issues.md closed items, test-plan.md, coverage.md); rulings recorded; `.dev/kernel-robustness.md`
   in the primary tree.

Deferred, and why: the scheduler Native crash on macOS (main, not CI); the unnamed scalafmt failure
(main); both listed in issues.md.
