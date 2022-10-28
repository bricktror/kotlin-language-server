package org.kotlinlsp.lsp4kt

import arrow.core.Either
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.TextDocumentService as JavaTextDocumentService

interface TextDocumentService {
    fun didOpen(params: DidOpenTextDocumentParams)
    fun didSave(params: DidSaveTextDocumentParams)
    fun didClose(params: DidCloseTextDocumentParams)
    fun didChange(params: DidChangeTextDocumentParams)

    suspend fun codeAction(params: CodeActionParams): List<Either<Command, CodeAction>>
    suspend fun hover(position: HoverParams): Hover?
    suspend fun documentHighlight(position: DocumentHighlightParams): List<DocumentHighlight>
    suspend fun onTypeFormatting(params: DocumentOnTypeFormattingParams): List<TextEdit>
    suspend fun definition(position: DefinitionParams): Either<List<Location>, List<LocationLink>>
    suspend fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit>
    suspend fun codeLens(params: CodeLensParams): List<CodeLens>
    suspend fun rename(params: RenameParams): WorkspaceEdit?
    suspend fun completion(position: CompletionParams): Either<List<CompletionItem>, CompletionList>
    suspend fun resolveCompletionItem(unresolved: CompletionItem): CompletionItem
    suspend fun signatureHelp(position: SignatureHelpParams): SignatureHelp?
    suspend fun formatting(params: DocumentFormattingParams): List<TextEdit>
    suspend fun references(position: ReferenceParams): List<Location>
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
    private fun <T> async(fn: (suspend CoroutineScope.() -> T)): CompletableFuture<T> =
            scope.async { fn() }.asCompletableFuture()
    private fun launch(fn:(suspend CoroutineScope.()->Unit)): Unit {
        scope.launch{fn()}
    }

    override fun didOpen(params: DidOpenTextDocumentParams) = launch {
        service.didOpen(params)
    }

    override fun didSave(params: DidSaveTextDocumentParams) = launch {
        service.didSave(params)
    }

    override fun didClose(params: DidCloseTextDocumentParams) = launch {
        service.didClose(params)
    }

    override fun didChange(params: DidChangeTextDocumentParams) = launch {
        service.didChange(params)
    }

    override fun codeAction(params: CodeActionParams) = async {
        service.codeAction(params).map { it.asLsp4jEither() }
    }

    override fun hover(position: HoverParams) = async { service.hover(position) }

    override fun documentHighlight(
            position: DocumentHighlightParams
    ): CompletableFuture<List<DocumentHighlight>> = async { service.documentHighlight(position) }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams) = async {
        service.onTypeFormatting(params)
    }

    override fun definition(position: DefinitionParams) = async {
        service.definition(position).asLsp4jEither()
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams) = async {
        service.rangeFormatting(params)
    }

    override fun codeLens(params: CodeLensParams) = async { service.codeLens(params) }

    override fun rename(params: RenameParams) = async { service.rename(params) }

    override fun completion(position: CompletionParams) = async {
        service.completion(position).asLsp4jEither()
    }

    override fun resolveCompletionItem(unresolved: CompletionItem) = async {
        service.resolveCompletionItem(unresolved)
    }

    override fun signatureHelp(position: SignatureHelpParams) = async {
        service.signatureHelp(position)
    }

    override fun formatting(params: DocumentFormattingParams) = async {
        service.formatting(params)
    }

    override fun references(position: ReferenceParams) = async { service.references(position) }

    override fun semanticTokensFull(params: SemanticTokensParams) = async {
        service.semanticTokensFull(params)
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams) = async {
        service.semanticTokensRange(params)
    }

    override fun resolveCodeLens(unresolved: CodeLens) = async {
        service.resolveCodeLens(unresolved)
    }
}
