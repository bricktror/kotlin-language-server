package org.kotlinlsp.file

import java.nio.file.Path
import java.nio.file.Files
import java.io.File
import java.io.Closeable

class TemporaryFile : Closeable {
    val path: Path
    val file get() = path.toFile()

    private constructor(path: Path) {
        this.path=path
    }
    override fun close() { Files.deleteIfExists(this.path) }

    companion object {
        fun create(
            prefix: String,
            extension: String?=null,
            initFile: ((path: Path) -> Unit)
        ): TemporaryFile =
            TemporaryFile(Files.createTempFile(prefix, extension))
                .also{initFile.invoke(it.path)}
    }
}

/**
 * A directory in which temporary files may be created.
 * The advantage of using this class over a standard
 * function such as Files.createTempFile is that all
 * temp files in the directory can easily be disposed
 * of once no longer needed.
 */
class TemporaryDirectory(prefix: String = "kotlinlangserver") : Closeable {
    val dirPath: Path = Files.createTempDirectory(prefix)
    val file get() = dirPath.toFile()

    fun createTempFile(prefix: String = "tmp", suffix: String = ""): Path =
        Files.createTempFile(dirPath, prefix, suffix)

    override fun close() {
        if (Files.exists(dirPath)) {
            dirPath.toFile().deleteRecursively()
        }
    }
}
