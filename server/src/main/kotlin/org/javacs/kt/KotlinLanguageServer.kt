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
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.TempFile
import org.javacs.kt.util.parseURI
import org.javacs.kt.progress.*
import org.javacs.kt.semantictokens.semanticTokensLegend
import org.javacs.kt.logging.*
import java.io.Closeable
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.logging.Level
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture

class KotlinLanguageServer(
    private val loggerTarget: DelegateLoggerTarget
) : LanguageServer, LanguageClientAware, Progress.Factory, Closeable {
    val config = Configuration()
    private val compilerTmpDir = TempFile.createDirectory()
    private val scope = CoroutineScope(
        Dispatchers.Default
        + CoroutineName("kotlin-lsp-worker")
        + Job())
    val classPath = CompilerClassPath(config.compiler, compilerTmpDir.file, scope)

    private val tempDirectory = TemporaryDirectory()
    private val uriContentProvider = URIContentProvider(ClassContentProvider(config.externalSources, classPath, tempDirectory, CompositeSourceArchiveProvider(JdkSourceArchiveProvider(classPath), ClassPathSourceArchiveProvider(classPath))))
    val sourcePath = SourcePath(
            classPath,
            uriContentProvider,
            config.indexing,
            scope,
            this)
    val sourceFiles = SourceFiles(sourcePath, uriContentProvider)

    private val textDocuments = KotlinTextDocumentService(
        sourceFiles,
        sourcePath,
        config,
        tempDirectory,
        uriContentProvider,
        classPath,
        scope)
    private val workspaces = KotlinWorkspaceService(sourceFiles, sourcePath, classPath, textDocuments, config)
    private val protocolExtensions = KotlinProtocolExtensionService(uriContentProvider, classPath, sourcePath, scope)

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
        connectLoggingBackend()

        workspaces.connect(client)
        textDocuments.connect(client)

        log.info("Connected to client")
    }

    private fun connectLoggingBackend() {
        this.loggerTarget.inner = FunctionLoggerTarget {
            client?.logMessage(MessageParams().apply {
                type = it.level.toLSPMessageType()
                message = it.message
            })
        }
    }
    override fun getTextDocumentService(): KotlinTextDocumentService = textDocuments

    override fun getWorkspaceService(): KotlinWorkspaceService = workspaces

    @JsonDelegate
    fun getProtocolExtensionService(): KotlinProtocolExtensions = protocolExtensions

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = scope.async {
        val clientCapabilities = params.capabilities
        config.completion.snippets.enabled = clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport ?: false

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

        textDocuments.lintAll()

        val serverCapabilities = ServerCapabilities().apply {
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
        InitializeResult(
            serverCapabilities,
            ServerInfo("Kotlin Language Server", VERSION))
    }.asCompletableFuture()


    private fun Level.toLSPMessageType(): MessageType = when (this) {
        Level.SEVERE -> MessageType.Error
        Level.WARNING -> MessageType.Warning
        Level.INFO -> MessageType.Info
        else -> MessageType.Log
    }

    override fun close() {
        textDocumentService.close()
        classPath.close()
        tempDirectory.close()
        compilerTmpDir.close()
    }

    override fun shutdown(): CompletableFuture<Any> {
        close()
        return completedFuture(null)
    }

    override fun exit() {}

}
