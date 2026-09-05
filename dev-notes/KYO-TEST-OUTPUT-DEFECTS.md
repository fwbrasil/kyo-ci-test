# kyo-test output defects: a handoff

Written for an agent picking this up cold. Everything below was observed, with the commands that produced
it. Nothing here is inferred from symptoms alone.

The context that surfaced these: a CI stabilization drive whose core loop is "did this leg really pass",
repeated across ten legs per run. Every defect below is something that made that question expensive to
answer, and several of them made it answerable only by hand.

## 1. Aggregate counts are ZERO for every forked run

The headline defect. kyo-test's run summary reports nothing for forked JVM runs.

| run | forked | tests that actually ran | framework summary |
|---|---|---|---|
| local `kyo-netNative/test` | no | 965 | `kyo-test: 965 tests, 698 passed, 0 failed` |
| local `kyo-dataJVM/test` | yes | 322 | `kyo-test: 0 tests, 0 passed, 0 failed` |
| CI linux-x64 JVM leg | yes | 29,911 | `kyo-test: 0 tests, 0 passed, 0 failed` (x54) |

Reproduce in one command:

```
sbt 'kyo-dataJVM/test'      # Results: lines total 322; summary says 0
```

The discriminator is the FORK BOUNDARY, not CI and not platform: an in-process Native run counts correctly
and a forked JVM run on the same machine reports zero. `fork := true` lives in `kyo-settings`
(`build.sbt:132`) and the fork is `sbt.ForkMain` talking back over a socket, so the natural suspicion is
that per-suite events do not propagate back to the driver's aggregator.

Worse than useless: the ONLY nonzero summaries in a CI leg are the nested self-test children
(`kyo-test: 1 tests, 1 passed` and `kyo-test: 1 tests, 0 passed, 1 failed`). The real work reports nothing;
the deliberately-fake failures are the only thing counted.

This has already been institutionalized as a workaround rather than fixed. The drive's operating rules
warn that `[info] kyo-test: 0 tests, 0 passed...` "prints on genuinely-passing runs too, so it is NOT the
signal". The truer statement is that it prints ALWAYS, because for forked runs it is always zero.

## 2. The knock-on: per-suite summing is forced, which drags in the self-test fixtures

Because the aggregate is dead, the only way to verify a leg is to sum the per-suite
`Results: N passed, M failed` lines. That sum includes the nested runs kyo-test spawns to test itself, whose
fixtures FAIL ON PURPOSE. Every green leg therefore reports failures that a human must adjudicate:

| leg type | "failed" on a fully green run |
|---|---|
| JVM | 50 |
| JS / Wasm | 42 |
| Native | 43 |

There is no machine-readable discriminator between an intentional fixture failure and a real one. Three
different classifiers were attempted and all were wrong on some platform: suite-name patterns (the fixtures
are named for what they exercise, e.g. `TimeoutSuite`, `GSCShrinkSuite`, `MixedSeedSuite`), module
attribution via the nearest preceding compile line (works on JS/Wasm which link per module, fails on JVM
which compiles everything up front), and surrounding log shape. The classification was ultimately abandoned
as too dangerous: a wrong rule silently masks a real red.

FIX 1 (counts) LARGELY DISSOLVES THIS. With a correct aggregate, verification is one number and the
fixtures never enter it.

## 3. stdout and stderr collide on a single physical line

`/tmp/rung3b.log:11713`:

```
Exception in thread "kyo-scheduler-worker-1" --- FatalFiberTest: 1 passed, 0 failed  (4ms)
```

A stderr banner and a stdout suite-result line merged into one line. Any line-oriented parser mis-reads
both: the suite line is no longer anchored, and the exception line has no stack attached. Only one
occurrence in a 57,672-line log, so it is rare, but it corrupts precisely the line format that every
verification depends on, and it does so nondeterministically.

## 4. Suite lines carry no module attribution

