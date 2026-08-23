# kyo-pod HTTP backend consistency analysis

Scope: the intermittent kyo-podJVM failures on the CI linux-arm64 rootless-podman runner (one run: 333 passed / 95 failed, every reported failure on the `http` backend arm). Runtime facts supplied from the runner: podman 5.8.4, runc 1.4.3, netavark, pasta, rootless, cgroup v2 with cgroupfs manager, and ~465 accumulated `conmon` processes by the end of the run. Static analysis only: no code changed, nothing run.

The question answered here, per failure category: **does the evidence point to a kyo-pod code bug (exact function + racing sequence) or to an environment/setup issue?** File references are relative to `kyo-pod/shared/src/main/scala/kyo/` unless another path is shown.

---

## 0. Summary of findings

1. **The container lifecycle in kyo-pod does not self-inflict the exec failures.** A full trace of every mutating actor (init teardown registration vs firing, `ensurePendingExit`, the `stop` wait-observer fiber, `pendingExit`, sibling ops under sequential leaves) shows **no kyo-pod code path that stops, kills, or removes a container between `HealthCheck.running`'s Running observation and the test body's `exec`** (section 2). The Running -> not-running regression happens outside kyo-pod.
2. **kyo-pod does carry four real code bugs** that shape, mislabel, or defer these failures (section 3): the health-check death-swallowing paths in `runHealthCheck`; the exec exit-code read that fabricates `0`; the wrong-state error classification in `mapHttpError`; and the wire-status-only 304 handling. One of them (exec exit code) fully explains the health-check-timing failure category on its own.
3. **The environment/setup side is not "podman is racy"; it is a specific, repo-visible CI configuration**: apt-installing the distro podman/conmon stack onto a runner that already ships podman 5.8.4 (`.github/actions/setup/action.yml:110`), a crun-version skew already being worked around by forcing runc (`action.yml:138-143`), and the API service run via `nohup` from a workflow step outside any systemd scope with the cgroupfs manager (`action.yml:152`). The measured ~465 leaked conmon processes are direct evidence that per-container exit/cleanup systematically fails on this setup; a service whose cleanup pipeline is wedged is exactly the process that mis-reports container state, delays exec exit-code recording, and double-runs network teardown. Only the http arm depends on that single long-lived process; the shell arm builds a fresh libpod runtime per CLI call (section 5).
4. **A build-configuration contradiction and a marker-detection false positive** (section 6): the build intends 2 concurrent forked test JVMs on CI (`build.sbt:99-103`, `213-216`) with "at most one fork per daemon", but CI sets `SBT_TASK_LIMIT=1` (`.github/workflows/build.yml:76`) whose `Tags.limitAll(1)` (`build.sbt:94`) serializes all tasks including forked groups; and the textual suite-marker detection (`build.sbt:2703-2706`) matches the *scaladoc comment* of `ContainerOrchestrationItTest`, forking that http-only suite per-runtime against its own documented expectation. The "one fork per daemon" guarantee is structurally unsound the moment forks actually run in parallel, because http-only suites (`runBackend`/`runBackendLong`) auto-detect the podman socket.
5. **"ALL failures on the http arm" is partially suite composition**: `ContainerOrchestrationItTest` and `ContainerPredefItTest` register http-only leaves by design (`ContainerOrchestrationItTest.scala:8-10`, `BasePodTest.scala:121-150`), so their failures are necessarily "http". The per-suite breakdown from the CI log is needed before treating the arm asymmetry as purely mechanical (section 7 lists the disambiguating log checks).

---

## 1. Post-condition inventory (unchanged facts)

### 1.1 HttpContainerBackend (`internal/HttpContainerBackend.scala`)

