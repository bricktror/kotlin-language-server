package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.NotebookDocumentService
import org.javacs.kt.command.ALL_COMMANDS
import org.javacs.kt.externalsources.*
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import org.javacs.kt.progress.Progress
import org.javacs.kt.progress.LanguageClientProgress
import org.javacs.kt.semantictokens.semanticTokensLegend
import java.io.Closeable
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture

class KotlinLanguageServer : LanguageServer, LanguageClientAware, Closeable {
    val config = Configuration()
    val classPath = CompilerClassPath(config.compiler)

    private val tempDirectory = TemporaryDirectory()
    private val uriContentProvider = URIContentProvider(ClassContentProvider(config.externalSources, classPath, tempDirectory, CompositeSourceArchiveProvider(JdkSourceArchiveProvider(classPath), ClassPathSourceArchiveProvider(classPath))))
    val sourcePath = SourcePath(classPath, uriContentProvider, config.indexing)
    val sourceFiles = SourceFiles(sourcePath, uriContentProvider)

    private val textDocuments = KotlinTextDocumentService(sourceFiles, sourcePath, config, tempDirectory, uriContentProvider, classPath)
    private val workspaces = KotlinWorkspaceService(sourceFiles, sourcePath, classPath, textDocuments, config)
    private val protocolExtensions = KotlinProtocolExtensionService(uriContentProvider, classPath, sourcePath)

    private lateinit var client: LanguageClient

    private val async = AsyncExecutor()
    private var progressFactory: Progress.Factory = Progress.Factory.None
        set(factory: Progress.Factory) {
            field = factory
            sourcePath.progressFactory = factory
        }

    companion object {
        val VERSION: String? = System.getProperty("kotlinLanguageServer.version")
    }

    init {
        LOG.info("Kotlin Language Server: Version ${VERSION ?: "?"}")
    }

    override fun connect(client: LanguageClient) {
        this.client = client
        connectLoggingBackend()

        workspaces.connect(client)
        textDocuments.connect(client)

        LOG.info("Connected to client")
    }

    override fun getTextDocumentService(): KotlinTextDocumentService = textDocuments

    override fun getWorkspaceService(): KotlinWorkspaceService = workspaces

    @JsonDelegate
    fun getProtocolExtensionService(): KotlinProtocolExtensions = protocolExtensions

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = async.compute {
        val serverCapabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Incremental)
            workspace = WorkspaceServerCapabilities().apply {
                workspaceFolders = WorkspaceFoldersOptions().apply {
                    supported = true
                    changeNotifications = Either.forRight(true)
                }
            }
            hoverProvider = Either.forLeft(true)
            renameProvider = Either.forLeft(true)
            completionProvider = CompletionOptions(false, listOf("."))
            signatureHelpProvider = SignatureHelpOptions(listOf("(", ","))
            definitionProvider = Either.forLeft(true)
            documentSymbolProvider = Either.forLeft(true)
            workspaceSymbolProvider = Either.forLeft(true)
            referencesProvider = Either.forLeft(true)
            semanticTokensProvider = SemanticTokensWithRegistrationOptions(semanticTokensLegend, true, true)
            codeActionProvider = Either.forLeft(true)
            documentFormattingProvider = Either.forLeft(true)
            documentRangeFormattingProvider = Either.forLeft(true)
            executeCommandProvider = ExecuteCommandOptions(ALL_COMMANDS)
            documentHighlightProvider = Either.forLeft(true)
        }

        val clientCapabilities = params.capabilities
        config.completion.snippets.enabled = clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport ?: false

        if (clientCapabilities?.window?.workDoneProgress ?: false) {
            progressFactory = LanguageClientProgress.Factory(client)
        }

        if (clientCapabilities?.textDocument?.rename?.prepareSupport ?: false) {
            serverCapabilities.renameProvider = Either.forRight(RenameOptions(false))
        }

        @Suppress("DEPRECATION")
        val folders = params.workspaceFolders?.takeIf { it.isNotEmpty() }
            ?: params.rootUri?.let(::WorkspaceFolder)?.let(::listOf)
            ?: params.rootPath?.let(Paths::get)?.toUri()?.toString()?.let(::WorkspaceFolder)?.let(::listOf)
            ?: listOf()

        val progress = params.workDoneToken?.let { LanguageClientProgress("Workspace folders", it, client) }

        folders.forEachIndexed { i, folder ->
            LOG.info("Adding workspace folder {}", folder.name)
            val progressPrefix = "[${i + 1}/${folders.size}] ${folder.name ?: ""}"
            val progressPercent = (100 * i) / folders.size

            progress?.update("$progressPrefix: Updating source path", progressPercent)
            val root = Paths.get(parseURI(folder.uri))
            sourceFiles.addWorkspaceRoot(root)

            progress?.update("$progressPrefix: Updating class path", progressPercent)
            val refreshed = classPath.addWorkspaceRoot(root)
            if (refreshed) {
                progress?.update("$progressPrefix: Refreshing source path", progressPercent)
                sourcePath.refresh()
            }
        }
        progress?.close()

        textDocuments.lintAll()

        val serverInfo = ServerInfo("Kotlin Language Server", VERSION)

        InitializeResult(serverCapabilities, serverInfo)
    }

    private fun connectLoggingBackend() {
        val backend: (LogMessage) -> Unit = {
            client.logMessage(MessageParams().apply {
                type = it.level.toLSPMessageType()
                message = it.message
            })
        }
        LOG.connectOutputBackend(backend)
        LOG.connectErrorBackend(backend)
    }

    private fun LogLevel.toLSPMessageType(): MessageType = when (this) {
        LogLevel.ERROR -> MessageType.Error
        LogLevel.WARN -> MessageType.Warning
        LogLevel.INFO -> MessageType.Info
        else -> MessageType.Log
    }

    override fun close() {
        textDocumentService.close()
        classPath.close()
        tempDirectory.close()
        async.shutdown(awaitTermination = true)
    }

    override fun shutdown(): CompletableFuture<Any> {
        close()
        return completedFuture(null)
    }

    override fun exit() {}

}
