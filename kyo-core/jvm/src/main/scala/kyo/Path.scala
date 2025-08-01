package kyo

import dev.dirs.BaseDirectories
import dev.dirs.ProjectDirectories
import dev.dirs.UserDirectories
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.lang.System as JSystem
import java.nio.*
import java.nio.channels.FileChannel
import java.nio.charset.*
import java.nio.file.*
import java.nio.file.Files as JFiles
import java.nio.file.Path as JPath
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern
import kyo.*
import kyo.Tag
import scala.io.*
import scala.jdk.CollectionConverters.*
import scala.jdk.StreamConverters.*

final class Path private (val path: List[String]) extends Serializable derives CanEqual:

    def toJava: JPath               = Paths.get(path.mkString(File.separator))
    lazy val parts: List[Path.Part] = path

    /** Methods to read files completely
      */
    def read(using Frame): String < Sync =
        Sync.defer(JFiles.readString(toJava))

    def read(charset: Charset)(using Frame): String < Sync =
        Sync.defer(JFiles.readString(toJava, charset))

    def readAll(extension: String)(using Frame): Seq[(String, String)] < Sync =
        list(extension).map { paths =>
            Kyo.foreach(paths) { p =>
                p.read.map { content =>
                    val j = p.toJava
                    j.getName(j.getNameCount() - 1).toString() -> content
                }
            }
        }

    def readBytes(using Frame): Array[Byte] < Sync =
        Sync.defer(JFiles.readAllBytes(toJava))

    def readLines(using Frame): List[String] < Sync =
        Sync.defer(JFiles.readAllLines(toJava).asScala.toList)

    def readLines(
        charSet: Charset = java.nio.charset.StandardCharsets.UTF_8
    )(using Frame): List[String] < Sync =
        Sync.defer(JFiles.readAllLines(toJava, charSet).asScala.toList)

    /** Methods to append and write to files
      */

    private inline def append(createFolders: Boolean)(inline f: (JPath, Seq[OpenOption]) => JPath)(using Frame): Unit < Sync =
        Sync.defer {
            if createFolders then
                discard(f(toJava, Seq(StandardOpenOption.APPEND, StandardOpenOption.CREATE)))
            else if javaExists(toJava.getParent()) then
                discard(f(toJava, Seq(StandardOpenOption.APPEND)))
        }

    /** Appends a String to this path.
      */
    def append(value: String, createFolders: Boolean = true)(using Frame): Unit < Sync =
        append(createFolders)((path, options) => Files.writeString(toJava, value, options*))

    /** Appends a Bytes Array to this path.
      */
    def appendBytes(value: Array[Byte], createFolders: Boolean = true)(using Frame): Unit < Sync =
        append(createFolders)((path, options) => Files.write(toJava, value, options*))

    /** Appends lines of String to this path.
      */
    def appendLines(value: List[String], createFolders: Boolean = true)(using Frame): Unit < Sync =
        append(createFolders)((path, options) => Files.write(toJava, value.asJava, options*))

    private inline def write(createFolders: Boolean)(inline f: (JPath, Seq[OpenOption]) => JPath)(using Frame): Unit < Sync =
        Sync.defer {
            if createFolders then
                discard(f(toJava, Seq(StandardOpenOption.WRITE, StandardOpenOption.CREATE)))
            else if javaExists(toJava.getParent()) then
                discard(f(toJava, Seq(StandardOpenOption.WRITE)))
        }

    /** Writes a String to this path.
      */
    def write(value: String, createFolders: Boolean = true)(using Frame): Unit < Sync =
        write(createFolders)((path, options) => Files.writeString(toJava, value, options*))

    /** Writes a Bytes Array to this path.
      */
    def writeBytes(value: Array[Byte], createFolders: Boolean = true)(using Frame): Unit < Sync =
        write(createFolders)((path, options) => Files.write(toJava, value, options*))

    /** Writes lines of String to this path.
      */
    def writeLines(value: List[String], createFolders: Boolean = true)(using Frame): Unit < Sync =
        write(createFolders)((path, options) => Files.write(toJava, value.asJava, options*))

    /** Methods to read files into Stream
      */

    /** Reads a file returning its contents as a Stream of Strings
      */
    def readStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8)(using Frame): Stream[String, Scope & Sync] =
        readLoop[String, Array[Byte], (FileChannel, ByteBuffer)](
            (FileChannel.open(toJava, StandardOpenOption.READ), ByteBuffer.allocate(2048)),
            ch => Sync.defer(ch._1.close()),
            readOnceBytes,
            arr => Chunk(new String(arr, charset))
        )

    /** Reads a file returning its contents as a Stream of Lines
      */
    def readLinesStream(charset: Charset = java.nio.charset.StandardCharsets.UTF_8)(using Frame): Stream[String, Scope & Sync] =
        readLoop[String, String, BufferedReader](
            Sync.defer(JFiles.newBufferedReader(toJava, Charset.defaultCharset())),
            reader => reader.close(),
            readOnceLines,
            line => Chunk(line)
        )

    /** Reads a file returning its contents as a Stream of Bytes
      */
    def readBytesStream(using Frame): Stream[Byte, Scope & Sync] =
        readLoop[Byte, Array[Byte], (FileChannel, ByteBuffer)](
            Sync.defer(FileChannel.open(toJava, StandardOpenOption.READ), ByteBuffer.allocate(2048)),
            ch => ch._1.close(),
            readOnceBytes,
            arr => Chunk.from(arr.toSeq)
        )

    private def readOnceLines(reader: BufferedReader)(using Frame) =
        Sync.defer {
            val line = reader.readLine()
            if line == null then Maybe.empty else Maybe(line)
        }

    private def readOnceBytes(res: (FileChannel, ByteBuffer))(using Frame) =
        Sync.defer {
            val (fileChannel, buf) = res
            val bytesRead          = fileChannel.read(buf)
            if bytesRead < 1 then Maybe.empty
            else
                buf.flip()
                val arr = new Array[Byte](bytesRead)
                buf.get(arr)
                Maybe(arr)
            end if
        }

    private def readLoop[A, ReadTpe, Res](
        acquire: Res < Sync,
        release: Res => Unit < Async,
        readOnce: Res => Maybe[ReadTpe] < Sync,
        writeOnce: ReadTpe => Chunk[A]
    )(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Scope & Sync] =
        Stream[A, Scope & Sync] {
            Scope.acquireRelease(acquire)(release).map { res =>
                readOnce(res).map { state =>
                    Loop(state) {
                        case Absent => Loop.done
                        case Present(content) =>
                            Emit.valueWith(writeOnce(content))(readOnce(res).map(Loop.continue(_)))
                    }
                }
            }
        }
    end readLoop

    /** Other file utilities
      */

    /** Truncates the content of this file
      */
    def truncate(size: Long)(using Frame): FileChannel < (Scope & Sync) =
        Scope
            .acquireRelease(FileChannel.open(toJava, StandardOpenOption.WRITE))(ch => ch.close())
            .map { ch =>
                ch.truncate(size)
                ch
            }
    end truncate

    /** List contents of path
      */
    def list(using Frame): IndexedSeq[Path] < Sync =
        Sync.defer(JFiles.list(toJava).toScala(LazyList).toIndexedSeq).map(_.map(path => Path(path.toString)))

    /** List contents of path with given extension
      */
    def list(extension: String)(using Frame): IndexedSeq[Path] < Sync =
        Sync.defer(JFiles.list(toJava).toScala(LazyList).filter(path =>
            path.getFileName().toString().split('.').toList.lastOption.getOrElse("") == extension
        ).toIndexedSeq.map(path => Path(path.toString)))

    /** Returns if the path exists
      */
    def exists(using Frame): Boolean < Sync =
        exists(true)

    /** Returns if the path exists
      */
    def exists(followLinks: Boolean)(using Frame): Boolean < Sync =
        val path = toJava
        if path == null then Sync.defer(false)
        else if followLinks then Sync.defer(JFiles.exists(path))
        else Sync.defer(JFiles.exists(path, LinkOption.NOFOLLOW_LINKS))
    end exists

    private def javaExists(jPath: JPath): Boolean =
        if jPath == null then false
        else JFiles.exists(jPath, LinkOption.NOFOLLOW_LINKS)

    /** Returns if the path represents a directory
      */
    def isDir(using Frame): Boolean < Sync =
        Sync.defer(JFiles.isDirectory(toJava))

    /** Returns if the path represents a file
      */
    def isFile(using Frame): Boolean < Sync =
        Sync.defer(JFiles.isRegularFile(toJava))

    /** Returns if the path represents a symbolic link
      */
    def isLink(using Frame): Boolean < Sync =
        Sync.defer(JFiles.isSymbolicLink(toJava))

    /** Creates a directory in this path
      */
    def mkDir(using Frame): Unit < Sync =
        Sync.defer(javaExists(toJava.getParent())).map { parentsExist =>
            if parentsExist == true then Sync.defer(JFiles.createDirectory(toJava))
            else Sync.defer(JFiles.createDirectories(toJava))
        }.unit

    /** Creates a directory in this path
      */
    def mkFile(using Frame): Unit < Sync =
        Sync.defer(javaExists(toJava.getParent())).map { parentsExist =>
            if parentsExist == true then Sync.defer(JFiles.createDirectory(toJava))
            else Sync.defer(JFiles.createDirectories(toJava))
        }.unit
    end mkFile

    /** Moves the content of this path to another path
      */
    def move(
        to: Path,
        replaceExisting: Boolean = false,
        atomicMove: Boolean = false,
        createFolders: Boolean = true
    )(using Frame): Unit < Sync =
        val opts = (if atomicMove then List(StandardCopyOption.ATOMIC_MOVE) else Nil) ++ (if replaceExisting then
                                                                                              List(StandardCopyOption.REPLACE_EXISTING)
                                                                                          else Nil)
        Sync.defer(javaExists(toJava.getParent())).map { parentExists =>
            (parentExists, createFolders) match
                case (true, _) => Sync.defer(JFiles.move(toJava, to.toJava, opts*)).unit
                case (false, true) =>
                    Path(toJava.getParent().toString).mkDir.andThen(Sync.defer(JFiles.move(toJava, to.toJava, opts*)).unit)
                case _ => ()
        }
    end move

    /** Copies the content of this path to another path
      */
    def copy(
        to: Path,
        followLinks: Boolean = true,
        replaceExisting: Boolean = false,
        copyAttributes: Boolean = false,
        createFolders: Boolean = true,
        mergeFolders: Boolean = false
    )(using Frame): Unit < Sync =
        val opts = (if followLinks then List.empty[CopyOption] else List[CopyOption](LinkOption.NOFOLLOW_LINKS)) ++
            (if copyAttributes then List[CopyOption](StandardCopyOption.COPY_ATTRIBUTES) else List.empty[CopyOption]) ++
            (if replaceExisting then List[CopyOption](StandardCopyOption.REPLACE_EXISTING) else List.empty[CopyOption])
        Sync.defer(javaExists(toJava.getParent())).map { parentExists =>
            (parentExists, createFolders) match
                case (true, _) => Sync.defer(JFiles.copy(toJava, to.toJava, opts*)).unit
                case (false, true) =>
                    Path(toJava.getParent().toString).mkDir.andThen(Sync.defer(JFiles.copy(toJava, to.toJava, opts*)).unit)
                case _ => ()
        }
    end copy

    /** Removes this path if it is empty
      */
    def remove(using Frame): Boolean < Sync =
        remove(false)

    /** Removes this path if it is empty
      */
    def remove(checkExists: Boolean)(using Frame): Boolean < Sync =
        Sync.defer {
            if checkExists then
                JFiles.delete(toJava)
                true
            else
                JFiles.deleteIfExists(toJava)
        }

    /** Removes this path and all its contents
      */
    def removeAll(using Frame): Unit < Sync =
        Sync.defer {
            val path = toJava
            if javaExists(path) then
                val visitor = new SimpleFileVisitor[JPath]:
                    override def visitFile(path: JPath, basicFileAttributes: BasicFileAttributes): FileVisitResult =
                        JFiles.delete(path)
                        FileVisitResult.CONTINUE

                    override def postVisitDirectory(path: JPath, ioException: IOException): FileVisitResult =
                        JFiles.delete(path)
                        FileVisitResult.CONTINUE
                discard(JFiles.walkFileTree(path, visitor))
            end if
            ()
        }

    /** Creates a stream of the contents of this path with maximum depth
      */
    def walk(using Frame): Stream[Path, Sync] =
        walk(Int.MaxValue)

    /** Creates a stream of the contents of this path with given depth
      */
    def walk(maxDepth: Int)(using Frame): Stream[Path, Sync] =
        Stream.init(Sync.defer(JFiles.walk(toJava).toScala(LazyList).map(path => Path(path.toString))))

    override def hashCode(): Int =
        val prime  = 31
        var result = 1
        result = prime * result + (if path == null then 0 else path.hashCode)
        result
    end hashCode

    override def equals(obj: Any): Boolean = obj match
        case that: Path =>
            (this eq that) || this.path == that.path
        case _ => false

    override def toString = s"Path(\"${path.mkString(File.separator)}\")"