| Operation | Location | Current post-condition |
|---|---|---|
| `create` | 245-340 | `POST /containers/create`, success = 2xx + decoded `Id` (314). Daemon-synchronous. |
| `start` | 400-407 | `postUnitAccept304` (200-213): wire 2xx/304 = success; HTTP deadline >= 30s (405). No post-`Running` read. |
| `stop` | 409-431 | Pre-attached `/wait` fiber before `/stop` (415), deadline `timeout + 30s` (422), joins the wait then `awaitTerminalState` (427). |
| `awaitTerminalState` | 437-447 | 20 x 50ms poll to Stopped/Dead; **silent give-up** on exhaustion (443). |
| `kill` | 449-450 | Signal delivery ack only (matches daemon contract and CLI). |
| `restart` | 452-454 | Ack; daemon-synchronous; no deadline bump (unlike `stop`). |
| `pause`/`unpause` | 456-460 | Ack; daemon-synchronous. |
| `remove` | 462-472 | `DELETE ?force&v`; ack; force bumps deadline to 30s; no gone-verification. |
| `waitForExit` | 477-512 | `/wait` long-poll; `ContainerMissingException` -> `ExitCode.Success` (492-494). |
| `exec` | 654-696 | exec-create (657-659, no state precondition), `/exec/{id}/start` blocking collect (664-669), one `GET /exec/{id}/json` (675-677); exit code from `ExecInspectResponse(ExitCode: Int = 0)` (2517-2519, read at 679) — `Running` not decoded, absent code decodes to 0. |
| `execStream`/`execInteractive` | 698-770 | exec-create + hijacked connection; no exit code. |
| `top` | 610-627 | GET; no state gate; failures classified generically. |
| `networkCreate` | 1733-1765 | Ack + returns `Network.Id(config.name)`. |
| `networkConnect` | 1795-1828 | Ack; wire 403 = idempotent already-connected (1817); no visibility verification. |
| `networkDisconnect` | 1830-1842 | Ack. |
| `networkRemove` | 1781-1793 | Pre-guard: refuses while endpoints attached (1782-1787); then DELETE. |
| `volumeCreate` | 1857-1867 | Success = decoded `Info`. |
| `volumeRemove` | 1885-1888 | `DELETE ?force`; no referencing-container handling. |
| error mapping | 74-105, 2783-2810 | canonical 304 -> `ContainerAlreadyStoppedException` (87-89); 404 -> missing; 409 -> `ContainerAlreadyExistsException` via `conflictFor` (93-94, `internal/ErrorClassification.scala:31-35`); everything else -> generic `ContainerOperationException` (95-99). `inferStatusFromMessage` (2802-2810) has **no wrong-state phrases** ("container state improper", "is not running", ...). |

### 1.2 ShellBackend (`internal/ShellBackend.scala`) and the parity gaps

Shell post-conditions: CLI exit 0 per op (`start` 156-157, `stop` 159-161 + the same silently-exhausting `awaitTerminalState` 167-177, `kill` 179-180, `restart` 182-183, `remove` 191-197, `waitForExit` 202-238 with inspect fallback 246-259, network ops 1693-1868, `volumeRemove` with referencing-container pre-handling 1953-1978). `exec` (585-692) takes its exit code from the exec process's own `waitFor` (643) and classifies daemon stderr phrases, including `"container state improper"` and `"exec sessions on running"` (`isDockerDaemonError` 2320-2328) and `"is not running"`/`"already stopped"` -> `ContainerAlreadyStoppedException` (`ErrorPatterns.AlreadyStopped` 2252-2253, table entry [5] at 2121-2126).

HTTP weaker than shell: exec exit-code source (W1), wrong-state classification (W2), no transient-retry hook around exec (W3, cf. shell 597-614), `volumeRemove` force semantics (W4), `restart` deadline (W5). HTTP stronger than shell: `stop`'s pre-attached wait; `networkRemove`'s endpoint guard.

---

## 2. The lifecycle trace: kyo-pod does not kill its own container between health-check and exec

The dominant failing shape is `Container.init(alpine).map { c => c.exec(...) }` with the long-lived trap-TERM/sleep-infinity fixture (`shared/src/test/scala/kyo/ContainerItTest.scala:5-7`) and the default `HealthCheck.running` (`Container.scala:968`, check at 1129-1142). Every mutating actor that exists between the health-check pass and the body's exec:

