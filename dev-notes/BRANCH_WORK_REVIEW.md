# Branch `kyo-compat-external-bindings`: full work review

Comprehensive record of the entire body of work on this branch, for an independent
review. The branch began as a small enablement change and grew into a CI-stabilization
campaign across many modules. This document covers **all 98 commits** (vs `origin/main`
merge-base `2e9bb02d40`), the major investigations, the current state, and the open
decisions that would benefit from a second opinion.

Corpus size: **98 commits, 93 files, +2939 / -1551**.

---

## UPDATE (post-review execution)

Executed after the Fable review and validated:

- **PidsLimit fix** (`23f6a5e551`): the fork-EAGAIN root cause; kyo-pod suite goes 0/9 -> full green
  on real rootless podman arm64. Fable verdict SOUND.
- **Setup config** (`b5642334a3`): dropped the podman 4.9.3 pin and `exit_command_delay` (band-aids
  for the now-fixed bug; the full kyo-pod suite is green on real-CI serialization without them); kept
  `Delegate=yes` (deterministic controller delegation, not a mask).
- **Dead fork-pressure readiness retry reverted** (`1fe4aa581d`).
- **Comment hygiene** (`ae28b4f4e3`): Fable's full Group A/B/C inventory + the `build.sbt`
  false-invariant correction; dev-process narration stripped from source prose.
- Ship state validated: full kyo-pod suite (both runtimes) green under CI-faithful serialization
  (`fork-error lines: 0`, `failed leaves: 0`). Pushed; full matrix running for whole-branch feedback.

**kyo-http never-consumed-body leak (Fable Finding 2): DESIGN DECISION DEFERRED.** Attempting the
caller-Scope fix proved it is a framework-wide effect-model change: `& Scope` propagates through the
`HttpFilter` effect-typed machinery, all 42 public client methods, and ~10 consumer modules, forcing
`Scope` onto every HTTP op including the ~40 non-streaming ones. The only in-repo streaming path
(`getStreamBytes`) is already leak-free (lazy, send-on-consume); the leak is reachable only by a direct
`sendWith(streamingRoute)(f)` that ignores the body, which nothing in the repo does and which never
touches CI. Recommendation: document that `getStreamBytes` is the safe streaming API and a raw
streaming `sendWith` must consume its body, rather than impose the framework-wide `Scope` change.
Held for the maintainer's design sign-off.

Reviewer note: every claim below is traceable to a commit and to source you can open.
The headline item (Section G / the fork-EAGAIN resolution) is the one most worth
scrutinizing, because the diagnosis reversed twice before landing.

---

## 0. Purpose and shape of the branch

- **Origin**: `4a0746600b [kyo-compat] let backends be maintained outside the repo`, the
  actual feature: allow effect-system backends (cats, zio, ...) to live outside the repo.
- **What it became**: driving this branch to a fully green full CI matrix
  (JVM / JS / Native / Wasm x linux-x64 / linux-arm64 / windows-x64) surfaced a long tail
  of test flakiness and container-runtime problems. The bulk of the 98 commits are fixes
  for those, at root, not masks. This review is mostly about that tail.
- **Mandate driving the work**: only complete and correct fixes; no masking; find the
  root cause of every red signal; windows must be in the default matrix; 3 consecutive
  fully-green matrix runs before "done".

---

## 1. Corpus overview by theme

| Theme | Modules | Rough commit count | Status |
|-------|---------|--------------------|--------|
| A. External backends (feature) | kyo-compat | 1 | done |
| B. Test-reliability (sleeps -> deterministic) | ~18 modules | ~35 | done |
| C. kyo-sql io_uring fd-leak / connection custody | kyo-sql(+pg/mysql) | ~11 | done, container-validated |
| D. kyo-http streaming pool desync | kyo-http | 2 (+tests) | done |
| E. kyo-aeron flakiness | kyo-aeron | 5 | done |
| F. Windows CI fixes | kyo-net/http/ui/test/scheduler/pod | ~6 | done |
| G. kyo-pod container suite (fork-EAGAIN etc.) | kyo-pod + CI | ~25 | **root cause fixed; validation in progress** |
| H. CI infrastructure | .github, scripts, build.sbt, kyo-test | ~12 | mostly done; cleanup pending |