end Path

object Path:
    private val empty = new Path(Nil)

    type Part = String | Path

    def apply(parts: List[Part]): Path =
        val flattened = parts.flatMap {
            case p if isNull(p) => Nil
            case s: String      => List(s)
            case p: Path        => p.path
        }
        val javaPath       = if flattened.isEmpty then Paths.get("") else Paths.get(flattened.head, flattened.tail*)
        val normalizedPath = javaPath.normalize().toString
        if normalizedPath.isEmpty then empty else new Path(normalizedPath.split(Pattern.quote(File.separator)).toList)
    end apply

    def apply(path: Part*): Path =
        apply(path.toList)

    case class BasePaths(
        cache: Path,
        config: Path,
        data: Path,
        dataLocal: Path,
        executable: Path,
        preference: Path,
        runtime: Path
    )

    def basePaths(using Frame): BasePaths < Sync =
        Sync.defer {
            val dirs = BaseDirectories.get()
            BasePaths(
                Path(dirs.cacheDir),
                Path(dirs.configDir),
                Path(dirs.dataDir),
                Path(dirs.dataLocalDir),
                Path(dirs.executableDir),
                Path(dirs.preferenceDir),
                Path(dirs.runtimeDir)
            )
        }

    case class UserPaths(
        home: Path,
        audio: Path,
        desktop: Path,
        document: Path,
        download: Path,
        font: Path,
        picture: Path,
        public: Path,
        template: Path,
        video: Path
    )

    def userPaths(using Frame): UserPaths < Sync =
        Sync.defer {
            val dirs = UserDirectories.get()
            UserPaths(
                Path(dirs.homeDir),
                Path(dirs.audioDir),
                Path(dirs.desktopDir),
                Path(dirs.documentDir),
                Path(dirs.downloadDir),
                Path(dirs.fontDir),
                Path(dirs.pictureDir),
                Path(dirs.publicDir),
                Path(dirs.templateDir),
                Path(dirs.videoDir)
            )
        }

    case class ProjectPaths(
        path: Path,
        cache: Path,
        config: Path,
        data: Path,
        dataLocal: Path,
        preference: Path,
        runtime: Path
    )

    def projectPaths(qualifier: String, organization: String, application: String)(using Frame): ProjectPaths < Sync =
        Sync.defer {
            val dirs = ProjectDirectories.from(qualifier, organization, application)
            ProjectPaths(
                Path(dirs.projectPath),
                Path(dirs.cacheDir),
                Path(dirs.configDir),
                Path(dirs.dataDir),
                Path(dirs.dataLocalDir),
                Path(dirs.preferenceDir),
                Path(dirs.runtimeDir)
            )
        }

