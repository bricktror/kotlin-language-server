package org.kotlinlsp.lsp4kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import java.util.concurrent.CompletableFuture
import org.kotlinlsp.logging.*

interface ProtocolExtensions {
    suspend fun overrideMember(position: TextDocumentPositionParams): List<CodeAction>
}

@JsonSegment("kotlin")
interface JavaProtocolExtensions {
    @JsonRequest
    fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>>
}

fun ProtocolExtensions.asLsp4j(scope: CoroutineScope): JavaProtocolExtensions =
    JavaProtocolExtensionsFacade(this, scope)


private class JavaProtocolExtensionsFacade(
    private val service: ProtocolExtensions,
    private val scope: CoroutineScope,
): JavaProtocolExtensions {
    private val log by findLogger

    private fun <T> asyncOr(
        description: String,
        onErrorValue: ()->T,
        fn: (suspend CoroutineScope.() -> T)
    ): CompletableFuture<T> =
        scope.async {
            log.catchingOr(description, onErrorValue) {
                fn()
            }
        }.asCompletableFuture()


    override fun overrideMember(
        position: TextDocumentPositionParams
    ) = asyncOr("overrideMember", {emptyList()}) {
        service.overrideMember(position)
    }
}
