package org.kotlinlsp.lsp4kt

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.WorkspaceService as JavaWorkspaceService
import org.kotlinlsp.logging.*

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
    private fun <T> async(fn: (suspend CoroutineScope.() -> T)): CompletableFuture<T> =
            scope.async { fn() }.asCompletableFuture()

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any?> = async {
        service.executeCommand(params)
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        scope.launch  {
            service.didChangeWatchedFiles(params)
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        scope.launch  {
            service.didChangeConfiguration(params)
        }
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        scope.launch  {
            service.didChangeWorkspaceFolders(params)
        }
    }
}
