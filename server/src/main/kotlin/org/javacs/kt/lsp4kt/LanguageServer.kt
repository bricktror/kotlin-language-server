package org.javacs.kt.lsp4kt

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageServer as JavaLanguageServer
import org.eclipse.lsp4j.services.TextDocumentService as JavaTextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService as JavaWorkspaceService
import org.javacs.kt.logging.*

interface LanguageServer {
    val textDocumentService: TextDocumentService
    val workspaceService: WorkspaceService
    val extensions: ProtocolExtensions
    fun exit() {}
    suspend fun initialize(params: InitializeParams): InitializeResult
    suspend fun shutdown(): Any?
}

fun LanguageServer.asLsp4j(scope: CoroutineScope): JavaLanguageServer
    = JavaLanguageServerFacade(this, scope)

class JavaLanguageServerFacade(
    private val service : LanguageServer,
    private val scope: CoroutineScope,
): JavaLanguageServer {
    private fun <T> launch(fn: (suspend CoroutineScope.() -> T)): CompletableFuture<T> =
            scope.async { fn() }.asCompletableFuture()

    override fun getTextDocumentService(): JavaTextDocumentService = service.textDocumentService.asLsp4j(scope)
    override fun getWorkspaceService(): JavaWorkspaceService = service.workspaceService.asLsp4j(scope)
    @JsonDelegate fun getExtensionsService(): JavaProtocolExtensions = service.extensions.asLsp4j(scope)

    override fun initialize(params: InitializeParams) = launch {
        service.initialize(params)
    }

    override fun shutdown()=launch{
        service.shutdown()
    }

    override fun exit() {
        service.exit()
    }
}


