package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.NotebookDocumentService
import org.eclipse.lsp4j.services.TextDocumentService as JavaTextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService as JavaWorkspaceService
import org.javacs.kt.command.ALL_COMMANDS
import org.javacs.kt.externalsources.*
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.TempFile
import org.javacs.kt.util.parseURI
import org.javacs.kt.progress.*
import org.javacs.kt.semantictokens.semanticTokensLegend
import org.javacs.kt.logging.*
import org.javacs.kt.lsp4kt.*
import java.io.Closeable
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.logging.Level
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture

class KotlinLanguageServer(
    scope: CoroutineScope,
) : LanguageServer, LanguageClientAware, Progress.Factory, Closeable {
    private val config = Configuration()
    private val compilerTmpDir = TempFile.createDirectory()
    private val classPath = CompilerClassPath(config, compilerTmpDir.file, scope)

    private val tempDirectory = TemporaryDirectory()
    private val uriContentProvider = URIContentProvider(
        ClassContentProvider(
            config,
            classPath,
            tempDirectory,
            CompositeSourceArchiveProvider(
                JdkSourceArchiveProvider(classPath),
                ClassPathSourceArchiveProvider(classPath))))
    private val sourcePath = SourcePath(
            classPath,
            uriContentProvider,
            config,
            scope,
            this)
    private val sourceFiles = SourceFiles(sourcePath, uriContentProvider)

    override val textDocumentService = KotlinTextDocumentService(
        sourceFiles,
        sourcePath,
        config,
        tempDirectory,
        uriContentProvider,
        classPath)
    override val workspaceService = KotlinWorkspaceService(
        sourceFiles,
        sourcePath,
        classPath,
        textDocumentService,
        config)

    private val protocolExtensions = KotlinProtocolExtensionService(
        uriContentProvider,
        classPath,
        sourcePath,
        scope)

    private var client: LanguageClient? = null

    private var progressFactory: Progress.Factory = Progress.Factory.None
    override fun create(label: String) = progressFactory.create(label)

    companion object {
        private val log by findLogger

        val VERSION: String? = System.getProperty("kotlinLanguageServer.version")
        init {
            log.info("Kotlin Language Server: Version ${VERSION ?: "?"}")
        }
    }

    override fun connect(client: LanguageClient) {
        this.client = client
        workspaceService.connect(client)
        textDocumentService.connect(client)
        log.info("Connected to client")
    }


    @JsonDelegate
    fun getProtocolExtensionService(): KotlinProtocolExtensions = protocolExtensions

    override suspend fun initialize(params: InitializeParams): InitializeResult {
        val clientCapabilities = params.capabilities
        config.snippets = clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport ?: false

        if (clientCapabilities?.window?.workDoneProgress ?: false && client!=null) {
            progressFactory = LanguageClientProgress.Factory(client!!)
        }

        @Suppress("DEPRECATION")
        val folders = params.workspaceFolders?.takeIf { it.isNotEmpty() }
            ?: params.rootUri?.let(::WorkspaceFolder)?.let(::listOf)
            ?: params.rootPath?.let(Paths::get)?.toUri()?.toString()?.let(::WorkspaceFolder)?.let(::listOf)
            ?: listOf()

        (params.workDoneToken
            ?.takeIf { client!=null }
            ?.let { LanguageClientProgress("Workspace folders", it, client!!) }
            ?: Progress.None).use {
                it.reportSequentially(folders, {it.name}) { folder ->
                    log.info("Adding workspace folder ${folder.name}")

                    report("Updating source path")
                    val root = Paths.get(parseURI(folder.uri))
                    sourceFiles.addWorkspaceRoot(root)

                    report("Updating class path")
                    val refreshed = classPath.addWorkspaceRoot(root)
                    if (refreshed) {
                        report("Refreshing source path")
                        sourcePath.refresh()
                    }
                }
            }

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
                executeCommandProvider = ExecuteCommandOptions(ALL_COMMANDS)
            }
        }
    }

    override fun close() {
        textDocumentService.close()
        classPath.close()
        tempDirectory.close()
        compilerTmpDir.close()
    }

    override suspend fun shutdown(): Any? {
        close()
        return null
    }

    override fun exit() {}

}
