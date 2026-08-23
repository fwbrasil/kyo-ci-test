# Held-out review: `CompatBackendAxis.local` (commit `d973838a7c`)

**Verdict: SOUND-WITH-ISSUES** — the implementation is correct and I verified every behavior it claims by running it, but the feature's core safety promise (an unbound `local` backend fails with a clear error) ships with **no automated test**, and the author's stated reason for omitting it ("can't be cleanly tested in scripted") is false: the sibling `empty-intersection-fail` test already uses the exact pattern needed, and I used it to trigger the guard myself.

## What I ran (all from the worktree)

1. `sbt 'kyo-compat-plugin/scripted kyo-compat/bind-locally'` → **PASS**. New checks green:
   - `checkLocalAxisDep OK; myLocalAcme dependsOn Set(fakeLocalAcme)`
   - `checkLocalAxisNoMaven OK; vendored local axis has no maven compat dependency`
   - (all pre-existing bind-locally checks also green)
2. `sbt 'kyo-compat-kyoJVM/doctest'` → **PASS**: `total=30 compiled=0 cacheHits=19 warnings=0 failures=0` (README validates; the two changed blocks are `doctest:expect=skipped`).
3. `sbt 'kyo-compat-plugin/scripted kyo-compat/external-backend'` → **PASS** (regression guard for the shared helper): `checkExternalCoords OK; myLibAcme -> com.acme:kyo-compat-acme:9.9.9-EXTERNAL`, `checkBuiltinCoords OK; myLibKyo -> io.getkyo:kyo-compat-kyo:STUB-COMPAT-VERSION`.
4. **Independently triggered the unbound guard** with a throwaway build using the HEAD plugin (`1.0.0-RC5+113-d973838a-SNAPSHOT`) from `~/.ivy2/local`, two ways:
   - Task-wrapped (empty-intersection-fail pattern): caught `compatLibrary: backend 'acme' was declared with CompatBackendAxis.local but not bound; add .bindLocally(<axis>, <project>) for it.`
   - Top-level unbound `local` axis: `Project loading failed` with the same message — confirming the README's "fails at build load" claim.
   - Token/dash checks on added lines: no `cats`/`cats-effect`/`CatsEffect`/`CE` tokens (whole branch `origin/main..HEAD` and the single commit), no em/en dashes, no marketing AI tells.

## The unbound guard — independent assessment

`CompatLibrary.scala:185-189`. Fires iff `backend.local && !metaOf(matrixId).exists(_.bindings.contains(backend.name))`, evaluated at the top of the `process` closure, i.e. at row materialization (`componentProjects`/`projectRefs`).

- **Fires correctly, and only when it should.** Gated on `backend.local`, so `external` and the five built-ins (all `local = false`) never reach it. For a bound local backend the predicate is false (verified: `checkLocalAxisDep`/`NoMaven` green, and my top-level *bound* case in the scripted run loads fine). For an unbound local backend it fires — I reproduced this both at build-load and task-wrapped.
- **No false-positive vs. the re-read pattern.** The guard reads the live registry at materialization; a `bindLocally` chained after `.compatLibrary(...)` (the documented usage) runs before materialization, so the binding is seen. Confirmed by the passing chained scripted case.
- **Preempts the maven miss it claims to.** An unbound `local` axis carries default coordinates (`resolvedArtifactName = "kyo-compat-acme"`, `organization = "io.getkyo"`), so without the guard the `libraryDependencies` else-branch (`CompatLibrary.scala:214-221`) would try to resolve a nonexistent `io.getkyo:kyo-compat-acme`. The guard throws first (same closure, eager), so that path is unreachable. The three sites (guard, suppression, `dependsOn`) all read the same `bindings.contains(backend.name)` predicate consistently.
- **It IS cleanly testable.** `empty-intersection-fail/build.sbt` wraps a failing `.compatLibrary(...)` + `m.componentProjects` in a task body and asserts the caught message (`-> triggerError` / `checkErrorMessage`). The unbound guard is a structurally identical build-load error and I drove it with that exact pattern. The author's "verified by construction" reasoning does not hold.

## Findings

