# Independent corpus review: branch `kyo-compat-external-bindings`

Reviewer: held-out analysis pass (read-only). Every claim below was checked against the
code at HEAD `23f6a5e551` plus the uncommitted working tree; where BRANCH_WORK_REVIEW.md
and the code disagree, the code is reported.

## Top three findings

1. **Development-process leakage in source prose (Area 9), headlined by a comment whose
   causal claim is now known FALSE.** `.github/actions/setup/action.yml:153-158` still
   narrates "the conmons pile up (measured climbing past 200) ... catatonit hits fork
   EAGAIN ... cascading into ~100 [podman] http failures", a chain the PidsLimit root
   cause disproved; the report's claim that this comment was corrected is false. Beyond
   it, the sweep found a consistent pattern of investigation narration, changelog
   references, test-harness rationale inside production source, and an unexplained
   incident tag repeated across kyo-sql. Full inventory with replacements in Area 9.

2. **kyo-http deferred pool release leaks the connection when a streaming body is never
   consumed (RISK, moderate).** Commit `4fc29b5158` covers the interrupt, abort, drop-mid-
   consumption, error, and panic edges thoroughly, but a streaming response whose body
   stream is never *started* leaves `bodyOutcome` pending forever. `releasingConn`'s
   success CAS has already transferred the reuse decision to the promise
   (`kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala:1050-1072`),
   so the connection is never released and never discarded: a permanent fd plus one unit of
   per-host pool capacity, ending in `HttpPoolExhaustedException` under repetition. The
   edge is reachable two ways: `f` completes normally after reading only status/headers,
   or the response (with its lazy stream) escapes `f` (legal, `A` is generic at
   `HttpClientBackend.scala:1207-1208`) and is dropped unconsumed. Before the change this
   edge released the connection dirty (the desync bug being fixed), so the trade is
   corruption for a leak, which is directionally right, but the leak needs closing:
   either tie the body stream to a request Scope whose exit completes the promise false,
   or track a "stream started" flag and have use-completion discard when the body never
   started. At minimum add a regression test for the never-consumed case.

3. **The headline PidsLimit fix is SOUND, complete on both wire paths, and the omission
   semantics are verified end to end in kyo-schema.** Details in Area 1; the fix also
   silently repairs a real docker-side contract violation on the update path that nobody
   had noticed (absent `maxProcesses` used to actively reset the pids limit on docker,
   contradicting the documented "Only provided fields are changed" contract at
   `kyo-pod/shared/src/main/scala/kyo/Container.scala:378`).

---

## Area 1: headline fix (`23f6a5e551`) - verdict SOUND

**Root cause plausibility.** The empirical chain in the report (sampler caught
`pids.max=1` on the failing container's own cgroup; API-create with `PidsLimit:0` yields
`pids.max=1` while CLI run yields `max`; postgres via the API fork-fails with the field
and runs with it omitted; 0/9 to 9/9 after the fix alone) is direct measurement, and the
mechanism is credible: in the Docker Engine API `HostConfig.PidsLimit` is the one
*nullable* resource field (`*int64`, "0 or -1 for unlimited, null to not change"), so an
explicit 0 is a set-value on the wire, and podman's compat shim translating it into a
1-clamped cgroup is exactly the kind of divergence a nullable field invites.

**Fix completeness.** Both wire paths carry the fix: `buildHostConfig`
(`HttpContainerBackend.scala:380`) and `updateDockerCompat`
(`HttpContainerBackend.scala:1280`), and both DTOs are `Maybe[Long] @omit`
(`HttpContainerBackend.scala:2318-2325` and `2363-2369`). Note the podman runtime never
reaches `updateDockerCompat` (update routes to `updateLibpod` at line 1219, which already
filtered `> 0`), so the UpdateRequest change is a docker-only behavior change, and a
correct one (see finding 3 above).

