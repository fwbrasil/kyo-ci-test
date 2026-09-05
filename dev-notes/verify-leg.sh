#!/usr/bin/env bash
# Verify that a CI leg actually RAN tests, rather than trusting its green conclusion.
#
# A green conclusion is not proof a test ran: kyo-browser / kyo-ui self-cancel on linux-arm64
# (no chrome-headless-shell for Aarch64) while still reporting success, and
# "[info] kyo-test: 0 tests, 0 passed" prints on passing runs too. This reads the per-suite
# "Results: N passed, M failed" totals instead.
#
# kyo-test's own self-test fixtures (PTFailSuite and friends) fail ON PURPOSE to assert that the
# framework records failures, so a raw failed-count is misleading. Those are reported separately.
#
# Usage: verify-leg.sh <run-id> <job-name-substring> [-R owner/repo] [--baseline N]
#   e.g. verify-leg.sh 33245417241 'linux-x64) / build (JVM'
#        verify-leg.sh 33283477063 'linux-arm64) / build (JVM' -R getkyo/kyo --baseline 27627
#
# --baseline is the passed-count of a previously VERIFIED run of the same leg. It answers the question
# a bare pass-count cannot: a leg that silently stops exercising a module still reports a large count,
# and only a comparison against a known-good run shows the drop.

set -uo pipefail

USAGE="usage: verify-leg.sh <run-id> <job-name-substring> [-R owner/repo] [--baseline N]"
RUN="${1:?$USAGE}"
NAME="${2:?$USAGE}"
shift 2

# Flags are parsed rather than read positionally: the previous form took the repo from $4 unconditionally,
# so any third argument silently shifted the repo onto a flag name and the job lookup failed with a
# confusing "no job matching" instead of a usage error.
REPO="fwbrasil/kyo-ci-test"
expected=""
while [ $# -gt 0 ]; do
    case "$1" in
        -R|--repo)    REPO="${2:?$USAGE}"; shift 2 ;;
        --baseline)   expected="${2:?$USAGE}"; shift 2 ;;
        *)            echo "unknown argument: $1"; echo "$USAGE"; exit 2 ;;
    esac
done

# Job names contain spaces and parentheses, so select on the name inside jq rather than
# word-splitting the shell.
job="$(gh run view "$RUN" -R "$REPO" --json jobs \
    -q ".jobs[] | select(.name | contains(\"$NAME\")) | \"\(.databaseId)\t\(.conclusion // .status)\t\(.name)\"")"

[ -n "$job" ] || { echo "no job matching '$NAME' in run $RUN"; exit 2; }
[ "$(printf '%s\n' "$job" | wc -l)" -eq 1 ] || { echo "ambiguous: '$NAME' matches"; printf '%s\n' "$job"; exit 2; }

id="$(printf '%s' "$job" | cut -f1)"
concl="$(printf '%s' "$job" | cut -f2)"
jobname="$(printf '%s' "$job" | cut -f3)"

# An unfinished job must never reach the baseline comparison. `gh` reports a running job's conclusion as the EMPTY
# STRING rather than null, so jq's `.conclusion // .status` keeps the "" (only null and false take the right side)
# and every downstream count is legitimately zero. Without this guard that renders as
# "SUSPECT (passed 0 < baseline N: coverage dropped by N)", which reads exactly like a real coverage regression on a
# leg that has simply not run yet. Exit 3 is the same "no verdict available" code the empty-log branch below uses.
case "$concl" in
    ""|queued|in_progress|waiting|pending|requested)
        echo "$jobname: PENDING (status='${concl:-in_progress}'); no verdict, the job has not finished"
        exit 3
        ;;
esac

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
# Strip both the ANSI colouring and the leading GitHub timestamp, so anchored patterns
# ("^--- <Suite>:") match the runner's own line starts rather than the log framing.
gh api "repos/$REPO/actions/jobs/$id/logs" 2>/dev/null |
    sed -e 's/\x1b\[[0-9;]*m//g' -e 's/^[0-9-]\{10\}T[0-9:.]\{8,\}Z //' > "$tmp"
[ -s "$tmp" ] || { echo "$jobname: no log (job may still be running)"; exit 3; }

read -r suites passed failed < <(
    grep -oE "Results: [0-9]+ passed, [0-9]+ failed" "$tmp" |
        awk '{n++; p+=$2; f+=$4} END {print n+0, p+0, f+0}'
)

# This script deliberately does NOT adjudicate failures. kyo-test runs its own framework self-tests,
# whose fixture suites fail on purpose so the harness can assert that failures and timeouts get
# recorded; those nested runs emit real "*** FAILED ***" and "Results: N passed, M failed" lines
# that never reach the runner's failure path. Separating them from genuine failures by suite name,
# by module, or by surrounding log shape was tried three ways and each was wrong on some platform,
# so the failing count below is reported raw and `ci-logs.sh --failures` remains the failure signal.
#
# What this script IS for is the question --failures cannot answer: did the leg run any tests at
# all? A green conclusion does not prove that (kyo-browser and kyo-ui self-cancel on linux-arm64,
# which has no chrome-headless-shell for Aarch64, and still report success).

# Cancellations are counted separately because they are the failure mode a green conclusion hides best:
# a leaf that self-cancels reports neither a pass nor a fail, so a module whose whole suite cancels leaves
# `passed` untouched and looks identical to one that simply has fewer tests. The known case is kyo-browser
# and kyo-ui on linux-arm64, where chrome-headless-shell is not published for Aarch64 and every leaf
# cancels while the job still reports success. Suites carry the count on the same Results line, so the
# total is read from there rather than inferred.
cancelled="$(
    grep -oE "Results: [0-9]+ passed, [0-9]+ failed, [0-9]+ cancelled" "$tmp" |
        awk '{c+=$6} END {print c+0}'
)"

echo "job:        $jobname"
echo "conclusion: $concl"
echo "suites:     $suites   passed: $passed   failed: $failed   cancelled: $cancelled"

echo "--- modules reporting zero tests ---"
grep -oE "\[info\] [a-zA-Z0-9-]+: 0 tests, 0 passed" "$tmp" | sort -u || echo "(none)"

echo "--- suites with cancelled leaves ---"
# The suite name sits on the preceding "--- <Suite>:" line, so print that line's suite together with the
# cancelled count rather than the bare Results line, which carries no attribution of its own.
grep -E "^--- .*: [0-9]+ passed, [0-9]+ failed, [0-9]+ cancelled" "$tmp" |
    sed -e 's/^--- //' -e 's/  *(total:.*//' | sort -u | head -40 || true
[ "$cancelled" -eq 0 ] && echo "(none)"

echo "--- failure adjudication ---"
echo "  not done here by design; run: scripts/ci-logs.sh -R $REPO job $id --failures"
echo "  ($failed failing suite-results counted above, kyo-test's intentional self-test fixtures included)"

if [ -n "$expected" ]; then
    if [ "$passed" -lt "$expected" ]; then
        echo "VERDICT: SUSPECT (passed $passed < baseline $expected: coverage dropped by $((expected - passed)))"
        exit 1
    fi
    echo "baseline:   $expected (this run is $((passed - expected)) higher)"
fi

if [ "$passed" -eq 0 ]; then
    echo "VERDICT: SUSPECT (zero tests passed; the leg did not run tests)"
    exit 1
fi
echo "VERDICT: ran $passed tests across $suites suites, $cancelled cancelled"
