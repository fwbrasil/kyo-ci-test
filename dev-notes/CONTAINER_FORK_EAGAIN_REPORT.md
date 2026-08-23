# CI stabilization: status, fixes shipped, and the one unresolved blocker

Branch: `kyo-compat-external-bindings` (pushed to fork `fwbrasil/kyo-ci-test`).
Goal: consecutive fully-green full-matrix CI runs (JVM/JS/Native/Wasm × linux-x64/linux-arm64/windows-x64).

## Fixed and committed (validated)

1. **`[ci] exit_command_delay=30`** — root fix for the dominant container failure. At the podman
   default (300s) every `exec`'s `conmon` lingers 5 min; across the kyo-pod suite they pile up (measured
   climbing past 200), and once that baseline is high a fresh container's PID 1 hits fork-EAGAIN,
   cascading into ~100 `[podman] http` failures. Bounding it to 30s made **ContainerItTest 428/0**
   (was 331/97) and eliminated the cascade in the isolated suite.
2. **`[kyo-aeron]` TopicUniformInvariantsTest flake** — two round-trips reused one aeron stream id on a
   shared driver, so the first run's lingering closed-publication image could deliver its payload to the
   second subscriber. Distinct stream ids isolate the runs. Validated locally.
3. **`[ci]` cgroup `Delegate=yes` drop-in + attribution logging** — guarantees controller delegation to
   the rootless user and makes podman version / cgroup manager / delegation greppable next to any failure.
4. **`[ci]` windows-x64 re-enabled** in push/PR runs (full matrix restored).
5. **`[kyo-pod]` teardown remove-retry** and **readinessLoop probe-retry** — bounded retries on transient
   transport failure (container cleanup) and on host fork-pressure during the DB readiness probe.

A DB-fixture create+start retry was tried and **reverted**: re-creating a container on fork-pressure
adds more forking and amplified the pressure into a near-hang.

## Unresolved blocker: DB-container fork-EAGAIN under the podman service + JVM load

`ContainerPredefItTest` (postgres/mysql/mongo) and some `ContainerOrchestrationItTest` cases fail with:

```
ERROR (catatonit:1): failed to fork child: Resource temporarily unavailable
sh: can't fork: Resource temporarily unavailable
/usr/local/bin/docker-entrypoint.sh: fork: retry: Resource temporarily unavailable
```

during a DB image's heavy init fork burst, on the `linux-arm64` rootless runner.

### What is ruled out (measured directly, 19 targeted CI diagnostics)

- **podman version**: NOT the cause. The suite fails on 5.8.4 (the "healthy" version) under the JVM
  load, exactly as on 4.9.3. (A single DB container started via the CLI on 5.8.4 is healthy.)
- **container pids cgroup**: `pids.max = max`, `pids.current` peaks at 7 (postgres) / 34 (mysql).
- **RLIMIT_NPROC**: the failing pid 1 (catatonit) has `Max processes = 63642`; the subuid runs only a
  few dozen processes.
- **kernel.threads-max**: 127284, with ~400 host threads at the failure.
- **parent cgroup pids**: `user@$uid.service` TasksMax=**infinity**, `user-$uid.slice`=42003, the GHA
  step cgroup (`hosted-compute-agent.service`)=19092 with `pids.current` peaking at **167**.
- **kernel keyring**: 4 / 200 keys.
- **memory**: ~10 GB available.

Every fork()-EAGAIN trigger is at well under 1% utilization, yet the fork fails.

- **container ulimit (service vs CLI)**: identical. A container created through the `podman system
  service` gets pid 1 `RLIMIT_NPROC = 63642` (same as a CLI-created one), and in isolation **forks
  5000 processes without EAGAIN**. So the container/service/ulimit are healthy on their own; the
  failure only appears once the kyo-pod JVM test process is driving the workload.

### The one strong correlation

The failure is **podman-service-specific and load-dependent**: containers created via the long-lived
`podman system service` (the kyo-pod HTTP backend) fail; the same images started via the podman CLI or
via docker are healthy, and a single service-less container is healthy. The failure needs the JVM test
process running (even the *first* DB test in an isolated `ContainerPredefItTest` run fails).

This points at a podman/runc/kernel interaction in the rootless container-init fork path under the
service, not any accountable resource limit — which is beyond what resource sampling can resolve.

## Options (need a decision)

1. **Kernel-level trace**: `strace -f`/`bpftrace` the failing `clone()` to get the actual reason the
   kernel returns EAGAIN (the only way to disambiguate, since every named limit is far from its cap).
2. **Run the container integration suite on a less-constrained runner** (larger/self-hosted) where the
   fork-EAGAIN does not occur, and gate arm64 on the rest.
3. **Treat it as an upstream podman/runc issue** on GitHub arm64 rootless runners and pin/patch the
   runtime accordingly.

Recommendation: (1) to get the definitive cause, then the corresponding fix.

## Cleanup owed

- The best-effort `podman=4.9.3` apt pin is ineffective (a preinstalled `/usr/local/bin/podman` 5.8.4
  shadows it) and based on the disproven version hypothesis; it should be removed.
- Throwaway `podman-diag` workflow (fork `main` + `ci/podman-diag` branch) to delete once done.