### MAJOR — the unbound guard has no regression test, and the omission rationale is wrong
- **Where:** new coverage in `bind-locally/build.sbt:184-227` + `bind-locally/test` covers only the *bound* path. The error path (the entire point of `local`'s safety story, documented at `README.md:716` and in the commit message) is untested.
- **Problem:** The commit message and briefing claim it "can't be cleanly tested in scripted (build-load failure)" and was "verified by construction." That is factually incorrect — `kyo-compat/plugin/src/sbt-test/kyo-compat/empty-intersection-fail` tests a build-load `sys.error` via a task-wrapped `componentProjects` + catch. Per the repo conventions ("Write Meaningful Tests: cover error paths"; "a fix with no reproducing test is incomplete"), a demonstrably-feasible test for a shipped safety behavior should exist.
- **Evidence / drop-in fix:** add to `bind-locally/build.sbt` (and one `> ` line in `test`):
  ```scala
  val checkUnboundLocalErrors = taskKey[Unit]("unbound CompatBackendAxis.local fails with a clear message")
  checkUnboundLocalErrors := {
      val caught =
          try { val m = sbt.internal.ProjectMatrix("unboundLib", file("unbound-lib"))
                    .compatLibrary(CompatBackendAxis.local("acme","Acme","-acme",Set("jvm")))(VirtualAxis.jvm)(Seq("3.3.4"))
                m.componentProjects; None }
          catch { case t: Throwable => Some(t) }
      caught match {
          case None    => sys.error("unbound local axis should have failed")
          case Some(t) => val msg = t.getMessage
              val missing = Seq("acme","local","bindLocally").filterNot(k => msg != null && msg.contains(k))
              if (missing.nonEmpty) sys.error(s"unclear message, missing $missing: $msg")
      }
  }
  ```
  I ran exactly this shape against the HEAD plugin and it passes (caught the clear message).

### NIT — "Setting up an external binding" example now declares a `local` axis
- **Where:** `README.md:736` (inside the section headed "Setting up an external binding") uses `CompatBackendAxis.local("acme", ...)`.
- **Problem:** Defensible (the in-development conformance harness vendors the not-yet-published binding, so `local` is the right choice, and the commit intentionally drops the awkward placeholder coordinates), but a skim reader hitting `local(...)` under an "external binding" heading may briefly stumble. Consider a half-sentence noting the harness vendors locally until the artifact is published. Not blocking.

### Context note (not a defect) — guard inherits `bindLocally`'s "bind before materialization" contract
- If a consumer binds in a *separately-forced* `lazy val` rather than chaining (outside the usage `bindLocally`'s scaladoc documents at `CompatPlugin.scala:110-114`), materialization order is not guaranteed and the guard could fire. But that misuse already mis-behaved before this change (silent stale maven dep). The guard makes the same misuse fail *louder* (build-load error, not a confusing maven miss) — a net improvement. All documented (chained) usage is safe; verified.

## Areas checked and cleared
- **`local` + `bindLocally` semantics:** row `dependsOn` the vendored project, no maven `kyo-compat-*` dep — verified (scripted `checkLocalAxisDep`/`checkLocalAxisNoMaven`).
- **Re-read-at-materialization interaction:** chained `bindLocally` after `.compatLibrary(...)` is seen — verified.
- **No regressions:** `external` still pulls explicit coordinates and built-ins are unchanged (external-backend scripted green); the collision-message reword ("external backend name" → "backend name") is asserted by no test and has no stale references; bare `CompatBackendAxis(...)` apply and the five built-in axes still compile because `local` defaults to `false`.
- **Scripted substance:** new checks exercise the *new* `local(...)` factory (not just `bindLocally` on a built-in) and assert concrete behavior — not a rubber stamp.
- **README accuracy:** documented `local(name, idSuffix, directorySuffix, supportedPlatforms)` signature matches the implementation; "fails at build load with a clear error" verified true; "`bindLocally` also works on an `external` backend" is consistent with the name-keyed binding mechanism.
- **Conventions:** no em/en dashes, no cats/`ce`/`Ce`/`CatsEffect` tokens, no marketing AI tells on any added line.
