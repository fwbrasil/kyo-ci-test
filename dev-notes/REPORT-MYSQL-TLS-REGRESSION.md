# Cycle 4 regression: MysqlSqlConfigTlsModeIntegrationTest, sslmode=allow upgrade

## The failure

Fork full matrix `33408649887` on `ci-stab-cycle4` @ `616ebedf28`. NINE legs green, ONE red:

    JOB: build (linux-x64) / build (JVM)
      --- MysqlSqlConfigTlsModeIntegrationTest: 7 passed, 1 failed  (2m 36s)
      [FAIL] sslmode=allow upgrades to TLS when server requires TLS
        kyo.SqlConnectionEstablishTimeoutException

## It is ours, and that direction is established rather than assumed

On GREEN MAIN `6661a333ef` (run `33391183175`, linux-x64 JVM) the same suite reads
`MysqlSqlConfigTlsModeIntegrationTest: 8 passed, 0 failed` and the leaf PASSES.

Cycle 4 is main plus exactly four commits, so the regression is in one of them:

    fe8dddf687  [kyo-net] io_uring submitConnect guard: reject an arm whose fd close was already claimed
    64f414c5a2  [ci]      pin a native container run to the host's own platform      (build.sh; CI never runs it)
    c6253f0865  [kyo-net] fail the TLS engine build when a configured setting did not apply
    616ebedf28  [kyo-net] test-only coverage for the above

`64f414c5a2` and `616ebedf28` cannot affect a CI leg (a local-only script, and a test file in a suite that
did not fail). So it is `fe8dddf687` or `c6253f0865`.

## What the leaf does

MySQL container with `--require-secure-transport=ON`. `TlsMode.Allow` tries plaintext, the server rejects
with error 3159, and `MysqlSqlConnection.scala:363-380` retries via
`MysqlConnection.connectWithMode(..., TlsMode.Require, ...)`. So the leaf is a CONNECT, FAIL, CLOSE,
RECONNECT-OVER-TLS sequence. Both candidate commits sit on that path: one changes connect arming, the other
changes TLS engine construction.

## Two hypotheses, and the evidence currently CONTRADICTS the one I reached for first

H1, the io_uring connect guard (`fe8dddf687`). Attractive because the symptom is a CONNECT failure and the
leaf is the only close-then-reconnect case in the suite. `submitConnect` now rejects when
`handle.fdCloseIsClaimed`.

    EVIDENCE AGAINST H1, and it is strong: the guard COMPLETES the promise with a `Closed` failure. A
    rejection therefore fails FAST. The observed symptom is `SqlConnectionEstablishTimeoutException`, a
    promise that was never completed at all. For H1 to hold, something above would have to swallow the
    fast failure and retry until the establish budget expired, which is an extra claim with no evidence yet.

H2, the TLS return-code checks (`c6253f0865`). `applyConfig` now throws `NetTlsConfigException` when a setter
did not apply.

    EVIDENCE AGAINST H2: `TlsMode.Require` on MySQL "mandates encryption without validating the chain", so
    `trustAll` is set and the `ctxLoadSystemCa` check is skipped by its own `!config.trustAll` guard. No
    `caCertPath` and no client cert are configured on this path, so `ctxLoadCa` and `ctxSetCert` are not
    reached either. Only `ctxSetMinMaxVersion` runs unconditionally, and if THAT returned non-zero for the
    default config every TLS engine build would fail, which it demonstrably does not: the Postgres twin
    `SqlConfigTlsModeIntegrationTest` passed 16/0 in a container with BoringSSL staged, and the whole kyo-net
    TLS surface passed 47 suites / 217 leaves.

So both hypotheses have a real objection, which is why this needs direction rather than another guess.

## What is already known and should not be re-derived

- The leaf is retry-prone even on green main: it appears TWICE there with different durations (9.5s, 2.6s),
  so a retry fires on a passing run too.
- The suite took 2m36s on the failing run against a 10-minute leaf budget, so this is not the budget expiring.
- The Postgres equivalent of the same sslmode matrix passes with the TLS change in place, container-verified.
- The io_uring guard is red-then-green proven, and validated against a REAL ring in a container
  (129 suites, 280 passed, 0 failed), but nothing in that validation exercised close-then-reconnect.

## Reproduction status

A container run of the failing suite against a real MySQL and a real io_uring ring is IN FLIGHT
(`--env podman`, `KYO_POD_SOCKET`, `STAGE_BORINGSSL=1`). The podman machine had stopped and was restarted to
get it going. No local result yet, so nothing below the CI observation is confirmed.

## The question

Given two hypotheses each contradicted by a specific piece of evidence, what is the cheapest experiment that
DISCRIMINATES between them rather than accumulating more one-sided support? And is there a third mechanism
this framing has missed, given the timeout-versus-fast-fail tension?

## RESOLVED: it is NOT a cycle-4 regression. Both my hypotheses are dead.

LOCAL REPRODUCTION ON CYCLE 4, container, real MySQL, real io_uring ring:

    [PASS] sslmode=allow upgrades to TLS when server requires TLS  (7.1s)
    --- MysqlSqlConfigTlsModeIntegrationTest: 8 passed, 0 failed  (35.6s)

The cycle-4 code passes. Neither hypothesis survived, and each was killed by evidence rather than argument:

  H1, the io_uring connect guard: the ONLY "handle closed" string in the failing CI log is a TEST NAME in a
  PASSING WritePump leaf ("a writable-wait failure tears down the pump (handle closed while awaiting
  writable)"). That is the same grep trap already recorded against `fatal error` and `[FAIL]`: matching a
  word that appears in test names. The guard never fired.

  H2, the TLS return-code checks: all four `NetTlsConfigException` occurrences are in PASSING kyo-net leaves
  that deliberately exercise it (`connect(tls) propagates a buildEngine NetTlsConfigException as-is`,
  `createEngine with an empty hostname...`, `a malformed configured PEM path...`). None is on the MySQL path.

## THE ACTUAL ROOT CAUSE: one connect budget spanning two connections

`SqlConnectionPool.connect` (`:666-675`) wraps the WHOLE of `factory.open(...)` in a single
`Async.timeoutWithError(budget, ...)`, budget = 5 seconds by default. For MySQL `sslmode=allow`,
`factory.open` internally performs TWO connections (`MysqlSqlConnection.scala:363-380`): a plaintext connect,
which the `--require-secure-transport=ON` server rejects with error 3159, and then a FULL TLS reconnect via
`connectWithMode(..., TlsMode.Require, ...)`. Both run inside the one budget.

The exception's own text states the intended contract: "The budget covers the TCP connect and the
authentication handshake together". Here it is covering two connects and two handshakes, so this leaf is
structurally the most timing-fragile in the suite.

CORROBORATION, all of it measured:
  - locally, unloaded: the leaf takes 7.1s against a 5s per-connection budget, so it passes with almost no
    margin even at rest;
  - on the failing CI leg: neighbouring leaves take 25.6s, 30.4s, 32.4s; this one 39.6s; ci-mon reports
    load 2.15-2.72 with conmon=20-22;
  - ON GREEN MAIN the leaf appears TWICE with different durations (9.5s, 2.6s), so a retry ALREADY fires on
    a passing run. The fragility predates cycle 4.

## WHY WIDENING THE TIMEOUT IS THE WRONG FIX

It is the last-resort move and it does not address the asymmetry: every other leaf pays one connect against
this budget and this one pays two. A retry that opens a NEW connection should get a FRESH budget, because
per-connection is what the budget's own contract says it measures. That is a product change in the
pool/factory boundary, not a test tweak, which is why it wants a second opinion before being written.
