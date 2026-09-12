| row | base | tip | delta | |
|---|---|---|---|---|
| pureIterationViaArrow | 102.609 ± 48.845 | 94.321 ± 36.636 | -8.1% | noise |
| collectOverCollection | 14.585 ± 1.006 | 13.537 ± 0.217 | -7.2% | noise |
| foreignCrossingsPayRotation | 1096.754 ± 428.598 | 1029.095 ± 78.406 | -6.2% | noise |
| nestedPayloadsUnwrapInMaps | 6.814 ± 3.042 | 6.419 ± 0.406 | -5.8% | noise |
| deferBindUnderTrailingMap | 31.230 ± 3.791 | 29.998 ± 1.635 | -3.9% |  |
| deepRecursionOneRescue | 2.869 ± 0.081 | 2.773 ± 0.026 | -3.4% |  |
| fusionAfterSuspensionRunOnly | 0.545 ± 0.007 | 0.532 ± 0.013 | -2.4% |  |
| evalFixedOverhead | 0.002 ± 0.000 | 0.002 ± 0.000 | -2.3% |  |
| fusionAfterSuspension | 130.222 ± 15.714 | 127.682 ± 1.418 | -2.0% |  |
| entryFloorBatch | 0.000 ± 0.000 | 0.000 ± 0.000 | -1.7% |  |
| handleLoopFusesContinuation | 41.764 ± 2.444 | 41.105 ± 1.715 | -1.6% |  |
| effectfulIterationViaLoop | 220.743 ± 15.893 | 218.072 ± 4.423 | -1.2% |  |
| emittingClausesPayRegionRebuild | 97.811 ± 2.718 | 96.780 ± 4.633 | -1.1% |  |
| handleLoopAnswersInPlace | 41.126 ± 0.567 | 40.711 ± 1.968 | -1.0% |  |
| foreignCrossingsAnsweredInPlace | 807.792 ± 178.903 | 799.852 ± 276.829 | -1.0% |  |
| fusionAllocatesNothing | 0.210 ± 0.005 | 0.209 ± 0.001 | -0.9% |  |
| statefulAnswersPaySuccessorAltRef | 101.340 ± 1.515 | 100.499 ± 5.386 | -0.8% |  |
| deferBindPerStep | 12.993 ± 0.425 | 12.885 ± 0.487 | -0.8% |  |
| contextReadsUnderBindings | 40.391 ± 1.317 | 40.066 ± 0.134 | -0.8% |  |
| deepRecursionNoRescue | 1.853 ± 0.015 | 1.840 ± 0.037 | -0.7% |  |
| dynamicChainOfBindsStaysLinear | 4.083 ± 0.067 | 4.057 ± 0.040 | -0.6% |  |
| sharedHandlerPaysDispatch | 172.417 ± 22.387 | 171.541 ± 8.275 | -0.5% |  |
| foldOverCollection | 5.699 ± 0.057 | 5.676 ± 0.214 | -0.4% |  |
| deepRecursionPaysRescuesOnly | 48.583 ± 0.683 | 48.427 ± 4.409 | -0.3% |  |
| evalFixedOverheadBatch | 0.002 ± 0.000 | 0.002 ± 0.000 | -0.3% |  |
| userTypesSkipKernelWrapping | 50.829 ± 0.653 | 50.711 ± 0.677 | -0.2% |  |
| bracketEnsuringOnly | 75.337 ± 8.456 | 75.194 ± 3.598 | -0.2% |  |
| deferBindUnderIdleHandler | 16.953 ± 1.084 | 17.033 ± 0.888 | +0.5% |  |
| statefulAnswersPaySuccessor | 41.178 ± 0.209 | 41.412 ± 1.947 | +0.6% |  |
| suspensionBaselineAltInstall | 71.012 ± 3.549 | 71.416 ± 6.174 | +0.6% |  |
| pureIterationViaLoop | 15.945 ± 0.226 | 16.045 ± 0.123 | +0.6% |  |
| effectfulIterationViaArrow | 114.280 ± 3.956 | 115.044 ± 7.155 | +0.7% |  |
| fusionPastBudgetPaysRescuesOnly | 48.931 ± 0.205 | 49.269 ± 3.008 | +0.7% |  |
| uncachedValuesPayBoxingOnly | 50.015 ± 5.569 | 50.515 ± 1.524 | +1.0% |  |
| idleHandlerAddsNothing | 48.911 ± 0.378 | 49.415 ± 4.700 | +1.0% |  |
| inlineLimitCostsTimeNotAllocation | 287.600 ± 7.032 | 290.893 ± 6.802 | +1.1% |  |
| repeatedRegionsPayEntryRecovering | 57.998 ± 7.940 | 58.752 ± 2.025 | +1.3% |  |
| contextRegionsPayEntryExit | 74.580 ± 1.045 | 75.584 ± 9.669 | +1.3% |  |
| foreachOverCollection | 14.952 ± 0.349 | 15.158 ± 1.612 | +1.4% |  |
| bracketPerRound | 83.309 ± 1.071 | 84.505 ± 1.715 | +1.4% |  |
| dynamicChainOfMapsStaysLinear | 4.077 ± 0.059 | 4.147 ± 0.585 | +1.7% |  |
| inlineLimitKeepsZeroAllocation | 1.261 ± 0.020 | 1.285 ± 0.216 | +1.9% |  |
| pureIterationViaMethod | 73.808 ± 6.197 | 75.583 ± 9.585 | +2.4% |  |
| partialSuspensionBaseline | 124.403 ± 1.603 | 127.835 ± 12.487 | +2.8% |  |
| suspensionBaseline | 101.379 ± 2.812 | 104.481 ± 29.519 | +3.1% |  |
| suspensionFusesContinuation | 35.830 ± 0.287 | 36.956 ± 3.072 | +3.1% |  |
| suspensionBaselineAltEnv | 70.971 ± 1.528 | 74.687 ± 14.929 | +5.2% | noise |
| trailingMapsStayLinear | 275.749 ± 10.350 | 295.230 ± 27.900 | +7.1% | noise |
| repeatedRegionsPayEntry | 55.865 ± 4.733 | 65.200 ± 38.498 | +16.7% | noise |
| bracketAroundLoop | 63.778 ± 23.858 | 74.711 ± 8.500 | +17.1% | noise |
| continuationBodiesFuse | 16.538 ± 1.180 | 19.744 ± 3.280 | +19.4% | noise |
| repeatedClausesPayReentry | 100.790 ± 7.421 | 694.550 ± 164.216 | +589.1% | SUSPECT |

rows: 52  drift band: ±5.0%  suspects: 1
