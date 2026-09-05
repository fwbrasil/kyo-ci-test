# PENDING: two leaf-hardening edits, parked out of the tree so the drive can run

WHY THEY ARE HERE AND NOT IN A COMMIT: the session that made them is isolated to a different worktree and
git against `ci-stab-cycle4` is refused, so they could not be committed. Left in the tree they made the
preflight fail on "2 uncommitted change(s): CI would test a different tree", which blocked every dispatch and
froze the drive for six wakeups. They are parked here instead: fully specified, reproducible in a minute, and
costing nothing while parked. Re-apply as the first action once git works again.

## Rationale (do not re-derive, this was settled with a held-out advisor)

`SqlConnectionPool.connect:666-675` wraps ALL of `factory.open` in ONE `Async.timeoutWithError`, default
5 seconds. Two leaves perform TWO connect-plus-handshake rounds inside that single budget, where every
sibling leaf performs one: a plaintext attempt far enough to draw the server's refusal, then a full TLS
reconnect. On a loaded runner they are the first to cross the budget, against a server still cold from its
own per-leaf container start. Measured: 7.1s locally UNLOADED against a 5s budget; 39.6s on the failing CI
leg where sibling leaves took 25-32s at load 2.5 with conmon=20.

NOT a blanket timeout widening. Leaf 1 of the MySQL suite is a single-connect leaf and deliberately keeps the
default; an earlier sed caught it too and was reverted. Only the two leaves whose SHAPE differs get an
explicit budget. Neither weakens an assertion: both still prove the upgrade happened, via `Ssl_cipher` and
`pg_stat_ssl` respectively.

## Edit 1: kyo-sql-mysql/shared/src/test/scala/kyo/mysql/MysqlSqlConfigTlsModeIntegrationTest.scala

In leaf "sslmode=allow upgrades to TLS when server requires TLS" ONLY (not leaf 1), append
`&connectTimeout=30s` to the url, and put this comment directly above the `val url` line:

                    // An explicit establish budget, because this leaf is shaped differently from every other one here.
                    // The pool applies ONE budget to the whole of `factory.open`, and `allow` against a
                    // `--require-secure-transport=ON` server performs TWO connect-plus-handshake rounds inside it: a
                    // plaintext connect and auth far enough to draw error 3159, then a full TLS reconnect. Its siblings
                    // pay one. The 5-second default is sized for one, so on a loaded runner this leaf is the first to
                    // cross it, while the server is still cold from its own per-leaf container start.
                    // This does not weaken anything: the Ssl_cipher assertion below still proves the upgrade happened.

## Edit 2: kyo-sql-postgres/shared/src/test/scala/kyo/postgres/SqlConfigTlsModeIntegrationTest.scala

In leaf 11 "sslmode=allow upgrades to TLS when server requires TLS", same append of `&connectTimeout=30s`,
with this comment above the `val url` line:

            // An explicit establish budget, for the same reason as the MySQL twin of this leaf. The pool applies ONE
            // budget to the whole of `factory.open`, and `allow` against a server that refuses plaintext performs TWO
            // connect-plus-handshake rounds inside it: a plaintext attempt far enough to draw SQLSTATE 28000, then a
            // full TLS reconnect. Every sibling leaf pays one. The 5-second default is sized for one, so under runner
            // load this is the first leaf to cross it.

Postgres has NOT been observed failing this way. It is the same proven mechanism that has not yet drawn a
slow runner, which is why it is fixed alongside rather than left as a known twin.