Main-source (behavior, not test) files touched: kyo-pod (5), kyo-sql-postgres (3),
kyo-sql-mysql (3), kyo-sql (2), kyo-http (2), kyo-test (1), kyo-reactive-streams (1),
kyo-net (1). Everything else is tests and CI.

---

## 2. Theme detail

### A. External backends (the feature)
`4a0746600b`. Lets backends be maintained outside the repo. Small, foundational; not the
subject of the CI campaign. **Review question: is the feature itself complete, or did the
CI campaign bury an unfinished feature?** (Belief: the feature is complete and the rest is
CI hardening, but worth confirming.)

### B. Test-reliability campaign (sleeps -> deterministic waits)
~35 commits across kyo-stm, kyo-core, kyo-flow, kyo-mcp, kyo-actor, kyo-compat, kyo-zio,
kyo-reactive-streams, kyo-http, kyo-browser, kyo-jsonrpc, kyo-caliban, kyo-scheduler,
kyo-scheduler-finagle, kyo-direct, kyo-ffi, kyo-stats-otlp, kyo-test.
Pattern: replace `Thread.sleep`/fixed settle delays and elapsed-time assertions with
deterministic barriers (Channel/Fiber/CPromise latches, `eventually`, outcome assertions).
Also: replaced GC-dependent leak tests (kyo-stm TRef, kyo-stats-otlp gauge) with
deterministic checks; made kyo-test power-assert instrumentation opt-in to cut
test-compile time (`14d1f4bbba`). Two rounds of held-out review findings folded in
(`0da6e17e10`, `5746314c53`). **Review question: any test here that now asserts less than
before (a weakened test masquerading as a de-flake)?**

### C. kyo-sql io_uring fd-leak + connection custody
~11 commits (`c24f3af0ec`, `a096908387`, `14e3061052`, `ce317e9d46`, `fd4f563a66`,
`dc4dd6b395`, `e46dc7d43c`, `cf48e7d5b8`, `715c3a2ab7`, `f0d0679375`, `7c109718f7`).
Root cause: an io_uring processSharedTransport fd-leak surfaced under the kyo-sql
conformance suites. The fix introduces a "custody" concept: a leased connection holds a
close-thunk with continuous custody across the acquire handover, guarding the orphan close
against a health-probe eviction, covering the stream path and cancel sidecars.
`Custody`/`custodyLocal` are `private[kyo]`. Validated with real Postgres/MySQL container
conformance runs. **Review question: the custody design touches the connection lifecycle
in several places (openSocket, warmUp, openDedicated, cancel sidecars, stream path) - is
the invariant ("a live fd always has exactly one custodian") actually upheld on every
path, or is there a window left uncovered?**

### D. kyo-http streaming pool desync
`4fc29b5158` (defer streaming-route pool release to body completion) + `6819f7325c`
(regression tests). A streaming route released its pooled connection before the body was
fully written, desyncing the pool. Fix defers release to body completion. **Review
question: does deferring release risk holding a pooled connection indefinitely if the body
never completes (interrupt/abort)?**

### E. kyo-aeron flakiness
`63995bc305`, `825d64461f`, `0640785a07`, `b4004ef2f2`, `f3ea3ec906`. Fan-out/topic tests
were racy on connect and on driver reuse. Fixes: readiness probe before fan-out publish;
probe until both consumers connect (no fixed ceiling); explicit timeout headroom for UAF
stress leaves; and (`f3ea3ec906`) distinct stream ids per round-trip so a lingering
closed-publication image from run 1 cannot deliver to run 2's subscriber. Validated
locally. **Review question: are these genuinely deterministic now, or still probabilistic
with a larger margin?**

