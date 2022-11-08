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
    private val log by findLogger

    private fun <T> async(
        description: String,
        fn: (suspend CoroutineScope.() -> T?)
    ): CompletableFuture<T?> =
        scope.async {
            log.catching(description) {
                fn()
            }
        }.asCompletableFuture()

    private fun launch(description: String, fn:(suspend CoroutineScope.()->Unit)): Unit {
        scope.launch {
            log.catching(description){
                fn()
            }
        }
    }

    override fun executeCommand(
        params: ExecuteCommandParams
    ): CompletableFuture<Any?> = async("executeCommand") {
        service.executeCommand(params)
    }

    override fun didChangeWatchedFiles(
        params: DidChangeWatchedFilesParams
    ) = launch("didChangeWatchedFiles") {
        service.didChangeWatchedFiles(params)
    }

    override fun didChangeConfiguration(
        params: DidChangeConfigurationParams
    ) = launch("didChangeConfiguration") {
        service.didChangeConfiguration(params)
    }

    override fun didChangeWorkspaceFolders(
        params: DidChangeWorkspaceFoldersParams
    ) = launch("didChangeWorkspaceFolders") {
        service.didChangeWorkspaceFolders(params)
    }
}
