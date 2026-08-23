# Senior review: external cats-effect integration package (code + public design doc)

Reviewer scope: architecture judgment, publication-readiness of the public doc, and risks
the three correctness reviews (VALIDATION_REPORT.md, SAGE_VALIDATION.md, DESIGN_VALIDATION.md)
did not cover. Facts established there are assumed, not re-derived.

## Overall

**Ship with changes.** The code is the right shape: every major architectural call
(bundle-in-plugin, coordinates-on-axis, `external` factory with guard, the selftest) is one I
would have made, and each has a structural justification, not just a convenience one. The design
doc is close to publication-ready: tone is clean, the political subtext is handled by staying
strictly technical, and the structure earns its length. Before the owner posts it, three things
need fixing: a compile-broken recipe in `kyo-compat/README.md` (the exact snippet a binding
author will copy), a subtle timeline misframe in the sentence that names `sage`, and a missing
availability statement (the extension points the doc presents as existing are in no published
release; the removal itself is not in RC5, verified). None is rework; all are small and specific.

## Design judgment

### 1. Bundling the conformance suite inside the plugin jar: ENDORSE

This is not merely acceptable, it is the natural design, for a reason none of the prior reports
states explicitly: **the suite cannot be a compiled artifact at all.** Each binding itself
provides the `kyo.compat.*` types (opaque aliases, everything `inline def`; there is no shared
`kyo-compat-api` the suite could compile against once). So the suite must be distributed as
source and compiled per binding. The real alternatives were (a) a published source/resources
artifact plus extraction machinery, or (b) resources in the plugin jar. (b) wins cleanly:

- Zero extra coordinates. Every kyo-compat consumer already has the plugin; the row-generation
  workflow is the plugin.
- Version lock for free. The suite version is the plugin version is the kyo version whose
  surface contract it tests. A separate testkit would be a second artifact whose version must
  always equal the plugin's: pure coordination overhead with no benefit.
- The plugin already knows the platform bucketing the extraction needs.

Cost is 20 source files in a jar. A future maintainer will not regret this. The one thing
bundling does create is a new responsibility, covered under Risks (R4): `kyo-compat/test` is now
a shipped public contract, not just an in-repo test tree.

### 2. Coordinate overrides on `CompatBackendAxis` itself: ENDORSE

The axis already IS the backend's identity (name, suffixes, platforms); the artifact coordinates
are per-backend data consumed at row materialization. Putting them on the axis keeps one
resolution code path (`CompatLibrary.scala:206-213`), which is why both validators could confirm
built-ins byte-identical with so little effort. The alternative (a separate declaration, e.g. a
`bindExternal(axis, coords)` in the style of `bindLocally`) would split identity from resolution,
create an ordering question, and allow declaring coordinates for a built-in. Two accepted
consequences, both fine:

- Axis equality stays name-only, so coordinates do not participate in identity. The sharp edge
  (an external axis masquerading as a built-in) is exactly what the collision guard closes.