### F. Windows CI fixes
`c77f3552dd` (kyo-net unbound-port connect vs Windows close-linger), `cf29ff1ac4`
(kyo-http retry transient local connect), `912c200e0b` (kyo-ui fast-refused loopback srcs
in iframe/img tests - a hanging external subresource was timing out withUI settle),
`6060cfe199` (kyo-scheduler ReporterTest tolerate Windows file-lock during atomic
replace), `4353a74e6f` (kyo-test JUnitXml skip read-only-write leaf on Windows),
`00fabfe717` (kyo-pod skip container ITs on Windows - Windows runners can't run the Linux
container images). **Review question: `4353a74e6f` and `00fabfe717` are platform SKIPS -
is each a legitimate platform-specific exclusion, or does it dodge a real bug?**

### G. kyo-pod container suite -- the headline (see Section 3 for the deep dive)
~25 commits. This is where most time went. Summarized here, detailed in Section 3:
- Container leak-check infrastructure in BasePodTest (verify via inspect, not the lagging
  list); HTTP remove waits for full retirement + re-force if DELETE lingers.
- `Config.initProcess` added; DB fixtures run under an init process (catatonit / docker-init).
- In-container readiness poll (one exec) instead of repeated host-side execs.
- Exec exit-code recovery machinery for reaped sessions -- later **removed as dormant**
  (`c4401de4c3`) once the root cause was understood.
- The fork-EAGAIN blocker: DB containers failed to fork under load. **Root cause finally
  identified as a kyo-pod code bug (PidsLimit:0 -> pids.max=1 on podman) and fixed at root**
  (`23f6a5e551`). This is the item most worth reviewing.

### H. CI infrastructure
- `.github/actions/setup/action.yml`: force runc (crun on the runners rejects the OCI
  config version); rootless podman service startup; cgroup controller delegation;
  `exit_command_delay` tuning; (removed) a podman 4.9.3 version pin.
- `scripts/ci-test.sh` / `ci-monitor.sh`: three-phase JVM/JS/Wasm split; run-phase driver
  heap cap for out-of-JVM targets (JS/Wasm/Native) to prevent OOM; fork-pressure headline
  metrics.
- `build.sbt`: concurrency restrictions (`SBT_TASK_LIMIT`, forked-test cap of 2 on CI).
- `ci.yml`: windows-x64 re-included in the default push/PR matrix.
- Many `TEMP ... (revert after)` diagnostic commits that were subsequently reverted; net
  state should carry none of them. **Review question: confirm no TEMP diagnostic survived.**

---

## 3. The fork-EAGAIN investigation (headline) -- diagnosis, reversals, root cause, fix

### Symptom
On `linux-arm64`, under the kyo-pod JVM test load, DB-image containers
(postgres/mysql/mongo) died during init with:
```
ERROR (catatonit:1): failed to fork child: Resource temporarily unavailable
ERROR (catatonit:1): failed to spawn pid1: Resource temporarily unavailable
/usr/local/bin/docker-entrypoint.sh: fork: retry: Resource temporarily unavailable
```
The container's PID 1 could not fork even once.

### What was ruled out (measured directly, ~20 CI diagnostics)
Every fork()-EAGAIN accountable cause was measured far from its cap at the failure:
RLIMIT_NPROC (63642, ~400 used), kernel.threads-max (127284), container pids cgroup
(pids.max=max in the isolated measurement), all parent cgroup pids budgets, kernel keyring
(4/200), memory (~10GB free). A single service-created container forked 5000+ in isolation.

### The two reversals (important -- these are where a reviewer should be skeptical)
1. **"podman version" hypothesis -- DISPROVEN.** Podman 5.8.4 (the "healthy" version)
   fails the DB suite identically to 4.9.3. Version pinning is not the fix.
2. **"rootless-specific" hypothesis -- DISPROVEN.** A same-runner/same-load A/B showed
   rootful **docker** 9/0 pass vs rootless **podman** 0/9 fail, which *looked* like a
   rootless-vs-rootful split. But running **podman itself rootful** (systemd-run daemon,
   Rootless=False, alpine runs) ALSO failed the DB suite 0/9 identically. So it is
   **podman-vs-docker, not privilege.**

