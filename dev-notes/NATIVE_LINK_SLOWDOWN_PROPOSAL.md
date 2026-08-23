# Proposal: recover the Scala Native CI build-time regression without dropping the disk cleanup

Status: proposal for review (Fable). Groundwork complete; not implemented.
Constraint (from the user): **keep the CI disk cleanup, but stop it costing compile time.**

## TL;DR

`#1822` (2026-07-30) made the Native CI rows ~50-60% slower (arm64 ~126 -> ~196 min,
measured on `main`). Cause: it prunes each module's whole Scala Native work directory right
after that module links, and the CI flow then **re-links nearly every module a second time**
(and some more than twice). With the work directory gone, every one of those relinks is a
**full codegen** instead of a cache hit. Across a row that is **84 full codegen cycles for
~40-52 modules, with only 2 "Build skipped" in the entire run**.

The disk the prune reclaims *is* the codegen cache (67% of a work dir is the `.ll` IR that an
incremental relink reuses), so you cannot selectively delete the big part and keep the speed.
The way to satisfy both constraints is to **clean at a different moment**: keep each module's
work directory until that module's tests finish, then delete it. Relinks stay incremental,
disk is still reclaimed per module, and the peak (~20GB) fits the runners (41-64GB free,
measured).

## Evidence

### The regression (main, isolating #1822)

| | arm64 Native | x64 Native |
|---|---|---|
| PRE-#1822 (07-22..29), 3 runs | 116/128/133 min | 94/110/121 min |
| POST-#1822 first runs (07-31) | 188/196/201 min | 143/146/157 min |

A +50-60% step at the merge boundary (later climbs are code growth; the branch adds more on
top). See the sibling analysis; not repeated here.

### The double/triple linking (arm64 Native, run 31841875109)

Scala Native prints one `Total (…ms)` per full codegen+compile cycle and `Build skipped` when
it reuses. In one row:

- **84** `Total (…ms)` cycles; **2** `Build skipped`.
- Split at the `testKyo --all Native` boundary (test phase starts line 4044): **59 full
  codegens before** (the upfront `kyoNative/Test/nativeLink` aggregate + the heavy-module
  isolated pre-link + the relink self-test), **25 after** (test-phase `X/test` relinking).
- Per cycle: `Generating intermediate code` 30-145 s + `Compiling to native code` 50-90 s;
  `Total` 88 s to 372 s. ~84 x ~90 s ≈ the bulk of the ~196 min row.

So the flow links a module upfront, prunes it, then the test phase links it again from
scratch (checksum miss + pruned work dir = full codegen). The upfront codegen is thrown away.

### Work-directory composition (kyo-data, local link)

748 MB total:

| Part | Size | Role |
|---|---|---|
| `generated/*.ll` | **502 MB (67%)** | LLVM IR; **this is the incremental-relink cache** |
| `generated/*.ll.o` | 103 MB (14%) | objects (clang output from the `.ll`) |
| `TestMain-test/` | 78 MB | linked test binary + objects |
| `dependencies/` | 65 MB | unpacked native dependency libs |
| `package2hash` | 3 KB | incremental-codegen index |
| `build-checksum` | 10 B | build-skip key |

**Key point:** the disk the prune reclaims is dominated by `generated/*.ll`, which is exactly
what an incremental relink reuses to skip codegen. Disk saving and codegen speed are the same
bytes. You cannot delete the 67% and keep the speed. Keeping relinks fast for ~40-52 modules
therefore requires retaining ~20 GB of `.ll` cache across the row.

### Disk headroom (measured, with pruning active, branch snapshot)

arm64 Native min free 64 GB; x64 Native min free 41 GB. No disk-free step in setup. Retaining
~20 GB of caches leaves ~44 GB (arm64) / ~21 GB (x64) free. Fits, with a caveat on x64 growth.

### Upstream

Scala Native 0.5.12 is the latest release (no 0.5.13 fix to upgrade to). The specific defect
(`separateIncrementally` returns a `.ll` path without verifying the file exists; `#1821`'s
root) is not filed on `scala-native/scala-native`; closest open issues are #182 (incremental
compilation) and #3567 (stale incremental state). No upstream lever short-term.

