| row | base | tip | delta | |
|---|---|---|---|---|
| contextRegionsPayEntryExit | 78.690 ± 12.090 | 37.518 ± 4.956 | -52.3% | SUSPECT |
| bracketPerRound | 85.024 ± 4.502 | 41.817 ± 1.527 | -50.8% | SUSPECT |
| foreignCrossingsPayRotation | 1051.802 ± 38.605 | 866.967 ± 81.372 | -17.6% | SUSPECT |
| repeatedClausesPayReentry | 648.637 ± 30.227 | 558.373 ± 116.712 | -13.9% | noise |
| deferBindUnderIdleHandler | 19.895 ± 17.712 | 17.664 ± 0.636 | -11.2% | noise |
| pureIterationViaArrow | 102.938 ± 37.296 | 94.409 ± 43.055 | -8.3% | noise |
| deepRecursionPaysRescuesOnly | 53.298 ± 23.355 | 49.299 ± 1.617 | -7.5% | noise |
| collectOverCollection | 14.631 ± 1.228 | 13.653 ± 0.193 | -6.7% | noise |
| entryFloorBatch | 0.000 ± 0.000 | 0.000 ± 0.000 | -6.1% | noise |
| handleLoopFusesContinuation | 44.872 ± 11.773 | 42.341 ± 1.767 | -5.6% | noise |
| statefulAnswersPaySuccessor | 44.835 ± 17.147 | 42.640 ± 1.729 | -4.9% |  |
| deferBindPerStep | 13.861 ± 1.619 | 13.213 ± 0.529 | -4.7% |  |
| fusionAfterSuspension | 138.651 ± 13.789 | 132.171 ± 7.214 | -4.7% |  |
| partialSuspensionBaseline | 132.204 ± 28.903 | 127.868 ± 10.748 | -3.3% |  |
| effectfulIterationViaLoop | 228.317 ± 3.852 | 220.844 ± 2.276 | -3.3% |  |
| foreignCrossingsAnsweredInPlace | 870.257 ± 38.990 | 846.557 ± 310.883 | -2.7% |  |
| suspensionFusesContinuation | 37.331 ± 3.986 | 36.334 ± 0.886 | -2.7% |  |
| dynamicChainOfMapsStaysLinear | 4.234 ± 0.102 | 4.134 ± 0.017 | -2.4% |  |
| deepRecursionNoRescue | 1.926 ± 0.133 | 1.888 ± 0.033 | -2.0% |  |
| deepRecursionOneRescue | 2.891 ± 0.259 | 2.848 ± 0.053 | -1.5% |  |
| dynamicChainOfBindsStaysLinear | 4.190 ± 0.103 | 4.138 ± 0.065 | -1.2% |  |
| foldOverCollection | 5.864 ± 0.180 | 5.798 ± 0.321 | -1.1% |  |
| uncachedValuesPayBoxingOnly | 51.115 ± 2.695 | 50.794 ± 1.556 | -0.6% |  |
| foreachOverCollection | 15.837 ± 0.398 | 15.763 ± 1.866 | -0.5% |  |
| fusionAfterSuspensionRunOnly | 0.559 ± 0.022 | 0.558 ± 0.021 | -0.2% |  |
| userTypesSkipKernelWrapping | 51.316 ± 0.852 | 51.225 ± 1.344 | -0.2% |  |
| inlineLimitKeepsZeroAllocation | 1.295 ± 0.052 | 1.294 ± 0.075 | -0.1% |  |
| pureIterationViaMethod | 76.455 ± 3.689 | 76.989 ± 24.446 | +0.7% |  |
| evalFixedOverheadBatch | 0.002 ± 0.000 | 0.002 ± 0.000 | +0.8% |  |
| fusionAllocatesNothing | 0.217 ± 0.016 | 0.219 ± 0.012 | +1.0% |  |
| evalFixedOverhead | 0.002 ± 0.000 | 0.002 ± 0.000 | +1.7% |  |
| repeatedRegionsPayEntry | 61.449 ± 3.428 | 62.476 ± 2.671 | +1.7% |  |
| repeatedRegionsPayEntryRecovering | 61.290 ± 5.861 | 62.335 ± 1.842 | +1.7% |  |
| suspensionBaseline | 103.305 ± 5.320 | 105.189 ± 10.584 | +1.8% |  |
| sharedHandlerPaysDispatch | 175.124 ± 14.028 | 178.617 ± 15.313 | +2.0% |  |
| bracketEnsuringOnly | 74.943 ± 2.072 | 76.484 ± 11.170 | +2.1% |  |
| nestedPayloadsUnwrapInMaps | 6.470 ± 0.288 | 6.610 ± 1.420 | +2.2% |  |
| trailingMapsStayLinear | 285.572 ± 6.761 | 293.216 ± 13.001 | +2.7% |  |
| effectfulIterationViaArrow | 116.555 ± 6.887 | 120.289 ± 19.905 | +3.2% |  |
| handleLoopAnswersInPlace | 42.623 ± 3.846 | 45.130 ± 8.818 | +5.9% | noise |
| inlineLimitCostsTimeNotAllocation | 303.470 ± 28.239 | 322.688 ± 141.452 | +6.3% | noise |
| continuationBodiesFuse | 16.984 ± 1.164 | 18.292 ± 8.328 | +7.7% | noise |
| fusionPastBudgetPaysRescuesOnly | 50.050 ± 1.315 | 54.092 ± 21.413 | +8.1% | noise |
| statefulAnswersPaySuccessorAltRef | 102.567 ± 6.455 | 110.918 ± 55.907 | +8.1% | noise |
| bracketAroundLoop | 73.644 ± 6.301 | 81.133 ± 19.365 | +10.2% | noise |
| contextReadsUnderBindings | 42.784 ± 12.156 | 49.153 ± 3.146 | +14.9% | noise |
| deferBindUnderTrailingMap | 33.449 ± 7.405 | 38.777 ± 2.457 | +15.9% | noise |
| pureIterationViaLoop | 16.395 ± 0.250 | 19.128 ± 11.423 | +16.7% | noise |
| idleHandlerAddsNothing | 51.319 ± 3.086 | 61.496 ± 40.434 | +19.8% | noise |
| suspensionBaselineAltInstall | 81.244 ± 12.739 | 104.631 ± 5.257 | +28.8% | SUSPECT |
| suspensionBaselineAltEnv | 71.811 ± 1.337 | 100.670 ± 6.903 | +40.2% | SUSPECT |
| emittingClausesPayRegionRebuild | 102.370 ± 2.193 | 151.955 ± 3.351 | +48.4% | SUSPECT |

rows: 52  drift band: ±5.0%  suspects: 6
