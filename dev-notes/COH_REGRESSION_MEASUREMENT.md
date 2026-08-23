# COH-removal regression measurement (for approval)

Purpose: quantify the performance/memory regression of disabling `-XX:+UseCompactObjectHeaders` (commit
`8286f445d1`) across EVERY build leg, so the accept/reject decision is made on complete data, not one
leg. Disabling COH is a real perf/resource regression; accepting it is the maintainer's call.

Method: with-COH baseline = the last clean green full runs (`32409304829`, `32437875190`); without-COH =
the COH-off HEAD `8286f445d1`, run as individual legs. Per leg: wall-clock duration and peak memory
(min free MB of the 16 GB box; peak driver/fork RSS; OOM markers). Native/JS/Wasm run in the shared
driver JVM (which lost COH), so they are measured too; the Scala Native optimizer is the heaviest driver
workload and is the leg most at risk.

## Per-leg regression (with COH -> without COH)

| leg | dur with | dur without | time delta | box-free with | box-free without | OOM |
|---|---|---|---|---|---|---|
| linux-x64 JVM | 135m | 143m | **+6.4%** | 4.8 GB | 3.0 GB | none |
| linux-arm64 JVM | (pending 32472047241) | | | | | |
| windows JVM | (pending 32472049272) | | | | | |
| linux-x64 JS | (pending 32472777595) | | | | | |
| linux-x64 Native | (pending 32472777595) | | | | | |
| linux-x64 Wasm | (pending 32472777595) | | | | | |
| linux-arm64 JS | (pending 32472777595) | | | | | |
| linux-arm64 Native | (pending 32472777595) | | | | | |
| linux-arm64 Wasm | (pending 32472777595) | | | | | |
| windows JS | (pending 32472777595) | | | | | |

x64 JVM detail: driver peak RSS 10.1 GB -> 11.9 GB (of 12 GB `-Xmx`); peak box used 11.2 GB -> 13.0 GB.

## Status

INCOMPLETE. Do not present for approval until every row is filled. Measurement source is now a SINGLE
full-matrix run `32473809504` (COH off, HEAD `8286f445d1`): it measures every leg AND counts as streak
run 1 if green (a full run does both; the earlier per-arch/per-target legs were cancelled while
little-spent because they could not count a green). Compare each leg against the with-COH baseline full
run `32409304829` (all legs green). x64 JVM row above is from the earlier rung-3 vs that baseline.

## Mitigation levers (if a leg is over-tight without COH)

- Memory only: raise the driver `-Xmx` (12 GB -> ~14 GB) uniformly; safe because the driver runs alone
  during the memory-critical compile/link/optimize phase (the 5 GB test forks are not concurrent with it).
  Restores headroom; does NOT recover the time cost.
- Time: intrinsic to COH-off; not mitigable. This is the number the maintainer must approve.

## Alternatives to COH-off (keep the throughput)

- Disable only the specific buggy path: the C2 arraycopy intrinsic (JDK-8380060) or move the TEST JVM off
  G1 GC. Fragile / JDK-version-specific; needs its own soak validation.
- Keep COH, mitigate the flake differently until a JDK 25u fix ships.
