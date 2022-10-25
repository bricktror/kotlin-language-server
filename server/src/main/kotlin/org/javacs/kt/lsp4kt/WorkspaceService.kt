package org.javacs.kt.lsp4kt

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.WorkspaceService as JavaWorkspaceService
import org.javacs.kt.logging.*

interface WorkspaceService {
    fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams)
    fun didChangeConfiguration(params: DidChangeConfigurationParams)
    fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams)
    suspend fun executeCommand(params: ExecuteCommandParams): Any?
}

fun WorkspaceService.asLsp4j(scope: CoroutineScope): JavaWorkspaceService =
        JavaWorkspaceServiceFacade(this, scope)

class JavaWorkspaceServiceFacade(
        private val service: WorkspaceService,
        private val scope: CoroutineScope
) : JavaWorkspaceService {
    private fun <T> launch(fn: (suspend CoroutineScope.() -> T)): CompletableFuture<T> =
            scope.async { fn() }.asCompletableFuture()

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any?> = launch {
        service.executeCommand(params)
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        service.didChangeWatchedFiles(params)
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        service.didChangeConfiguration(params)
    }

    /*     @Suppress("DEPRECATION") */
    /*     override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> { */
    /*         val result = workspaceSymbols(params.query, sp) */

    /*         return CompletableFuture.completedFuture(Either.forRight(result)) */
    /*     } */

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        service.didChangeWorkspaceFolders(params)
    }
}
