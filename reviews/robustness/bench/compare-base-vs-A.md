| row | base | tip | delta | |
|---|---|---|---|---|
| collectOverCollection | 14.121 ± 0.544 | 12.877 ± 0.406 | -8.8% | SUSPECT |
| nestedPayloadsUnwrapInMaps | 6.603 ± 2.093 | 6.206 ± 0.017 | -6.0% | noise |
| deferBindPerStep | 12.928 ± 0.749 | 12.325 ± 0.276 | -4.7% |  |
| continuationBodiesFuse | 16.478 ± 0.805 | 15.845 ± 2.139 | -3.8% |  |
| deferBindUnderIdleHandler | 16.685 ± 0.876 | 16.053 ± 0.510 | -3.8% |  |
| entryFloorBatch | 0.000 ± 0.000 | 0.000 ± 0.000 | -3.6% |  |
| deepRecursionPaysRescuesOnly | 48.652 ± 2.048 | 47.025 ± 1.376 | -3.3% |  |
| bracketPerRound | 83.887 ± 1.186 | 81.328 ± 2.674 | -3.1% |  |
| deepRecursionOneRescue | 2.785 ± 0.026 | 2.716 ± 0.068 | -2.5% |  |
| fusionAfterSuspensionRunOnly | 0.523 ± 0.012 | 0.511 ± 0.009 | -2.2% |  |
| dynamicChainOfMapsStaysLinear | 4.039 ± 0.043 | 3.955 ± 0.048 | -2.1% |  |
| deepRecursionNoRescue | 1.847 ± 0.030 | 1.809 ± 0.037 | -2.1% |  |
| suspensionBaseline | 100.675 ± 8.010 | 98.661 ± 2.624 | -2.0% |  |
| bracketEnsuringOnly | 73.554 ± 16.074 | 72.440 ± 2.441 | -1.5% |  |
| statefulAnswersPaySuccessor | 41.194 ± 0.980 | 40.575 ± 1.073 | -1.5% |  |
| sharedHandlerPaysDispatch | 171.256 ± 4.889 | 168.693 ± 0.961 | -1.5% |  |
| partialSuspensionBaseline | 125.446 ± 3.712 | 123.686 ± 1.755 | -1.4% |  |
| dynamicChainOfBindsStaysLinear | 4.031 ± 0.144 | 3.977 ± 0.094 | -1.3% |  |
| evalFixedOverhead | 0.002 ± 0.000 | 0.002 ± 0.000 | -1.0% |  |
| fusionAfterSuspension | 129.510 ± 8.548 | 128.340 ± 4.934 | -0.9% |  |
| statefulAnswersPaySuccessorAltRef | 99.453 ± 1.611 | 98.717 ± 2.444 | -0.7% |  |
| pureIterationViaLoop | 15.951 ± 0.783 | 15.839 ± 0.207 | -0.7% |  |
| deferBindUnderTrailingMap | 29.204 ± 0.851 | 29.015 ± 1.673 | -0.6% |  |
| handleLoopFusesContinuation | 40.413 ± 0.627 | 40.179 ± 0.679 | -0.6% |  |
| suspensionBaselineAltEnv | 69.481 ± 7.058 | 69.181 ± 0.546 | -0.4% |  |
| evalFixedOverheadBatch | 0.002 ± 0.000 | 0.002 ± 0.000 | -0.1% |  |
| suspensionFusesContinuation | 35.449 ± 0.287 | 35.438 ± 0.600 | -0.0% |  |
| foldOverCollection | 5.565 ± 0.112 | 5.565 ± 0.040 | +0.0% |  |
| handleLoopAnswersInPlace | 40.189 ± 0.766 | 40.192 ± 0.613 | +0.0% |  |
| contextReadsUnderBindings | 39.573 ± 1.944 | 39.591 ± 1.801 | +0.0% |  |
| suspensionBaselineAltInstall | 68.974 ± 0.608 | 69.033 ± 1.763 | +0.1% |  |
| fusionPastBudgetPaysRescuesOnly | 48.027 ± 0.279 | 48.083 ± 0.220 | +0.1% |  |
| effectfulIterationViaArrow | 112.961 ± 16.876 | 113.112 ± 15.839 | +0.1% |  |
| trailingMapsStayLinear | 276.164 ± 6.812 | 276.672 ± 5.583 | +0.2% |  |
| userTypesSkipKernelWrapping | 49.852 ± 0.296 | 49.986 ± 1.084 | +0.3% |  |
| contextRegionsPayEntryExit | 73.253 ± 2.171 | 73.506 ± 3.202 | +0.3% |  |
| foreignCrossingsPayRotation | 1052.932 ± 90.141 | 1056.988 ± 53.819 | +0.4% |  |
| uncachedValuesPayBoxingOnly | 49.040 ± 0.840 | 49.231 ± 0.589 | +0.4% |  |
| pureIterationViaMethod | 73.340 ± 5.472 | 73.626 ± 3.580 | +0.4% |  |
| idleHandlerAddsNothing | 47.953 ± 2.153 | 48.162 ± 0.929 | +0.4% |  |
| inlineLimitKeepsZeroAllocation | 1.238 ± 0.008 | 1.244 ± 0.060 | +0.5% |  |
| foreachOverCollection | 14.579 ± 0.165 | 14.661 ± 0.147 | +0.6% |  |
| bracketAroundLoop | 73.976 ± 5.370 | 74.605 ± 0.992 | +0.8% |  |
| fusionAllocatesNothing | 0.205 ± 0.002 | 0.207 ± 0.004 | +1.0% |  |
| effectfulIterationViaLoop | 210.393 ± 2.278 | 213.106 ± 5.620 | +1.3% |  |
| emittingClausesPayRegionRebuild | 90.454 ± 7.185 | 91.739 ± 3.961 | +1.4% |  |
| inlineLimitCostsTimeNotAllocation | 280.973 ± 1.847 | 285.263 ± 5.047 | +1.5% |  |
| foreignCrossingsAnsweredInPlace | 804.432 ± 78.141 | 819.257 ± 90.852 | +1.8% |  |
| pureIterationViaArrow | 95.156 ± 59.713 | 104.117 ± 63.577 | +9.4% | noise |

rows: 49  drift band: ±5.0%  suspects: 1
