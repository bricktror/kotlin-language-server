package org.kotlinlsp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.io.path.toPath
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.kotlinlsp.file.CompositeURIWalker
import org.kotlinlsp.file.DirectoryIgnoringURIWalker
import org.kotlinlsp.file.FilesystemURIWalker
import org.kotlinlsp.file.TemporaryFile
import org.kotlinlsp.file.FileProvider
import org.kotlinlsp.logging.*
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.util.execAndReadStdoutAndStderr
import org.kotlinlsp.util.fileName
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.findCommandOnPath
import org.kotlinlsp.util.parseURI

private val log by findLogger.atToplevel(object{})

class KotlinWorkspaceService(
    private val fileProvider: FileProvider,
    private val sourceFileRepository: SourceFileRepository,
    private val commands: Map<String, (List<Any>)->Any?>,
) : WorkspaceService, Closeable {
    private val log by findLogger
    init {
        log.fine("WorkspaceService created")
    }

    private val innerWorkspaceRoots = mutableMapOf<URI, FilesystemURIWalker>()
    val workspaceRoots get()= innerWorkspaceRoots.keys.toSet()

    override fun close() {
        closeCompiler()
    }

    var compiler = lazy { createCompiler() }
    fun createCompiler(): Pair<Compiler, Compiler> =
        resolveClasspath(fileProvider, innerWorkspaceRoots.values.map{it.root})
            .let { (classpath, buildClasspath)->
                log.debug{"Instantiating compilers"}
                log.fine { "Compiler for source uses classpath ${classpath}" }
                log.fine { "Compiler for buildscript uses classpath ${buildClasspath}" }
                CompilerImpl(classpath, "default") to CompilerImpl(buildClasspath, "default")
            }

    private fun invalidateCompiler(){
        compiler = lazy { createCompiler() }
        sourceFileRepository.refresh()
    }
    private fun closeCompiler() {
        if (!compiler.isInitialized()) return
        log.fine("closing compilers")
        compiler.value.let{(a,b)->
            a.close()
            b.close()
        }
    }

    override suspend fun executeCommand(params: ExecuteCommandParams): Any? {
        log.info("Executing command: ${params.command} with ${params.arguments}")
        return commands.get(params.command)
                .also { if(it==null) log.warning{"Unhandled command workspace/${params.command}(${params.arguments})" } }
                ?.invoke(params.arguments)
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        params.changes
            .map { it.type to parseURI(it.uri) }
            .forEach { (type, uri)-> when (type) {
                FileChangeType.Created -> {
                    sourceFileRepository.readFromProvider(uri)
                    onChange(uri)
                }
                FileChangeType.Deleted -> {
                    sourceFileRepository.remove(uri)
                    onChange(uri)
                }
                FileChangeType.Changed -> {
                    sourceFileRepository.readFromProvider(uri)
                    onChange(uri)
                }
            } }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        val settings = params.settings as? JsonObject ?: return
        log.info("Updating configuration: ${settings}")
        // TODO allow for configuration
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        params.event.added
            .map {parseURI(it.uri)}
            .forEach{ addWorkspaceRoot(it) }
        params.event.removed
            .map {parseURI(it.uri)}
            .forEach { removeWorkspaceRoot(it) }
    }

    fun addWorkspaceRoot(root: URI) {
        log.info("Adding workspace root ${root}")
        val walker= root.filePath
            ?.let(::FilesystemURIWalker)
            ?: run {
                log.warning("Unable to walk root ${root}. Ignored")
                return
            }
        innerWorkspaceRoots[root]=walker
        invalidateCompiler()

        walker
            .let { DirectoryIgnoringURIWalker(it, listOf("build")) }
            .walk()
            .map{ it.toPath() }
            .filter { it.fileName.toString().run { endsWith(".kt") || endsWith(".kts") } }
            .map { it.toUri() }
            .toList()
            .also{
                log.info{"Registering ${it.size} source files"}
                log.finer{ "Registering source files ${root}: ${it.map(root::relativize)}" }
            }
            .forEach { sourceFileRepository.readFromProvider(it) }
    }

    fun removeWorkspaceRoot(root: URI) {
        log.info("Removing workspace root ${root}")
        val walker=innerWorkspaceRoots
            .remove(root)
            ?: run {
                log.warning("Root ${root} is not tracked. Unable to remove.")
                return
            }
        sourceFileRepository.removeMatching{ walker.contains(it) }
        invalidateCompiler()
    }

    private fun onChange(uri: URI) {
        val uriWalker= DirectoryIgnoringURIWalker(
            // TODO read gitignore for each path
            ignoredDirectories=listOf(".*", "bin", "build", "node_modules", "target"),
            inner = CompositeURIWalker(innerWorkspaceRoots.values))
        if (!uriWalker.contains(uri)) return
        val updateClassPath = uri.getPath()
            .let{File(it)}
            .getName()
            .let { it == "pom.xml" || it.endsWith(".gradle") || it.endsWith(".gradle.kts") }
        if(!updateClassPath) return
        invalidateCompiler()
    }
}
// TODO more sources than gradle? Look in git history for maven and more

