package org.javacs.kt.util
import java.nio.file.Path
import java.nio.file.Files
import java.io.File
import java.io.Closeable

class TempFile : Closeable {
    val path: Path
    val file get() = path.toFile()

    private constructor(path: Path) {
        this.path=path
    }
    override fun close() { Files.deleteIfExists(this.path) }

    companion object {
        fun create(
            prefix: String,
            extension: String?,
            initFile: ((path: Path) -> Unit)? = null
        ): TempFile {
            val tmpFile = TempFile(Files.createTempFile(prefix, extension))
            initFile?.invoke(tmpFile.path)
            return tmpFile
        }

        fun create(
            prefix: String,
            initFile: ((path: Path) -> Unit)? = null
        ): TempFile {
            val tmpFile = TempFile(Files.createTempFile(prefix, null))
            initFile?.invoke(tmpFile.path)
            return tmpFile
        }

        fun createDirectory(
            name: String = "kotlinlangserver"
        ): TempFile = TempFile(Files.createTempDirectory(name))

        /** Create a temporary file, run the initializer function and return the lines of the file */
        fun linesFrom(
            prefix: String,
            extension: String?,
            initFile: ((path: Path) -> Unit)
        ) = create(prefix, extension, initFile)
            .use { it.file.readLines() }
        /** Create a temporary file, run the initializer function and return the lines of the file */
        fun linesFrom(
            prefix: String,
            initFile: ((path: Path) -> Unit)
        ) = create(prefix, null, initFile)
            .use { it.file.readLines() }
        /** Create a temporary file, run the initializer function and return the lines of the file */
        fun linesFrom(
            initFile: ((path: Path) -> Unit)
        ) = create("kotlin-language-server", null, initFile)
            .use { it.file.readLines() }
    }
}