**`@omit` semantics verified, not assumed.** Bare `@omit` defaults to `omit.WhenAbsent`
(`kyo-schema/shared/src/main/scala/kyo/schema/SchemaAnnotation.scala:183`); at derivation
time a `Maybe` field desugars WhenAbsent to `OmitPolicy.WhenNone`
(`kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:809-812`); the
serializer then drops the field entirely, producing no name/value pair rather than a null
(`kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:226-227` returns
`Chunk.empty` for an omitted field). A round-trip test for `@omit` on a `Maybe` field
exists (`kyo-schema-json/shared/src/test/scala/externalschema/SchemaExternalPackageDerivationTest.scala:29,85`).
The report writes `@omit(omit.WhenAbsent)` where the code has bare `@omit`; equivalent
(it is the default), cosmetic report inaccuracy only. The DTOs are encode-only (create
and update requests; inspect uses separate response DTOs), so decode-side reconstruction
is not in play.

**Other bare-0 HostConfig fields (Memory, MemorySwap, NanoCPUs, CpusetCpus): leaving
them is correct, not a latent copy of the same bug.** Those are plain `int64`/string
fields in the Docker API where 0/"" is the *documented* unset value; PidsLimit is the
lone nullable field, which is precisely why only it had an explicit-0-vs-unset gap for a
compat shim to fall into. The measured probe covered PidsLimit only, so this is
API-shape inference rather than measurement for the other fields; if paranoia is cheap,
one CI probe of `Memory:0` on podman would close it, but I do not consider it required.

**Two minor residuals.**
- `filter(_ > 0)` means `Present(0)`/negative now reads as "no limit configured". On
  create that matches intent. On docker update it changes "clear the limit" (previous
  accidental behavior of sending 0) into "no change". No contract documents a clear
  semantics, so this is acceptable, but a scaladoc line on `update`'s `maxProcesses`
  would pin it down.
- The regression test (`ContainerItTest.scala:39-46`, init-process alpine forking 16
  background sleeps, `assertRuns`) is meaningful on the `[podman]`/http leaf: with
  `PidsLimit:0` regressed, catatonit cannot spawn pid1, the container dies, and the
  single `state == Running` assert fails. Its failure signal on a regressed build is
  near-certain (init performs several post-start round trips before the state read) but
  not proven deterministic; an exec (which must fork in-container) or a short settle
  plus re-assert would make the guard airtight. On docker legs the test cannot detect
  the regression at all (docker treats 0 as unlimited); expected, worth knowing.

## Area 2: leak-artifact diagnosis - verdict SOUND mechanism, pending empirical close

The cross-fork contamination explanation is structurally verified:

- The leak check diffs the daemon-global `Container.list(all = true)` around each leaf
  and inspect-confirms candidates (`kyo-pod/shared/src/test/scala/kyo/BasePodTest.scala:64-97`).
  Its validity explicitly ASSUMES "<=1 in-flight container op per daemon"
  (`BasePodTest.scala:33-36`, `104-105`); concurrent forks violate the assumption by
  construction, and a concurrent fork's containers are live at inspect time, so they
  are reported as leaks of the observing leaf. "One leaf leaked 7" fits this and fits
  nothing about the teardown path.
- Real CI serializes everything: `SBT_TASK_LIMIT: 1` (`.github/workflows/build.yml:76`,
  `ci.yml:151`) feeds `Tags.limitAll(1)` (`build.sbt:104`). Without it, the CI fork cap
  is 2 and the local cap is cores/2 (`build.sbt:102`), and the per-runtime testGrouping
  (`build.sbt:2681-2725`) registers `#podman` forks for every suite using
  `runBackends`/`runRuntimes`, so two different suites' podman forks can co-run against
  the one podman service. That is the contaminating pair.
- **The `[Stopped]` leaked containers do not prove a real teardown gap.** The other
  fork's tests legitimately hold stopped-not-yet-removed containers mid-test
  (`autoRemove(false)` flows that stop, inspect, then remove; `ensureCleanup` at scope
  exit). A concurrent observer listing the daemon mid-window sees exactly a mix of
  `[Running]` and `[Stopped]`.
- Teardown robustness checked independently: the scope finalizer retries force-remove on
  transient backend failure with backoff and treats Missing as success
  (`kyo-pod/shared/src/main/scala/kyo/Container.scala:500-527`); HTTP remove blocks on
  daemon-authoritative `/wait?condition=removed`, re-forces once if the record lingers
  (`HttpContainerBackend.scala:493-549`). I found no unguarded teardown edge.

