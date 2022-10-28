package org.kotlinlsp.lsp4kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import java.util.concurrent.CompletableFuture

interface ProtocolExtensions {
    suspend fun jarClassContents(textDocument: TextDocumentIdentifier): String?
    suspend fun overrideMember(position: TextDocumentPositionParams): List<CodeAction>
}

@JsonSegment("kotlin")
interface JavaProtocolExtensions {
    @JsonRequest
    fun jarClassContents(textDocument: TextDocumentIdentifier): CompletableFuture<String?>

    @JsonRequest
    fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>>
}

fun ProtocolExtensions.asLsp4j(scope: CoroutineScope): JavaProtocolExtensions =
    JavaProtocolExtensionsFacade(this, scope)


private class JavaProtocolExtensionsFacade(
    private val service: ProtocolExtensions,
    private val scope: CoroutineScope,
): JavaProtocolExtensions {
    private fun <T> async(fn: (suspend CoroutineScope.() -> T)): CompletableFuture<T> =
            scope.async { fn() }.asCompletableFuture()

    override fun jarClassContents(textDocument: TextDocumentIdentifier) = async {
        service.jarClassContents(textDocument)
    }

    override fun overrideMember(position: TextDocumentPositionParams) = async {
        service.overrideMember(position)
    }
}
