# Fable review: NATIVE_LINK_SLOWDOWN_PROPOSAL.md

Verdict: **PASS on the recommended direction (post-test, per-module cleanup), with required revisions.**
The moment-shift is mechanically correct, #1821-safe, and addresses the entire regression component
of the 84 cycles. The proposal under-scopes the artifacts the change must carry (the relink selftest
in particular) and should firm up two implementation details (keep `build-checksum` in the post-test
prune; pair the x64 row with a free-disk step). Verified against the Scala Native 0.5.12 sources
(sbt-scala-native and tools sources jars, from the local coursier cache) and the repo's CI scripts.

## Q1. Moment-shift correctness and #1821 safety: CONFIRMED

**Why relinks will be fast with a kept dir.** Verified in tools 0.5.12:

- Build-skip (`Build.scala` `buildCached`, lines 36-56): skip requires the artifact to exist and
  `build-checksum` to equal `checkSum(config)` = hash(config, per-classpath-entry newest mtime,
  artifact mtime) (`checkSum`, lines 253-267). Both inputs survive today's prune, so **the 2/84 skip
  rate is not caused by the prune**: ~25 modules' checksums miss between the link process and the
  test process because some classpath entry's mtime churns across sbt processes (cause undiagnosed;
  candidates: regenerated test-main sources, resource copies). Pre-#1822 those same misses were cheap
  incremental relinks; #1822 turned each into a full codegen. That is the regression, exactly.
- Incremental codegen on a miss (`CodeGen.scala` `separateIncrementally`, lines 95-146 +
  `IncrementalCodeGenContext`): per-.ll-file NIR-defn hashes vs `package2hash`; only changed files
  regenerate. Object reuse (`LLVM.scala:63`, `needsCompiling` line 400) is mtime-based: unchanged
  `.ll` keep their `.ll.o`, no clang. So a kept-dir relink with unchanged NIR = NIR load +
  reachability + hashing + final system link; the measured 30-145s IR-gen and 50-90s clang phases
  collapse to ~0. The proposal's speed claim holds.

**No interleaving reproduces #1821.** #1821 required a deletion-induced inconsistency
(`package2hash` naming `.ll` files someone else deleted). With nothing deleted mid-lifecycle the only
writer is codegen itself: `package2hash` is dumped only after all IR generators complete; a kill
mid-generation leaves the OLD index plus regenerated-in-place `.ll`, and the next attempt re-derives
changed-ness from NIR vs that old index and regenerates again; a kill mid-dump truncates the index,
and missing entries count as *changed* (over-regeneration, the safe direction); a config change
deletes the index wholesale (`userConfigHasChanged`, `Build.scala:321`) forcing full regen. Worst
residual is a truncated `.ll` in a narrow kill window, which fails loudly in clang, and that window
exists identically today (the current hook fires only on link success; a killed link leaves a
populated dir now too). The only deletion the new scheme ever performs is the #1822-vetted
all-but-checksum shape, at a later moment. Strictly safer than the status quo.

## Q2. Root of the 84 cycles: the upfront phase is NOT over-linking; keep the aggregate