Caveat that keeps this "pending": the artifact explanation is closed only by the
in-progress CI-faithful serialized run being green. The report states this honestly;
hold "done" until that run lands.

One comment defect found on the way: `build.sbt:98-101` claims the CI fork cap of 2
"ends up with at most one fork per daemon". That is not guaranteed: the cap is numeric
and daemon-blind, and two different suites' `#podman` forks can be co-scheduled. Under
real CI (`limitAll(1)`) it is moot, but the comment states an invariant the build does
not enforce; correct the comment (or enforce with a daemon-scoped Tag).

## Area 3: band-aid setup config - verdicts: pin removal GOOD; Delegate KEEP; exit_command_delay DEFECT (comment) + A/B (setting)

- **podman pin**: removed in the uncommitted working-tree diff, matching the report.
  Correct: the version theory was disproven and the pin was inert anyway.
- **`Delegate=yes`** (`action.yml:128-136`): comment corrected as claimed (no more "root
  fix" language; now "makes it deterministic across images"). Recommendation: KEEP.
  Controller delegation to the user manager is a genuine precondition for rootless
  podman per-container pids cgroups, making it explicit is standard rootless-CI
  hardening, and it does not mask the fixed bug (pids.max=1 requires the controller to
  be present, so the bug reproduced WITH delegation). It is prophylaxis against runner
  image variance, not a mask.
- **`exit_command_delay = 30`** (`action.yml:153-160`): the comment still carries the
  disproven fork-EAGAIN causal chain (see top finding 1); the report's "KEPT, comment
  corrected" is false for this item. On the setting itself: the honest justification is
  resource hygiene (conmon default lingers 300s per exec; measured accumulation past
  200; 0 breaks the exit-code read). Whether that hygiene is load-bearing is exactly
  what the earlier "ContainerItTest cascade" incident cannot tell you, because that
  cascade is plausibly another PidsLimit manifestation. Recommendation: rewrite the
  comment now to the hygiene justification with no EAGAIN claim, and let the planned
  CI-faithful A/B decide keep-vs-remove; under the no-masks mandate, remove if the
  clean config is green.

## Area 4: fork-pressure readiness retry (`a7d3ee4d30`) - verdict: REVERT (dead, mask-shaped)

Still present at HEAD (`kyo-pod/shared/src/main/scala/kyo/ContainerPredef.scala:53-92`).
With the root cause fixed its trigger has no producer, and as a stderr-text-matching
retry ("resource temporarily unavailable", "can't fork", ...) it is precisely the mask
shape the mandate bans: a FUTURE fork-pressure regression would burn up to 40s of retry
before surfacing, and probe output that legitimately contains the phrase would be
delayed the same way. Note it would NOT mask a PidsLimit regression (an init-process DB
container dies outright; the readiness exec then fails with not-running, which this
retry does not catch), and the ContainerItTest guard covers that path, so removal costs
nothing. The superficially similar teardown-remove retry (`eaa62029b3`,
`Container.scala:520-526`) is different in kind: bounded, typed on
`ContainerBackendException`, and its failure still trips the leak check; keep it.

## Area 5: kyo-sql custody - verdict SOUND, one theoretical residual window

The invariant "a live fd always has exactly one custodian" holds on every path I could
construct except one microscopic edge:

- Ring path: the claim happens in the SAME unsafe block as the poll, no suspension
  between (`SqlConnectionPool.scala:501-527`); an interrupt during the health probe or
  before `onLease` is covered by the orphan finalizer; the take happens inside
  `onLease`'s body after `resolvingOnce` has registered `decideExit`, and the
  interrupt-between window double-fires safely (the orphan close is `isOpen`-guarded,
  `decideExit`'s destroy is a plain close, and `resolvingOnce`'s CAS keeps the lease
  resolution exactly-once, `SqlConnectionPool.scala:687-698`).
- Connect path: the factory claims into the inheritable `custodyLocal` from inside the
  `timeoutWithError` child fiber (`kyo-sql/shared/src/main/scala/kyo/db/Connection.scala:237-240`,
  `392-417`); pre-claim interrupt is covered by the connect-fiber ensure plus
  `closingOnFailure`; the TLS no-op-close hazard mid-handshake is covered by the engine
  second bracket (`PostgresConnection.scala:473-483`, and again at `612-622`).
- Stream path (`acquireScoped`, `SqlConnectionPool.scala:839-861`): `Scope.ensure(decideExit)`
  registered before `take()`; the interrupt-between edge resolves as destroy + no-op
  orphan close.
- `warmUp`: `take()` and `pool.release` in one non-suspending unsafe block
  (`SqlConnectionPool.scala:157-170`), so no window between take and ring entry.
- Dedicated listener: its own custody around `openDedicated`
  (`PostgresSqlConnection.scala:465-497`), take after `Scope.ensure(adapter.close)`.
- Cancel sidecars: MySQL wraps the sidecar connect in a local custody plus `closingOnce`
  (`MysqlSqlConnection.scala:172-205`); Postgres `CancelExchange` has the connect-fiber
  ensure plus a DOUBLE `closingOnce` (raw and post-upgrade,
  `CancelExchange.scala:68-141`), and the raw close in the Upgrading state routes to
  upgradeAbandon, so even the mid-upgrade edge releases the fd.

**The residual window**: in `openSocket` the claim runs in the continuation AFTER `body`
completes (`Connection.scala:408-413`). Between the engine second bracket's success exit
and the claim there are one or two Sync suspension points; an interrupt landing exactly
there leaves only the outer bracket, whose `rawConn` close is a documented no-op after a
TLS upgrade, so the upgraded fd would leak. No I/O occurs in the window and the
container-validated fd-leak runs never hit it, so this is theoretical, but the invariant
is not airtight by construction on that edge. Cheap hardening: claim at the tail of
`body` (inside the engine bracket, once the fd owner exists) rather than in `openSocket`'s
continuation; or document the window.

Cosmetic: the orphan close bypasses `metrics.recordDiscard` (it closes the fd directly),
so pool discard metrics undercount on orphan-close edges. Capacity is unaffected: the
ring bounds idle+inFlight only (`kyo-net/.../ConnectionPool.scala:188-196`), and
`pool.discard` is a bare close callback (`ConnectionPool.scala:66-67`), so no accounting
strands. Also noted: `custodyLocal` is inheritable, so an `openDedicated` performed
inside a lease's `op` would claim into the outer, already-taken custody; harmless
(orphan checks `taken` first) and the real listener caller allocates its own custody,
but it is an unstated sharp edge.

## Area 6: kyo-http streaming pool (`4fc29b5158`) - verdict RISK

See top finding 2 for the full analysis. What IS covered, verified branch by branch:
`onInvalid`, connection-closed, panic, and send-throw edges complete false
(`HttpClientBackend.scala:152, 199-208`); the >=400 buffered fallback mirrors the
buffered contract (171-175); chunked reuse is decided by the decoder's own terminal
result with a fresh per-request DecoderState (615-623), and consumer abandonment closes
the per-request channel so the decoder's next put fails Closed and decides discard
(628-634); the content-length path completes true on drain (671) and false on both the
inbound-Closed edge (679) and the consumer-side ensure (646). The caller-scoped
(non-pooled) path correctly passes Absent (242). The one uncovered edge is the
never-started body described above. Severity assessment: kyo's in-repo consumers always
consume, so this will not show in CI; it is a public-client-API hazard for status-only
reads on streaming routes, and it deserves a fix plus a regression test rather than
documentation alone.

## Area 7: corpus hygiene - verdict SOUND on the sampled set

- `7f233908be` (kyo-stm): three GC/WeakReference leak probes replaced with one
  deterministic CommitBuffer clear-invariant test. The structural argument that the
  thread-local CommitBuffer is the only cross-transaction retention root checks out
  (waiter edges point TRef-to-fiber, logs are per-attempt Var state), and the removed
  shape (assert on `System.gc()` behavior) is inherently flaky. Honest note: coverage
  narrows from end-to-end GC-eligibility to one named mechanism; a future retention
  root elsewhere would be invisible to the new test. Acceptable trade, worth knowing.
- `d5ce2cf494` (kyo-stats-otlp): strictly stronger. Root cause fixed (gauge was
  weakly-referenced and GC'd mid-test), `.flaky` dropped, value assertions kept.
- `6d8994d8a0` (kyo-browser) and the ceiling-removal family: each removed wall-clock
  ceiling is replaced by a stronger discriminator, not by nothing: force-close proven by
  the in-flight send observing ConnectionLost; wrong settle behavior converted into a
  hang against a never-completing endpoint; schedule-application proven by an
  effectively-infinite outer schedule that turns non-application into a hang. These are
  the model of how to de-flake without weakening.
- `4353a74e6f` (JUnitXml Windows skip): legitimate platform semantics
  (`File.setWritable(false)` does not block child creation on Windows), exactly
  mirroring the existing root cancel in the same leaf.
- `00fabfe717` (kyo-pod Windows skip): legitimate for CI (Windows-container-mode docker
  cannot run the Linux images; 178 failures otherwise). Minor over-breadth: the
  unconditional `Platform.isWindows` gate also disables the suites on a Windows dev
  machine whose Docker Desktop runs Linux containers (WSL2), which could run them; a
  daemon OSType probe would be precise. Not a dodge, but a real (small) coverage
  forfeit on that configuration.
- Not audited in depth: the remaining ~30 theme-B commits and `6060cfe199` (subject
  reads as a legitimate platform accommodation). Sampling was per the review brief.

## Area 8: TEMP residue - verdict CLEAN at HEAD, one working-tree stray

- Every file touched by a TEMP commit was checked at HEAD: `action.yml` (no probe or
  sampler blocks; read in full), `build.yml` (no RESMON dump step), `BasePodTest.scala`
  (the `d3d9d911c8` inspect-dump/re-remove diagnostic is absent; the leak-fail message
  is the simple one at lines 87-90).
- `fe6725a297` -> `3ebde07d5b`: verified an exact net no-op
  (`git diff fe6725a297~1 3ebde07d5b -- ContainerPredef.scala` is empty). Nothing leaked
  past the revert.
- Working-tree stray: `.github/workflows/podman-diag.yml` (untracked) is a diagnostic
  workflow that must not ship; delete it before ship-prep. The ~20 untracked analysis
  `.md` files are dev artifacts and likewise stay out of the PR.

## Area 9: development-process leakage and comment hygiene - verdict: SYSTEMATIC PATTERN, fix before ship

Standing rule applied: source prose (comments and scaladoc) is strategic communication to
a future maintainer; no investigation narration, no changelog, no lab notebook. Sweep
covered the full branch diff's added comment lines plus the working tree. Findings in
three severity groups; each has a tightened replacement.

### Group A: leaks in production source and CI config (fix all)

1. `.github/actions/setup/action.yml:153-158` (working tree)
   Quote: "the conmons pile up (measured climbing past 200), and once that baseline is
   high a fresh container's PID 1 (catatonit) hits fork EAGAIN on an otherwise-idle
   runner, cascading into ~100 [podman] http failures. exit_command_delay=0 goes too far"
   Why: measurement log plus an investigation narrative whose causal claim is disproven.
   Replacement: `# Bound exec-session conmon lifetime: the 300s default leaves one
   lingering conmon per exec. 0 reaps before the HTTP backend reads the exit code; 30s
   covers the read.`

2. `.github/actions/setup/action.yml:192-198`
   Quote: "docker is load-bearing too, and it has been removed out from under this step
   before, so name what broke rather than letting `set -e` end the step"
   Why: repo-history storytelling ("has been removed ... before").
   Replacement: `# The kyo-pod suite also runs against docker, and installing ubuntu runc
   can remove docker-ce (containerd.io conflict). Fail with that diagnosis, not a bare
   exit code.`

3. `.github/actions/setup/action.yml:183-185`
   Quote: "Attribution for the podman version variance across runner images (4.9.3 vs
   5.8.4) and the resolved cgroup manager"
   Why: preserves the investigation's version question; the pinned versions will rot.
   Replacement: `# Print podman version and cgroup manager so container failures in the
   log are attributable.`

4. `.github/actions/setup/action.yml:57-60`
   Quote: "the stock runner swap has been observed fully exhausted right before kernel
   OOM kills"
   Why: observation narration; the guidance survives without it.
   Replacement: `# The Native link phase can overcommit the 16GB runner; extra swap turns
   a marginal overcommit into slowdown instead of an OOM kill. Best-effort.`

5. `kyo-pod/shared/src/main/scala/kyo/Container.scala:480-484`
   Quote: "The runner discharges Scope OUTSIDE any caller-scoped `HttpClient.let` ...
   would then hold the teardown connections open past end-of-run and trip the leak check."
   Why: production teardown justified by the TEST harness ("the runner", "the leak
   check"). The durable invariant is client identity across the finalizer boundary.
   Replacement: `// Capture the HttpClient bound at registration: by finalizer time the
   fiber-local has unwound to the default client, and tearing down through a different
   client leaves its pooled connections open.`

6. `kyo-pod/shared/src/main/scala/kyo/Container.scala:514-519`
   Quote: "Swallowing it here leaks the container and trips the leak check, so retry"
   Why: same test-harness rationale in prod source.
   Replacement: `// A transient transport failure here would leak the container
   daemon-side; retry the removal briefly. Missing means it is already gone, the desired
   end state.`

7. `kyo-pod/shared/src/main/scala/kyo/ContainerPredef.scala:445`
   Quote: "(a daemon-side leak the container leak check flags)"
   Why: test-harness reference in a production fixture.
   Replacement: end the sentence at "and the fixture leaks daemon-side."

8. `kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala:421`
   Quote: "Container start can stall under daemon load (e.g. parallel test forks)."
   Why: dev-harness example in prod source.
   Replacement: `// Container start can stall under daemon load; raise the default 5s
   HTTP timeout to at least 30s (max preserves longer caller overrides).`

9. The incident tag `(the processSharedTransport fd-leak)` in kyo-sql MAIN source, four
   sites: `kyo-sql/shared/src/main/scala/kyo/db/Connection.scala:219` and `:389`,
   `kyo-sql/shared/src/main/scala/kyo/internal/client/SqlConnectionPool.scala:833`,
   `kyo-sql-postgres/.../exchange/CancelExchange.scala:73`.
   Why: names a historical investigation a future maintainer cannot look up. In kyo-net
   `processSharedTransport` is a real identifier; in kyo-sql it is incident vocabulary.
   Replacement at each site: state the failure mode, e.g. "otherwise the fd is stranded
   with an armed read and never closed". Same treatment for the test reference at
   `kyo-sql/shared/src/test/scala/kyo/internal/SqlConnectionCancelTest.scala:778`.

### Group B: changelog and migration narration (fix all; cheap)

10. `kyo-pod/shared/src/test/scala/kyo/BasePodTest.scala:52`
    Quote: "Ported from the ScalaTest base's `run` override to kyo-test's `aroundLeaf`
    hook."  Why: pure changelog.  Replacement: delete the sentence.

11. `BasePodTest.scala:35` and `:41`
    Quotes: "kyo-test defaults to parallel leaves whereas the ScalaTest base ran them
    sequentially, so restore that"; "That fix belongs to the transport (frozen for the
    kyo-net rewrite)".
    Why: migration history, and a roadmap/process reference documenting project planning
    rather than code.
    Replacement: ":35 keep only 'Leaves run sequentially: runBackends assumes <=1
    in-flight container op per daemon.' :41 keep only the mechanism: the NIO transport
    defers the real fd close to an idle selector nothing wakes, and the socket is an
    opaque socket:[inode] no allowlist can match."

12. `kyo-stm/shared/src/test/scala/kyo/CommitBufferTest.scala:245-247`
    Quote: "This is the invariant the removed GC-based ... leak test probed indirectly,
    asserted here directly and deterministically"
    Why: references removed tests; changelog framing.
    Replacement: `// withBuffer must clear the reused thread-local buffer after each use
    so a prior commit's TRef entries cannot leak into the next commit.` (keep the
    fill/re-enter mechanics sentence that follows).

13. `kyo-browser/shared/src/test/scala/kyo/BrowserDownloadTest.scala:465-466`
    Quote: "(an earlier file-existence barrier was reverted: file writes are a separate
    pipeline from event drain and could race it)"
    Why: narrates a reverted attempt.
    Replacement: `(do not gate on file existence: file writes are a separate pipeline
    from event drain and can race it)`.

14. `kyo-direct/shared/src/test/scala/kyo/CoreTest.scala:64-65`
    Quote: "Keep the same 80% floor-to-sleep ratio"
    Why: "same" is change-relative; meaningless to a fresh reader.
    Replacement: `// Assert an 80% floor at a duration where millisecond clock
    granularity is negligible.`

### Group C: over-long lab-notebook blocks (tighten)

15. `ContainerPredef.scala:26-35` (readinessLoop scaladoc, 9 lines): "piles up hundreds
    of orphaned conmon across a fixture suite and, on a loaded runner, exhausts the
    per-user process limit until exec and container start themselves begin to fail" is
    investigation-derived storytelling. Four lines suffice: in-container poll = one exec
    per fixture; each host exec leaves a ~300s conmon on rootless podman; `budget` bounds
    the loop; the probe must exit non-zero until the service answers.

16. `ContainerPredef.scala:43-52`, `:84-86` (fork-pressure retry comments): removed
    wholesale by the Area 4 revert of `a7d3ee4d30`; no rewrite needed.

17. `HttpContainerBackend.scala:495-502` (awaitRemoved scaladoc, 8 lines): keep the
    greppable failure signature, halve the narrative: "DELETE acks while teardown is
    still in flight on rootless podman; block on /wait?condition=removed so creation
    cannot outrun retirement (unretired churn exhausts the per-user kernel keyring:
    `runc create` fails 'unable to create session key: disk quota exceeded')."

18. `BasePodTest.scala:13-22` (runToken comment, 9 lines): three lines suffice:
    per-runtime forks share /tmp; a per-JVM counter collides on host bind-mount dirs
    across forks; the random token keeps generated names unique.

19. `BasePodTest.scala:56-63` (checkingContainerLeak scaladoc): same treatment; keep the
    keyring signature, drop the essay.

Also under this dimension: `build.sbt:98-101` states an invariant the build does not
enforce ("at most one fork per daemon"); correct it (see Area 2).

### Calibration: what was checked and NOT flagged

The PidsLimit comments themselves (`HttpContainerBackend.scala:2318-2320`, `:2363-2364`)
are the model the rule asks for: two to three lines, forward-looking (why this field must
stay `Maybe` + `@omit` while its neighbors are bare 0), no history. The
`ContainerItTest.scala:35-38` regression header is conventional for a regression guard
(compressible to 2 lines, not required). The Windows-fix comments
(TransportLifecycleTest, IframeTest, ReporterTest) are concise platform guidance. The
kyo-http `bodyOutcome` obligation comments are load-bearing concurrency contracts and
earn their length. The long mechanism-rationale scaladoc style in kyo-sql
(`leftSessionIdle`, `isProtocolFatal`, `resolvingOnce`) explains durable invariants, not
process, and was not flagged. HEAD-only offenders ("Root fix for the intermittent arm64
container fork-EAGAIN", the pin block's disproven version theory) are already deleted by
the uncommitted working-tree diff; committing that diff is what makes them disappear.

## Consolidated recommendations, in order

1. Apply the Area 9 comment-hygiene pass: all Group A and B items, Group C tightenings
   (the `exit_command_delay` rewrite is item A1; its causal claim is false); then run the
   planned CI-faithful A/B and remove the setting if the clean config is green (keep
   `Delegate=yes` either way).
2. Close the kyo-http never-consumed-body leak (scope-tie or started-flag fallback) and
   add the regression test.
3. Revert `a7d3ee4d30` (dead, mask-shaped readiness retry).
4. Hold "done" on the leak-artifact diagnosis until the serialized (`SBT_TASK_LIMIT=1`)
   kyo-pod run is green; correct the `build.sbt:98-101` "one fork per daemon" comment.
5. Optional hardening: claim custody inside the engine bracket to close the theoretical
   TLS-window in `openSocket`; strengthen the PidsLimit regression test with an exec or
   settle+re-assert; scaladoc the `update(maxProcesses = Present(0))` semantics; delete
   the untracked `podman-diag.yml`.
