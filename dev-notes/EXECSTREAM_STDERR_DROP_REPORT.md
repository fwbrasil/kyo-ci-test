# kyo-pod execStream drops stderr on native — investigation report

## Symptom (the CI failure to fix)

Full-matrix run `32473809504`, leg **linux-arm64 Native**, `kyo.ContainerItTest`:

```
[FAIL] execStream › streams stdout and stderr as LogEntry with correct sources › [podman] › shell
  *** FAILED *** // message: expected 'err' in stderr: Chunk.Indexed()
--- ContainerItTest: 856 passed, 1 failed
```

The test (kyo-pod/shared/src/test/scala/kyo/ContainerItTest.scala:1137):

```scala
c.execStream(Command("sh", "-c", "echo out; echo err >&2")).run.map { entries =>
  val stdout = entries.filter(_.source == Stdout)
  val stderr = entries.filter(_.source == Stderr)
  assert(stdout.exists(_.content.contains("out")))          // PASSED: stdout captured 'out'
  assert(stderr.exists(_.content.contains("err")))          // FAILED: stderr was Chunk.Indexed() (EMPTY)
  ...
}
```

So: **stdout was captured ('out' present), stderr was completely empty.** Only the `[podman][shell]` backend on the Native leg; the `[http]` (API) backend passed. Intermittent: 1 of 857 leaves in this run; the test passes the vast majority of runs.

## What this is NOT (ruled out with evidence)

- NOT the COH change. This is a native binary; `-XX:+UseCompactObjectHeaders` is a JVM flag with no effect on a scala-native binary. The COH fix is validating green on every other leg (linux-x64 JVM, windows JVM/JS, arm64 JVM/JS) with no recurrence and no OOM.
- NOT a podman exec-session reap race. This was a WRONG TURN driven by a local artifact: my dev machine's `/etc/containers/containers.conf` has `exit_command_delay = 0` (my own leftover), which makes podman 5.0.1 emit `Error: no such exec session` on EVERY fast exec (100%) and lose stderr ~7.5% under parallel load. That is NOT the CI environment. A raw-podman diagnostic on the actual arm64 runner (`gh` run `32493369600`) showed: podman **5.8.4**, `exit_command_delay` **not set (podman default)**, and reap-error / stderr-loss = **0/60 serial, 0/120 parallel (12-way)**. Raw podman on CI delivers stdout+stderr cleanly. So the empty stderr is NOT podman dropping data.
- Therefore the empty stderr is **kyo dropping stderr that podman delivered** — a kyo-side, code-level bug on the Native platform.

Do not re-run the exit_command_delay / reap-race path; it is a dead end for CI. Any timing/threshold "fix" (e.g. tuning exit_command_delay) is disqualified regardless — see DETERMINISTIC_TESTS.md and the standing mandate.

## The code paths

execStream `[shell]` (kyo-pod/shared/src/main/scala/kyo/internal/ShellBackend.scala:695):

```scala
def execStream(id, command): Stream[LogEntry, Async & Abort[ContainerException]] =
  Stream {
    ... Abort.runWith[CommandException](execCmd.spawn) {
      case Result.Success(proc) =>
        Channel.initUnscoped[LogEntry](streamBufferSize).map { channel =>
          def drain(byteStream, source): Unit < (Async & Abort[Closed]) =
            Scope.run(byteStream
              .mapChunkPure { bytes => Seq(new String(bytes.toArray, UTF_8)) }
              .into(LineAssembler.pipe)
              .foreachChunk { lines => Kyo.foreachDiscard(lines.filter(_.trim.nonEmpty)) { line =>
                channel.put(LogEntry(source, line)) } })
          val drainBoth = Async.zip(drain(proc.stdout, Stdout), drain(proc.stderr, Stderr)).unit  // <-- suspect
          Fiber.init(Abort.run[Closed](drainBoth).andThen(channel.close.unit)).andThen {
            channel.streamUntilClosed().emit
          }
        }
      case Result.Failure(cmdEx) => Abort.fail(...)
      case Result.Panic(ex)      => Abort.fail(...)
    }
  }
```

Contrast — the NON-stream `exec` / `execOnce` (ShellBackend.scala:638) and `Process.collectOutput`
(kyo-core Process.scala:90) both drain with TWO INDEPENDENT fibers, joined separately, not `Async.zip`:

```scala
outFib <- Fiber.init(Scope.run(proc.stdout.run))
errFib <- Fiber.init(Scope.run(proc.stderr.run))
// ... then get both
```

The byte stream (kyo-core Process.scala:54/61) is `StreamCoreExtensions.streamFromJavaInputStream(stdoutJava/stderrJava)`, which does a **blocking** `is.read(buf)` loop (n<0 => EOF-done; n==0 => retry; n>0 => emit) — kyo-core/shared/src/main/scala/kyo/StreamCoreExtensions.scala. On Native, `stdoutJava`/`stderrJava` are scala-native javalib process-pipe InputStreams; `read` is a blocking syscall.

SCHEDULER THREADING (corrected): the kyo scheduler is MULTI-THREADED and ADAPTIVE on native, same as
JVM — workers come from `Executors.newCachedThreadPool` (Scheduler.scala ~570) and the `BlockingMonitor`
(Scheduler.scala:132) detects blocked workers and grows the pool. So `Async.zip(drain stdout, drain
stderr)` runs the two drains on SEPARATE worker threads; a blocking pipe read on one does NOT starve the
other. `LeafPool.globalK == 1` on native only bounds how many TEST LEAVES run at once, NOT scheduler
threads. (An earlier version of this report wrongly claimed a single-threaded native scheduler; that
premise is void.)

Process.scala:26 carries the warning: "Reading both stdout and stderr sequentially can deadlock if the
process produces more output than the OS pipe buffer" — i.e. the reads are blocking and must be drained
concurrently, which is why both `execStream` and the working paths drain them on separate fibers.

## Candidate mechanisms (multi-threaded reality)

The stdout/stderr drains run on separate worker threads; the blocking-read-starves-the-other theory is
dead. Live candidates:

1. `BlockingMonitor` interrupt of a blocked native pipe read. The monitor dispatches `Thread.interrupt()`
   to a worker it judges blocked (BlockingMonitor.scala ~244 `mount.interrupt()`). The stderr drain, blocked
   in `streamFromJavaInputStream`'s `is.read()` waiting for the child to write `err`, is exactly such a
   worker. On scala-native (0.5.12) javalib, does an interrupted blocking `InputStream.read()` on a process
   pipe throw / return -1 (false EOF) / lose buffered bytes? If so the stderr stream ends empty. Native-only,
   intermittent — matches.
2. `Async.zip` cancellation semantics: does it interrupt the still-running side when the other side merely
   COMPLETES (not just on failure)? If so, the fast stdout drain finishing could cancel the stderr drain.
   Must be traced in kyo-core Async.scala + Fiber, not assumed.
3. A scala-native javalib process-pipe bug (`getErrorStream` losing buffered output after the child exits).

The working paths (`execOnce`, `collectOutput`) use two independent `Fiber.init` drains, both awaited.
Whether that structurally avoids the fault depends on which of the above is real — under investigation.

Candidate root fix (not yet applied): drain stdout and stderr in `execStream` as two independent
`Fiber.init` fibers whose results are both awaited (mirroring `execOnce`/`collectOutput`), instead of
`Async.zip`, so a completed/failed stdout drain cannot cancel the stderr drain. Must be validated by
reproduction on Native (the failing platform), not JVM.

## Open questions for the held-out reviewer

1. Confirm/refute the `Async.zip` cancel-the-sibling hypothesis: does `Async.zip` interrupt the still-running side when the other side completes normally, or only on failure? What exactly happens on Native single-threaded when the stdout drain finishes first?
2. On Native (scala-native javalib), does reading a process pipe InputStream after the child exits return -1 cleanly, or can it throw / return 0 / lose buffered bytes? Does closing `stdoutJava` (via the stdout drain's `Scope`) affect `stderrJava`?
3. Is the ordering of `channel.put` vs `channel.streamUntilClosed().emit` on a single-threaded scheduler able to drop entries (buffer/close race), independent of the drain question?
4. Why does `[http]` pass but `[shell]` fail — is the difference solely the drain mechanism, or also the source of the streams?
5. Propose the minimal deterministic fix and how to reproduce the failure on Native before/after.

## Reproduction status

- CI: reproduced once in the wild (run `32473809504`, arm64 Native). Rare (~1/857 leaves).
- Local: a clean-podman Native reproduction is the next step (my local podman config was the artifact and has been reset). JVM local runs did not reproduce the empty case (only the local podman pollution artifact).