## Why the simple ideas do not satisfy "keep cleaning, no compile cost"

- **Delete only `.ll` / keep `package2hash`** = exactly the `#1821` bug (codegen trusts the
  index, hands clang a deleted `.ll`). Off the table.
- **Selective clean of the big part** = deleting `.ll` = forcing re-codegen. The big part *is*
  the cache. Cannot both.
- **Delete only `.ll.o` (14%), keep `.ll`+`package2hash`** (`#1821`-safe): relink skips
  codegen (reuses `.ll`), re-runs clang. Saves ~half of each relink's time but only 14% of
  disk, and it retains 67% (`.ll`) anyway, so it neither reclaims much nor is simplest. A weak
  middle.

## Recommended solution: clean per module after its tests, not after its link

Move the cleanup from the `Test / nativeLink` hook (fires right after a link, stranding the
next relink) to a **post-test, per-module** hook: keep the work directory through the upfront
link and the test-phase relink (so both reuse the `.ll` cache -> incremental / build-skip ->
no full re-codegen), then delete the whole directory once that module's tests complete.

- **Compile time:** relinks become cache hits/incremental; the ~84 full codegens collapse
  toward the unavoidable ~one-per-module. Recovers most of the regression.
- **Cleaning kept:** each module's ~0.5-0.75 GB is still reclaimed, just after its tests
  instead of after its link; the peak is bounded to modules linked-but-not-yet-tested.
- **Disk:** worst-case peak ~20 GB (all linked, none tested yet) vs 41-64 GB free. Fits.
- **#1821:** never reintroduced - nothing is deleted mid-lifecycle, so no partial/stale state;
  the delete happens only when the module is fully done and will not relink.
- **Safety net:** add a loud free-disk assertion (the `ci-mon` `diskFreeMB` telemetry already
  exists) so the original silent OOM/disk-kill can only ever surface as a named failure, and a
  future growth past headroom triggers Option C (free-disk step) not a silent death.

### Open questions for Fable (the strategic calls)

1. **Correctness of the moment-shift.** Does keeping the work directory through the test-phase
   relink actually make it a build-skip / incremental relink (fast), given the checksum misses
   today (2/84 skips)? An incremental relink still reuses `.ll` even on a checksum miss, so it
   should be fast, but confirm there is no path where a kept-but-stale work dir reproduces the
   `#1821` shape. (My read: no, because nothing is deleted, so `package2hash` and `generated/`
   stay mutually consistent - the exact opposite of `#1821`.)
2. **Where the redundant links come from.** 59 upfront codegens for ~40 modules means the
   upfront phase itself over-links (prune-driven intra-aggregate relinks) and the test phase
   adds 25. Is the cleaner fix to shift the clean (this proposal) or to also stop the upfront
   over-linking (e.g., is the upfront aggregate link even worth keeping if the test phase links
   anyway, beyond fail-fast + the heavy-module isolated pre-link for OOM)? The Native staging
   is memory-tuned (8.5 GB linker heap; isolated heavy pre-link); I do not want to destabilize
   OOM avoidance to chase link count.
3. **Implementation seam.** Cleanest sbt hook to delete a module's `native-test` after its
   `Test / test` completes (including on test failure), CI-only, without a value-transform
   ordering trap like the current one. Candidate: a `Test / test` `~=`/`andFinally` per native
   module, or a testKyo-driven cleanup keyed off each `X/test` completion.
4. **Disk-peak risk on x64** (21 GB free after retention): acceptable with the loud guard, or
   worth pairing with a `free-disk-space` setup step for margin?

## Alternatives (rejected or deferred)

- **Remove the prune entirely** (my earlier draft): simplest and disk fits today, but the user
  wants the cleanup kept for peak-bounding / future-proofing. This proposal keeps it.
