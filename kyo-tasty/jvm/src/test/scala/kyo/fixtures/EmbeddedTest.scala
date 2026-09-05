package kyo.fixtures

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path as JPath
import java.nio.file.Paths
import kyo.*

/** Guards the embedded fixture bytes against drifting away from the sources they came from.
  *
  * JS and Native cannot read the fixture module from disk, so they decode a checked-in copy of its compiled output. That copy is produced
  * by hand, which means a change to the fixture sources, or a move of the module itself, leaves it describing a tree that no longer
  * exists while every other test keeps passing: the fixtures still parse, they just describe somewhere else. Each compiled fixture records
  * the source file it came from, so requiring those paths to resolve catches both cases at the point the copy goes stale.
  */
class EmbeddedTest extends kyo.test.Test[Any]:

    private val scalaPath = """[\w./$-]+\.scala""".r

    /** The build's working directory is not guaranteed to be the repository root, so the recorded paths are resolved against the first
      * ancestor that actually contains the module they name.
      */
    private def repoRoot: Maybe[JPath] =
        @annotation.tailrec
        def walk(dir: JPath): Maybe[JPath] =
            if dir == null then Absent
            else if Files.isDirectory(dir.resolve("kyo-tasty")) then Present(dir)
            else walk(dir.getParent)
        walk(Paths.get("").toAbsolutePath)
    end repoRoot

    "every source path recorded in an embedded fixture resolves on disk" in {
        val accessors = Embedded.getClass.getMethods.toSeq
            .filter(m => m.getParameterCount == 0 && m.getReturnType.equals(classOf[Array[Byte]]))
        assert(accessors.nonEmpty, "no embedded fixtures were found, so the reflection filter is wrong rather than the fixtures")
        repoRoot match
            case Absent => fail("could not locate the repository root, so a passing result would prove nothing")
            case Present(root) =>
                val missing = accessors.flatMap { accessor =>
                    val bytes = accessor.invoke(Embedded).asInstanceOf[Array[Byte]]
                    // A tasty payload is not text, but the paths inside it are stored as plain bytes, so a latin-1 view exposes them
                    // without needing the format's own reader.
                    val text = new String(bytes, StandardCharsets.ISO_8859_1)
                    // Only the fixture module's own sources are checked. A fixture that references a library type records that
                    // library's source path too, which belongs to a published artifact and is never present in this repository.
                    scalaPath.findAllIn(text).toSeq.distinct.collect {
                        case p if p.contains("/kyo/fixtures/") && !Files.exists(root.resolve(p)) => s"${accessor.getName} records $p"
                    }
                }
                assert(
                    missing.isEmpty,
                    s"embedded fixtures record source paths that no longer exist, so they were generated from a tree that has moved: ${missing.mkString("; ")}"
                )
        end match
    }
end EmbeddedTest
