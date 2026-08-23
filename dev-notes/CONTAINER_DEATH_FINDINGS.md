# Container-death-under-load: findings (delay=0 investigation)

Branch: kyo-compat-external-bindings. Goal: two fully-green full-matrix CI runs.
HEAD: a58dcda993 (edf2dc0859 exit_command_delay=0 + a58dcda993 execIds leak-check).

## Two distinct issues, one shared root (resource pressure)

**(A) exec-conmon exhaustion — ROOT-CAUSED + largely fixed.**
- podman HTTP API exec defers each session's cleanup exit-command (`podman container cleanup --rm --exec <execId> <cid>`) by `exit_command_delay` (default 300s). Unlike the CLI (synchronous cleanup), the API exposes NO force-reap endpoint (DELETE /exec, POST /exec/cleanup, POST /containers/cleanup all 404).
- Each API exec leaves ~2 conmon (double-fork) until the delay elapses. ContainerItTest issues 800+ execs -> conmon accumulate -> rootless process table exhausts -> in-container `sh: Cannot fork`.
- PROVEN against real rootless podman: delay=300 -> 50 execs accumulate 101 conmon; delay=0 -> peak stays at baseline (1), zero accumulation.
- FIX (edf2dc0859): exit_command_delay=0 in .github/actions/setup/action.yml. Reduced JS-x64 Cannot-fork 26 -> 1.

**(B) containers vanish mid-test — NOT yet root-caused.**
- delay=10 gate#1 JS-x64: 758 passed, 95 failed = 126 ContainerAlreadyStopped + 19 HealthCheck + 26 Cannot-fork.
- delay=0 JS-x64 (run 31668820289): 815 passed, 38 failed = mostly `ContainerMissingException: Container not found`.
- Same container the test created and is still using becomes stopped (delay>0) / removed+not-found (delay=0). exit_command_delay only changes stopped-vs-removed (autoRemove reaps immediately at delay=0).
- So the ROOT of (B) is containers STOPPING mid-test. delay=0 reduced count 126 -> 38 (less resource pressure) but did not eliminate it.

## Evidence
- Local (healthy rootful podman, delay=0): single exec AND 20 parallel execs on a running alpine (`sh -c "trap 'exit 0' TERM; sleep infinity & wait"`, autoRemove true/false) leave the container ALIVE (exists=204). So execs do not remove/stop running containers at small scale.
- delay=0 exec cleanup `--rm` does NOT remove a RUNNING container (tested both autoRemove settings).
- No OOM / "Killed" / keyring "disk quota" / "session key" signatures in the test stdout for the failures.
- Failing tests correlate with this campaign's healthcheck/readinessLoop/DB changes: DB round-trips (Postgres/MySQL/MongoDB, use readinessLoop), "isHealthy runs the check once", plus "shared volume between two containers", "parallel exec on same container".
- Failures cluster late in the run (after ~800 tests), flaky (38/853).

## Relevant code / campaign changes
- alpine config (ContainerItTest): `.command("sh","-c","trap 'exit 0' TERM; sleep infinity & wait").stopTimeout(0.seconds)`, autoRemove = Config default.
- HttpContainerBackend.remove -> awaitRemoved (added this campaign): blocks on `/wait?condition=removed` + a guarded force-remove, "fully retired on return" backpressure; notes keyring exhaustion.
- awaitTerminalState: polls state up to 20 x 50ms.
- ensurePendingExit/pendingExit: attaches a `/wait` fiber before kill/stop on autoRemove containers to capture exit code.
- Campaign changes: ContainerPredef.readinessLoop (in-container `sh -c` bounded poll, schedule=Schedule.done, HttpClient.withConfig long timeout) for MySQL/Postgres/MongoDB; MySQL `.initProcess(true).stopSignal(SIGKILL)`, defaultStopTimeout 30s; Config.initProcess (--init/HostConfig.Init).
- BasePodTest: checkingContainerLeak wraps every leaf (list-before/after diff, inspect-verify); config.sequential (leaves sequential per daemon); leakCheckSockets(false); aroundLeaf -> 60s http timeout.

## User steering (binding)
- "aren't you masking a leak/lack of backpressure?"
- "do not workaround by limiting concurrency. Check that resources are freed properly before the test exits."
- "the execution doesn't really wait for the container to be fully retired."
- "do not assume it's a bug in podman" (find OUR root cause).
- "WHAT DID I SAY ABOUT HARDCODING" (no magic numbers / retry schedules).
- "Maybe we need a new leak check in kyo-test for this case?" (done: Info.execIds + ContainerItTest exec-session assertion).

## ROOT CAUSE OF (B) — FOUND + EMPIRICALLY CONFIRMED