### Root cause (measured, then confirmed)
A 0.2s sampler over the real failing suite found the failing container's own cgroup at
`.../user@<uid>.service/user.slice/<id>` with **`pids.max = 1`** (global tasks only peaked
at 403 / threads-max 127284 -- nothing systemic). A pids budget of 1 is why PID 1 cannot
fork. Probing container creation showed:

| create path | resulting cgroup pids.max |
|-------------|---------------------------|
| podman CLI `run` (with/without `--init`) | `max` |
| **docker-compat API create with `HostConfig.PidsLimit: 0`** (what kyo sends) | **1** |

Podman's docker-compat API **mistranslates an explicit `PidsLimit: 0` into cgroup
`pids.max = 1`**; docker treats `0` as unlimited. kyo-pod's `buildHostConfig` sent
`PidsLimit = maxProcesses.getOrElse(0L)`, i.e. `0` whenever no `maxProcesses` was
configured (all the DB fixtures). Same code, different daemon -> docker passed, podman
failed. A real-image confirmation: postgres via the API with `PidsLimit:0` exits with a
fork error; with the field **omitted** it runs, ready, `pids.max=max`.

### The fix (`23f6a5e551`)
`kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala`: make
`HostConfig.PidsLimit` and `UpdateRequest.PidsLimit` `Maybe[Long]` annotated
`@omit(omit.WhenAbsent)` (kyo Schema), set from `maxProcesses.filter(_ > 0)` -> the field
is omitted from the wire when no limit is configured, matching the podman CLI default of
unlimited. Regression test added to `ContainerItTest`: an init-process container with no
pids limit that must fork to stay alive.

### Validation
On real rootless podman arm64, `ContainerPredefItTest` went **0/9 -> 9/9** with the fix
alone (current setup). fork-error lines: 0.

---

## 4. Setup-action cleanup, and the honesty problem it exposes

The fork-EAGAIN was chased from the wrong direction before the root cause was found, which
left three CI "fixes" whose justification is now **false**:

1. **podman 4.9.3 pin** (`54c2a0313b`): based on the disproven version theory, and
   ineffective anyway (a preinstalled `/usr/local/bin/podman` 5.8.4 shadows the apt pin).
   **-> REMOVED** (uncommitted in the working tree).
2. **`Delegate=yes` cgroup drop-in** (`54c2a0313b`): its comment claims it is the "root fix
   for the fork-EAGAIN". That is false (the sampler showed pids WAS delegated -- by default
   on this runner -- and the cgroup was created, just with pids.max=1 from PidsLimit:0).
   **-> KEPT, comment corrected** to state its real purpose (deterministic controller
   delegation across runner images).
3. **`exit_command_delay = 30`** (`d7be605bd5`): bounds conmon reaping. Its original comment
   attributed the fork-EAGAIN cascade to conmon accumulation -- also not the root cause.
   **-> KEPT, comment corrected.**

**This is the sharpest open decision (see Section 6).** An experiment removing all three
band-aids (code fix only) left the fork-EAGAIN gone (good) but produced ~10 OTHER failures
in the full suite. However -- crucially -- those other failures turned out to be a **diag
artifact** (Section 5), not caused by the band-aid removal, which muddies the question of
whether delegation / exit_command_delay are actually needed at all.

---

## 5. The "container leak" failures were a diagnostic artifact (SBT_TASK_LIMIT)

Running the full `kyo-podJVM/test` in the diagnostics produced ~10 "leaf leaked N
container(s) not freed before exit" failures (one leaf reported **7** leaked -- more than
any single test creates). Cause: the diagnostics ran with default parallelism, but real CI
sets **`SBT_TASK_LIMIT=1`** (build.yml), which via `build.sbt` `Tags.limitAll(1)`
serializes all sbt tasks. Without it, two of kyo-pod's per-runtime forked suites can run
**two podman forks concurrently against the one podman service**, so a leaf's global
container-list leak check sees the *other* fork's containers and reports a false leak. The
"leaked 7" is that cross-fork contamination.

