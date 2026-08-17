package kyo.http.client

import kyo.*

/** Idle-connection timeout for the process-lifetime default [[kyo.HttpClient]] (the client backing the ambient
  * `HttpClient.get*` / `post*` helpers). A pooled keep-alive connection is closed after this much inactivity by the pool's
  * background reaper.
  *
  * The default client is never closed, so its pool relies entirely on this timeout to release idle sockets. Configurable via
  * `-Dkyo.http.client.defaultIdleTimeout` (a Duration, e.g. `30seconds`), defaulting to 60 seconds. Only the default client
  * reads it: an explicit `HttpClient.init(..., idleConnectionTimeout)` sets its own.
  */
private[kyo] object defaultIdleTimeout extends StaticFlag[Duration](60.seconds)
