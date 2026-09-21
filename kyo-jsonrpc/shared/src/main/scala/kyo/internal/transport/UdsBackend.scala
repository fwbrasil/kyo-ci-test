package kyo.internal.transport

import kyo.*
import kyo.net.NetException
import kyo.net.NetPlatform

/** Unix-domain-socket backend over kyo-net, shared across JVM, JS, Native, and Wasm.
  *
  * Binds a listener on `sockPath` through the platform transport and serves a single client: the first accepted connection completes `first` and
  * becomes the wire; any later accept is closed immediately. Scope cleanup closes the accepted connection, closes the listener, waits for the
  * listener's descriptor to be released, and removes the socket file (kyo-net does not unlink it). A single backend path that runs everywhere
  * kyo-net's transport runs.
  */
private[kyo] object UdsBackend:

    def connect(
        sockPath: Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]],
        releaseTimeout: Duration = JsonRpcTransport.DefaultReleaseTimeout
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        // Unsafe: listenUnix and Promise.Unsafe are unsafe-tier; the AllowUnsafe bridged here is captured by the accept-handler closure below.
        Sync.Unsafe.defer {
            val first                      = Promise.Unsafe.init[kyo.net.Connection, Abort[NetException | Closed]]()
            val wire: JsonRpcWireTransport = new UdsServerWireTransport(first)
            // The teardown finalizer is registered BEFORE the listen launches, reading the listen fiber from a cell the
            // launch fills in the same unsafe step: `listenUnix` binds a socket (and its file) synchronously on the JVM,
            // so a finalizer on the far side of the join leaves a window where an interrupt strands the bound listener.
            // Registered first, it closes the listener and removes the socket file on any exit; `Scope.acquireRelease` has the same window.
            Sync.Unsafe.defer(AtomicRef.Unsafe.init(Maybe.empty[kyo.Fiber[kyo.net.Listener, Abort[NetException]]])).map { listenCell =>
                Scope.ensure { _ =>
                    wire.close.andThen {
                        Sync.Unsafe.defer(listenCell.get()).map {
                            case Present(listenFiber) =>
                                listenFiber.interrupt.andThen(listenFiber.getResult).map {
                                    case Result.Success(listener) =>
                                        // The unlink below has to follow the descriptor's release, not just the close: a platform that
                                        // refuses to unlink a socket file whose descriptor is open fails otherwise. The wait is bounded
                                        // because this finalizer runs uninterruptibly, so a release that never arrives must not wedge
                                        // the scope; the unlink is attempted either way.
                                        Sync.Unsafe.defer { listener.close(); listener.released.safe }.map { released =>
                                            Abort.run[Timeout](Async.timeout(releaseTimeout)(released.get)).unit
                                        }
                                    case _ => ()
                                }
                            case Absent => ()
                        }
                    }.andThen {
                        // A socket file left behind is what the next bind on the same path trips over, so the failure is logged rather
                        // than swallowed.
                        Abort.run[FileSystemException](Path.run(sockPath.remove)).map(_.foldError(
                            _ => (),
                            error => Log.error(s"UdsBackend: could not remove the socket file at $sockPath", error.exception)
                        ))
                    }
                }.andThen {
                    Sync.Unsafe.defer {
                        val listenFiber =
                            NetPlatform.transport.listenUnix(sockPath.toString, backlog = 1) { conn =>
                                if !first.complete(Result.succeed(conn)) then conn.close()
                            }.safe
                        listenCell.set(Maybe(listenFiber))
                        listenFiber
                    }.map { listenFiber =>
                        listenFiber.get.map(_ => JsonRpcTransport.fromWire(wire, framer, codec))
                    }
                }
            }
        }
end UdsBackend

/** Wire over the single client a [[UdsBackend]] listener accepts. `send`/`incoming` park on the first-accept promise until a client arrives;
  * `close` unblocks a never-connected wire by failing the promise Closed, and closes the accepted connection if one exists.
  */
final private[kyo] class UdsServerWireTransport(
    first: Promise.Unsafe[kyo.net.Connection, Abort[NetException | Closed]]
) extends JsonRpcWireTransport:

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        val p: Promise[kyo.net.Connection, Abort[NetException | Closed]]         = first.safe
        val pending: kyo.net.Connection < (Async & Abort[NetException | Closed]) = p.get
        Abort.run[NetException | Closed](pending).map {
            case Result.Success(conn)            => conn.outbound.safe.put(Span.fromUnsafe(bytes.toArray))
            case Result.Failure(closed: Closed)  => Abort.fail(closed)
            case Result.Failure(e: NetException) =>
                Abort.panic(e) // a transport failure reaching the first-accept promise surfaces typed, never silently
            case Result.Panic(e) => Abort.panic(e)
        }
    end send

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream:
            val p: Promise[kyo.net.Connection, Abort[NetException | Closed]]         = first.safe
            val pending: kyo.net.Connection < (Async & Abort[NetException | Closed]) = p.get
            Abort.run[NetException | Closed](pending).map {
                case Result.Success(conn)            => ConnectionWireTransport(conn).incoming.emit
                case Result.Failure(_: Closed)       => () // closed before any client connected: empty stream, orderly end
                case Result.Failure(e: NetException) =>
                    Abort.panic(e) // a transport failure reaching the first-accept promise surfaces typed, never silently
                case Result.Panic(e)                 => Abort.panic(e)
            }

    def close(using Frame): Unit < Async =
        // Unsafe: promise completion and Connection.close are unsafe-tier, both idempotent.
        Sync.Unsafe.defer {
            val closedEarly =
                first.complete(Result.fail(Closed("UdsBackend", summon[Frame], "closed before a client connected")))
            if !closedEarly then
                first.poll() match
                    case Present(Result.Success(conn)) => conn.eval.close()
                    case _                             => ()
            end if
        }
end UdsServerWireTransport
