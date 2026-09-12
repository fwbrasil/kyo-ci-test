| row | base | tip | delta | |
|---|---|---|---|---|
| pureIterationViaArrow | 98.555 ± 47.719 | 92.385 ± 53.693 | -6.3% | noise |
| continuationBodiesFuse | 16.273 ± 1.537 | 15.312 ± 1.062 | -5.9% | noise |
| entryFloorBatch | 0.000 ± 0.000 | 0.000 ± 0.000 | -4.3% |  |
| deferBindUnderTrailingMap | 29.929 ± 0.832 | 28.892 ± 0.824 | -3.5% |  |
| foreachOverCollection | 15.078 ± 0.994 | 14.696 ± 0.135 | -2.5% |  |
| collectOverCollection | 13.899 ± 0.331 | 13.550 ± 0.331 | -2.5% |  |
| sharedHandlerPaysDispatch | 172.269 ± 3.181 | 168.347 ± 2.390 | -2.3% |  |
| fusionAfterSuspensionRunOnly | 0.533 ± 0.005 | 0.522 ± 0.007 | -2.0% |  |
| fusionAllocatesNothing | 0.207 ± 0.009 | 0.205 ± 0.002 | -1.3% |  |
| contextReadsUnderBindings | 40.097 ± 1.559 | 39.580 ± 0.880 | -1.3% |  |
| inlineLimitCostsTimeNotAllocation | 285.255 ± 9.964 | 281.663 ± 2.771 | -1.3% |  |
| fusionAfterSuspension | 129.603 ± 3.072 | 128.013 ± 1.596 | -1.2% |  |
| idleHandlerAddsNothing | 48.539 ± 1.613 | 47.956 ± 0.590 | -1.2% |  |
| pureIterationViaLoop | 15.952 ± 0.333 | 15.775 ± 0.052 | -1.1% |  |
| suspensionBaseline | 99.086 ± 2.122 | 98.020 ± 1.518 | -1.1% |  |
| foreignCrossingsPayRotation | 1029.578 ± 40.313 | 1018.613 ± 55.005 | -1.1% |  |
| uncachedValuesPayBoxingOnly | 49.630 ± 1.090 | 49.114 ± 0.240 | -1.0% |  |
| foreignCrossingsAnsweredInPlace | 796.305 ± 163.589 | 788.851 ± 162.374 | -0.9% |  |
| statefulAnswersPaySuccessor | 40.934 ± 0.692 | 40.567 ± 0.375 | -0.9% |  |
| suspensionBaselineAltEnv | 69.589 ± 2.122 | 68.971 ± 1.350 | -0.9% |  |
| foldOverCollection | 5.596 ± 0.139 | 5.548 ± 0.052 | -0.9% |  |
| userTypesSkipKernelWrapping | 50.273 ± 0.971 | 49.864 ± 0.373 | -0.8% |  |
| suspensionBaselineAltInstall | 69.355 ± 3.387 | 68.823 ± 1.386 | -0.8% |  |
| suspensionFusesContinuation | 35.648 ± 0.596 | 35.383 ± 0.160 | -0.7% |  |
| fusionPastBudgetPaysRescuesOnly | 48.307 ± 1.278 | 48.045 ± 0.492 | -0.5% |  |
| trailingMapsStayLinear | 276.441 ± 17.075 | 275.189 ± 6.681 | -0.5% |  |
| inlineLimitKeepsZeroAllocation | 1.250 ± 0.030 | 1.244 ± 0.021 | -0.4% |  |
| deferBindUnderIdleHandler | 16.800 ± 0.434 | 16.734 ± 1.057 | -0.4% |  |
| nestedPayloadsUnwrapInMaps | 6.225 ± 0.123 | 6.201 ± 0.080 | -0.4% |  |
| deferBindPerStep | 12.898 ± 0.726 | 12.856 ± 0.278 | -0.3% |  |
| partialSuspensionBaseline | 123.662 ± 2.577 | 123.283 ± 2.250 | -0.3% |  |
| effectfulIterationViaLoop | 210.984 ± 3.786 | 210.395 ± 1.775 | -0.3% |  |
| statefulAnswersPaySuccessorAltRef | 99.539 ± 1.989 | 99.418 ± 1.581 | -0.1% |  |
| dynamicChainOfMapsStaysLinear | 4.043 ± 0.065 | 4.040 ± 0.039 | -0.1% |  |
| handleLoopFusesContinuation | 40.414 ± 1.139 | 40.417 ± 1.460 | +0.0% |  |
| evalFixedOverhead | 0.002 ± 0.000 | 0.002 ± 0.000 | +0.2% |  |
| deepRecursionNoRescue | 1.832 ± 0.030 | 1.835 ± 0.046 | +0.2% |  |
| evalFixedOverheadBatch | 0.002 ± 0.000 | 0.002 ± 0.000 | +0.2% |  |
| dynamicChainOfBindsStaysLinear | 4.033 ± 0.054 | 4.045 ± 0.091 | +0.3% |  |
| contextRegionsPayEntryExit | 73.728 ± 3.583 | 73.970 ± 2.162 | +0.3% |  |
| handleLoopAnswersInPlace | 40.190 ± 1.005 | 40.366 ± 0.542 | +0.4% |  |
| emittingClausesPayRegionRebuild | 90.901 ± 10.458 | 91.460 ± 2.528 | +0.6% |  |
| deepRecursionOneRescue | 2.753 ± 0.034 | 2.774 ± 0.079 | +0.8% |  |
| bracketEnsuringOnly | 73.217 ± 1.422 | 74.305 ± 1.467 | +1.5% |  |
| effectfulIterationViaArrow | 110.810 ± 5.454 | 112.797 ± 2.995 | +1.8% |  |
| bracketAroundLoop | 72.555 ± 5.418 | 74.028 ± 8.766 | +2.0% |  |
| deepRecursionPaysRescuesOnly | 47.373 ± 1.532 | 48.453 ± 0.688 | +2.3% |  |
| bracketPerRound | 82.445 ± 2.392 | 84.352 ± 2.529 | +2.3% |  |
| pureIterationViaMethod | 73.780 ± 1.855 | 76.300 ± 1.944 | +3.4% |  |

rows: 49  drift band: ±5.0%  suspects: 0