**CONFIRMED.** The CI-faithful run (`SBT_TASK_LIMIT=1` + CI `JAVA_OPTS`, shipping setup +
code fix) is **fully green**: `sbt rc=0`, `fork-error lines: 0`, `failed leaves: 0` -
ContainerItTest 430/0 (podman) + 430/0 (docker), ContainerPredefItTest 9/0,
ContainerOrchestrationItTest 28/0, all other suites 0 failed. So the leak failures were the
parallelism artifact, not a real teardown gap.

**Review question: is this diagnosis correct, or is there a real teardown-under-load leak
being hand-waved as "parallelism artifact"?** The leaked containers were a mix of
`[Running]` and `[Stopped]`; a real un-removed `[Stopped]` container would be a genuine
teardown gap, not contamination. This deserves a skeptical read.

---

## 6. Open decisions (where a second opinion is most valuable)

1. **Band-aid setup config.** Given the root cause is fixed in code, should
   `Delegate=yes` and `exit_command_delay=30` be **removed** (mandate: no masks), or are
   they legitimate podman CI configuration independent of the PidsLimit bug? Evidence is
   ambiguous because the removal experiment was confounded by the SBT_TASK_LIMIT artifact.
   The clean resolution is a CI-faithful (`SBT_TASK_LIMIT=1`) A/B of clean-setup vs
   band-aid-setup; that is the plan, but it is more CI cycles.
2. **Dead fork-pressure retry.** `a7d3ee4d30` added an `isForkPressure` retry to the DB
   readiness probe (`ContainerPredef.readinessLoop`), matching stderr like "resource
   temporarily unavailable" / "can't fork". With the root cause fixed, fork pressure no
   longer occurs, so this retry is **dead code that masks nothing real** and should likely
   be reverted. Confirm and remove?
3. **PidsLimit fix shape.** Omitting the field (chosen) vs sending `-1` both give
   `pids.max=max` on podman. Omission matches the CLI default and is minimal. Is there any
   API consumer (docker, older podman) that treats an *absent* PidsLimit differently from
   `0` in a way that regresses? (Belief: no -- absent == unset == unlimited for all.)
4. **`fe6725a297` / `3ebde07d5b`.** A create+start fork-pressure retry was added then
   reverted (it amplified pressure into a near-hang). Net no-op, but both commits remain in
   history -- fine for a working branch, but confirm nothing from `fe6725a297` leaked past
   the revert.
5. **Whole-corpus correctness.** 93 files, +2939/-1551, much of it test changes. The
   review question that matters: **did any "de-flake" quietly weaken a test's assertion**,
   and **is every platform skip legitimate**?

---

## 7. Current git state

- Branch `kyo-compat-external-bindings`, HEAD `23f6a5e551` (the PidsLimit fix, committed).
- Uncommitted working-tree change: `.github/actions/setup/action.yml` (pin removed,
  delegation/exit_command_delay comments corrected). Not yet committed pending the
  CI-faithful validation decision.
- The full matrix has **not** been re-run since the fix (pushing resets the "3 green"
  counter, so the intent is to validate the config first, then push once).

---

## 8. Path to done

1. Confirm CI-faithful serialized kyo-pod suite is green (in progress).
2. Decide the band-aid config (Section 6.1) and the dead retry (6.2); apply.
3. Commit the setup change; push branch (resets counter).
4. Drive the full matrix to 3 consecutive fully-green runs (all targets x all OSes incl.
   windows).

---

## Appendix: full commit list (oldest -> newest)

(98 commits; `git log --reverse <merge-base>..HEAD`.) The list is in the branch history;
notable anchors: `4a0746600b` (feature), the B-theme block `9292f78eea..fa3eda3faa`, the
C-theme block `c24f3af0ec..7c109718f7`, the G/H kyo-pod+CI block
`d00eed9bb1..a752ad8d89` and `5b374578cd..23f6a5e551`.
