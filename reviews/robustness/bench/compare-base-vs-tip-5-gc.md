| row | base us/op | tip us/op | delta | base B/op | tip B/op | dB/op |
|---|---|---|---|---|---|---|
| contextReadsUnderBindings | 39.0 | 45.9 | +17.6% | 48312 | 48240 | -72 |
| contextRegionsPayEntryExit | 74.3 | 39.9 | -46.3% | 112129 | 88104 | -24024 |
| deferBindUnderTrailingMap | 31.6 | 40.2 | +27.0% | 112144 | 112144 | +0 |
| emittingClausesPayRegionRebuild | 99.8 | 159.8 | +60.2% | 232337 | 208337 | -24000 |
| suspensionBaseline | 102.8 | 103.1 | +0.3% | 480121 | 480121 | +0 |
| suspensionBaselineAltEnv | 73.4 | 102.5 | +39.7% | 480129 | 480105 | -24 |
| suspensionBaselineAltInstall | 72.7 | 101.5 | +39.7% | 480129 | 480105 | -24 |

`-f 1 -wi 3 -i 3 -prof gc`. `gc.alloc.rate.norm` is B/op, exact and near noise-free: every regressed row is flat or lower in allocation, so each regression is path length, not allocation. contextRegionsPayEntryExit drops 24 KB/op (the Context object gone per region entry), which is the win.
