package org.kotlinlsp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.io.path.toPath
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.kotlinlsp.logging.*
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.source.FileContentProvider
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.util.describeURI
import org.kotlinlsp.util.describeURIs
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.util.partitionAroundLast
import org.jetbrains.kotlin.idea.KotlinLanguage

class KotlinWorkspaceService(
    private val sp: SourceFileRepository,
    private val cp: CompilerClassPath,
    private val docService: KotlinTextDocumentService,
    private val config: Configuration,
    private val commands: Map<String, (List<Any>)->Any?>,
) : WorkspaceService, LanguageClientAware {
    private val log by findLogger
    private var languageClient: LanguageClient? = null

    override fun connect(client: LanguageClient): Unit {
        languageClient = client
    }

    override suspend fun executeCommand(params: ExecuteCommandParams): Any? {
        log.info("Executing command: ${params.command} with ${params.arguments}")
        return commands.get(params.command)
                .also { if(it==null) log.warning{"Unhandled command workspace/${params.command}(${params.arguments})" } }
                ?.invoke(params.arguments)
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        params.changes
            .map { parseURI(it.uri) to it.type }
            .forEach { (uri,type)-> when (type) {
                FileChangeType.Created -> createdOnDisk(uri)
                FileChangeType.Deleted -> deletedOnDisk(uri)
                FileChangeType.Changed -> changedOnDisk(uri)
                null -> Unit
            } }
    }

    var onConfigChange: ((Configuration)->Unit)? = null

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        val settings = params.settings as? JsonObject
        log.info("Updating configuration: ${settings}")
        if(settings == null) return

        settings.get("kotlin").apply {
            fun g(vararg path: String) =
                path.fold(this) { s, segment ->
                    s?.asJsonObject?.get(segment)
                }

            /* // Update deprecated configuration keys */
            /* g("debounceTime")?.asLong?.let { */
            /*     config.lintDebounceTime = it */
            /*     docService.updateDebouncer() */
            /* } */
            /* // Update linter options */
            /* g("linting", "debounceTime")?.asLong?.also { */
            /*     config.lintDebounceTime = it */
            /*     docService.updateDebouncer() */
            /* } */

            g("snippetsEnabled")?.asBoolean?.let {
                config.snippets = it
            }

            // Update compiler options
            g("compiler", "jvm", "target")?.asString?.also {
                config.jvmTarget=it
            }

            // Update code-completion options
            g("completion", "snipptes", "enabled")?.asBoolean?.also {
                config.snippets = it
            }

            // Update indexing options
            g("indexing", "enabled")?.asBoolean?.also {
                config.indexEnabled = it
            }

            g("externalSources", "autoConvertToKotlin")?.asBoolean?.also {
                config.autoConvertToKotlin = it
            }
        }
        onConfigChange?.invoke(config)
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        params.event.added
            .map {parseURI(it.uri)}
            .forEach{
                log.info("Adding workspace ${it} to source path")
                addWorkspaceRoot(it)
            }
        params.event.removed
            .map {parseURI(it.uri)}
            .forEach {
                log.info("Dropping workspace ${it} from source path")
                removeWorkspaceRoot(it)
            }
    }

    fun createdOnDisk(uri: URI) {
        sp.readFromProvider(uri)
        cp.createdOnDisk(uri)
    }

    fun deletedOnDisk(uri: URI) {
        sp.remove(uri)
        cp.deletedOnDisk(uri)
    }

    fun changedOnDisk(uri: URI) {
        sp.readFromProvider(uri)
        cp.changedOnDisk(uri)
    }

    fun addWorkspaceRoot(root: URI) {
        cp.addWorkspaceRoot(root)
            .forEach { sp.readFromProvider(it) }
    }

    fun removeWorkspaceRoot(root: URI) {
        sp.removeMatching{ it.toPath().startsWith(root.toPath()) }
        cp.removeWorkspaceRoot(root)
    }
}
