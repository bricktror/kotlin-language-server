package org.kotlinlsp

import java.io.Closeable
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.coroutines.*
import kotlin.io.path.toPath
import kotlinx.coroutines.*
import org.kotlinlsp.resolveClasspath
import org.kotlinlsp.logging.*
import org.kotlinlsp.source.CompositeURIWalker
import org.kotlinlsp.source.DirectoryIgnoringURIWalker
import org.kotlinlsp.source.FilesystemURIWalker
import org.kotlinlsp.source.URIWalker
import org.kotlinlsp.util.TempFile
import org.kotlinlsp.util.TemporaryDirectory
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.fileExtension

/**
 * Manages the class path (compiled JARs, etc), the Java source path
 * and the compiler. Note that Kotlin sources are stored in SourcePath.
 */
class CompilerClassPath(
    private val config: Configuration,
    val outputDirectory: TemporaryDirectory,
) {
    private val log by findLogger

    private val innerWorkspaceRoots = mutableMapOf<URI, FilesystemURIWalker>()
    val workspaceRoots get()= innerWorkspaceRoots.keys.toSet()
    private val javaSourcePath get()= uriWalker
        .walk()
        .filter{it.fileExtension == "java"}
        .map{it.toPath()}
        .toSet()

    private val uriWalker get()=
        DirectoryIgnoringURIWalker(
                // TODO read gitignore for each path
                ignoredDirectories=listOf(".*", "bin", "build", "node_modules", "target"),
                inner = CompositeURIWalker(innerWorkspaceRoots.values))

    fun createCompiler(): Pair<Compiler, Compiler> =
        resolveClasspath(innerWorkspaceRoots.values.map{it.root})
            .let { (classpath, buildClasspath)->
                fun init(classpath: Set<Path>) =
                    CompilerImpl(
                        javaSourcePath,
                        classpath,
                        outputDirectory.file,
                        config.jvmTarget)
                init(classpath) to init(buildClasspath)
            }

    var onNewCompiler: ((Pair<Compiler, Compiler>)->Unit)?=null

    fun addWorkspaceRoot(root: URI): Sequence<URI> {
        log.info("Adding workspace root ${root}")
        val workspaceWalker= root.filePath
            ?.let(::FilesystemURIWalker)
            ?: run {
                log.warning("Unable to walk root ${root}. Ignored")
                return emptySequence()
            }
        innerWorkspaceRoots[root]=workspaceWalker
        invalidateCompiler()
        return workspaceWalker
            .walk()
            .map{ it.toPath() }
            .filter { it.fileName.run { endsWith(".kt") || endsWith(".kts") }}
            .map { it.toUri() }
    }

    fun removeWorkspaceRoot(root: URI) {
        log.info("Removing workspace root ${root}")
        innerWorkspaceRoots.remove(root) ?: run {
            log.warning("Root ${root} is not tracked. Unable to remove.")
            return
        }
        invalidateCompiler()
    }

    fun createdOnDisk(uri: URI) = changedOnDisk(uri)
    fun deletedOnDisk(uri: URI) = changedOnDisk(uri)
    fun changedOnDisk(uri: URI) {
        if (!uriWalker.contains(uri)) return
        fun isBuildScript(uri: URI): Boolean = File(uri.getPath()).getName().let {
            it == "pom.xml" || it.endsWith(".gradle") || it.endsWith(".gradle.kts")
        }
        fun isJavaSource(file: Path): Boolean = file.fileName.endsWith(".java")
        val updateClassPath = isBuildScript(uri)
        val updateJavaSourcePath = uri.filePath?.let(::isJavaSource) ?: false
        if(!updateClassPath && !updateJavaSourcePath) return
        invalidateCompiler()
    }

    /** Updates and possibly reinstantiates the compiler using new paths. */
    private fun invalidateCompiler() {
        onNewCompiler?.invoke(createCompiler())
    }
}