- **Eliminate the upfront aggregate link** (test phase links once): ~halves codegens but
  touches the OOM-tuned staging; higher risk. Consider only if Q2 says the upfront link is pure
  redundancy.
- **Larger-disk runner / free-disk step**: unnecessary given measured headroom; keep as the
  documented response if the loud guard ever fires.
- **Upstream Scala Native fix**: not actionable (0.5.12 latest, not filed). File it as a
  non-blocking follow-up so a future release could allow aggressive pruning again.

## Validation plan

- Local CI-faithful A/B on both arches (`scripts/build.sh --env podman-ci --arch arm|x86`):
  current prune vs post-test prune; measure row wall-clock, `Total`-cycle count, `Build
  skipped` count, and peak `native-test` disk. Expect cycle count ~40-52 (from 84), time back
  toward pre-#1822, peak < measured free disk.
- Keep `scripts/native-relink-selftest.sh` green (it guards relink correctness).

## Relation to the 3-green campaign

Main-repo CI-infra; not touched. If adopted on the branch it would also cut each full run from
~5h toward ~4h, speeding the remaining 3-green runs - an opportunity, not a dependency; needs
an explicit go and would reset the counter on push.

## Finalized plan (post-Fable review)

Fable reviewed against the Scala Native 0.5.12 sources and the CI scripts: **PASS on the
post-test-cleanup direction, with revisions.** Resolutions:

1. **Moment-shift is correct and #1821-safe (verified).** `Build.buildCached`'s skip checksum
   is hash(config, classpath mtimes, artifact mtime); both inputs already survive today's
   prune, so the 2/84 skip rate is classpath-mtime churn between sbt processes, not the prune.
   Pre-#1822 those ~25 misses were cheap incremental relinks; #1822 turned each into a full
   codegen - that is the regression. With kept dirs, `separateIncrementally` regenerates zero
   `.ll` (NIR-hash based) and `LLVM.needsCompiling` reuses every `.ll.o` (mtime based), so both
   expensive phases collapse. No interleaving reproduces #1821 (every torn-state window
   resolves to over-regeneration or a loud clang error, and those windows exist identically
   today).
2. **The upfront phase is NOT over-linking - keep the aggregate link.** ~56 native modules; the
   59 upfront cycles = ~56 aggregate + 2 self-test (kyo-data) + 1 heavy pre-link. Moving first
   links into the test phase recreates the measured OOM config (the test-phase driver is
   heap-capped to 6G without `NATIVE_LINK_CPUS`), and fail-fast is self-tested. Post-test
   cleaning alone removes the whole regression; do not touch the OOM-tuned staging.
3. **Seam:** a per-module CI-only `Test / test :=` transform using the `.result.value` /
   rethrow pattern in `native-settings` (fires on test failure too, reads `insideCI`, work dir
   from `crossTarget / "native-test"`), pruning all-but-`build-checksum`; remove the old
   `Test / nativeLink` hook.
4. **REQUIRED REVISION:** `scripts/native-relink-selftest.sh` fails as written - its
   `assert_pruned` asserts the post-link prune we are removing. It must be reshaped (assert the
   dir is retained post-link and pruned post-test) alongside the build.sbt comment and any
   workflow references, in the same change.
5. **Disk guard:** the honest peak is ~20GB retained into the test phase (post-test cleaning
   bounds the tail, not the peak). Arm the existing `CI_MON_DISK_ABORT_MB` seam in
   `ci-monitor.sh` as the loud guard, and pair x64 with a `free-disk-space` setup step for
   margin.
6. **Alternatives dominated:** delete-`.ll.o`-only redoes all clang (~25-40 min lost) for 33%
   reclaim; the checksum-miss root-cause fix is the highest-ceiling follow-up probe and is
   compatible with this change (can layer on later).

Implementation touch-set: `build.sbt` `native-settings` (swap the hook), `scripts/
native-relink-selftest.sh` (reshape), `scripts/ci-monitor.sh` (arm the disk abort),
`.github/actions/setup` (optional free-disk step on x64). Validate with the local CI-faithful
A/B before any PR.
