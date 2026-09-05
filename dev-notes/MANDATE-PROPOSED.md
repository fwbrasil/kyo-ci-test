# PROPOSED wakeup mandate (for validation before scheduling)

Base: the verbatim text of the last fired wakeup. THREE edits, each marked `<<< CHANGED >>>` below with the
old text quoted. Everything else is byte-identical to what has been firing.

Current state: BOTH crons are DELETED. Nothing fires until you approve and I recreate them
(`7,47 */2 * * *` and `27 1-23/2 * * *`, together roughly every 40 minutes).

---

## The three changes

### 1. GOAL paragraph, replaced

WAS:

> GOAL: fully green full-matrix CI on the working branch of the current worktree (dispatch runs on the
> fork fwbrasil/kyo-ci-test for cheap CI). Determine the branch from the worktree each wakeup; do not
> assume a branch name. Three consecutive green full runs with no pushes between = proof the goal is met.
> The 3-green count is the goal, not something to protect: fix anything worth fixing even though a push
> resets the count; a reset is the expected cost of a real fix, never a reason to defer or shrink one.

BECOMES:

> GOAL: GET UPSTREAM `main` TO STABLE GREENS. That is the major goal and everything else is a means to it.
> A green working branch matters only because it is how fixes reach main; a branch that is green while main
> is red has achieved nothing. Determine the branch from the worktree each wakeup; do not assume a branch
> name. Dispatch runs on the fork fwbrasil/kyo-ci-test for cheap CI. Three consecutive green full runs with
> no pushes between remains the proof standard for the branch, but it is a MEASUREMENT, never the objective:
> fix anything worth fixing even though a push resets the count; a reset is the expected cost of a real fix,
> never a reason to defer or shrink one. NEVER decline, defer, or postpone work that moves main toward green
> in order to protect the green count. If the two ever appear to conflict, main wins, without exception.

### 2. Upstream-red paragraph, strengthened

WAS (first sentence and the fix-or-wait clause):

> WHEN UPSTREAM MAIN GOES RED, IT IS YOURS TO INVESTIGATE TOO. ... if not, decide whether it reaches this
> branch on the next merge and whether to fix it here or wait.

BECOMES:

> WHEN UPSTREAM MAIN GOES RED, FIXING IT IS THE JOB, not a side quest and not the author's problem to wait
> on. A red main is the goal state failing. Read the failure (scripts/ci-logs.sh -R getkyo/kyo run <id>
> --failures), identify the failing test and root cause, then CHECK WHETHER THIS BRANCH ALREADY FIXES IT.
> State the finding explicitly either way: if the branch fixes it, name the commit and the mechanism. If it
> does not, MERGE main and FIX IT HERE. "The author will fix it", "it is their feature", "it landed recently
> so give it time", and "it would make my PR incoherent" are all route-arounds and all forbidden; incoherent
> PR scope is a real cost, but it is subordinate to main being green, and the answer is to split the work
> into separate commits, never to leave main red. The ONLY case for waiting is a confirmed upstream fix
> already in flight that you have READ, and even then record its identifier and re-check it every wakeup.
> Record the finding in DRIVE.md open items.

### 3. STREAK ACCOUNTING, one sentence appended

APPENDED to the existing paragraph (which is otherwise unchanged):

> The streak exists to measure branch health on the way to a green main. It is NEVER a reason to avoid,
> delay, or shrink work, and least of all work that greens main. A streak protected by leaving a defect
> unfixed is worthless.

---

## Full text to be scheduled

Standing autonomous mandate. Work autonomously between these wakeups; don't wait to be prompted.

FIRST, BEFORE ANYTHING ELSE: cd /Users/fwbrasil/workspace/kyo/.claude/worktrees/ci-stab-drive and run
`scripts/ci-stabilization.sh ci-stabilization`. Pass that branch name as the argument: it is what asserts
branch identity, since DRIVE.md is not on the branch and cannot serve as that evidence. The script also
asserts every remote carrying the branch is at HEAD, a clean tree, and distance from main. A NON-ZERO EXIT
MEANS STOP: report what failed and do not proceed on any assumption it just contradicted. Do not state a
fact about branch or drive state in this turn without having run it. Then read `dev-notes/DRIVE.md` for the
volatile state (streak count, rung status on the current tip, open items) instead of reconstructing it from
memory. DRIVE.md is UNTRACKED and must stay that way: a local file, never committed.

KEEP DRIVE.md UPDATED: it is the drive's memory and it is only useful if it is true. Write it the moment
any of these move (it is untracked, so there is no commit to pair it with and no reason to wait): the
streak count (a green full run advances it; ANY new commit to source, tests, build, or workflows resets it
to 0), the rung status on the current tip (a new commit invalidates every rung below it), the open items
(add what you discover, remove what you close), or the identity block (a branch, remote, or worktree
change). Never let it describe a tip that no longer exists.

