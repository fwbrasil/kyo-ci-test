# Single-link A/B: verdict on the aggregate no-op and on viability

Analysis of the failed podman-ci arm64 Native A/B of the single-link change (6d0997d5a0 +
d9d919c36d). Sources: the run log (`native-singlelink-ab.log`), `project/TestKyo.scala`,
`scripts/ci-test.sh`, sbt 1.12.5 sources (`command_2.12-1.12.5-sources.jar`), the old-design
CI row log (`arm64_native.log`, Aug 14), and the probe logs (`probe12g.log`, `probe6g.log`).

## Q1 verdict: the 51 modules never executed because `Command.process` does not run a `;`-batch, it queues it, and the queue was aborted by a failure in a command from a DIFFERENT scala pass

Root cause, mechanism confirmed from sbt source and from the run's own log:

1. **`Command.process` defers `;`-joined batches.** sbt 1.12.5 `Command.process(cmd, state,
   onParseError)` parses and applies one command; for a string containing `;` the parse
   resolves to the `multi` command, and `BasicCommands.multiApplied` ends in `commands :::
   state`: it PREPENDS the sub-commands to `state.remainingCommands` and returns. Nothing
   executes inside `testKyo`. The third argument is `onParseError: String => Unit`, a
   parse-error callback only; it plays no role in runtime failures. A single command (no `;`)
   is executed synchronously, which is why the heavy `--only kyo-schema-tests` session (one
   project, no semicolon) ran inline and worked.

2. **The per-scala passes therefore queue in LIFO order, and the version restore runs before
   any of them.** Inside one `testKyo` invocation (TestKyo.scala:100-125): the 3.8.4 pass
   prepends its 51-command batch (line 164); `++2.12.20` executes synchronously (single
   command), finds 0 Native projects; `++2.13.18` executes synchronously, selects
   kyo-configNative and kyo-stats-registryNative and prepends their 2-command batch IN FRONT
   of the 51; then the restore `++3.8.4` (line 121) executes synchronously, which drops
   kyo-config to its listed 3.3.8 ("Falling back kyo-configNative to listed 3.3.8"). Only
   after `testKyo` returns does sbt's main loop drain the queue: kyo-configNative/test first,
   at Scala 3.3.8, not 2.13.18.

3. **The first drained command failed and sbt dropped the rest.** kyo-configNative linked
   (2.2s, "Discovered 3243 classes"), ran 297 tests, and `kyo.FlagPlatformTest` failed (the
   env-var test; environmental). sbt batch mode with no `onFailure` handler then discarded the
   entire remaining queue: kyo-stats-registryNative/test and all 51 3.8.4 commands, and exited
   nonzero ("[error] Total time: 19 s" is that one command's timing). `run_native_retry`
   correctly classified it as a real failure (rc=1, no retry).

This explains every observation: "testing 51 projects / running: ..." with zero further
output (the batch was only queued); the immediate jump to the 2.12/2.13 pass logs (those
lines print during the synchronous `testKyo` evaluation); only kyo-config linking (it was at
the queue front); no OOM and no crash (none occurred); a normal sbt exit.

### Hypotheses weighed

- **(a) refined: CORRECT.** Not "the 3rd arg swallows a failure" but "the batch never ran in
  the first place, and a failure in the front-of-queue 2.13-pass command aborted it".
- **(b) 6G driver OOM on kyo-actor: REFUTED.** kyo-actor's link never started (no `Linking (`
  line for any of the 51); the only aggregate-session link, kyo-config, succeeded; the driver
  survived to run a full suite and exit on a test failure.
- **(c) scala-version state: adjacent but not causal.** Selection at 3.8.4 was correct (51
  projects). The `Falling back` mechanism only changed WHICH version the queued 2.13 commands
  ran at (3.3.8), a separate latent bug (below).
- **(d) 4th invocation: REFUTED.** The 1st sbt invocation (compile-main) shows the identical
  deferral: log line 72 "running: <52 compiles>" is immediately followed by line 73
  "switching to Scala 2.12.20"; all compile output appears after line 87 "restoring Scala
  3.8.4", and kyo-config/kyo-stats-registry compile FIRST, into `target/scala-3.3.8`. The
  compile phases survived only because no queued command failed, so the queue drained fully.

### The confirming check

Already performed, two-sided and conclusive:

- **In-log:** in the same run's compile-main pass, after "restoring Scala 3.8.4" the first
  compiles are kyo-config and kyo-stats-registry into `target/scala-3.3.8`, before any of the
  52 queued 3.8.4 compiles (log lines 88-127). Deferral, LIFO inversion, and restore-first,
  all in one place.
- **In-source:** sbt 1.12.5 `Command.scala:191-200` (3rd arg is parse-error only) and
  `BasicCommands.scala:225-271` (`multiApplied` returns `commands ::: state`, a prepend to
  `remainingCommands`, executing nothing).

Forward validation once fixed: rerun the aggregate and observe kyo-actorNative actually
reaching `Linking (`.

