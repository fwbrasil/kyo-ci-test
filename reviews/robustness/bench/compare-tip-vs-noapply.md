| row | base | tip | delta | |
|---|---|---|---|---|
| pureIterationViaArrow | 101.493 ± 22.069 | 86.397 ± 1.658 | -14.9% | noise |
| fusionAfterSuspensionRunOnly | 0.545 ± 0.006 | 0.516 ± 0.022 | -5.2% | SUSPECT |
| trailingMapsStayLinear | 290.020 ± 10.983 | 275.114 ± 14.878 | -5.1% | noise |
| emittingClausesPayRegionRebuild | 96.686 ± 2.702 | 92.553 ± 8.932 | -4.3% |  |
| evalFixedOverheadBatch | 0.002 ± 0.000 | 0.002 ± 0.000 | -2.2% |  |
| sharedHandlerPaysDispatch | 170.293 ± 1.904 | 166.718 ± 1.595 | -2.1% |  |
| bracketEnsuringOnly | 75.528 ± 6.836 | 74.089 ± 3.299 | -1.9% |  |
| foreignCrossingsAnsweredInPlace | 822.087 ± 101.767 | 807.000 ± 208.296 | -1.8% |  |
| repeatedClausesPayReentry | 642.646 ± 14.292 | 634.105 ± 40.515 | -1.3% |  |
| pureIterationViaMethod | 75.223 ± 3.346 | 74.334 ± 0.992 | -1.2% |  |
| repeatedRegionsPayEntryRecovering | 59.326 ± 8.839 | 58.760 ± 3.528 | -1.0% |  |
| contextReadsUnderBindings | 40.408 ± 0.633 | 40.026 ± 1.370 | -0.9% |  |
| contextRegionsPayEntryExit | 74.534 ± 4.059 | 73.913 ± 1.794 | -0.8% |  |
| partialSuspensionBaseline | 125.173 ± 1.270 | 124.441 ± 3.874 | -0.6% |  |
| deferBindPerStep | 13.014 ± 0.445 | 12.947 ± 0.430 | -0.5% |  |
| continuationBodiesFuse | 16.522 ± 0.842 | 16.445 ± 0.723 | -0.5% |  |
| nestedPayloadsUnwrapInMaps | 6.341 ± 0.052 | 6.317 ± 0.060 | -0.4% |  |
| repeatedRegionsPayEntry | 58.939 ± 3.720 | 58.715 ± 4.312 | -0.4% |  |
| foreachOverCollection | 14.757 ± 0.163 | 14.709 ± 0.217 | -0.3% |  |
| foldOverCollection | 5.641 ± 0.040 | 5.626 ± 0.205 | -0.3% |  |
| bracketAroundLoop | 74.019 ± 5.634 | 73.857 ± 6.247 | -0.2% |  |
| suspensionBaselineAltInstall | 69.911 ± 0.341 | 69.813 ± 1.532 | -0.1% |  |
| evalFixedOverhead | 0.002 ± 0.000 | 0.002 ± 0.000 | -0.1% |  |
| fusionAfterSuspension | 130.433 ± 0.907 | 130.405 ± 3.556 | -0.0% |  |
| pureIterationViaLoop | 15.962 ± 0.188 | 15.967 ± 0.223 | +0.0% |  |
| suspensionFusesContinuation | 35.831 ± 0.340 | 35.856 ± 0.607 | +0.1% |  |
| userTypesSkipKernelWrapping | 50.369 ± 0.456 | 50.416 ± 0.587 | +0.1% |  |
| suspensionBaselineAltEnv | 69.838 ± 1.040 | 70.109 ± 2.624 | +0.4% |  |
| uncachedValuesPayBoxingOnly | 49.472 ± 0.483 | 49.680 ± 0.633 | +0.4% |  |
| deepRecursionPaysRescuesOnly | 48.035 ± 2.111 | 48.238 ± 1.746 | +0.4% |  |
| fusionAllocatesNothing | 0.209 ± 0.007 | 0.210 ± 0.003 | +0.4% |  |
| idleHandlerAddsNothing | 48.505 ± 0.323 | 48.783 ± 0.889 | +0.6% |  |
| deepRecursionNoRescue | 1.841 ± 0.016 | 1.853 ± 0.031 | +0.6% |  |
| inlineLimitKeepsZeroAllocation | 1.251 ± 0.004 | 1.260 ± 0.015 | +0.7% |  |
| suspensionBaseline | 99.734 ± 1.839 | 100.419 ± 3.491 | +0.7% |  |
| inlineLimitCostsTimeNotAllocation | 285.727 ± 5.125 | 287.843 ± 8.323 | +0.7% |  |
| deepRecursionOneRescue | 2.774 ± 0.012 | 2.795 ± 0.007 | +0.8% |  |
| statefulAnswersPaySuccessorAltRef | 99.675 ± 1.746 | 100.541 ± 3.236 | +0.9% |  |
| statefulAnswersPaySuccessor | 41.129 ± 0.202 | 41.534 ± 3.528 | +1.0% |  |
| effectfulIterationViaLoop | 218.595 ± 2.824 | 221.450 ± 11.673 | +1.3% |  |
| dynamicChainOfBindsStaysLinear | 4.063 ± 0.020 | 4.119 ± 0.169 | +1.4% |  |
| handleLoopFusesContinuation | 40.663 ± 0.331 | 41.270 ± 0.471 | +1.5% |  |
| foreignCrossingsPayRotation | 1057.096 ± 71.491 | 1077.761 ± 78.971 | +2.0% |  |
| dynamicChainOfMapsStaysLinear | 4.069 ± 0.062 | 4.151 ± 0.271 | +2.0% |  |
| fusionPastBudgetPaysRescuesOnly | 48.459 ± 0.392 | 49.641 ± 4.179 | +2.4% |  |
| handleLoopAnswersInPlace | 40.981 ± 1.114 | 42.217 ± 9.923 | +3.0% |  |
| entryFloorBatch | 0.000 ± 0.000 | 0.000 ± 0.000 | +3.6% |  |
| collectOverCollection | 13.994 ± 0.256 | 14.649 ± 0.616 | +4.7% |  |
| bracketPerRound | 84.724 ± 1.413 | 95.706 ± 1.570 | +13.0% | SUSPECT |
| deferBindUnderIdleHandler | 17.235 ± 0.919 | 19.573 ± 4.417 | +13.6% | noise |
| deferBindUnderTrailingMap | 30.240 ± 0.636 | 39.737 ± 1.192 | +31.4% | SUSPECT |
| effectfulIterationViaArrow | 114.515 ± 5.815 | 386.360 ± 14.547 | +237.4% | SUSPECT |

rows: 52  drift band: ±5.0%  suspects: 4
