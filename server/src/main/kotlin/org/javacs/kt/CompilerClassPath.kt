package org.javacs.kt

import org.javacs.kt.classpath.ClassPathEntry
import org.javacs.kt.classpath.defaultClassPathResolver
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.util.TempFile
import java.io.Closeable
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.coroutines.*
import org.javacs.kt.logging.*


/**
 * Manages the class path (compiled JARs, etc), the Java source path
 * and the compiler. Note that Kotlin sources are stored in SourcePath.
 */
class CompilerClassPath(
    private val config: Configuration,
    val outputDirectory: File,
    private val scope: CoroutineScope,
) : Closeable {
    private val log by findLogger
    val workspaceRoots = mutableSetOf<Path>()

    private val javaSourcePath = mutableSetOf<Path>()
    private val buildScriptClassPath = mutableSetOf<Path>()
    val classPath = mutableSetOf<ClassPathEntry>()

    val javaHome: String? = System.getProperty("java.home", null)

    var compiler = Compiler(
        javaSourcePath,
        classPath.map { it.compiledJar }.toSet(),
        buildScriptClassPath,
        outputDirectory)


    init {
        compiler.updateConfiguration(config.jvmTarget)
    }
    fun updateCompilerConfiguration() {
        compiler.updateConfiguration(config.jvmTarget)
    }


    /** Updates and possibly reinstantiates the compiler using new paths. */
    private fun refresh(
        updateClassPath: Boolean = true,
        updateBuildScriptClassPath: Boolean = true,
        updateJavaSourcePath: Boolean = true
    ): Boolean {
        // TODO: Fetch class path and build script class path concurrently (and asynchronously)
        val resolver = defaultClassPathResolver(workspaceRoots)
        var refreshCompiler = updateJavaSourcePath

        if (updateClassPath) {
            val newClassPath = resolver.classpath
            if (newClassPath != classPath) {
                synchronized(classPath) {
                    syncPaths(classPath, newClassPath, "class path") { it.compiledJar }
                }
                refreshCompiler = true
            }

            scope.launch {
                val newClassPathWithSources = resolver.classpath
                synchronized(classPath) {
                    syncPaths(classPath, newClassPathWithSources, "class path with sources") { it.compiledJar }
                }
            }
        }

        if (updateBuildScriptClassPath) {
            log.info("Update build script path")
            val newBuildScriptClassPath = resolver.buildScriptClasspath
                .map { it.compiledJar }
                .toSet()
            if (newBuildScriptClassPath != buildScriptClassPath) {
                syncPaths(buildScriptClassPath, newBuildScriptClassPath, "build script class path") { it }
                refreshCompiler = true
            }
        }

        if (refreshCompiler) {
            log.info("Reinstantiating compiler")
            compiler.close()
            compiler = Compiler(javaSourcePath, classPath.map { it.compiledJar }.toSet(), buildScriptClassPath, outputDirectory)
            updateCompilerConfiguration()
        }

        return refreshCompiler
    }

    /** Synchronizes the given two path sets and logs the differences. */
    private fun <T> syncPaths(dest: MutableSet<T>, new: Set<T>, name: String, toPath: (T) -> Path) {
        val added = new - dest
        val removed = dest - new

        logAdded(added.map(toPath), name)
        logRemoved(removed.map(toPath), name)

        dest.removeAll(removed)
        dest.addAll(added)
    }

    fun addWorkspaceRoot(root: Path): Boolean {
        log.info("Searching for dependencies and Java sources in workspace root ${root}")

        workspaceRoots.add(root)
        javaSourcePath.addAll(findJavaSourceFiles(root))

        return refresh()
    }

    fun removeWorkspaceRoot(root: Path): Boolean {
        log.info("Removing dependencies and Java source path from workspace root ${root}")

        workspaceRoots.remove(root)
        javaSourcePath.removeAll(findJavaSourceFiles(root))

        return refresh()
    }

    fun createdOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.add(file)
        }
        return changedOnDisk(file)
    }

    fun deletedOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.remove(file)
        }
        return changedOnDisk(file)
    }

    fun changedOnDisk(file: Path): Boolean {
        val buildScript = isBuildScript(file)
        val javaSource = isJavaSource(file)
        if (buildScript || javaSource) {
            return refresh(updateClassPath = buildScript, updateBuildScriptClassPath = false, updateJavaSourcePath = javaSource)
        } else {
            return false
        }
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

    private fun isBuildScript(file: Path): Boolean = file.fileName.toString().let { it == "pom.xml" || it == "build.gradle" || it == "build.gradle.kts" }

    override fun close() {
        compiler.close()
    }

    private fun findJavaSourceFiles(root: Path): Set<Path> {
        val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.java")
        return SourceExclusions(root)
            .walkIncluded()
            .filter { sourceMatcher.matches(it.fileName) }
            .toSet()
    }

    private fun logAdded(sources: Collection<Path>, name: String) {
        when {
            sources.isEmpty() -> return
            sources.size > 5 -> log.info("Adding ${sources.size} files to ${name}")
            else -> log.info("Adding ${sources} to ${name}")
        }
    }

    private fun logRemoved(sources: Collection<Path>, name: String) {
        when {
            sources.isEmpty() -> return
            sources.size > 5 -> log.info("Removing ${sources.size} files from ${name}")
            else -> log.info("Removing ${sources} from ${name}")
        }
    }
}
