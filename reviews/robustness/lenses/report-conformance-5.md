PASS

Tip judged: `d8dcfaa8a7` (the lens's read); re-confirmed by the coordinator at HEAD `1d991ec10c`, which
commits the fifth-walk section edits (items 0a, 0b, 18, the base correction, the "As built"
reconciliation) that were in the working tree at the lens's read. Range: `bc6a48a2aa..HEAD`. The
runnable code tip is `96f279c752`; every commit after it touches `reviews/`, `sequence-5.json`, or the
gitignored `kyo-kernel/.claude` skill files, and `sequence-5.json` still verifies against the tip.

Recorded by the coordinator from the lens's reply (the harness blocks a subagent from writing a report
file). The lens read the design (`redesign.md`, `region-protocol.md`, `derivation-releases.md` with its
"As built" section) and the diff of the lens paths plus the walk's non-kernel surface, and ran no build
or tests.

The code became the derived design and everything the range touches is inside the declared surface. No
substitution, no work outside the surface, no declared item absent from the code, no design piece
without a counterpart.

## Surface enumerated

Every code file the range touches maps to a walk item and every item's file is touched: 0a Context
removed (`Context.scala` deleted, the context-read reshaping in `Eval`/`Handler`, `ContextEffect`'s
release arm gone, `ContextTest` deleted); 0b the `IOTask` boundary as a loop region, the abort arm
`Loop.done(())`, the waiting join re-raised as the answer, `abandon` link-then-release, the crossing
capture in `Isolate` shared with item 7, `FiberTest` pinning the ending; then items 1 to 17 as listed,
12b the js-wasm `Safepoint`, 12c the js-wasm shims. `package-check.sh` and `rulings.md` are review
tooling, outside the lens diff paths, not judged.

## Rules and equations

All eight rules of the release derivation and the bracket equation are composed of the designed values,
not substitutes (release lists in entries, `dump` moving lists, escaping forwarding with `kept`
snapshots, `Eval.release` collecting innermost-first and reporting the first operation, the gap for a
suspending clause and the second gap for a pending answer, the one `Finalize` region whose `done` turns
an empty cell live and continues with `use`). Every declared removal is verified absent (`truncate`,
`settle`, the owed lanes, `expandOwed`, `drainOwed`, `drainDiscarded`, `leftmost`, `Eval.stopped`,
`held`, `delivering`, `Pending.Fused`, `Effect.fused`, `Arrow.Ensure`/`ensure`, `ensureMap`,
`handleFirstRepeated`, the `repeated` overrides); the surviving `.settled` is `Loop.settled`, the
surviving `stopped` is `Safepoint.stopped`, the `Fused` hits are fixture names in an untouched file. The
non-kernel callers use `Bracket` in place of `ensureMap`, `Topic`'s adds are bracket acquires with a
nested-bracket token, `Choice.runStream` replays under `handleContRepeated`, the pool owns the take from
its issuing step, the js-wasm shims return `Unit`. No partial reference interpreter is in the diff.

## Notes (reconciled deviations, not findings)

- `redesign.md` and `region-protocol.md` predate the build on three points, reconciled in "As built" and
  named in the fifth-walk intro: the reporter overload of `Eval.release` is kept (rule 7 needs it),
  `maskedRead` is kept, and the gap stands in place of the planned `floor`. The earlier docs' two-region
  bracket with an added `ArrowHandler` is superseded by the one `ContextHandler` region with `Cell`
  extending `Release`. The code takes the as-built side throughout.
- The prior FAIL run's hint of an undone declared removal is not substantiated at this tip; every
  declared removal is complete with no dangling reference.