(B) is a MISDIAGNOSIS: at delay=0 the container never dies. Sequence:
1. exec = create session -> `POST /exec/{eid}/start` (attached, returns at process exit) -> `GET /exec/{eid}/json` to read ExitCode (HttpContainerBackend.scala:751).
2. At delay=0 podman's conmon reaps the exec SESSION the instant the process exits.
3. The exit-code GET races the reaper and gets 404 "no such exec session".
4. `withErrorMapping(ctxContainer(id))` maps that 404 to ContainerMissingException(containerId) -> looks like "container vanished". Container is alive throughout.

exit_command_delay exists in podman precisely to give API clients a grace window to read exec exit codes post-hoc. delay=0 removed it. Confirmed locally (rootful podman, delay=0):
- immediate AND +0.5s `GET /exec/{eid}/json` both return 404 "no such exec session".
- `GET /libpod/events?stream=false&since=<t0>&filters={"container":[cid],"event":["exec_died"]}` returns exec_died with `Actor.Attributes.containerExitCode="7"` for `sh -c "exit 7"` (correct non-zero recovery). Event has time/timeNano but NO execId; Actor.ID is the container. Emitted (duplicated) reliably on this podman version.

Explains all evidence: only exec leaves fail; only [podman] http arm (shell reaps in-process; docker never reaps sessions); adjacent leaves pass; delay=10 had 0 ContainerMissing (grace window intact), its 126 AlreadyStopped were (A) pressure.

## FIX IMPLEMENTED + VALIDATED (commit e1a322cbf1)
- HttpContainerBackend.exec: on inspect-404 after a completed start, recover ExitCode from the exec_died event (libpod events, since=exec-start, container+exec_died filter, Actor.Attributes.containerExitCode; execId-match when present else most-recent). New ResourceContext.ExecSession + ContainerExecSessionMissingException so a genuine session miss is not mislabeled as a missing container.
- Validated end-to-end against a real delay=0 rootless podman via the kyo HTTP backend: `true`->0, `false`->1, `sh -c "exit 42"`->42, `echo hello`->"hello"+0 all correct through the event fallback; a 25-exec burst all succeed and leave no tracked exec sessions. (This also proved the earlier "rootless container vanishes" was THIS race, not cgroups.)
- Branch: edf2dc0859 (delay=0, fixes A) + a58dcda993 (execIds leak-check) + e1a322cbf1 (exec-race fix, fixes B). CI validation: run 31676973766 (linux-x64 x JVM/JS/Native/Wasm).

## STATUS AFTER e1a322cbf1 (validation run 31676973766, linux-x64 x 4 targets)
- (B) exec-race: FIXED. Wasm shows 0 ContainerMissingException (was the dominant delay=0 failure). The events fallback works cross-platform.
- (A) exhaustion: NOT fixed on the constrained Wasm runner. Wasm ContainerItTest 758/96: 192 ContainerAlreadyStoppedException + 23 Cannot-fork. delay=0 DID apply (setup log line 755). So delay=0 trades conmon-accumulation (delay>0) for a per-exec `podman container cleanup` process storm (delay=0) — BOTH exhaust the constrained runner's process/task table. exit_command_delay is confirmed the WRONG lever (as the user warned).
- NEW: podman network-backend races on Wasm (netavark/aardvark): "remove aardvark entries: No such file or directory" on DELETE, "network inspection mismatch: asked to join 2 network(s) but have information on 1". These are retirement/teardown races on network-using containers (kyo-net-* in ContainerOrchestrationItTest).
- Local: ContainerOrchestrationItTest passes 28/0 on the healthy unconstrained machine; (A) and network races do NOT reproduce locally. They are load/runner-specific.
- Interpretation: both (A) and the network races are the user's "the execution doesn't really wait for the container to be fully retired" — awaitRemoved waits for the record removal (/wait?condition=removed) but conmon exit, netavark teardown, and cgroup/keyring release can still be in flight; the next container/exec races the prior one's incomplete retirement. This is a BACKPRESSURE gap, not fixable by an exit_command_delay value.

## OPEN (A)+network: candidate fixes (need CI ground truth on the exhausted resource before choosing)
1. Retirement backpressure: make removal (and/or exec) wait for FULL teardown, not just the record. Need an API-observable "fully retired" signal.
2. Small exit_command_delay to spread cleanups (rejected: magic number, user-forbidden, fragile).
3. Identify exactly what accumulates (per-exec cleanup processes / conmon / threads / keyring) via a CI resource sampler, then target that resource's retirement.
Next: CI resource-sampler diagnostic on Wasm-x64 to pin the exhausted resource.

