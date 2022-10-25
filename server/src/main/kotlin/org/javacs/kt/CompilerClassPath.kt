package org.javacs.kt

import java.io.Closeable
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.*
import kotlinx.coroutines.*
import org.javacs.kt.classpath.ClassPathEntry
import org.javacs.kt.classpath.defaultClassPathResolver
import org.javacs.kt.Compiler
import org.javacs.kt.logging.*
import org.javacs.kt.util.TempFile
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.filePath

/**
 * Manages the class path (compiled JARs, etc), the Java source path
 * and the compiler. Note that Kotlin sources are stored in SourcePath.
 */
class CompilerClassPath(
    private val config: Configuration,
    val outputDirectory: TemporaryDirectory,
) {
    private val log by findLogger
    val workspaceRoots = mutableSetOf<URI>()

    private val javaSourcePath = mutableSetOf<Path>()
    private val buildScriptClassPath = mutableSetOf<ClassPathEntry>()
    val classPath = mutableSetOf<ClassPathEntry>()

    val javaHome: String? = System.getProperty("java.home", null)

    fun createCompiler() =
        Compiler(
            javaSourcePath,
            classPath.map { it.compiledJar }.toSet(),
            buildScriptClassPath.mapNotNull{it.compiledJar}.toSet(),
            outputDirectory.file,
            config.jvmTarget)

    var onNewCompiler: ((Compiler)->Unit)?=null

    /** Updates and possibly reinstantiates the compiler using new paths. */
    private fun refresh(
        updateClassPath: Boolean = true,
        updateBuildScriptClassPath: Boolean = true,
        updateJavaSourcePath: Boolean = true
    ) {
        var refreshCompiler = updateJavaSourcePath
        // TODO: Fetch class path and build script class path concurrently (and asynchronously)
        val resolver = defaultClassPathResolver(workspaceRoots.mapNotNull{it.filePath})

        if (updateClassPath) {
            if(syncPaths(classPath, resolver.classpath)) {
                refreshCompiler = true
            }
        }

        if (updateBuildScriptClassPath) {
            log.info("Update build script path")
            if (syncPaths(buildScriptClassPath, resolver.buildScriptClasspath)) {
                refreshCompiler = true
            }
        }
        if (!refreshCompiler) return
        onNewCompiler?.invoke(createCompiler())
    }

    private fun  syncPaths(dest: MutableSet<ClassPathEntry>, new: Set<ClassPathEntry>): Boolean {
        if(dest==new) return false
        val added = new - dest
        val removed = dest - new
        synchronized(dest) {
            dest.removeAll(removed)
            dest.addAll(added)
        }
        return true;
    }

    fun addWorkspaceRoot(root: URI) {
        log.info("Searching for dependencies and Java sources in workspace root ${root}")
        workspaceRoots.add(root)
        javaSourcePath.addAll(findJavaSourceFiles(root))
        refresh()
    }

    fun removeWorkspaceRoot(root: URI) {
        log.info("Removing dependencies and Java source path from workspace root ${root}")
        workspaceRoots.remove(root)
        javaSourcePath.removeAll(findJavaSourceFiles(root))
        refresh()
    }

    fun createdOnDisk(uri: URI) {
        if (!exclusions.isURIIncluded(uri)) return
        uri.filePath?.also{file->
            if (isJavaSource(file)) {
                javaSourcePath.add(file)
            }
        }
        changedOnDisk(uri)
    }

    fun deletedOnDisk(uri: URI) {
        if (!exclusions.isURIIncluded(uri)) return
        uri.filePath?.also{file->
            if (isJavaSource(file)) {
                javaSourcePath.remove(file)
            }
        }
        changedOnDisk(uri)
    }

    fun changedOnDisk(uri: URI) {
        if (!exclusions.isURIIncluded(uri)) return
        val buildScript = isBuildScript(uri)
        val javaSource = uri.filePath?.let(::isJavaSource) ?: false
        if (!buildScript && !javaSource) return
        refresh(
            updateClassPath = buildScript,
            updateBuildScriptClassPath = false,
            updateJavaSourcePath = javaSource)
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.endsWith(".java")

    private fun isBuildScript(uri: URI): Boolean = File(uri.getPath()).getName().let {
        it == "pom.xml" || it == "build.gradle" || it == "build.gradle.kts"
    }

    private fun findJavaSourceFiles(root: URI): Set<Path> {
        val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.java")
        return SourceExclusions(listOfNotNull(root.filePath))
            .walkIncluded()
            .filter { sourceMatcher.matches(it.fileName) }
            .toSet()
    }
    private val exclusions get()= SourceExclusions(workspaceRoots.mapNotNull{it.filePath})

    fun allKotlinFiles()=
        exclusions
            .walkIncluded()
            .filter { it.fileName.run { endsWith(".kt") || endsWith(".kts") }}
            .map { it.toUri() }
            .toSet()
}

// TODO: Read exclusions from gitignore/settings.json/... instead of
// hardcoding them
// TODO: Rename? ClassPathWalker?
private class SourceExclusions(
    private val workspaceRoots: Collection<Path>
) {
    private val excludedPatterns = listOf(".*", "bin", "build", "node_modules", "target")
        .map { FileSystems.getDefault().getPathMatcher("glob:$it") }
    /** Finds all non-excluded files recursively. */
    fun walkIncluded(): Sequence<Path> =
        workspaceRoots
            .asSequence()
            .flatMap { root ->
                root.toFile()
                    .walk()
                    .onEnter { isPathIncluded(it.toPath()) }
                    .map { it.toPath() }
    }

    /** Tests whether the given URI is not excluded. */
    fun isURIIncluded(uri: URI) = uri.filePath?.let(this::isPathIncluded) ?: false

    /** Tests whether the given path is not excluded. */
    fun isPathIncluded(file: Path): Boolean =
        workspaceRoots.any { file.startsWith(it) }
        && excludedPatterns.none { pattern ->
            workspaceRoots
                .mapNotNull { if (file.startsWith(it)) it.relativize(file) else null }
                .flatMap { it } // Extract path segments
                .any(pattern::matches)
        }
}
