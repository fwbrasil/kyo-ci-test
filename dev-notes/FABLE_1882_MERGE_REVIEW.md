# Review: merge of PR #1882 onto kyo-compat-external-bindings (commit 6081551098)

States compared: OURS pre-merge = `5c3c818387`, THEIRS = `pr-1882` (base `origin/main`), MERGED = HEAD `6081551098`.
Method: hunk-by-hunk audit of `git diff origin/main pr-1882` (Damian's complete change, 9 files) against
`git diff 5c3c818387 HEAD` (what the merge changed) and the working files. Every hunk of both sides is
accounted for below. Static analysis only; no build was run.

## Verdict summary

| Area | Verdict |
|---|---|
| 1. `.github/actions/setup/action.yml` | CORRECT |
| 2. `HttpContainerBackend.scala` (pids + visibility) | CORRECT (ours equivalent-or-superior; exactly his 6 relaxations; his tests verbatim) |
| 3. Cleanly-applied files (scripts, test, workflows, release-setup) | CORRECT (byte-identical to pr-1882; callers/context verified on our branch) |

No improvement from either side was lost. Details and minor observations follow.

## Area 1: `.github/actions/setup/action.yml` - CORRECT

Damian's delta vs his base is exactly 4 hunks; disposition of each in HEAD:

1. **Install podman rewrite** (`--if-missing podman uidmap` + `--if-missing runc`, with the #1881
   conmon/journald root-cause comment): adopted verbatim (HEAD lines 105-124).
2. **runc-pin comment update** ("pre-20260810 images ... pin safe in both worlds"): adopted verbatim
   (HEAD lines 152-158). The pin itself (`containers.conf` with `runtime = "runc"`) is present **exactly
   once** (lines 157-158); no duplicate from our old version survives.
3. **Replace inline diagnostics + docker check with `scripts/container-check.sh`**: adopted (HEAD 185-189).
4. **Prepare libaeron `--if-missing cmake uuid-dev`**: adopted verbatim (HEAD 309-312).

Our-side improvements, all retained:
- Cgroup-controller delegation: `Delegate=yes` drop-in + `daemon-reload` + `systemctl restart
  user@$uid.service` (HEAD 131-139). Main/pr-1882 never had this; it is intact.
- Delegation attribution echo (HEAD 149-151).
- podman version / cgroupManager echo, `cgroup|rootless` grep, `podman version` (HEAD 181-184).

**Does container-check.sh cover what our dropped lines did?** Yes, as a superset:
- Our dropped inline `docker version` check with the containerd.io/runc-conflict diagnosis is reproduced
  in `check_docker` (container-check.sh lines 60-69) with the same diagnosis text, and improved: both
  checks always run, so a broken podman does not hide docker's state.
- Our dropped `podman info | grep -iE 'ociRuntime|runc|crun'` print is subsumed by container-check.sh
  line 40: `grep -iE 'ociRuntime|runc|crun|conmon|rootless|cgroup|path:'` (a strict superset), including
  the "printed rather than asserted on" rationale comment.
- On top of both, the gate adds what neither side's old inline code had: a real create + start + exec
  probe, which is the exact #1881 failure cycle.

**Internal consistency of the merged step**: `uid` defined (line 130) before all uses (135-151);
`XDG_RUNTIME_DIR` exported (148) before the containers.conf/socket section (159-160); `sock` defined
(160) before the retry loop; no orphaned or duplicated lines; ordering matches our pre-merge step with
the two Damian hunks spliced at the correct points. The Install-podman comment's "the `docker version`
probe below" still resolves correctly (the probe now lives in container-check.sh, invoked below).

Minor observations (not defects):
- HEAD lines 183-184 (`podman info | grep 'cgroup|rootless'`, `podman version`) duplicate output
  container-check.sh prints moments later (its line 37 `podman version`, line 40 grep). Damian removed
  the main-side equivalents as redundant; we kept ours by explicit decision. Harmless log duplication;
  optional cleanup.
- The duplicate "Install liburing (Linux)" step pair (HEAD 86-89 script-based, 94-103 via
  nick-fields/retry, which the file's own lines 74-76 say not to use) exists identically on
  origin/main, pr-1882, and our pre-merge. Pre-existing upstream issue, untouched by this merge;
  worth a follow-up on its own.

## Area 2: `HttpContainerBackend.scala` - CORRECT

File: `kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala` (shared, not jvm).

**Pids fix, ours vs his, all cases.** His change: `PidsLimit = config.maxProcesses` /
`PidsLimit = maxProcesses` at the create and docker-compat-update sites, fields `PidsLimit: Maybe[Long]
= Absent` (no `@omit`). Ours (kept): `PidsLimit = config.maxProcesses.filter(_ > 0)` (line 380) and
`maxProcesses.filter(_ > 0)` (line 1277), fields `@omit PidsLimit: Maybe[Long] = Absent` (lines 2321,
2365) with root-cause comments.

- Unset (`Absent`): both encode the field as absent. Kyo's JSON record encoding omits an Absent
  top-level `Maybe` field even without `@omit` (verified against
  `kyo-schema-json/shared/src/test/scala/kyo/JsonTest.scala`, "Maybe nesting works correctly in case
  class fields": `NestedMaybe(Maybe.empty)` encodes to `{}`), so his version and ours are wire-identical
  here; our `@omit` is belt-and-braces plus documentation. Equivalent.
- Set positive (e.g. 64): both encode `"PidsLimit":64` (Present delegates to the inner Long schema).
  Equivalent.
- Explicit 0 (`maxProcesses(0)`): his encodes `"PidsLimit":0`, which podman 5.x applies literally
  (pids.max=0/1: PID 1 cannot fork, the #1881 symptom); ours drops it, preserving the docker /
  podman-4.x "0 = no limit" semantics uniformly. **Ours strictly superior.**
- Negative: his sends it through (backend-dependent meaning); ours omits (unlimited). Same practical
  outcome; ours uniform.
- Both cover the same two call sites; `updateLibpod`'s `maxProcesses.toOption.filter(_ > 0)` (line 1241)
  was already filtered on both sides (pre-existing on main).

**Visibility relaxation.** `git diff 5c3c818387 HEAD` on this file shows exactly six `private ->
private[internal]` changes: `buildHostConfig` (line 362), `HostConfig` (2308), `PortBindingEntry`
(2333), `RestartPolicyEntry` (2338), `MountEntry` (2344), `UpdateRequest` (2359). That is exactly
Damian's set: no over- and no under-relaxation. (`apiVersion`/`url` were already `private[internal]` on
our branch before the merge; unrelated.)

**Anything else in his diff dropped?** No. `git diff origin/main pr-1882` on this file contains only the
pids changes and the six visibility changes; there is nothing else to lose. The remaining
pr-1882-vs-HEAD delta (106+/26-) is our branch's own kyo-pod work relative to main (Init field, meter,
apiVersion, libpod update path, comments), untouched by his PR.

**Test compatibility (static).** The adopted tests are byte-identical to pr-1882
(`git diff pr-1882 HEAD` on the test file is empty) and line up with our signatures:
`new HttpContainerBackend("/unused.sock")` matches the constructor (socketPath, defaulted apiVersion,
defaulted meter); the named `buildHostConfig(config, binds, portBindings, networkModeStr, tmpfs,
restartPol)` call matches the merged 6-parameter signature exactly; `backend.RestartPolicyEntry("no",
0)` and `backend.UpdateRequest(Memory = 1024L)` match the relaxed inner classes;
`Container.Config(ContainerImage("alpine")).maxProcesses(64)` matches `Config.apply(image:
ContainerImage)` and the `maxProcesses(limit: Long)` builder. All three assertions hold against our
implementation per the encoding analysis above.

Nit (doc only): his test comment says podman 5.8.4 applies PidsLimit 0 as "literal pids.max=0" while our
HostConfig comment (line 2318) says docker-compat translates it to "pids.max=1". Both now coexist in
HEAD and both motivate the same fix; the "exit 2, sh unable to fork" symptom in the test comment is
consistent with pids.max=1. Optional one-line alignment, no behavioral significance.

## Area 3: cleanly-applied files - CORRECT

`scripts/apt-install.sh`, `scripts/container-check.sh`,
`kyo-pod/shared/src/test/scala/kyo/internal/HttpContainerBackendTest.scala`, and
`.github/actions/release-setup/action.yml` are byte-identical to pr-1882 in HEAD. The three workflows
differ from pr-1882 only on `coursier/setup-action` version lines (see observation below); their
`--if-missing` hunks applied verbatim.

- **apt-install.sh `--if-missing`**: flag parsed only in first position, and every caller passes it
  first. `cmd="${arg%%:*}"; pkg="${arg#*:}"` maps plain `uidmap` to cmd=uidmap/pkg=uidmap and
  `go:golang-go` to cmd=go/pkg=golang-go correctly. Check order (dpkg first, then `command -v`) covers
  both the apt-installed and the image-shipped (/usr/local podman, tarball go) cases; empty-missing
  short-circuits before touching apt; the retry loop below is unchanged for non-flag callers. All caller
  spellings on our branch (`--if-missing podman uidmap`, `--if-missing runc`, `--if-missing cmake
  uuid-dev`, `--if-missing cmake go:golang-go`, `--if-missing cmake go:golang-go liburing-dev`) resolve
  to real packages and exist in the merged files at the pr-1882 positions.
- **container-check.sh**: probes the exact #1881 failure mode (create + start + inspect
  Status=running + exec round-trip), prints the component attribution first, appends /tmp/podman.log
  (which our Start-podman step writes) on failure, always runs the docker probe as well, and cleans up
  the probe container. `set -uo pipefail` without `-e` is correct for its accumulate-then-fail design.
  It matches how our suites consume the runtimes: kyo-pod runs against both the podman and docker
  backends, and the podman conmon breakage manifests through the CLI probe the same way it does through
  the HTTP backend.
- **Workflows/release-setup on our branch**: the `--if-missing` consolidation replaced exactly the
  `command -v`/`dpkg -s` guard lines that exist identically on our branch; surrounding steps (Prepare
  BoringSSL, Prepare libaeron, Install BoringSSL + posix build dependencies) all exist here, so the
  applied hunks are coherent in context.

Observation (pre-existing divergence, NOT a merge loss): pr-1882's copies of `release.yml`,
`deploy-site.yml`, `readme.yml` carry `coursier/setup-action@v3.0.2` because origin/main got that bump
in #1872 (`ee795b7d39`), which postdates this branch's merge-base (`2e9bb02d40`). Damian's PR did not
touch those lines, so keeping our `v3.0.0` is the correct merge outcome; the bump arrives with the
eventual rebase/merge against main. The same applies to main's reworded swap-step comment in
setup/action.yml (also not part of #1882).

## Bottom line

The merge is faithful to the user's instruction: every improvement from #1882 is present (verbatim
where adopted, subsumed-by-superset where superseded), and every branch-side improvement (cgroup
delegation, diagnostics, the stronger pids fix) is retained. No fixes required. Optional follow-ups:
dedupe the two redundant diagnostic lines in the Start-podman step, align the two pids.max comments, and
separately address the duplicate liburing step that exists upstream.
