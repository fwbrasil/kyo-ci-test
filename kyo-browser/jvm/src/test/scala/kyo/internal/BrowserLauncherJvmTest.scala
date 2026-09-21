package kyo.internal

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kyo.*

/** JVM-only scenarios for [[BrowserLauncher]].
  *
  * Lives in the JVM test tree because it drives `BrowserLauncher.createTempDir`'s read-only-parent abort path via the JVM-only
  * `java.nio.file.Files` / `java.nio.file.attribute.PosixFilePermissions` APIs, which have no Scala.js shim.
  */
class BrowserLauncherJvmTest extends BaseBrowserTest:

    // createTempDir failure path: point at a read-only parent directory; assert Abort shape.
    "createTempDir aborts with BrowserSetupFailedException when the temp parent is not writable" in {
        // POSIX permissions throw UnsupportedOperationException on Windows, and NTFS does not
        // block an owner from creating entries in a read-only directory anyway, so the
        // unwritable-parent scenario is expressible on POSIX hosts only.
        assume(!Platform.isWindows, "POSIX directory permissions")
        assume(java.lang.System.getProperty("user.name") != "root", "a non-root user, for whom a read-only parent refuses writes")
        val outerTmp = Paths.get(java.lang.System.getProperty("java.io.tmpdir"))
        val parent   = Files.createTempDirectory(outerTmp, s"kyo-browser-jvm-test-${UUID.randomUUID()}-")

        val readOnly = PosixFilePermissions.fromString("r-x------")
        val writable = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(parent, readOnly)

        Sync.ensure(
            Sync.defer {
                try Files.setPosixFilePermissions(parent, writable)
                catch case _: Throwable => ()
                try Files.deleteIfExists(parent)
                catch case _: Throwable => ()
            }
        ) {
            val kyoParent = Path(parent.toString)
            Abort.run[BrowserSetupException] {
                BrowserLauncher.createTempDir(kyoParent)
            }.map {
                case Result.Failure(ex: BrowserSetupFailedException) => assert(ex.getMessage.contains("temp dir"))
                case Result.Success(p)                               =>
                    fail(s"Expected BrowserSetupFailedException but createTempDir returned $p")
                case Result.Panic(ex) =>
                    fail(s"Expected Failure, got Panic: ${ex.getMessage}")
            }
        }
    }

    // The launch is stopped once the operating system shows its Chrome, which is during the port poll after the spawn.
    // The Chrome carries a unique flag Chrome ignores, so the count is of this launch's tree alone, and a process that
    // is never reaped ends this leaf as its timeout. A `pgrep` that cannot run fails the leaf rather than counting zero.
    "a launch stopped while its Chrome is up leaves no Chrome behind" in {
        assume(!Platform.isWindows, "POSIX process tree")
        val token                                                         = s"--kyo-launch-probe-${UUID.randomUUID().toString.take(8)}"
        def alive: Int < (Async & Abort[CommandException])                =
            Command("pgrep", "-f", token).textWithExitCode.map((out, _) => out.linesIterator.count(_.trim.nonEmpty))
        // A Chrome this leaf fails to reap would otherwise run for the rest of the suite.
        def kill: Unit < Async =
            Abort.run[CommandException](Command("pkill", "-9", "-f", token).textWithExitCode).unit
        Abort.run[BrowserSetupException](SharedChrome.chromeConfig).map { obtained =>
            val cfg = obtained match
                case Result.Success(base) => base.copy(extraArgs = Chunk(token))
                case other                => cancel(s"no Chrome to launch here: $other")
            Scope.run(Scope.ensure(kill).andThen {
                for
                    fiber <- Fiber.initUnscoped(Abort.run[BrowserSetupException](Scope.run(
                        BrowserLauncher.launch(cfg).andThen(Async.never)
                    )))
                    _ <- assertEventually(alive.map(_ > 0))
                    _ <- fiber.interrupt
                    _ <- fiber.getResult
                    _ <- assertEventually(alive.map(_ == 0))
                yield succeed
            })
        }
    }

    // Chrome's helpers (zygotes, GPU process, network service) outlive the main process by a few milliseconds and write
    // into the user-data-dir as they go down, so a removal that runs as soon as the main process is dead can find the
    // directory re-created behind it.
    "terminateTree leaves no descendant alive to write into the directory" in {
        assume(!Platform.isWindows, "POSIX process tree")
        val outerTmp                = Paths.get(java.lang.System.getProperty("java.io.tmpdir"))
        val dir                     = outerTmp.resolve(s"kyo-browser-jvm-test-${UUID.randomUUID()}")
        val script                  = s"mkdir -p '$dir'; (while true; do mkdir -p '$dir/x'; sleep 0.005; done) & wait"
        def removeDir: Unit < Async =
            Abort.run[FileSystemException](Path.run(Path(dir.toString).removeAll)).unit
        Scope.run {
            Scope.ensure(removeDir).andThen {
                for
                    proc <- Command("sh", "-c", script).spawnUnscoped
                    _    <- assertEventually(Sync.defer(Files.exists(dir.resolve("x"))))
                    _    <- BrowserLauncher.terminateTree(proc)
                    _    <- removeDir
                    _    <- Async.sleep(300.millis)
                yield assert(!Files.exists(dir), s"a descendant survived terminateTree and re-created $dir")
            }
        }
    }

end BrowserLauncherJvmTest
