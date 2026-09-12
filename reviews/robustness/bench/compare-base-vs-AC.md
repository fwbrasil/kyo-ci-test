| row | base | tip | delta | |
|---|---|---|---|---|
| pureIterationViaArrow | 99.162 ± 33.811 | 92.385 ± 53.693 | -6.8% | noise |
| foreignCrossingsAnsweredInPlace | 841.421 ± 215.169 | 788.851 ± 162.374 | -6.2% | noise |
| continuationBodiesFuse | 16.279 ± 2.016 | 15.312 ± 1.062 | -5.9% | noise |
| collectOverCollection | 14.179 ± 0.287 | 13.550 ± 0.331 | -4.4% |  |
| entryFloorBatch | 0.000 ± 0.000 | 0.000 ± 0.000 | -4.4% |  |
| foreignCrossingsPayRotation | 1062.447 ± 69.173 | 1018.613 ± 55.005 | -4.1% |  |
| deferBindUnderTrailingMap | 29.689 ± 2.183 | 28.892 ± 0.824 | -2.7% |  |
| effectfulIterationViaLoop | 216.135 ± 2.613 | 210.395 ± 1.775 | -2.7% |  |
| uncachedValuesPayBoxingOnly | 50.383 ± 9.884 | 49.114 ± 0.240 | -2.5% |  |
| trailingMapsStayLinear | 278.708 ± 17.489 | 275.189 ± 6.681 | -1.3% |  |
| fusionAllocatesNothing | 0.207 ± 0.003 | 0.205 ± 0.002 | -1.3% |  |
| evalFixedOverheadBatch | 0.002 ± 0.000 | 0.002 ± 0.000 | -1.2% |  |
| pureIterationViaLoop | 15.906 ± 0.606 | 15.775 ± 0.052 | -0.8% |  |
| idleHandlerAddsNothing | 48.337 ± 0.627 | 47.956 ± 0.590 | -0.8% |  |
| suspensionBaseline | 98.760 ± 0.607 | 98.020 ± 1.518 | -0.7% |  |
| statefulAnswersPaySuccessor | 40.835 ± 0.906 | 40.567 ± 0.375 | -0.7% |  |
| sharedHandlerPaysDispatch | 169.402 ± 1.650 | 168.347 ± 2.390 | -0.6% |  |
| bracketPerRound | 84.787 ± 3.607 | 84.352 ± 2.529 | -0.5% |  |
| foldOverCollection | 5.576 ± 0.041 | 5.548 ± 0.052 | -0.5% |  |
| suspensionFusesContinuation | 35.555 ± 0.962 | 35.383 ± 0.160 | -0.5% |  |
| handleLoopAnswersInPlace | 40.517 ± 0.823 | 40.366 ± 0.542 | -0.4% |  |
| inlineLimitCostsTimeNotAllocation | 282.665 ± 4.442 | 281.663 ± 2.771 | -0.4% |  |
| fusionAfterSuspensionRunOnly | 0.524 ± 0.008 | 0.522 ± 0.007 | -0.3% |  |
| nestedPayloadsUnwrapInMaps | 6.217 ± 0.084 | 6.201 ± 0.080 | -0.3% |  |
| fusionPastBudgetPaysRescuesOnly | 48.143 ± 0.955 | 48.045 ± 0.492 | -0.2% |  |
| deferBindUnderIdleHandler | 16.760 ± 0.535 | 16.734 ± 1.057 | -0.2% |  |
| userTypesSkipKernelWrapping | 49.933 ± 0.795 | 49.864 ± 0.373 | -0.1% |  |
| fusionAfterSuspension | 128.177 ± 5.858 | 128.013 ± 1.596 | -0.1% |  |
| dynamicChainOfMapsStaysLinear | 4.043 ± 0.025 | 4.040 ± 0.039 | -0.1% |  |
| effectfulIterationViaArrow | 112.884 ± 7.637 | 112.797 ± 2.995 | -0.1% |  |
| suspensionBaselineAltEnv | 69.006 ± 0.497 | 68.971 ± 1.350 | -0.0% |  |
| deepRecursionNoRescue | 1.835 ± 0.051 | 1.835 ± 0.046 | -0.0% |  |
| suspensionBaselineAltInstall | 68.751 ± 0.764 | 68.823 ± 1.386 | +0.1% |  |
| evalFixedOverhead | 0.002 ± 0.000 | 0.002 ± 0.000 | +0.1% |  |
| handleLoopFusesContinuation | 40.337 ± 0.728 | 40.417 ± 1.460 | +0.2% |  |
| dynamicChainOfBindsStaysLinear | 4.035 ± 0.037 | 4.045 ± 0.091 | +0.2% |  |
| partialSuspensionBaseline | 122.946 ± 4.998 | 123.283 ± 2.250 | +0.3% |  |
| inlineLimitKeepsZeroAllocation | 1.240 ± 0.010 | 1.244 ± 0.021 | +0.3% |  |
| foreachOverCollection | 14.617 ± 0.100 | 14.696 ± 0.135 | +0.5% |  |
| pureIterationViaMethod | 75.821 ± 1.109 | 76.300 ± 1.944 | +0.6% |  |
| statefulAnswersPaySuccessorAltRef | 98.736 ± 0.915 | 99.418 ± 1.581 | +0.7% |  |
| contextRegionsPayEntryExit | 73.350 ± 4.341 | 73.970 ± 2.162 | +0.8% |  |
| contextReadsUnderBindings | 39.204 ± 0.608 | 39.580 ± 0.880 | +1.0% |  |
| bracketAroundLoop | 72.944 ± 3.861 | 74.028 ± 8.766 | +1.5% |  |
| deepRecursionOneRescue | 2.723 ± 0.094 | 2.774 ± 0.079 | +1.9% |  |
| emittingClausesPayRegionRebuild | 89.102 ± 5.153 | 91.460 ± 2.528 | +2.6% |  |
| deferBindPerStep | 12.490 ± 0.949 | 12.856 ± 0.278 | +2.9% |  |
| deepRecursionPaysRescuesOnly | 47.012 ± 0.339 | 48.453 ± 0.688 | +3.1% |  |
| bracketEnsuringOnly | 66.337 ± 18.400 | 74.305 ± 1.467 | +12.0% | noise |

rows: 49  drift band: ±5.0%  suspects: 0
