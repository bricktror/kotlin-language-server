package org.kotlinlsp.lsp4kt

import arrow.core.Either
import arrow.core.right
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.TextDocumentService as JavaTextDocumentService
import org.kotlinlsp.logging.*

interface TextDocumentService {
    fun didOpen(params: DidOpenTextDocumentParams)
    fun didSave(params: DidSaveTextDocumentParams)
    fun didClose(params: DidCloseTextDocumentParams)
    fun didChange(params: DidChangeTextDocumentParams)

    suspend fun codeAction(params: CodeActionParams): List<Either<Command, CodeAction>>
    suspend fun hover(params: HoverParams): Hover?
    suspend fun documentHighlight(params: DocumentHighlightParams): List<DocumentHighlight>
    suspend fun onTypeFormatting(params: DocumentOnTypeFormattingParams): List<TextEdit>
    suspend fun definition(params: DefinitionParams): Either<List<Location>, List<LocationLink>>
    suspend fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit>
    suspend fun codeLens(params: CodeLensParams): List<CodeLens>
    suspend fun rename(params: RenameParams): WorkspaceEdit?
    suspend fun completion(params: CompletionParams): CompletionList
    suspend fun resolveCompletionItem(unresolved: CompletionItem): CompletionItem
    suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp?
    suspend fun formatting(params: DocumentFormattingParams): List<TextEdit>
    suspend fun references(params: ReferenceParams): List<Location>
    suspend fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens
    suspend fun semanticTokensRange(params: SemanticTokensRangeParams): SemanticTokens
    suspend fun resolveCodeLens(unresolved: CodeLens): CodeLens
}

fun TextDocumentService.asLsp4j(scope: CoroutineScope): JavaTextDocumentService =
        JavaTextDocumentServiceFacade(this, scope)

private class JavaTextDocumentServiceFacade(
        private val service: TextDocumentService,
        private val scope: CoroutineScope
) : JavaTextDocumentService {
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

    private fun launch(description: String, fn:(suspend CoroutineScope.()->Unit)): Unit {
        scope.launch {
            log.catching(description){
                fn()
            }
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) = launch("didOpen") {
        service.didOpen(params)
    }

    override fun didSave(params: DidSaveTextDocumentParams) = launch("didSave") {
        service.didSave(params)
    }

    override fun didClose(params: DidCloseTextDocumentParams) = launch("didClose") {
        service.didClose(params)
    }

    override fun didChange(params: DidChangeTextDocumentParams) = launch("didChange") {
        service.didChange(params)
    }

    override fun codeAction(params: CodeActionParams) = async("codeAction") {
        service.codeAction(params).map { it.asLsp4jEither() }
    }

    override fun hover(params: HoverParams) = async("hover") {
        service.hover(params)
    }

    override fun documentHighlight(
            params: DocumentHighlightParams
    ): CompletableFuture<List<DocumentHighlight>> =
        asyncOr("documentHighlight", {listOf()}) {
            service.documentHighlight(params)
        }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams) = async("onTypeFormatting") {
        service.onTypeFormatting(params)
    }

    override fun definition(params: DefinitionParams) = async("definition") {
        service.definition(params).asLsp4jEither()
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams) = async("rangeFormatting") {
        service.rangeFormatting(params)
    }

    override fun codeLens(params: CodeLensParams) = async("codeLense") { service.codeLens(params) }

    override fun rename(params: RenameParams) = async("rename") { service.rename(params) }

    override fun completion(params: CompletionParams) = async("completion") {
        service.completion(params)
            .right()
            .asLsp4jEither<List<CompletionItem>, CompletionList>()
    }

    override fun resolveCompletionItem(unresolved: CompletionItem) = async("resolveCompletionItem") {
        service.resolveCompletionItem(unresolved)
    }

    override fun signatureHelp(params: SignatureHelpParams) = async("signatureHelp") {
        service.signatureHelp(params)
    }

    override fun formatting(params: DocumentFormattingParams) = async("formatting") {
        service.formatting(params)
    }

    override fun references(params: ReferenceParams) = async("references") { service.references(params) }

    override fun semanticTokensFull(params: SemanticTokensParams) = async("semanticTokensFull") {
        service.semanticTokensFull(params)
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams) = async("semanticTokensRange") {
        service.semanticTokensRange(params)
    }

    override fun resolveCodeLens(unresolved: CodeLens) = async("resolveCodeLens") {
        service.resolveCodeLens(unresolved)
    }
}