fun resolveClasspath(
    fileProvider: FileProvider,
    workspaceRoots: Collection<Path>
) = fileProvider
    .getFile(URI("kls.resource", "kotlinDSLClassPathFinder.gradle.kts", ""))
    .let { it ?: return emptySet<Path>() to emptySet<Path>() }
    .let { tmpFile->
        val helperScript = tmpFile
            .path
            .toAbsolutePath()
            .toString()

        workspaceRoots
            .flatMap {
                resolveClasspathUsingGradle(it, helperScript)
                    .map { (a,b)->a to b }
            }
    }
    .toMap()
    .let { map->
        fun asPaths(key: String) =
            map.values // TODO per-project partitioning?
                .flatMap{it.get(key) ?: listOf()}
                .map { Paths.get(it) }
                .toSet()
                .also{log.debug("${key} classpath found ${it.size} dependencies")}
        asPaths("dependency") to asPaths("build-dependency")
    }

private fun resolveClasspathUsingGradle(path: Path, helperScript: String) =
        run {
            log.info("Resolving dependencies for '${path}' through Gradle's CLI")
            execAndReadStdoutAndStderr(path, listOf(
                    getGradleCommand(path).toString(),
                    "-I", helperScript,
                    "kotlin-lsp-deps",
                    "--console=plain",
                ))
        }
        .let { (result, errors) ->
            if(!errors.isBlank())
                log.warning("Gradle ran with errors: ${errors}")
            log.fine(result)
            result
        }
        .let{it.lines()}
        .mapNotNull { GradleKotlinLspRow.tryParse(it) }
        .let { GradleKotlinLspRow.inflateStructure(it) }


private data class GradleKotlinLspRow(
    val project: String,
    val key: String,
    val value: String,
) {
    companion object {
        private val pattern = "^kotlin-lsp ((?:\\ |[^ ])+) (\\S+) (.+)$".toRegex()
        fun tryParse(line: String) =
            pattern.find(line)
                ?.groups
                ?.let { GradleKotlinLspRow(
                    project=it[1]!!.value
                        .replace("\\ ", " ")
                        .replace("\\\\", "\\"),
                    key=it[2]!!.value,
                    value=it[3]!!.value)
                }

        fun inflateStructure(
            items: Collection<GradleKotlinLspRow>
        ): Map<String, Map<String, List<String>>> =
            items
                .groupBy{it.project}
                .map { (project, rows)-> project to rows.groupBy({it.key}, {it.value}) }
                .toMap()

    }
}

private fun getGradleCommand(workspace: Path): Path {
    val wrapper = workspace.resolve("gradlew").toAbsolutePath()
    if (Files.isExecutable(wrapper)) {
        return wrapper
    } else {
        return workspace.parent?.let(::getGradleCommand)
            ?: findCommandOnPath("gradle")
            ?: throw Error("Could not find 'gradle' on PATH")
    }
}