## LIMITS PROBE (runner ceilings) — RLIMIT_NPROC RULED OUT
On the ubuntu-latest runner (via `gh run view <id> --log`, NOT the jobs-logs API which drops composite-action output):
  ulimit_u_soft=63838 ulimit_u_hard=63838 threads_max=127677 pid_max=4194304 nproc=4 mem_mb=15989 swap_mb=11263 exit_command_delay=0
  baseline: threads=288 procs=173 conmon=0
RLIMIT_NPROC = 63838 (high). So the in-container `sh: Cannot fork` EAGAIN is NOT a too-low ulimit; raising it would not help. Candidates left: (a) the container's pids cgroup limit (~2048) filling, or (b) the host user genuinely reaching ~64k tasks (a thread leak in the memory-heavy Wasm driver over the run). A during-test sampler (RESMON, added to setup + a build.yml dump step) on a full Wasm-x64 run distinguishes these. Reap race (404 serial + 500 concurrent) is FIXED and is the only failure on JVM/JS.

## KEY: (A) is Wasm-specific
- JVM-x64 e1a322cbf1: ContainerItTest 426/3 (all 3 = concurrent-exec 500, fixed by ee4217e433). No exhaustion.
- JS-x64 e1a322cbf1: 851/3 (2 = concurrent-exec 500 fixed by ee4217e433; rest = intentional decode-test log noise). No exhaustion.
- Wasm-x64 e1a322cbf1: 758/96 = 192 ContainerAlreadyStopped + 23 Cannot-fork (EAGAIN). Failures steady from ~22s into the run (not cumulative). netavark races too.

## FIX PLAN (root-cause, per user steering)
1. HttpContainerBackend.exec: on inspect-404 after a completed start, recover ExitCode from the `exec_died` event (since=exec-create-time, container+exec_died filter, containerExitCode; match execId if a future podman includes it, else most-recent-in-window). delay=0 stays (required for A).
2. Stop mislabeling exec-session 404s: add ResourceContext.ExecSession(containerId, execId); use it for exec create/start/inspect so a genuine session-miss is not reported as container-miss.
3. Container.scala runHealthCheck (~:2197): confirm container aliveness before swallowing a ContainerMissingException as "container gone".
4. My leak-check leaf (ContainerItTest exec-sessions-reaped) is ALSO a victim of this race and will pass once the fix lands.

## Open question (RESOLVED — see above)
Why do containers stop/vanish mid-use under sustained load on the constrained rootless runners, when they never do at small scale locally and no OOM/keyring signature appears? Is it (a) generic memory/resource pressure accumulating over the run, (b) a race in a campaign change (readinessLoop / healthcheck / autoRemove pendingExit), or (c) pre-existing flaky? What is the correct root-cause fix (not a concurrency cap, not a podman-config tweak)?

## FINAL RESOLUTION (supersedes the delay=0 FIX PLAN above)

The delay=0 plan above was built on a misdiagnosis and is fully reverted. RESMON
resource sampling (added permanently to ci-monitor.sh) proved the (A) exhaustion
on the constrained Wasm/JS runners was MEMORY, not conmon or process-table:
the run-phase sbt driver holds 9-11GB RSS under .jvmopts -Xmx12G, dropping runner
availMB to ~900MB, which produces fork() EAGAIN ("Cannot fork") and OOM-killed
containers (surfacing as ContainerAlreadyStopped). Task/proc/conmon counts stayed
far below their ceilings (tasks peaked 594/64k, conmon <=5).

Fix (committed):
- ci-test.sh 6fd2a3269e: cap the run-phase driver heap to -J-Xmx6G for out-of-JVM
  targets (JS/Wasm/Native run tests in Node or a linked binary, so the run-phase
  driver needs no test heap). JVM keeps the full heap (tests run in-driver).
  Native run went green; JS/Wasm dropped to ~853/1 with healthy availMB.
- setup/action.yml a752ad8d89: revert exit_command_delay=0 to podman's default.
  At the default delay the exec inspect always succeeds, so the reap-race never
  occurs. delay=0 was the wrong lever (it traded conmon retention for a per-exec
  cleanup-process storm, and it reaped exec sessions before the HTTP backend could
  read their exit code).
- kyo-pod c4401de4c3: remove the now-dormant reap-race recovery machinery
  (exec_died event recovery, ExecSession context + exception, Info.execIds public
  field, ExecIDs DTOs). exec() is back to the clean create/start/inspect shape.

Standing signal: ci-monitor.sh samples availMB + tasks/procs/conmon every interval
(grep via ci-logs.sh --metrics), so a memory or fork-pressure regression is visible
in-run rather than surfacing as an unattributable container death.