- The split between the two constructors is well judged: `external` requires every coordinate
  explicitly (the intentioned front door), while the bare 4-arg `apply` keeps defaults and
  source compatibility (sage's hand-rolled `PekkoLib` still compiles, per SAGE_VALIDATION G5).

### 3. `external` factory + built-in-name collision guard: ENDORSE, one nit

Guard in `external` only, not in `apply`, is correct: `apply` constructs the built-ins
themselves and must stay unguarded. Allowing `name = "ce"` (no longer built in) is precisely
sage's migration. Nit: `builtinNames` (`CompatLibrary.scala:316-317`) is a hand-maintained
mirror of the `CompatPlugin.autoImport` axes; the comment says so, but nothing enforces it. A
one-line test asserting the set equals the autoImport axes' names would prevent silent drift the
next time a built-in is added or removed. Not blocking.

### 4. The conformance selftest: ENDORSE, one precision note and one brittleness nit

The selftest closes VALIDATION gap #1 / SAGE G2 the right way, and it has a second property
nobody named: because `kyo-compat-future` depends on no other kyo module, the selftest's
classpath (scalatest + the binding) approximates an external consumer's. So it doubles as a
contract guard: if a canonical test ever grows an in-repo-only dependency (a kyo `Test` base
class, a helper outside the bundle), the selftest compile breaks, not some third party's build.

Precision note, so nobody over-reads the coverage: the selftest consumes
`kyo-compat-plugin / Compile / managedResources` directly. It proves bundle content compiles and
passes against a real binding; it does NOT exercise `CompatConformance.extract` (jar
classloader, INDEX parse, collision guard). That path is covered only by the scripted test
against a stub. The two compose via the byte-identity check; the composition is sound, and
splitting it this way (scripted: mechanics; selftest: content vs real binding) is the right
trade against a slow full-fat scripted run. Accept.

Brittleness nit (`build.sbt:2538-2544`): the filter is substring matching on the absolute path
(`p.contains("-shared") || p.contains("-jvm")`). A checkout path containing `-jvm` or `-shared`
(CI workdirs do this) would admit every bucket, including a future `test-js`/`test-native`
bucket, into the JVM compile. Latent today (no js/native buckets exist in-tree, verified), but
anchor the match to path segments under `kyo-compat-testkit` rather than the whole path.

### 5. `.compatConformance()` granularity (all rows of a matrix): ENDORSE

Right for the intended use, because the documented pattern is a dedicated one-row harness matrix
(README and doc both show it). Wiring per-matrix rather than per-backend keeps the API to one
call with one parameter. A consumer who calls it on their main library matrix merely runs the
suite against extra backends; wasteful, not wrong.

## Doc judgment (EXTERNAL_CATS_INTEGRATION_DESIGN.md)

**Publication-ready after the specific fixes below.** As a document: the structure serves the
reader, not ceremony. Goals/Non-goals do the politically necessary fencing (no re-hosting, no
`io.getkyo` publishing, no governance prescriptions) in the least dramatic register possible.
"Alternatives considered" is substantive. The recipes are concrete enough to execute. Length is
earned; I found no waffle. The carrier-identity point appears twice at full strength (section B
and Cross-cutting/Testing); DESIGN_VALIDATION already judged the repetition defensible and I
agree, for a gotcha this load-bearing.

Tone for a public post: right. The doc never names Typelevel, the ban, or any person; it states
the removal as a fact with issue links and moves on. "Rejected by #1778" states the decision
without re-litigating it. "The integrations themselves are useful and some projects already
depend on them" (line 14) is exactly the sentence that keeps the doc from reading as dismissive
of the removed code's users. I found nothing I would refuse to publish.

Specific passages to change:

1. **The sage sentence misframes the timeline.** Line 289: "The `sage` Redis/Valkey client is a
   live example, pinned to the release where `ce` was still built in." "Pinned" reads as a
   defensive act, as if sage already reacted to the removal. In fact sage is simply on the
   latest published release (1.0.0-RC5), and the removal is in no release yet (verified:
   `90f79b86a4` is not an ancestor of `v1.0.0-RC5`). In a politically charged thread, phrasing
   that casts a named third party as already affected, when they are just current, is the kind
   of small inaccuracy that gets picked at. Reword to the plain fact, e.g.: "The `sage`
   Redis/Valkey client is a live example: it builds against 1.0.0-RC5, which predates the
   removal, so its first upgrade past it will hit exactly this."

2. **Naming sage at all: fair, keep it, but warn the maintainer first.** The mention is single,
   factual, and assigns no obligation; the worked example is phrased generically with sage as an
   instance. That is the right weight. The landing risk is not the sentence, it is the delivery:
   SAGE_VALIDATION found no sage issue or PR that acknowledges the removal, so the gist is
   plausibly how ghostdogpr learns their `ce` cell's future, by being named in a controversial
   thread. Recommend the owner ping them before posting (or simultaneously). If the owner
   prefers not to, an anonymous variant ("at least one published cross-backend library builds
   against the pre-removal release") loses little.

3. **Missing availability statement; two sentences currently overclaim.** Line 37: "Require no
   new work in the kyo repo. The integrations must build on already published `io.getkyo`
   artifacts and existing plugin extension points." And line 137: "Two extension points, added
   specifically to support out-of-repo backends". At posting time, `CompatBackendAxis.external`
   and `.compatConformance()` exist in NO published plugin (they are on this unmerged branch;
   RC5 predates even the removal). A reader who tries the recipe against the released plugin
   finds neither symbol and concludes the doc is wrong. Add one sentence stating the minimum
   version, e.g. in "Building blocks": "Both extension points ship in the first kyo release
   after the removal (kyo >= <next version>); every kyo version that lacks the built-in `ce`
   backend also has them." That last clause is the guarantee that matters, and it doubles as a
   commitment the release process must honor (Risk R1).

4. **Minor: "Status: Draft."** For a gist posted as the plan, "Draft" invites "so this isn't
   decided?" replies. Suggest "Proposed" or dropping the field when posting.

## Risks (prioritized)

**R1. Release sequencing is load-bearing and currently implicit.** The removal is unreleased
(not in RC5). If this branch merges before the next release, the first release without the
built-in `ce` also carries the migration tooling and the doc's claims become true exactly when
they matter. If any release ships in between, there is a stranded kyo version where `ce` is gone
and the tooling absent, and the public doc is wrong for it. Nobody flagged this because each
review looked at one artifact; the constraint lives between them. Make "this branch ships in the
first post-removal release" an explicit requirement.

**R2. The README recipe is compile-broken and has drifted from the doc's corrected recipe.**
`kyo-compat/README.md:718`: `version = version.value` inside the top-level
`val CeLib = CompatBackendAxis.external(...)`. In a `build.sbt`, `.value` outside a
setting/task macro is a compile error ("`value` can only be used within a task or setting
macro"). This is the exact snippet an external binding author will copy, and it is
`doctest:expect=skipped`, so nothing catches it. The version is irrelevant in that harness
anyway (`bindLocally` suppresses the injected dependency): use a literal placeholder. Same
snippet (README:711-712): the local binding project lacks `scalacOptions += "-Xmax-inlines:1024"`
and the fs2 dependency that the design doc's equivalent recipe gained when DESIGN_VALIDATION F2
was applied (doc lines 217-224). The fix was applied to one copy of the recipe and not the
other; that the two copies have already diverged is itself the warning. Mirror the fix, and
prefer making one of the two the reference ("see the kyo-compat README" from the doc, or
vice versa) so future drift has one source of truth.

**R3. The bundled suite is now a shipped contract, with no process guard on tightening it.**
Any edit to `kyo-compat/test/**` now changes what external bindings are held to, silently, at
the next plugin release. The selftest guards compilability and the scripted test guards
extraction, but nothing marks a semantically stricter test as a breaking change for external
bindings. This is a process risk, not a code defect: add a line to the kyo-compat contributor
docs stating that `kyo-compat/test` changes are contract changes for external bindings and
should be reviewed (and release-noted) as such.

**R4. scalatest version literal duplicated.** The `.compatConformance()` default `"3.2.20"`
(`CompatPlugin.scala:138`) and the scripted assertion (`conformance/build.sbt:85`) duplicate
`scalaTestVersion` (`build.sbt:34`). When the repo bumps scalatest, the bundle gets written
against the new version while the consumer-facing default stays old; if the suite ever uses
newer scalatest API, external conformance builds break with a confusing error. Low likelihood;
a cross-referencing comment at all three sites, or a drift test, is enough.

**R5. Loose ends in the review record itself.** VALIDATION_REPORT.md ends with "Full scripted
regression (run #5)" and an unfilled placeholder; either the run happened and was never
recorded, or it never happened. Before the owner treats the record as complete, fill it or
delete the section. Also: all five worktree `.md` artifacts (including the design doc, the actual
deliverable) are untracked working-tree files; nothing durable holds them. Per this project's
own preservation rules the doc should be committed somewhere (a branch is fine) before the gist
dance starts, and `CATS_BINDING_ANALYSIS.md` must not ride along into the PR (VALIDATION F5).

**R6. Selftest filter brittleness.** Covered in Design judgment 4: substring path matching
(`build.sbt:2540-2543`) can misclassify buckets on unlucky checkout paths once js/native buckets
exist. Segment-anchor it.