### The fix (required for any design, not just single-link)

Make `runAll`/`runDiff` execution match intent. Two sound shapes:

- Assemble the ENTIRE plan as one ordered command string including the switches
  (`++3.8.4; a/test; ...; ++2.13.18; config/test; ...; ++3.8.4`) and queue it once, so drain
  order equals intended order and each batch runs under its intended Scala version; or
- Execute per-module commands inline (single-command `Command.process` per module, which runs
  synchronously, checking the returned state for failure), keeping the `++` switches where
  they are.

Collateral pre-existing bug this exposes, on ALL platforms: the "2.13 pass" has never run at
2.13.18 in combined-scala runs. The restore executes before the queued 2.13 batch, so
kyo-config and kyo-stats-registry tests actually run at the 3.3.8 fallback everywhere
(JVM/JS/Native rows alike). Scala 2.13 test coverage via `testKyo` full runs is currently
zero. The same fix closes it.

## Q2 verdict: single-link is VIABLE. The "fundamental heap conflict" in SINGLELINK_AB_DIAGNOSIS.md is refuted by production evidence

The A/B never tested the aggregate's memory at all: zero of the 51 links were attempted, so
the run is evidence about `testKyo`, not about heap. And the central premise of the
unviability argument ("a large-closure test binary needs ~12G to link; 6G cannot") is
contradicted by the old design's own CI behavior:

**In the old-design arm64 Native row (`arm64_native.log`), the post-`testKyo` test phase runs
in the same 6G-capped driver (`-J-Xmx6G`, RUN_HEAP_CAP), and it performed ~26 FULL codegen
relinks there, all completing, tests interleaved in the same session.** Closures full-linked
at 6G include 22,197 / 29,820 (kyo-core, Total 189s) / 30,177 / 30,768 / 33,100 / 37,605 and
the largest, 44,570 classes / 133,599 methods (Total 191s), with peak java RSS ~8.3G total
process (6G heap plus toolchain off-heap) on the 16G runner. Coupled link+run at 6G for big
modules is not a hypothesis; it has been production reality since #1822.

So the heap need does not track class count the way the diagnosis assumed; it tracks
optimizer IR volume (methods). The one genuine outlier is kyo-schema-tests: 37,595 classes
but 189,618 methods, measured ~7.7G RSS clean (the NATIVE_HEAVY rationale), the only module
that cannot live in the 6G aggregate. The single-link design already isolates exactly that
module. There is no second driver-heap regime hiding in the 51: the container/browser modules
(kyo-sql-tests, kyo-pod, kyo-ui) can link within the 6G cap like the rest, so their fork
headroom is the same as the old design's run phase, where they already executed coupled
link+runs when #2514 missed.

### What must be fixed before the re-run (all concrete, none structural)

1. **The TestKyo execution bug (Q1).** Prerequisite; with it fixed, the aggregate actually
   links and runs its 51 modules in order.
2. **kyo.FlagPlatformTest.** Reproduce it cleanly (old design, same container) to confirm it
   is environmental (the podman-ci container's process env), then fix the test's env
   assumption or the harness env. It gated this entire run; it will gate the next one.
3. **De-edge the heavy session.** The isolated `--only kyo-schema-tests` session at 12G heap
   plus 2 clang forks cgroup-OOMed on attempt 1 (~12.5G java + ~3.5G clang vs 16G); attempt 2
   passed only because the surviving work dir made the retry incremental. The retry is
   currently load-bearing. Cap the heavy driver below the edge (measured need ~8G, so
   `-J-Xmx10G`) and/or set NATIVE_LINK_CPUS=1 for it, so attempt 1 fits.
4. **Watchdog margin.** Longest observed silent link phases (Total 191s at 6G, 327s for the
   heavy at 12G) sit under the 600s stale timeout; confirm on the corrected re-run as the
   proposal's open hole requires.

### The fallback

NATIVE_LINK_SLOWDOWN_PROPOSAL.md (keep the 12G upfront link + 6G run, move the #1822 prune to
after each module's test) is NOT the correct pivot now: its premise, that link and run need
different heaps and therefore separate sessions, is refuted by the same 6G-relink evidence
that rescues single-link. It remains the genuine fallback if the corrected A/B surfaces a
real aggregate memory failure (e.g. a module whose 6G link thrashes into the watchdog), and
its post-test-prune idea stays relevant to the crash-retry path: after a mid-queue driver
death, pruned already-tested modules re-link from scratch on the retry.

### Recommended path

1. Fix TestKyo execution ordering (also fixes the silent 2.13-at-3.3.8 coverage bug).
2. Fix or harden FlagPlatformTest for the container env.
3. Lower the heavy session's heap/CPU as above.
4. Re-run the same podman-ci arm64 A/B; expect ~51 sequential links in the 6G aggregate,
   `Total`-cycle count ~54 (from 84), containers and Chrome with their headroom, and the
   heavy session green on attempt 1.