There are 1683 `--- <Suite>: N passed, M failed` lines in a JVM leg and not one names the module it belongs
to. To attribute a suite you must scan backwards for a compile or link marker, which is why the classifier
in defect 2 worked on JS/Wasm and failed on JVM.

Putting the module on the suite line (or on the `Running N suite(s)` banner) would make attribution a field
lookup instead of a heuristic.

## 5. Cancelled leaves are invisible in aggregate

Per-suite lines carry cancellations (`0 passed, 0 failed, 85 cancelled`) but nothing reports a run-level
cancelled total. This is exactly the trap the drive's rules document: `kyo-browser` and `kyo-ui` cannot run
on linux-arm64 (no `chrome-headless-shell` for Aarch64), so every leaf self-cancels and the leg still
reports `conclusion=success`.

Detecting "a module cancelled everything" currently requires knowing the expected cancellation set in
advance, per platform. A run-level cancelled total, or an assertion that the cancelled set matches a
declared expectation, would turn a documented hazard into a caught error.

## 6. The leaf pool discards the failure cause

`kyo-test/runner/shared/src/main/scala/kyo/test/runner/internal/LeafPool.scala:98-101`:

```scala
Sync.ensure {
    promise.completeDiscard(Result.panic(LeafPool.LeafPoolPanic))
} {
    comp.map(a => promise.completeDiscard(Result.succeed(a)))
}
```

`LeafPoolPanic` is a constant sentinel carrying NO cause. When a leaf body dies from a fatal throwable the
real one is discarded; it survives only as a detached `Exception in thread "kyo-scheduler-worker-N"` banner
with no association to the leaf. The surfaced failure is the useless
`kyo-test leaf-pool: work body failed to complete its promise`.

This directly cost a long investigation: a `NoClassDefFoundError` was reported as the sentinel, and the
actual throwable had to be recovered by correlating stderr banners by line number.

The comment at that site says "runLeaf is total by contract, so this never fires in practice". It fired.
The contract assumption is wrong and the code documents itself as unreachable while being the only thing
that runs on the path that matters most.

CONSTRAINT THE NEXT AGENT MUST KNOW: the natural fix is the error-aware `Sync.ensure`
(`kyo-core/shared/src/main/scala/kyo/Sync.scala:108`, `f: Maybe[Error[Any]] => ...`), and that overload is
itself subject to a known kyo-core defect ("its error-aware form isn't passed the error" when the body
short-circuits via `Abort`), owned separately and explicitly off-limits to this drive. The known bug is
scoped to `Abort`, and this case is a fatal `Throwable`, so it may well work here, but that must be PROVEN
with a reproduction rather than assumed. If it does not hold, this fix is blocked behind that one.

## 7. Structured output is produced and thrown away

sbt writes per-suite JUnit XML with clean counts:

```xml
<testsuite name="kyo.net.BackendEchoTest" tests="24" errors="0" failures="0" skipped="0" time="1.048">
```

and kyo-test has an unused `--reporter=junit-xml:PATH` (`Args.scala:188`). CI never uploads
`target/test-reports`: the only `upload-artifact` steps in the repo are in `release.yml`.

STATED CAREFULLY, because it is tempting to treat this as the fix and it is not: uploading the XML would
give tooling a clean channel, but it would leave the console output exactly as broken for every human
reading it, and it would not fix defect 1. It is a complement to fixing the counts, not a substitute. Do
not let it become the reason defects 1 through 6 stay open.

## Suggested order

1. Defect 1, the zero aggregate. Highest leverage: it is the root of defect 2 and the reason the operating
   rules carry a workaround. Reproducible locally in seconds.
2. Defect 6, the discarded cause. Cheap once the `Sync.ensure` question above is settled, and it is what
   makes every future failure diagnosable instead of an archaeology exercise.
3. Defects 4 and 5, module attribution and a cancelled total. Both are small additions that convert
   heuristics into fields.
4. Defect 3, the output collision. Rare and harder; worth doing once the rest is stable.
5. Defect 7, the artifact upload, as an addition rather than a substitute.
