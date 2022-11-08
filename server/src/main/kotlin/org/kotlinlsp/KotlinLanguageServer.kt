package org.kotlinlsp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.Closeable
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.logging.Level
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.NotebookDocumentService
import org.eclipse.lsp4j.services.TextDocumentService as JavaTextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService as JavaWorkspaceService
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.logging.*
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.file.*
import org.kotlinlsp.source.*
import org.kotlinlsp.file.TemporaryDirectory
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.util.extractRange

class KotlinLanguageServer : LanguageServer, LanguageClientAware, Closeable {
    companion object {
        private val log by findLogger

        val VERSION: String? = System.getProperty("kotlinLanguageServer.version")
        init {
            log.info("Kotlin Language Server: Version ${VERSION ?: "?"}")
        }
    }

    private val fileProvider: FileProvider = BootstrappingFileProvider {
        SchemeDelegatingFileProvider(mapOf(
            "file" to localFileSystemProvider,
            "kls" to ZipFileProvider(it),
            "kls.resource" to ResourceFileProvider(),
        ))
    }

    private val sourceFileRepository = SourceFileRepository(
            {resolveCompiler(it)},
            fileProvider,
            SymbolIndex())

    private val gson = Gson()
    private val workspaceActions = mapOf<String, (List<Any>)->Any?>(
        "convertJavaToKotlin" to { args ->
            val fileUri = gson.fromJson((args[0] as JsonElement), String::class.java)
            val range = gson.fromJson(args[1] as JsonElement, Range::class.java)

            sourceFileRepository
                //Get the segment that should be converted
                .content(parseURI(fileUri))
                .let { range.extractRange(it) }
                // Apply the conversion
                .let{resolveCompiler(CompilationKind.DEFAULT).transpileJavaToKotlin(it)}
                // Wrap as an text-document edit
                .let{listOf(TextEdit(range, it))}
                .let{TextDocumentEdit(
                        // TODO more arguments to the identitifer?
                        VersionedTextDocumentIdentifier().apply { uri = fileUri },
                        it)
                }
                // Wrap as an workspace edit
                .let{listOf(Either.forLeft<TextDocumentEdit, ResourceOperation>( it))}
                .let{WorkspaceEdit(it)}
                // Apply the edit to the client
                .let{ApplyWorkspaceEditParams(it)}
                .also{client?.applyEdit(it)}
        },
    )


    override val workspaceService = KotlinWorkspaceService(
        fileProvider,
        sourceFileRepository,
        workspaceActions)

    private fun resolveCompiler(kind: CompilationKind): Compiler =
        workspaceService.compiler.value.let {
            if (kind==CompilationKind.DEFAULT)
                it.first
            else it.second
        }

    private val tempDirectory = TemporaryDirectory()

    override val textDocumentService = KotlinTextDocumentService(
        sourceFileRepository,
        tempDirectory,
        fileProvider)

    override val extensions = KotlinProtocolExtensionService(sourceFileRepository)

    private var client: LanguageClient? = null

    override fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.connect(client)
        log.info("Connected to client")
    }

    override suspend fun initialize(params: InitializeParams): InitializeResult {
        val clientCapabilities = params.capabilities
        /* config.snippets = clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport ?: false */

        /* if (clientCapabilities?.window?.workDoneProgress ?: false && client!=null) { */
            /* progressFactory = LanguageClientProgress.Factory(client!!) */
        /* } */

        /* (params.workDoneToken */
        /*     ?.takeIf { client!=null } */
        /*     ?.let { LanguageClientProgress("Workspace folders", it, client!!) } */
        /*     ?: Progress.None).use { */
                /* it.reportSequentially(folders, {it.toString()}) { folder -> */
                    /* sourceFiles.addWorkspaceRoot(folder) */
                /* } */
            /* } */

        params.workspaceFolders
            .map{parseURI(it.uri)}
            .forEach{ workspaceService.addWorkspaceRoot(it) }

        return InitializeResult().apply{
            serverInfo = ServerInfo("Kotlin Language Server", VERSION)
            capabilities = ServerCapabilities().apply {
                setTextDocumentSync(TextDocumentSyncKind.Incremental)
                workspace = WorkspaceServerCapabilities().apply {
                    workspaceFolders = WorkspaceFoldersOptions().apply {
                        supported = true
                        changeNotifications = Either.forRight(true)
                    }
                }
                hoverProvider = Either.forLeft(true)
                renameProvider =
                    if (clientCapabilities?.textDocument?.rename?.prepareSupport ?: false)
                        Either.forRight(RenameOptions(false))
                    else
                        Either.forLeft(true)
                definitionProvider = Either.forLeft(true)
                documentSymbolProvider = Either.forLeft(true)
                workspaceSymbolProvider = Either.forLeft(true)
                referencesProvider = Either.forLeft(true)
                codeActionProvider = Either.forLeft(true)
                documentFormattingProvider = Either.forLeft(true)
                documentRangeFormattingProvider = Either.forLeft(true)
                documentHighlightProvider = Either.forLeft(true)
                completionProvider = CompletionOptions(false, listOf("."))
                signatureHelpProvider = SignatureHelpOptions(listOf("(", ","))
                semanticTokensProvider = SemanticTokensWithRegistrationOptions(semanticTokensLegend, true, true)
                executeCommandProvider = ExecuteCommandOptions(workspaceActions.keys.toList())
            }
        }
    }

    override fun close() {
        workspaceService.close()
        tempDirectory.close()
    }

    override suspend fun shutdown(): Any? {
        close()
        return null
    }

    override fun exit() {}
}
