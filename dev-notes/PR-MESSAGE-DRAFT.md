# Draft PR message for cycle 3 (nothing pushed to origin until the windows-JVM leg reports)

SCOPE IS THE WHOLE BRANCH, and that is forced rather than chosen. `scripts/ci-stabilization.sh` requires
every remote carrying `ci-stabilization` to be at HEAD exactly, so `origin/ci-stabilization` cannot hold a
subset while the local branch holds more. Cycles 1 and 2 worked the same way: the branch becomes the PR,
squashed to one commit, and stays there until it merges.

The eight changes do share one subject, which is why this reads as a PR rather than a pile. Every one of
them is a place where something was reported without the evidence to back it.

  kyo-http   an interrupted fiber's result taken as proof its finalizer ran        MAIN RED
  kyo-ui     two settled regions taken as proof all eight settled                  MAIN RED
  kyo-ui     a fiber result taken as proof its count was cleared (product bug)     MAIN RED
  kyo-browser a missing click probe taken as proof of delivery
  build.yml  a leg dying in native code reported with no crash report
  kyo-sql    a descriptor leak reported with no counters to attribute it
  build.sh   a container that could not reproduce the real leg's Node and jsdom
  kyo-i18n   a README example committed in a shape its own doctest run rewrites

The aeron commit and its revert net to zero and vanish in the squash, which is correct: the theory behind
that change was refuted by experiment, so it must not appear in a diff or a description.

VALIDATION: kyo-http, 10 consecutive clean local runs plus a clean windows-JVM leg on the leg that was red.
kyo-ui topology, a before/after leg pair (13 passed 1 failed to 14 passed 0 failed on linux-x64 JS).
kyo-ui expiry worker, green locally on JVM and JS; its windows-JVM leg is `33355209603` and this must not be
pushed before that reports. kyo-browser, `BrowserActionabilityTest 47 passed, 0 failed` on JVM.

---

## [test] assert on settled state, and keep the evidence when a leg dies

### Problem

Three suites fail on main, each reading state before the work it reflects has finished: a streaming test
treating an interrupted fiber's result as proof its finalizer ran, a mount test waiting on two of eight
independently subscribed regions before reading a topology covering all eight, and a drag subscription whose
scope close awaits a worker result that lands before the finalizer clearing the worker count.

Reporting has the same gap: a leg whose JVM dies in native code fails with no test name and no crash report,
and a click whose delivery probe vanished reads like a confirmed one.

### Solution

The streaming leaves hold the consumer with an undrained body, so the interrupt lands on the state under
test. The mount test settles on the whole topology. The subscription's scope clears the worker count itself,
registered before the worker starts so finalizer ordering places it last. Click delivery becomes three
states, so an unconfirmed one still passes but says so. A failed leg prints any JVM crash report; the pool
reports interrupt-reclaim counters.

### Notes

Assertion strength is unchanged: a region that never updates still fails, by exhausting its retry budget.
The container runner installs the Node and jsdom the workflow provides and checks emulation where the
containers run, so JS, Wasm, and cross-architecture defects are reproducible outside CI. The kyo-i18n README
example is committed in the shape its doctest run produces.