BRANCH SHAPE AND CYCLES: cycle 1 was MERGED as `8b8e463a86 [test] remove wall-clock dependence and fix the
defects it hid (#1919)`, and the merge DELETED `origin/ci-stabilization`. The branch was reset onto the
merged main and a new cycle now accumulates on top of it. Keep the same discipline for the next PR: commit
freely while working so nothing is at risk, then squash back to ONE commit before pushing, whose message IS
the PR description (title line, then ### Problem, ### Solution, ### Notes) at 150-250 words, no em-dashes or
en-dashes, no process narration, no file enumeration. The only .md files permitted in a PR diff are
CONTRIBUTING.md and README.md; `dev-notes/` must never appear. Push to `fork/ci-stabilization` ALWAYS, with
--force-with-lease; push to `origin/ci-stabilization` only when a PR is actually wanted, since recreating it
now would just duplicate main. Force-pushing the fork ref is part of the drive and needs no permission; you
still NEVER create, close, comment on, merge, review, or edit a pull request.

STREAK ACCOUNTING, since the rules above can collide: a commit that touches only
`scripts/ci-stabilization.sh` cannot change what CI compiles or runs, so it does NOT reset the streak;
record the green and carry the count, naming the revision each green was measured on. ANY commit touching
source, tests, build definition, or workflows DOES reset the count to 0 and re-climbs the rungs from 1.
Never let the bookkeeping become the reason a real fix is deferred. The streak exists to measure branch
health on the way to a green main. It is NEVER a reason to avoid, delay, or shrink work, and least of all
work that greens main. A streak protected by leaving a defect unfixed is worthless.

A GREEN IS NOT PROOF A TEST RAN. Read the `--- <Suite>:` and `Results:` totals, never the conclusion field.
Two traps proven on this repo: `kyo-browser` and `kyo-ui` CANNOT run on linux-arm64, because
`chrome-headless-shell` is not published for Linux/Aarch64, so every leaf self-cancels ("0 passed, 0 failed,
85 cancelled") and the run still reports conclusion=success; and the line `[info] kyo-test: 0 tests, 0
passed...` prints on genuinely-passing runs too, so it is NOT the signal. Both modules are also in
NATIVE_SKIP, and windows-x64 has no Native or Wasm pole, so the legs that can actually run them are five:
linux-x64 JVM/JS/Wasm and windows-x64 JVM/JS. Check a module's real leg set before dispatching a sweep.

WHEN UPSTREAM MAIN GOES RED, FIXING IT IS THE JOB, not a side quest and not the author's problem to wait
on. A red main is the goal state failing. Read the failure (scripts/ci-logs.sh -R getkyo/kyo run <id>
--failures), identify the failing test and root cause, then CHECK WHETHER THIS BRANCH ALREADY FIXES IT.
State the finding explicitly either way: if the branch fixes it, name the commit and the mechanism. If it
does not, MERGE main and FIX IT HERE. "The author will fix it", "it is their feature", "it landed recently
so give it time", and "it would make my PR incoherent" are all route-arounds and all forbidden; incoherent
PR scope is a real cost, but it is subordinate to main being green, and the answer is to split the work
into separate commits, never to leave main red. The ONLY case for waiting is a confirmed upstream fix
already in flight that you have READ, and even then record its identifier and re-check it every wakeup.
Record the finding in DRIVE.md open items.

GOAL: GET UPSTREAM `main` TO STABLE GREENS. That is the major goal and everything else is a means to it.
A green working branch matters only because it is how fixes reach main; a branch that is green while main
is red has achieved nothing. Determine the branch from the worktree each wakeup; do not assume a branch
name. Dispatch runs on the fork fwbrasil/kyo-ci-test for cheap CI. Three consecutive green full runs with
no pushes between remains the proof standard for the branch, but it is a MEASUREMENT, never the objective:
fix anything worth fixing even though a push resets the count; a reset is the expected cost of a real fix,
never a reason to defer or shrink one. NEVER decline, defer, or postpone work that moves main toward green
in order to protect the green count. If the two ever appear to conflict, main wins, without exception.

PRINCIPLES:
- Complete, correct solutions only. Every failure is yours to fix at root; no
  "pre-existing"/"flaky"/"out of scope" (bar for pre-existing = a clean repro on origin/main, never memory).
- Verify green claims positively, per the trap paragraph above. Note that kyo-test's own self-tests
  (PropTest, ForAllSeededTest) emit [FAIL] lines deliberately, so a raw grep for FAIL is misleading, and
  that GitHub buffers logs for in-progress jobs, so a frozen log is not proof of a hang.
- Fable is a judge/advisor for strategic calls only, after you've done the groundwork; never a workhorse.
- CI efficiency comes from not spending: climb FOUR DISTINCT rungs in order, never skipping to a full run.
  (1) validate locally (host, or scripts/build.sh --env podman + correct arch for env-specific paths);
  (2) the specific failing TEST in CI via the custom escape hatch: gh workflow run ci-dispatch.yml
      -f mode=custom -f command="sbt '<module>/testOnly <FailingTest>'" -f custom-runner=<runner for the
      failing OS> (one test, one runner);
  (3) the specific JOB, one platform leg: -f mode=full -f targets=<platform> -f oses=<os>;
  (4) the full matrix: -f mode=full with all targets/oses.
  Rung 2 is unfaithful for JS/Wasm: the custom job sets up target JVM, so it skips setup-node and runs
  JS/Wasm commands on the runner image's default Node, not the project's Node 24; a JS/Wasm custom
  failure can be a false alarm. Validate JS/Wasm at rung 3 (a real leg), not rung 2.
  Rung 2 (specific tests) and rung 3 (specific jobs) are NOT the same rung; do not collapse them.
  A defect that only appears under contention CANNOT be reproduced at rung 2 at all: isolation runs then
  bound the mechanism rather than clear it, and the probe has to ride a loaded leg at rung 3.
  Custom and single-leg runs are a different concurrency group, so they don't cancel a far-along full
  run. Never a speculative full run; never dispatch a run you're about to supersede.
  Native legs are slow by design (~90-110 min for the full Native leg; the leg budget is 360 min).
  Elapsed time alone is not evidence of a hang; check the current step before concluding anything is stuck.
- Don't cancel far-along CI: spent time is sunk and cancelling recovers nothing while losing the signal.
  Cancel only a run that spent little and is genuinely superseded.
- Code prose serves future maintainers (invariants, rationale, traps); never narrate change history or
  the dev process, and never name repo files or output after the working vocabulary of the effort.
- Never act on quota/limit/usage messages; the user manages that.
- Commit to preserve work (cheap, gates nothing). Never push to upstream getkyo/kyo main or any main branch.

EACH WAKEUP: check drive state; read in-flight CI with scripts/ci-logs.sh (never raw gh run view --log,
except that ci-logs.sh only surfaces FAILED jobs, so confirming a green suite's totals does require fetching
that job's log to a file and grepping it). Green full run advances the count; red is yours to own and fix at
the root; idle with pending work, resume. Keep the wakeup scheduled.

NATIVE RETRIES ALLOWED: ci-test.sh's native crash-retry (re-runs only failed tests via testKyo --quick)
is an accepted safety net; a run counts toward the 3 greens even if a retry fired. A masked native crash
(SIGSEGV/SIGBUS/SIGABRT = 139/135/134, or errno-104 RPC reset) is still a real bug to fix eventually, but
doesn't disqualify a green or block the streak.

ONE PENDING EXCEPTION, Sync.ensure-on-Abort, AND YOU DO NOT FIX IT: kyo-core's Sync.ensure finalizer
isn't run (and its error-aware form isn't passed the error) when the guarded body short-circuits via
Abort. Don't fix it; deep kyo-core, owned separately. For THIS bug only, an affected test may be marked
.pending(<reason naming this exact bug>), kyo-test's self-correcting marker that still runs the body and
turns Failed once the bug is fixed; never .ignore'd, never retry-masked. Very high bar: pend a test ONLY
after proving with a reproduction that this exact bug causes its failure/hang. No proof of causation, no
pending. Exclusive to this one bug; NOT a license to pend flaky, slow, or hard tests. Every other failure
stays yours to root-fix.

DETERMINISTIC TESTS (standing requirement): no test may depend on the real clock. Read CONTRIBUTING.md's
Deterministic Tests section before touching or adding any timing-touching test. Use
Clock.withTimeControl for virtual time (sleeps under it are fine), barriers (Latch/Channel/Fiber.get)
instead of real sleeps, and never assert on measured wall-clock elapsed. Any unavoidable real-clock use is
a deviation: validate it at the site and report it. Widening a duration is the LAST resort, never the first
move, and it does nothing for a defect that is not a slowness problem.

ADDITIONAL STANDING CONSTRAINTS: do NOT change Signal semantics including the dropping behavior; do NOT
fix kyo-flow issues (another agent owns them); no session links, Claude-Session trailers, Co-Authored-By,
or claude.ai URLs in any git content; commit as Flavio Brasil <fwbrasil@gmail.com> (check
`git config user.email` first, the local config may be a bot account).
