# Situation report: getting `getkyo/kyo` main to stable greens

Written for a strategic advisor. Facts only; where something is inferred rather than measured it says so.

## The goal, restated

The objective is **upstream `main` stably green**. A green working branch matters only as the vehicle. The
branch's "three consecutive green full-matrix runs" is a measurement, not the objective.

## Current state

Branch `ci-stabilization`, tip `0751abfc55`, **0 behind main**, fork matches, tree clean.
Streak 0 of 3. No full-matrix run dispatched, deliberately: two known defects are unresolved and dispatching
over them is sampling around a known bug.

Five commits on the branch:

    8684b46727  [kyo-sql]   pool interrupt-reclaim diagnostics
    b4bdd03a04  [kyo-http]  interrupted-streaming-body leaves made deterministic   (fixes a main red)
    5b9bc0bb91  [kyo-i18n]  README end-for so doctest stops rewriting it
    be58198a74  [kyo-aeron] async-add token ownership window on interrupt          (production bug)
    a47a1abe79  merge of main f084e1d08f
    0751abfc55  [ci]        container JS/Wasm get Node 24 + jsdom

## Main is red, on three legs, all from one recently-landed PR (#1876, kyo-ui drag and drop)

    linux-x64   JS   DomBackendReactiveRangesTest   13 passed, 1 failed
    linux-arm64 JS   DomBackendReactiveRangesTest   13 passed, 1 failed
    windows-x64 JVM  UIEventWiringTest              (NOT yet investigated by me)

The failing leaf is `local mount preserves every exposed restricted parent keyed identity and nested JS
properties`, asserting `topology == (2, 2, 2, 2, 2, 2, 2, true, null, 0)`. kyo-test prints the expression,
not the actual tuple, so the ACTUAL value is still unknown.

I merged main so this is now the branch's red and mine to fix, rather than waiting on the author.

## Problem 1: #112 will not reproduce, and four hypotheses are dead

| environment | OS | Node | arch | scope | result |
|---|---|---|---|---|---|
| macOS host | darwin | 24.16.0 | arm64 | suite alone | 14 passed, 0 failed |
| windows-x64 CI | windows | 24 | x64 | full leg | PASS |
| linux-x64 CI | linux | 24 | x64 | full leg | **1 FAILED** |
| linux-arm64 CI | linux | 24 | arm64 | full leg | **1 FAILED** |
| container | linux | 24.16.0 | arm64 | suite alone | 14 passed, 0 failed |
| container | linux | 24.16.0 | arm64 | full kyo-uiJS module, same parallelism | 14 passed, 0 failed |

Dead: "it is Linux", "it is the Node major", "it is the arch", "it is module scope/parallelism".

Still untested: the CI leg runs EVERY JS module together, not kyo-uiJS alone; and CI resolves Node `24` to
the latest 24.x while my container pins 24.16.0. A rung-3 `targets=JS oses=linux-x64` leg is in flight
(`33340063287`) on the branch tip.

## Problem 2: #113 is fixed by construction but causation is NOT proven

Root cause identified in source. In `Topic.scala`'s async-add path, a `Sync.ensure` finalizer frees the async
token when a `tokOwned` flag is set. On a `Done` poll the C layer already freed that token
(`kyo_aeron.c:722`) and transferred the client refcount to the publication bundle, but the Scala flag
recording the transfer was written in the CONTINUATION, across a suspension point:

    Sync.Unsafe.defer(transport.pollAddPublication(tok)).map { poll =>   // suspension point
        case Done(pub) => tokOwned = false                               // flip lands here

An interrupt in that window runs the finalizer with the flag still set: `free(tok)` twice plus
`client_bundle_release` through a dangling pointer. Heap corruption then faults wherever the stale pointer
leads, which is why it surfaced as `EXCEPTION_ACCESS_VIOLATION` in `combase.dll` and looked like a Windows COM
or teardown problem. The crash landed mid-suite at leaf 11 of 27, and leaf 11 is literally "an interrupt
during a pending add is honored and does not hang".

Fix: clear the flag inside the same synchronous block as the poll. Both publication and subscription paths.

BUT: reproduction attempts on the PRE-FIX code are all clean.
  - 4 isolated aeron module runs on windows-latest: clean
  - 12 more, with `hs_err` capture: clean, no crash dump produced
  - a local 4000-iteration sweep varying the interrupt instant 100us..4075us to bracket the ~2ms IPC add: clean
A double-free corrupts rather than reliably faulting, and macOS/Linux allocators commonly absorb it silently
where Windows faulted, so this is not a refutation. But causation is inferred, not demonstrated.
Rate before the fix: about 1 crash in 4 loaded windows-JVM legs. Rung-3 validation in flight (`33334911178`).

## Problem 3: #54 local reproduction is structurally walled

An io_uring read-reclaim leak in kyo-sql, seen once with a full attribution (end-of-run probe reported
`file-descriptor leak (1)` with the pending Read attributed to a source line). It cannot be reproduced
locally: podman's default seccomp blocks the io_uring syscalls, so containers select epoll, and the defect
lives in `IoUringDriver`. 9 isolated container samples and 1 full-module container run are all clean and NONE
of them bear on the defect. The faithful instrument is a real linux-x64 JVM leg. It did not fire on the last
full matrix.

## What I fixed in the harness along the way

Local container runs could not exercise ANY JS/Wasm code faithfully:
  1. jsdom is installed only by the CI setup action, so DOM-backed kyo-ui suites aborted in their constructor
     in every container, identically whether or not the code was broken.
  2. The container image ships Node 18 while CI pins Node 24; jsdom@30 requires >= 22, so even after
     installing it the package would not load, and any JS result was on a different V8 major than CI.
Both now staged on the same JS/Wasm condition CI uses. This unblocked the #112 investigation and is a
standing capability gain, since a large share of open items are JS-side.

## The questions I want judged

1. **#112 next move.** Four discriminators are eliminated and it passes locally in an environment matching a
   failing leg on every axis I can control. Is continuing to chase a local reproduction the right spend, or
   should I instrument the assertion in CI instead (print the actual `topology` tuple, which kyo-test does not
   show) and let one real leg tell me the value? What would you attack next?

2. **Prioritisation for a green main.** Main has three reds. Two are the same kyo-ui test on two legs; one
   (`UIEventWiringTest`, windows JVM) I have not looked at yet. My branch additionally carries an unvalidated
   aeron fix and a still-open io_uring leak. What is the ordering that gets main green soonest?

3. **#113 shipping bar.** Is a correct-by-construction fix with an identified mechanism, matching crash
   timing, but no reproduction, acceptable to land? Or does it need proven causation first, given the cost of
   continuing to hunt a 1-in-4 native race?

4. **PR shape, and this is the one I am least sure about.** The branch now mixes: a CI harness fix, a README
   fix, test-determinism fixes, pool diagnostics, and a production concurrency fix in kyo-aeron. Chasing three
   consecutive green matrix runs on a five-commit branch delays ALL of it reaching main. Would splitting into
   several small, independently-landable PRs get main green faster than the current single-branch cycle, even
   though it costs more merge overhead?