Arithmetic: build.sbt has ~56 native module projects (58 `nativeSettings` references). 59 upfront
cycles = ~56 aggregate links (one whole-program codegen per module, inherent to Scala Native
per-binary linking and identical pre-#1822) + 2 selftest links (kyo-data, `native-relink-selftest.sh`)
+ 1 heavy pre-link (kyo-schema-tests), minus a couple of skips. Within one sbt process each module
links exactly once (task caching); the prune cannot cause intra-aggregate relinks. So the regression
is confined to the 25 test-phase full codegens (25 x ~2.5 min matches the +60-70 min step), and
post-test cleaning removes precisely that. No second fix is required for the goal.

Do not eliminate the upfront aggregate link:

- The link invocations run under the full 12G driver heap with `NATIVE_LINK_CPUS=2`
  (`ci-test.sh` `link_sbt` ~lines 292-301; `build.yml:86-92`), while the test-phase driver is capped
  to 6G with uncapped CPUs (`RUN_HEAP_CAP`, `ci-test.sh` ~317-330). Moving first links into the test
  phase puts every whole-program link under the exact configuration the NATIVE_HEAVY staging was
  measured to avoid (7.7G fresh vs 9.9G accumulated RSS, `ci-test.sh:272-281`).
- Fail-fast is load-bearing and self-tested (`ci-test.sh` scenarios "Native links upfront before any
  test process" / "link failure exits 1", lines 158-174).
- Upfront artifacts + checksums keep crash-retry attempts cheap.

The checksum-miss root cause is worth a follow-up probe (find which classpath entry's
`getLastModifiedChild` changes between the two sbt processes): fixing it would turn the ~25
incremental relinks into true build-skips (a further ~10-20 min ceiling) and would even make a
post-link prune free again. Orthogonal to this change; not a blocker.

## Q3. Implementation seam: `Test / test :=` with `.result.value`, in `native-settings`

```scala
Test / test := {
    val outcome = (Test / test).result.value
    if (insideCI.value) {
        val workDir = crossTarget.value / "native-test"
        if (workDir.exists())
            IO.listFiles(workDir).filterNot(_.getName == "build-checksum").foreach(IO.delete)
    }
    outcome match {
        case Value(v)   => v
        case Inc(cause) => throw cause
    }
}
```

- **Runs on failure too**: `.result.value` yields `Result`; cleanup happens before the rethrow, and
  rethrowing `Inc` preserves failure semantics for `check_log`/testKyo.
- **No ordering trap**: self-referencing `:=` resolves to the previous definition; unlike the current
  `~=` value transform it sees settings, so `insideCI.value` works (retiring the
  `sys.env` workaround documented at build.sbt:3221; keeping `sys.env.contains("CI")` for parity with
  the selftest's `CI=1` arming is also fine, but pick one and update the comment).
- **Workdir path without touching `nativeLink`**: `crossTarget / "native-test"` is exact
  (tools `Config.scala:272-273` resolves workDir as `baseDir/"native-test"` for test config; the sbt
  plugin passes `baseDir = crossTarget`). Not depending on the `nativeLink` task means a failed test
  run cleans without ever triggering a link.
- **No testKyo keying needed**: testKyo emits `X/test` per module sequentially
  (`TestKyo.scala:34-38,145`), so the per-module transform fires exactly when that module's tests
  finish, on Scala 3 and on the 2.13 pass (separate crossTarget) alike. On a module failure sbt drops
  the remaining batched commands, so later modules keep their dirs; acceptable, the row is ending.
- **Keep `build-checksum`** (delete all-but-checksum, same shape as today), not the whole dir: a
  crash-retry attempt then build-skips already-tested modules instead of full-relinking them.
- **Remove the old hook** (build.sbt:3223-3229); running both would nullify the fix.
- Composition verified: the only other `Test / test :=` redefinitions are the 2.12 sbt plugins
  (build.sbt:3340, 3392), JVM-only, no overlap.

**Required co-scope the proposal misses (main revision):** `scripts/native-relink-selftest.sh` FAILS
as written against this change: `assert_pruned` after the first link (and after the relink) asserts
the exact post-link prune being removed, and its `--self-test` sbt stub emulates prune-on-link.
Reshape it to guard the new lifecycle: link (assert dir retained), apply the post-test prune shape
(either `CI=1 sbt kyo-dataNative/test` or the same all-but-checksum delete the hook performs), touch
a `.nir`, relink, assert rebuild with no missing-`.ll.o`. Update the workflow step comment
(`build.yml:115-124`) and the build.sbt/ci-test.sh comments describing the prune moment. The
proposal's "keep the selftest green" line understates this: it will not stay green unmodified.

## Q4. Disk peak: accept, but with the guard armed AND a free-disk step on x64

Be honest that post-test cleaning does not bound the row peak the way post-link cleaning did: all ~56
work dirs coexist from the end of the aggregate link into the test phase (~20GB), decaying as tests
complete. Projected mins: ~44GB arm64, ~21GB x64. The guard has an existing seam: `ci-monitor.sh`
already carries `diskFreeMB` and an opt-in `CI_MON_DISK_ABORT_MB` abort (ci-monitor.sh header, lines
20-30); set it on Native rows so exhaustion is a named failure, never a logless death. On x64,
21GB of margin erodes with module growth (~0.5-0.75GB each) and runner-image variance (the original
incident was an image that "started small", build.sbt:3208-3209), so pair the guard with a free-disk
setup step there (dropping preinstalled toolchains reclaims 15-25GB for 1-2 min). Both, not either:
the guard converts silent death into a diagnosis; the step prevents it.

## Q5. Alternatives: post-test-clean dominates

- **Keep `.ll`+`package2hash`, delete `.ll.o`/`dependencies`/`TestMain-test` post-link**: verified
  worse on both axes. `needsCompiling` is mtime-based, so deleted `.o` means every clang compile is
  redone (50-90s x ~25 modules, ~25-40 min still lost) while reclaiming only ~33% and retaining the
  67% `.ll` anyway; and it reintroduces a mid-lifecycle partial deletion, the #1821 family, resting
  on undocumented upstream invariants. Reject.
- **Shared native-lib unpack**: ~65MB x N, ~3-4GB, needs upstream plumbing. Micro; skip.
- **Fix the checksum miss** (test phase build-skips): highest ceiling, cause undiagnosed; pursue as a
  follow-up probe, compatible with and not instead of the moment-shift.
- **Upstream filing** of the `separateIncrementally` missing-file defect: file it, non-blocking, as
  the proposal says.

## Bottom line

Adopt the post-test per-module cleanup exactly as proposed, with these revisions folded in before
implementation: (1) the selftest/workflow/comment co-scope above; (2) post-test prune keeps
`build-checksum`; (3) the post-link hook is removed, not paralleled; (4) `CI_MON_DISK_ABORT_MB` armed
on Native rows plus a free-disk step on x64; (5) the proposed podman-ci A/B on both arches, expecting
cycle count ~56-59 total (from 84), `Build skipped`/fast-incremental in the test phase, and row time
back toward the pre-#1822 baseline.
