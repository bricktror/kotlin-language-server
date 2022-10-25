package org.javacs.kt.lsp4kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import java.util.concurrent.CompletableFuture

interface ProtocolExtensions {
    suspend fun jarClassContents(textDocument: TextDocumentIdentifier): String?
    suspend fun buildOutputLocation(): String?
    suspend fun mainClass(textDocument: TextDocumentIdentifier): Map<String, Any?>
    suspend fun overrideMember(position: TextDocumentPositionParams): List<CodeAction>
}

@JsonSegment("kotlin")
interface JavaProtocolExtensions {
    @JsonRequest
    fun jarClassContents(textDocument: TextDocumentIdentifier): CompletableFuture<String?>

    @JsonRequest
    fun buildOutputLocation(): CompletableFuture<String?>

    @JsonRequest
    fun mainClass(textDocument: TextDocumentIdentifier): CompletableFuture<Map<String, Any?>>

    @JsonRequest
    fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>>
}

fun ProtocolExtensions.asLsp4j(scope: CoroutineScope): JavaProtocolExtensions =
    JavaProtocolExtensionsFacade(this, scope)


private class JavaProtocolExtensionsFacade(
    private val service: ProtocolExtensions,
    private val scope: CoroutineScope,
): JavaProtocolExtensions {
    private fun <T> launch(fn: (suspend CoroutineScope.() -> T)): CompletableFuture<T> =
            scope.async { fn() }.asCompletableFuture()

    override fun jarClassContents(textDocument: TextDocumentIdentifier) = launch {
        service.jarClassContents(textDocument)
    }

    override fun buildOutputLocation() = launch {
        service.buildOutputLocation()
    }

    override fun mainClass(textDocument: TextDocumentIdentifier) = launch {
        service.mainClass(textDocument)
    }

    override fun overrideMember(position: TextDocumentPositionParams) = launch {
        service.overrideMember(position)
    }
}
