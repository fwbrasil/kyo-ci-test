#!/bin/bash
# Runs every module's test suite one at a time on this worktree and records one row per module.
#
#   modules.txt   cross-platform modules, run as <m>JVM/test
#   noncross.txt  single-platform modules, run as <m>/test
#
# results.tsv columns: module, sbt exit code, tests passed, tests failed, seconds. The exit code is the
# signal; the counts are read from the ScalaTest summary ("Tests: succeeded N, failed M") or, for
# kyo-test modules, summed over their "Results: N passed, M failed" lines. kyo-test's own suites print
# intentional "*** FAILED ***" lines, so the raw text is never grepped for failure.
set -u
cd /Users/fwbrasil/workspace/kyo/.claude/worktrees/robustness || exit 1
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"
OUT=reviews/robustness/sweep
RESULTS=$OUT/results.tsv
: > "$RESULTS"

run_one() {
    local project=$1 name=$2
    local log="$OUT/logs/$name.log"
    local start code dur passed failed
    start=$(date +%s)
    sbt --batch "$project/test" > "$log" 2>&1
    code=$?
    dur=$(( $(date +%s) - start ))
    local clean
    clean=$(sed 's/\x1b\[[0-9;]*m//g' "$log")
    if grep -qE 'Tests: succeeded [0-9]+' <<< "$clean"; then
        passed=$(grep -oE 'Tests: succeeded [0-9]+' <<< "$clean" | awk '{s+=$3} END {print s+0}')
        failed=$(grep -oE 'Tests: succeeded [0-9]+, failed [0-9]+' <<< "$clean" | awk '{s+=$5} END {print s+0}')
    else
        passed=$(grep -oE '^Results: [0-9]+ passed' <<< "$clean" | awk '{s+=$2} END {print s+0}')
        failed=$(grep -oE '^Results: [0-9]+ passed, [0-9]+ failed' <<< "$clean" | awk '{s+=$4} END {print s+0}')
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$code" "$passed" "$failed" "$dur" >> "$RESULTS"
    echo "[$(date +%H:%M:%S)] $name exit=$code passed=$passed failed=$failed ${dur}s"
}

while read -r m; do
    [ -z "$m" ] && continue
    run_one "${m}JVM" "$m"
done < "$OUT/modules.txt"

while read -r n; do
    [ -z "$n" ] && continue
    run_one "$n" "$n"
done < "$OUT/noncross.txt"

echo "SWEEP DONE $(date)"
