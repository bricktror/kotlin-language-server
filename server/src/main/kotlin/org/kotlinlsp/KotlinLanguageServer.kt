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
import org.kotlinlsp.externalsources.*
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.logging.*
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.extractRange
import org.kotlinlsp.semanticTokensLegend
import org.kotlinlsp.source.*
import org.kotlinlsp.util.TempFile
import org.kotlinlsp.util.TemporaryDirectory
import org.kotlinlsp.util.parseURI

class KotlinLanguageServer(
    scope: CoroutineScope,
) : LanguageServer, LanguageClientAware, Closeable {
    private val config = Configuration()
    private val compilerTmpDir = TempFile.createDirectory()
    private val classPath = CompilerClassPath(config, compilerTmpDir)

    private var compilers = classPath.createCompiler()
    private fun resolveCompiler(kind: CompilationKind) =
        if (kind==CompilationKind.DEFAULT)
            compilers.first
        else
            compilers.second

    private val tempDirectory = TemporaryDirectory()

    private val contentProvider = SchemeDelegatingFileContentProvider(mapOf(
        "file" to localFileSystemContentProvider,
        "kls" to ClassContentProvider(),
    ))

    private val sourceFileRepository = SourceFileRepository(
            {resolveCompiler(it)},
            contentProvider,
            SymbolIndex())

    override val textDocumentService = KotlinTextDocumentService(
        sourceFileRepository,
        {config},
        tempDirectory,
        contentProvider,
        classPath)

    private val workspaceActions = mapOf<String, (List<Any>)->Any?>(
        "convertJavaToKotlin" to { args ->
            val gson = Gson()
            val fileUri = (args[0] as JsonElement).asString
            val range = gson.fromJson(args[1] as JsonElement, Range::class.java)

            sourceFileRepository
            //Get the segment that should be converted
            .content(parseURI(fileUri))
            .let{extractRange(it, range)}
            // Apply the conversion
            .let{compilers.first.transpileJavaToKotlin(it)}
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
        sourceFileRepository,
        classPath,
        textDocumentService,
        config,
        workspaceActions)

    override val extensions = KotlinProtocolExtensionService(
        contentProvider,
        sourceFileRepository)

    private var client: LanguageClient? = null

    companion object {
        private val log by findLogger

        val VERSION: String? = System.getProperty("kotlinLanguageServer.version")
        init {
            log.info("Kotlin Language Server: Version ${VERSION ?: "?"}")
        }
    }

    init {
        classPath.onNewCompiler={
            log.info("Reinstantiating compiler")
            compilers.let{(a,b)->
                a.close()
                b.close()
            }
            compilers=it
            sourceFileRepository.refresh()
        }
        workspaceService.onConfigChange={
            compilers.let{(a,b)->
                a.close()
                b.close()
            }
            compilers=classPath.createCompiler()
        }
    }

    override fun connect(client: LanguageClient) {
        this.client = client
        workspaceService.connect(client)
        textDocumentService.connect(client)
        log.info("Connected to client")
    }

    override suspend fun initialize(params: InitializeParams): InitializeResult {
        val clientCapabilities = params.capabilities
        config.snippets = clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport ?: false

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

        textDocumentService.lintAll()

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
        textDocumentService.close()
        compilers.first.close()
        compilers.second.close()
        tempDirectory.close()
        compilerTmpDir.close()
    }

    override suspend fun shutdown(): Any? {
        close()
        return null
    }

    override fun exit() {}

}