end Path

extension [S](stream: Stream[Byte, S])
    def sink(path: Path)(using Frame): Unit < (Scope & Sync & S) =
        Scope.acquireRelease(Sync.defer(FileChannel.open(path.toJava, StandardOpenOption.WRITE)))(ch => ch.close()).map { fileCh =>
            stream.foreachChunk(bytes =>
                Sync.defer {
                    fileCh.write(ByteBuffer.wrap(bytes.toArray))
                    ()
                }
            )
        }
end extension

extension [S](stream: Stream[String, S])
    @scala.annotation.targetName("stringSink")
    def sink(path: Path, charset: Codec = java.nio.charset.StandardCharsets.UTF_8)(using Frame): Unit < (Scope & Sync & S) =
        Scope.acquireRelease(Sync.defer(FileChannel.open(path.toJava, StandardOpenOption.WRITE)))(ch => ch.close()).map { fileCh =>
            stream.foreach(s =>
                Sync.defer {
                    fileCh.write(ByteBuffer.wrap(s.getBytes))
                    ()
                }
            )
        }

    def sinkLines(
        path: Path,
        charset: Codec = java.nio.charset.StandardCharsets.UTF_8
    )(using Frame): Unit < (Scope & Sync & S) =
        Scope.acquireRelease(FileChannel.open(path.toJava, StandardOpenOption.WRITE))(ch => ch.close()).map { fileCh =>
            stream.foreach(line =>
                Sync.defer {
                    fileCh.write(ByteBuffer.wrap(line.getBytes))
                    fileCh.write(ByteBuffer.wrap(JSystem.lineSeparator().getBytes))
                    ()
                }
            )
        }
end extension
