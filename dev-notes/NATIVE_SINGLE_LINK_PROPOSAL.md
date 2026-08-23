# Proposal: single Native link in CI (compile upfront, link once in the run phase)

Status: proposal for review (Fable). Supersedes the post-test-cleanup approach in
`NATIVE_LINK_SLOWDOWN_PROPOSAL.md` (kept as fallback). Not implemented.

## TL;DR

The Native CI rows link every module **twice** — once in the upfront aggregate
`kyoNative/Test/nativeLink`, then again when `testKyo --all Native` runs each `X/test`. The
second link is not intentional: it is Scala Native's **build-skip failing across sbt
invocations** (SN #2514), so the intended reuse of the upfront binary turns into a full
re-link (re-doing closed-world reachability, codegen, and clang). That doubled work is the
Native time.

**Fix: don't link upfront at all. Compile upfront (no link), and let the run phase do the one
and only link, inside the same session as the test.** Every other platform (JVM/JS/Wasm)
already uses this `compile-main -> compile-test -> run` phase split; Native is the only one
that fused compile+link into an upfront step. Linking inside `X/test` means the link result is
reused in-session, so there is no cross-session build-skip to fail (#2514 becomes irrelevant),
and each module links exactly once.

Benefits: one link per module (no redundant reachability), no dependence on the flaky
build-skip, keeps `#1822`'s low-disk prune (no cache retention), and drops the upfront
aggregate link plus the isolated heavy pre-link.

## Evidence

### The double-link and its cause

- One arm64 Native row: **84 full codegen cycles for ~56 modules, 2 `Build skipped`**; 59
  before the test phase, 25 after. Each cycle re-runs reachability + codegen + clang.
- Root cause is **SN #2514** ("`nativeLink` always relink output"), CLOSED. The maintainer:
  the build-skip is unreliable across sbt invocations, specifically **when Scala versions are
  switched** ("we probably override last linking result, so since it's different we try to
  link it again"), and mtime perturbation breaks it too. His stance: caching "is better at the
  level of the build tool instead of the linker" - i.e. SN will not fix it in the linker. SN
  0.5.12 is latest; not fixable by upgrade.
- `testKyo` cross-builds Scala versions (3.8.4 -> 2.13 -> 2.12 -> back). The upfront link and
  `testKyo` are **separate sbt processes**; that split plus the version churn is #2514's exact
  trigger.

### Confirmed locally (the fix is sound, generators are innocent)

- `kyo-core`, `kyo-ffi`, `kyo-prelude`, `kyo-data`: the test-phase link **`Build skip`s** in a
  simple two-process `nativeLink` then `test`. So the miss is **not** reproducible in the
  simple flow, and **not** caused by our source generators (`kyo-ffi` has generators and still
  skipped). It is the CI-specific cross-session + version-switch flow.
- **link + test in ONE sbt session `Build skip`s** (control). This is the load-bearing result:
  coupling the link with the run in the same session avoids the relink entirely. That is
  exactly what "link inside `X/test`" does.
- **The trigger is proven to be the Scala-version switch.** Empirically: link in one process,
  then a *separate* process that runs `++2.13 ++2.12 ++3.8.4` then `X/test` -> **RELINKED**;
  the one-session flow -> build-skipped. This reproduces #2514's exact cause locally and
  confirms that removing the separate upfront link (so the only link is in the run session,
  before any `++` switch reaches it) eliminates the relink.

## Design

Make Native take the same shape JVM/JS/Wasm already take (`scripts/ci-test.sh`,
`project/TestKyo.scala` `taskFor`):

1. `testKyo --phase compile-main --all Native`  (uncapped 12G driver)  - compile main, no link.
2. `testKyo --phase compile-test --all Native`  (uncapped 12G driver)  - compile test, no link.
3. `testKyo --all Native`  (run phase)  - each `X/test` links `X` once (in-session) and runs.

Remove: the upfront `kyoNative/Test/nativeLink`, `NATIVE_HEAVY` isolated pre-link, and the
`NATIVE_LINK_CPUS` parallel-link cap (there is no separate parallel link to cap).

Why one link, guaranteed: with no upfront link there is no prior artifact for a build-skip to
mis-compare; the run-phase `X/test` performs the first and only link, and `testKyo` runs
`X/test; Y/test; ...` **sequentially** (`;`), so links happen **one module at a time**.

### Memory (the crux)

Sequential per-module linking bounds the footprint to a **single** link at a time, which is
why the current design's isolated heavy pre-link and parallel-link cap become unnecessary. Two
facts to reconcile:

- The run phase is currently capped at **`-J-Xmx6G`** (to leave headroom for forked test
  processes: native binaries, and podman/chrome for kyo-pod/kyo-browser native tests). The
  Native optimizer for the heavy module (`kyo-schema-tests`) needs a **12G** driver (today it
  is pre-linked in its own 12G driver, `NATIVE_HEAVY`).
- So linking in the run phase needs the run-phase heap **raised** (toward 12G) for Native. The
  build.yml note warns that "the Native optimizer plus forked LLVM/podman/chrome run alongside
  it; two of those at once exhaust the 12GB heap." **But the current test phase ALREADY does a
  coupled link+run in the 6G driver** for the 25 relinking modules and completes - so coupled
  link+run is already in production; this proposal makes it the only link and sizes the heap
  for it deliberately rather than by accident.

## Open questions for Fable (the strategic calls)

1. **Heavy-module memory.** With sequential run-phase linking at a raised heap (~12G), does
   `kyo-schema-tests` (and any other heavy module) link safely in the run driver given its own
   test then forks (LLVM/podman/chrome)? Or must the heaviest still be linked in an isolated
   step (e.g. an isolated `X/test` before the aggregate run), and if so does that isolated step
   re-introduce a cross-session relink for that one module?
2. **Speed: sequential vs parallel.** The upfront aggregate link ran with `NATIVE_LINK_CPUS=2`
   (2 concurrent module links). Sequential run-phase linking loses that cross-module
   parallelism (each link still parallelizes clang internally). Is one sequential pass (~56
   links) faster than today's parallel-upfront (~56) + sequential-relink (~25) = 84? Estimate
   the wall-clock, not just the codegen count.
3. **Run-phase heap vs fork headroom.** Raising the Native run driver to ~12G leaves less room
   for the podman/chrome forks the note worries about. Is per-module sequencing (link, then run
   that module's forks, then next) enough to keep the peak safe, or does the link heap held
   during a module's fork run overcommit the 16G runner?
4. **Fail-fast.** compile-main/compile-test catch compile errors early; a *link* error now
   surfaces in the run phase instead of upfront. Acceptable, or is upfront link-failure
   detection worth preserving (and if so, it re-introduces the double-link)?
5. **Is there residual #2514 risk** inside a single `testKyo` run? The Native tests run in the
   first `runForScala(3.8.4)` pass, before any `++` switch, so the switch is after - but
   confirm the in-session first link for each module cannot be spuriously re-triggered by
   anything `testKyo` does between modules.

## Alternatives

- **Post-test-cleanup (retain the cache)** - `NATIVE_LINK_SLOWDOWN_PROPOSAL.md`. Makes the
  inevitable relink cheap (codegen/clang cached) but still pays redundant reachability and
  retains ~20GB. Robust and low-risk; the **fallback** if the single-link memory can't be tuned
  safely.
- **Fix the build-skip / file upstream** - not actionable (SN won't fix in the linker).
- **Keep upfront link, force in-session reuse** - would need link+run in one session for all
  modules, which is what this proposal does per-module anyway.

## Validation plan

- Local CI-faithful A/B (`scripts/build.sh --env podman-ci --arch arm|x86`): current flow vs
  compile-split + run-phase single link. Measure row wall-clock, `Total`-cycle count (expect
  ~56, from 84), `Build skipped` count, peak driver heap and peak disk, and confirm no OOM on
  `kyo-schema-tests`.
- Update `scripts/ci-test.sh` Native strategy + its self-tests (they currently assert the
  Native aggregate-link path with no `--phase`), and `native-relink-selftest.sh` (its premise
  changes).

## Finalized design (post-Fable review): REVISE -> implement

Fable verified against the sources: the single-link direction is sound; two corrections.

1. **Confirmed it eliminates the relink.** `testKyo` joins `X/test; Y/test; ...` through one
   sequential `Command.process` (`TestKyo.scala:145-147`); no module depends on an earlier
   module's Test link; the `++` switches happen only after the full 3.8.4 pass
   (`TestKyo.scala:89-107`). `#1822`'s prune stays unchanged (binary is outside the pruned
   dir). Residual #2514 exposure is only the rare crash-retry and the heavy step (closed below).

2. **Memory - do NOT raise the shared run driver; keep the heavy module isolated.** Decisive
   fact I missed: `runAll` sorts **alphabetically**, so right after `kyo-schema-tests` come
   `kyo-sql-tests` (podman Postgres+MySQL container suites, `build.sbt:963-994`) and `kyo-ui`
   (real Chrome, `build.sbt:2886-2900`). A run driver raised to ~10-12G with containers and
   Chrome stacking on it is the documented kernel-OOM shape (`build.yml:71-75`). **Safe config:**
   - compile-main + compile-test, uncapped (12G .jvmopts), no link;
   - an **isolated `kyo-schema-testsNative/test` at 12G with `NATIVE_LINK_CPUS=2`**, under the
     stale watchdog (link+run in one session, so the single link survives for the heavy module);
   - the **aggregate run at `-J-Xmx6G` with `testKyo --exclude kyo-schema-tests`**.
   - `--exclude` is **mandatory**: without it a #2514 skip-miss would relink the 9.9G heavy
     module inside the 6G driver. `NATIVE_HEAVY` and `NATIVE_LINK_CPUS` survive, rescoped.

3. **Speed: strictly faster, and my "parallel upfront" premise was wrong.** `SBT_TASK_LIMIT=1`
   = `Tags.limitAll(1)` (`build.sbt:93-106`), so today's upfront link is **already sequential**
   across modules, each capped to 2 CPUs. Single-link goes ~84 -> ~50 cycles, with surviving
   links at 4 CPUs instead of 2: **~50-70 min saved** on the ~196-min arm64 row. No dedicated
   parallel-link phase warranted. Diff rows also stop paying today's unconditional full
   aggregate link (`ci-test.sh:419-424`).

4. **Blast radius:** rewrite `run_native` in `scripts/ci-test.sh` + its self-test cases
   (2b/6/7/8/8a/8a2/8b) and the count gate; add `--exclude` to `TestKyo.scala`; comment-only
   `build.yml`; **`native-relink-selftest.sh` stays functionally unchanged** (its #1821 premise
   remains live via crash-retry / diff / local relinks - contra the draft above). Fail-fast
   loss is acceptable (only kyo-schema-protobuf lacks its own tests; it is link-validated
   through kyo-schema-tests' binary). **One hole to close in the A/B: links now run under the
   600s stale watchdog - verify the longest silent link phase stays under it.**

The post-test-cleanup fallback is dominated and not recommended.

## Relation to the 3-green campaign

Main-repo CI infra; not implemented. If adopted it cuts each full run materially (~50-70 min off
the ~5h Native long pole). Separate decision; resets the counter on push.
