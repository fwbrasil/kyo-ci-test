#!/usr/bin/env bash
# Scratch driver for the Windows JS investigation. Not for merge.
#   probe-win.sh sqlite <iterations>   kyo-sql-sqliteJS/test, rebuilding the SQLite library before each pass
#   probe-win.sh browser <iterations>  the browser suites, with Chrome's net log captured per launch
set -uo pipefail
mode=$1
iterations=${2:-4}
out="${RUNNER_TEMP:-/tmp}/probe"
mkdir -p "$out/netlog"
export KYO_PROBE_NETLOG_DIR="$(cd "$out/netlog" && pwd -W 2>/dev/null || pwd)"

# One line per second: epoch ms, then TCP connection counts by state.
(
    while true; do
        printf '%s %s\n' "$(date +%s%3N)" "$(netstat -an -p TCP | awk 'NR>4 {c[$4]++} END {for (s in c) printf "%s=%d ", s, c[s]}')"
        sleep 1
    done
) > "$out/netstat.log" 2>&1 &
sampler=$!

status=0
for i in $(seq 1 "$iterations"); do
    echo "=== probe pass $i/$iterations ($mode) at $(date +%s%3N)"
    case "$mode" in
        sqlite)
            if [ "$i" -gt 1 ]; then
                find kyo-sql-sqlite kyo-sql-sqlite-driver -path '*js/target*' \( -name '*.dll' -o -name '*.so' -o -name '*.dylib' \) -print -delete
            fi
            sbt 'kyo-sql-sqliteJS/test' 2>&1 | tee "$out/pass-$i.log"
            ;;
        sqlchain)
            find kyo-sql-sqlite kyo-sql-sqlite-driver -path '*js/target*' \( -name '*.dll' -o -name '*.so' -o -name '*.dylib' \) -print -delete
            sbt 'kyo-sql-sqlite-driverJS/test' 'kyo-sql-sqliteJS/test' 2>&1 | tee "$out/pass-$i.log"
            ;;
        uijvm)
            sbt 'kyo-uiJVM/test' 2>&1 | tee "$out/pass-$i.log"
            ;;
        browserjvm)
            sbt 'kyo-browserJVM/test' 2>&1 | tee "$out/pass-$i.log"
            ;;
        browser)
            sbt 'kyo-browserJS/test' 2>&1 | tee "$out/pass-$i.log"
            ;;
    esac
    [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
    echo "=== probe pass $i summary"
    grep -a -E '\[probe|\*\*\* FAILED|timed out after|connect failed|tcp_socket_win' "$out/pass-$i.log" | head -300
done

kill "$sampler" 2>/dev/null
echo "=== netstat samples (first and last 20)"
head -20 "$out/netstat.log"
tail -20 "$out/netstat.log"
exit $status