1. **The init teardown** is *registered* before start (`Scope.ensure` at `Container.scala:486-517`, registration precedes `b.start(cid)` at 519) but *fires* only when the enclosing Scope closes — for `runBackends` bodies that is the end of the test leaf (the body's effect type carries `Scope`, discharged by the harness; `BasePodTest.scala:64-77`). It cannot run concurrently with the body.
2. **`ensurePendingExit`** (`Container.scala:93-107`) attaches only a read-only `/wait` observer, and only when `config.autoRemove` is true — the default is false (`Container.scala:963`) and the alpine fixture does not set it. It never sends a signal.
3. **`stop`'s temporary wait fiber** (`HttpContainerBackend.scala:415-418`) is interrupted via `Sync.ensure` when stop exits; read-only regardless.
4. **Sibling containers/ops**: leaves are sequential within a fork (`BasePodTest.scala:33-43`, `override def config = super.config.sequential...`), `initAll` awaits each container before the next (`Container.scala:614-622`), `statsStream`/`logStream` fibers are read-only.
5. **Post-health init steps** (`waitForPortMappings` 2273-2307, `probePortConflict` 2235-2265) are inspect/list-only.

Conclusion: **no kyo-pod function issues a state-mutating call on the container in the window where the daemon flipped it out of Running.** The teardown, kill, waitForExit, and pendingExit machinery the reframing asked about are all either strictly-after-body, read-only, or gated off by `autoRemove = false`. The regression the daemon reports ("can only create exec sessions on running containers") therefore originates outside kyo-pod code — see section 5 for where.

One deliberate fixture choice is worth naming because it shapes the *daemon-side* load profile: `alpine.stopTimeout(0.seconds)` (`ContainerItTest.scala:7`) makes every leaf's teardown a `/stop?t=0` (near-immediate SIGKILL) followed by `remove(force = true, removeVolumes = true)` (`Container.scala:507`, 514). That is contract-legal, but it routes every leaf's network/exit cleanup through the kill path — the path whose systematic failure the 465 leaked conmons measure.

---

## 3. kyo-pod code bugs (real, file:line, independent of environment)

These do not create the daemon-state regression, but they are genuine defects: two of them convert daemon trouble into *wrong test outcomes* (false pass, wrong exception type), and two convert it into *undiagnosable* ones.

- **B1 — `runHealthCheck` silently swallows container death during init** (`Container.scala:2164-2216`): `isContainerAlive` false -> return `()` (2167-2168); `ContainerMissingException` from a check attempt -> `()` (2181); `stillAlive` false between retries -> `()` (2193-2194). A container that dies right after `/start` escapes `init` as success; the failure then surfaces at the first `exec`/`top` with the daemon's confusing wrong-state message instead of at init with a truthful one. (The one-shot `command("true")` use case, `ContainerItTest.scala:603-611`, explains the *intent*, but the current form cannot distinguish "exited by design" from "died".)
- **B2 — `exec` fabricates exit code 0** (`HttpContainerBackend.scala:675-679`, DTO at 2517-2519): `ExecInspectResponse` decodes only `ExitCode: Int = 0`; `Running` is ignored; a single snapshot is taken immediately after the start-stream closes. If the daemon has not yet recorded the exit code, kyo-pod reports success-with-0. This alone produces failure category F5 (always-failing health checks passing).
- **B3 — wrong-state responses are mis-typed** (`HttpContainerBackend.scala:87-99` + `2802-2810`): podman-compat reports wrong-state errors with HTTP 500 bodies carrying stable phrases; `inferStatusFromMessage` has no entry for them, so exec/top/kill-family wrong-state errors become generic `ContainerOperationException`, and lifecycle 409s become `ContainerAlreadyExistsException` (93-94) — the shell backend types the same daemon conditions as `ContainerAlreadyStoppedException` (`ShellBackend.scala:2252-2253`, 2320-2328). The two backends present different exception types for identical daemon states.
- **B4 — 304 handling is wire-status-only** (`HttpContainerBackend.scala:200-213` vs 87-89): `postUnitAccept304` treats only wire-304 as idempotent success, while `mapHttpError` maps *canonical* (body-derived) 304 to `ContainerAlreadyStoppedException` — so a shim response whose wire and canonical statuses disagree leaks `ContainerAlreadyStoppedException` out of operations that are documented idempotent. Together with the `stats` state guard (572-574) these are the only two emitters of that exception type in the HTTP path.

Also latent (not implicated by this run's taxonomy, but found while tracing): the fixed host port 18080 in `ContainerItTest.scala:3499` exists in both the `#podman` and `#docker` fork of the suite and would collide if forks ever run concurrently; `awaitTerminalState`'s silent exhaustion (both backends) converts a real stop failure into a downstream flake.

---

## 4. Per-category verdicts: code bug vs environment/setup

### F1 — 102x exec-create -> 500 "can only create exec sessions on running containers: container state improper"

**Verdict: environment/setup causes the state regression; kyo-pod bugs B1 + B3 shape and mislabel it. Not a kyo-pod lifecycle race.**

- The refusal is libpod's own state check under the container lock; the daemon's authoritative view was not-Running at that moment. kyo-pod had verified Running via the same daemon during init (`HealthCheck.running`, `Container.scala:1129-1142`).
- Section 2 rules out every kyo-pod mutating actor in the window. The remaining actor set is daemon-side: the `podman system service` process (which serves *only* the http arm; `ShellBackend` spawns the CLI, `ShellBackend.scala:2049-2086`, which builds its own libpod runtime per invocation) and its conmon/cleanup children. Setup evidence in section 5 shows that pipeline is systematically failing on this runner.
- kyo-pod's contribution: B1 lets a container that died during init masquerade as healthy until the exec; B3 renders the failure as an opaque HTTP-500 `ContainerOperationException` instead of the truthful `ContainerAlreadyStoppedException` the shell arm would produce, which is why 102 of these read as inscrutable backend noise rather than "the container was not running".

### F2 — 2x "top can only be used on running containers"

**Verdict: same as F1** (same daemon condition through `top`, `HttpContainerBackend.scala:610-627`; same B3 mislabeling; `Container.top` has no state semantics of its own, `Container.scala:230-235`).

### F3 — ~8x multi-network create/remove -> "network inspection mismatch ... internal libpod error"

**Verdict: environment/setup. kyo-pod's ordering is verified contract-correct; no wrong-order removal exists.**

- Sequencing trace for the failing test (`ContainerItTest.scala:3414-3441`): networks are created *before* the container (`Container.Network.init` registers `Scope.ensure(networkRemove)`, `Container.scala:1677-1685`); Scope finalizers run LIFO, so teardown is container `stop(t=0)` + `remove(force, v)` first (`Container.scala:500-515`), then net2, then net1. The container is never removed after its networks; `networkRemove` additionally pre-guards on attached endpoints (`HttpContainerBackend.scala:1781-1793`). Not disconnecting before `remove -f` is permitted by the daemon contract (removal tears down endpoints).
- The mismatch error itself fired during the *body* (connect net1, connect net2, inspect — strictly sequential calls on one backend), where no kyo-pod ordering can be at fault: it reflects the daemon's internal network-config/DB divergence.
- kyo-pod's only contribution is the load profile: every leaf's kill-path teardown (stopTimeout 0) plus `remove -f` runs the daemon's rm-cleanup concurrently with conmon's exit-command cleanup for the same container; on a runner where cleanup is wedged (section 5) those pairs misfire.

### F4 — 2x netavark "remove aardvark entries: No such file or directory (os error 2)"

**Verdict: environment/setup.** Same teardown-pair analysis as F3: the aardvark state files were already gone when the second cleanup actor ran. kyo-pod's removal ordering is correct; the double-teardown is inside the daemon's kill/cleanup pipeline, and the ~465 leaked conmons show that pipeline is not healthy on this runner.

### F5 — 2x health-check timing: expected `ContainerHealthCheckException`, got a running Container

**Verdict: kyo-pod code bug (B2), outright.** The always-failing checks (`sh -c "echo ...; exit 1"`, `ContainerItTest.scala:740-768`; wget pipeline, 771-788) pass iff `result.isSuccess` (`Container.scala:1173`); B2 turns "exit code not yet recorded" into exit code 0. The environment (delayed exit-code recording on a degraded service) only widens a window kyo-pod should never have had: the shell arm is immune because its exit code comes from process wait. Deterministically fixable in kyo-pod regardless of the runner.

### F6 — several `ContainerAlreadyStoppedException` (stop/kill idempotency)

**Verdict: kyo-pod code shape (B3 + B4), environment-triggered.** These tests are http-only leaves (`ContainerOrchestrationItTest.scala:193-216` via `runBackend`, `BasePodTest.scala:121-132`), so no shell counterpart exists — "shell always passes" is vacuous here. The only HTTP emitters of this exception are `mapHttpError`'s canonical-304 arm (87-89) and the `stats` guard (572-574); with B4, any wire/canonical-status disagreement or a stats/state read racing an exit surfaces it from operations documented as idempotent. Pinning the exact emitting site per CI instance needs the log text (the message carries the operation context) — listed in section 7.

---

## 5. The environment/setup evidence (repo-visible, all in `.github/actions/setup/action.yml`)

1. **Double container stack.** `bash scripts/apt-install.sh podman uidmap` (action.yml:110) installs Ubuntu's podman + conmon (runtime-reported: podman 4.9.3, conmon 2.1.10) onto a runner that already ships podman 5.8.4 — the version that actually runs, per the runtime report. The load-bearing residue of the apt step is therefore the *companion* stack (conmon, netavark/aardvark versions, a second `podman` binary on disk) sitting next to a newer engine. podman resolves conmon/netavark from fixed search paths; an older conmon paired with a newer engine is precisely the combination that breaks the exit-file/exit-command handshake.
2. **Known component skew already being worked around.** The step forces `runtime = "runc"` because "the crun shipped on the runners fails to start containers with 'crun: unknown version specified'" (action.yml:138-143) — i.e. the runner's crun predates the OCI config the engine writes. This documents that the runner's container components are version-skewed; conmon is the same class of companion binary, without the workaround.
3. **The service runs outside any supervisor and outside a systemd scope**: `nohup podman system service --time=0 unix://$sock &` from a workflow step (action.yml:152), with linger enabled (128) but no `systemd-run`/user unit. Rootless podman without a systemd session falls back to the cgroupfs manager — a condition the kyo-pod suite itself documents ("rootless podman without a systemd user session emits `cgroupv2 manager` warnings on every command", `ShellBackend.scala:2045-2048`). In this mode container processes and the accumulating conmons live in the runner job's cgroup with no delegation and no supervisor to reap or restart anything.
4. **Measured cleanup failure: ~465 conmon processes.** One conmon per container that never completed its exit path; with leaves running serially, that is roughly every container of the run. A daemon whose per-container exit/cleanup pipeline is wedged is the direct producer of: state reads that flip to not-running (F1/F2), unrecorded exec exit codes (widens F5), double/late network teardown (F3/F4), and progressively slower responses (already acknowledged by the test-side 60s HTTP timeout bump, `BasePodTest.scala:45-54`).
5. **Why the http arm bears the damage**: every http-arm operation flows through that single long-lived service process; every shell-arm operation is a fresh CLI process building its own libpod runtime against the store. The service is the only accumulation point in the system — exec-session registry, event queue, cleanup handlers, and 400+ leaves worth of degradation — and it serves only the http arm.

Setup-level remediation is environment work, not kyo-pod code: run the service under a supervisor in a proper systemd user scope (or at minimum with matched conmon/netavark from the same distribution as the 5.8.4 engine), stop installing the distro podman stack over the runner's newer one (install only what is missing, e.g. `uidmap`), and assert at setup time that `podman info` reports the intended conmon path/version the way the runc pin is already asserted (action.yml:166-171). The 465-conmon count is the regression signal for this: a healthy setup holds it near zero.

---

## 6. Build-configuration findings (our setup, `build.sbt` + workflow)

1. **Serialization vs the intended 2-wide fork parallelism.** CI sets `SBT_TASK_LIMIT=1` (`.github/workflows/build.yml:76`), which becomes `Tags.limitAll(1)` (`build.sbt:94`) — at most one sbt task of any kind at a time, which serializes forked test groups as well (forked groups are engine tasks tagged `Tags.ForkedTestGroup`; that is what the cap at `build.sbt:107` exists to govern). Meanwhile `build.sbt:99-103` documents a CI fork cap of 2 "so kyo-pod ... ends up with at most one fork per daemon", and `build.sbt:213-216` sizes fork heaps for "two 5GB forks" on the 16GB box. These two beliefs contradict: either forks are serial on CI (the limitAll reading — then the per-daemon invariant holds trivially today, the 2-fork sizing comment is dead, and daemon contention cannot come from fork overlap), or groups somehow escape `limitAll` and forks do overlap — in which case the next finding makes same-daemon overlap likely. **Which of the two actually happens is directly visible in the CI log (interleaved vs sequential suite output) and must be confirmed there** (section 7).
2. **"One fork per daemon" is structurally unsound under any real parallelism.** The scheme pins only suites whose *source text* contains `runBackends`/`runRuntimes` (`build.sbt:2700-2706`). Http-only suites (`runBackend`/`runBackendLong`, `BasePodTest.scala:121-150`) auto-detect the podman socket first (podman-preferred order at 122-126) — `ContainerPredefItTest` contains no marker string (verified: zero occurrences) so it forks once, unpinned, and always lands on the podman daemon. Any parallel regime can therefore co-schedule it with `ContainerItTest#podman`: two forks, one daemon, violating the invariant the suite's sequential-leaves design depends on (`BasePodTest.scala:33-43`, which itself documents that parallel daemon access produces "port conflicts, already-exists, image-pull, and backend errors").
3. **Textual marker detection false positive.** `ContainerOrchestrationItTest`'s *scaladoc* contains the literal `runBackends` (`ContainerOrchestrationItTest.scala:10`), so the `src.contains("runBackends")` check forks the suite per-runtime — directly contradicting the suite's own doc ("the build's testGrouping does not fork it per runtime — every test runs once total", lines 8-10). Consequence today: the whole http-only suite runs twice (once pinned podman, once pinned docker via `KYO_POD_RUNTIME` honored in `ContainerRuntimeBase.scala:56-58`), doubling http-arm container load and wall time; consequence under parallelism: another unpinned-vs-pinned same-daemon pairing source.

---

## 7. Disambiguating evidence to pull from the CI run (log-level, no re-run needed)

1. **Per-suite failure breakdown**: how many of the 95 failures are in `ContainerItTest#podman` http leaves (which have shell siblings) vs `ContainerOrchestrationItTest`/`ContainerPredefItTest` (http-only by design)? If the bulk is in the http-only suites, the "all failures http" statistic is largely composition, and time-of-run (late-run degradation) becomes the operative variable.
2. **Fork scheduling**: does the log show suite groups interleaving (parallel forks despite `SBT_TASK_LIMIT=1`) or strictly sequential groups? This resolves finding 6.1 and decides whether same-daemon fork overlap is in play at all.
3. **Failure time distribution**: do the exec-500s cluster in the later portion of the run (consistent with progressive service/conmon degradation) or uniformly?
4. **`/tmp/podman.log`** (the service's output, captured at `action.yml:152`): state-refresh lines, cleanup errors, netavark/aardvark messages at the failure timestamps.
5. **conmon parentage and count over time**: whether the ~465 conmons are children of the service's containers only or of CLI containers too, and which conmon binary path they run (`/usr/bin/conmon` = apt 2.1.10 vs the runner's own).
6. For the F6 instances: the exception messages in the log name the operation context (`ctx.describe` is embedded), which pins the emitting site (`mapHttpError` 304 arm vs `stats` guard).

---

## 8. Corrective work implied (grouped by owner; no "retry because the daemon is racy" anywhere)

kyo-pod code (deterministic fixes for the real bugs of section 3):
- **B2**: decode `Running` and `ExitCode: Option[Int]` in `ExecInspectResponse`; treat an unrecorded exit code as an error or re-read until recorded within the operation's existing deadline; never fabricate 0 (`HttpContainerBackend.exec`, 654-696).
- **B3**: extend the shared `DaemonErrorPhrases` (`internal/ErrorClassification.scala:49-75`) with the wrong-state vocabulary both engines emit and that `ShellBackend` already matches (2252-2253, 2320-2328); make `mapHttpError` produce `ContainerAlreadyStoppedException` for wrong-state conditions on `ResourceContext.Container` instead of generic 500s and instead of `ContainerAlreadyExistsException` for lifecycle 409s (87-99). This restores identical exception semantics across backends.
- **B4**: accept canonical-304 (not only wire-304) in `postUnitAccept304` (200-213).
- **B1**: make `runHealthCheck`/`init` distinguish "exited by design" from "died during init": when the container is found dead/missing during the health phase and the config is not a one-shot (no immediate-exit expectation), fail init truthfully (with the container's `State`/`ExitCode`/`FinishedAt` in the message) instead of returning success (`Container.scala:2164-2216`). This moves F1-type failures from "opaque exec 500 later" to "diagnosable init failure now" whenever the death happens inside init.
- Small parity items from section 1: `volumeRemove(force)` semantics (W4), `restart` deadline bump (W5), and failing (not silently exhausting) `awaitTerminalState` in both backends.

CI setup (environment root cause of F1-F4's daemon-state regressions):
- Stop installing the distro podman/conmon stack over the runner's 5.8.4 engine; install only missing prerequisites; assert the resolved conmon path/version at setup like the runc pin is asserted (`action.yml:106-171`).
- Run `podman system service` under a systemd user scope/supervisor rather than `nohup` from a step (`action.yml:152`), so cleanup children are reaped and the cgroup manager is the supported one.
- Track the end-of-run conmon count as the health signal for this (expected ~0).

Build configuration:
- Resolve the `SBT_TASK_LIMIT=1` vs `ForkedTestGroup=2` contradiction explicitly (`build.yml:76`, `build.sbt:94-107`, 213-216) once the log confirms the actual behavior; if fork parallelism is wanted, the per-daemon exclusivity must be made structural (per-daemon sbt tag or pinning http-only suites via `KYO_POD_RUNTIME`), not inferred from a cap of 2.
- Fix the marker detection false positive (`build.sbt:2703-2706` matching scaladoc text; `ContainerOrchestrationItTest.scala:10`), e.g. match call sites rather than raw substrings, so http-only suites fork once as their design states.

---

## Appendix: API-semantics references used

- Docker Engine API: lifecycle endpoints are daemon-synchronous; `POST /containers/{id}/wait` supports `condition=not-running|next-exit|removed` only — https://docs.docker.com/reference/api/engine/version-history/ , https://docker-docs.uclv.cu/engine/api/v1.40/
- Podman libpod-native wait accepts the full state set (`condition=running|stopped|...`, repeatable) — https://docs.podman.io/en/stable/markdown/podman-wait.1.html
- Exec create requires state Running (checked under the container lock); the exec-inspect `ExitCode` is meaningful only once the session stopped, and session `Running` can lag the process exit through the REST API — https://github.com/containers/podman/issues/18424
- Wrong-state exec surfaces as "can only create exec sessions on running containers: container state improper" — https://www.ibm.com/support/pages/error-can-only-create-exec-sessions-running-containers-container-state-improper , https://github.com/kubernetes-sigs/kind/issues/2464
- "network inspection mismatch ... internal libpod error" is libpod network-config/DB divergence — https://github.com/containers/podman/issues/9234
- aardvark/netavark ENOENT during teardown — https://github.com/containers/podman/issues/24622 , https://github.com/containers/podman/issues/24367 , https://github.com/containers/podman/issues/16956
- Rootless state refresh can drop running-container state — https://github.com/containers/podman/issues/9849
