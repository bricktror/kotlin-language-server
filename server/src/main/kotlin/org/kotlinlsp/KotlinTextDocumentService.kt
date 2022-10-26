package org.kotlinlsp

import arrow.core.Either
import java.io.Closeable
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.coroutines.*
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.kotlinlsp.codeaction.codeActions
import org.kotlinlsp.completion.*
import org.kotlinlsp.logging.*
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.source.CompiledFile
import org.kotlinlsp.source.FileContentProvider
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.util.TemporaryDirectory
import org.kotlinlsp.util.describeURIs
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.noResult
import org.kotlinlsp.util.parseURI
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics

class KotlinTextDocumentService(
    private val sp: SourceFileRepository,
    private val config: ()->Configuration,
    private val tempDirectory: TemporaryDirectory,
    private val fileContentProvider: FileContentProvider,
    private val cp: CompilerClassPath,
) : TextDocumentService, Closeable {
    private val log by findLogger
    private lateinit var client: LanguageClient

    val lintTodo = mutableSetOf<URI>()
    var lintCount = 0

    private val TextDocumentItem.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.isKotlinScript: Boolean
        get() = uri.endsWith(".kts")

    private val TextDocumentIdentifier.content: String
        get() = sp.content(parseURI(uri))

    fun connect(client: LanguageClient) {
        this.client = client
    }

    private enum class Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private fun recover(position: TextDocumentPositionParams) =
        recover(position.textDocument.uri, position.position)

    private fun recover(uriString: String, position: Position): Pair<CompiledFile, Int> =
        sp.compileFile(parseURI(uriString)).let{
            it.asCompiledFile() to offset(it.content, position.line, position.character)
        }

    override suspend fun codeAction(params: CodeActionParams): List<Either<Command, CodeAction>> {
        val (file, _) = recover(params.textDocument.uri, params.range.start)
        return codeActions(file, sp.index, params.range, params.context)
    }

    override suspend fun hover(position: HoverParams): Hover? {
        log.info{"Hovering at ${describePosition(position)}"}
        val (file, cursor) = recover(position)
        return hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
    }

    override suspend fun documentHighlight(position: DocumentHighlightParams): List<DocumentHighlight> {
        val (file, cursor) = recover(position)
        return documentHighlightsAt(file, cursor)
    }

    override suspend fun onTypeFormatting(params: DocumentOnTypeFormattingParams): List<TextEdit> {
        TODO()
    }

    override suspend fun definition(position: DefinitionParams): Either<List<Location>, List<LocationLink>> {
        log.info{"Go-to-definition at ${describePosition(position)}"}
        val (file, cursor) = recover(position)
        return goToDefinition(file, cursor, fileContentProvider, tempDirectory, cp)
            ?.let(::listOf)
            ?.let { Either.Left(it) }
            ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.Left(emptyList()))
    }

    override suspend fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit> {
        val code = extractRange(params.textDocument.content, params.range)
        return listOf(TextEdit(
            params.range,
            formatKotlinCode(code, params.options)
        ))
    }

    override suspend fun codeLens(params: CodeLensParams): List<CodeLens> {
        TODO()
    }

    override suspend fun rename(params: RenameParams) :WorkspaceEdit? {
        val (file, cursor) = recover(params)
        return renameSymbol(file, cursor, sp, params.newName)
    }

    override suspend fun completion(position: CompletionParams):Either<List<CompletionItem>, CompletionList> {
        log.info{"Completing at ${describePosition(position)}"}
        val (file, cursor) = recover(position) // TODO: Investigate when to recompile
        val completions = completions(file, cursor, sp.index, config().snippets)
        log.info("Found ${completions.items.size} items")
        return Either.Right(completions)
    }

    override suspend fun resolveCompletionItem(unresolved: CompletionItem): CompletionItem {
        TODO()
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        with(params.textDocument) {
            val parsedUri = parseURI(uri)
            sp.applyManualEdit(parsedUri, version, text)
            lintNow(parsedUri)
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        val uri = parseURI(params.textDocument.uri)
        lintNow(uri)
        /* debounceLint.schedule { */
        /*     sp.save(uri) */
        /* } */
    }

    override suspend fun signatureHelp(position: SignatureHelpParams): SignatureHelp? {
        log.info{"Signature help at ${describePosition(position)}"}
        val (file, cursor) = recover(position)
        return fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sp.closeTransient(uri)
        clearDiagnostics(uri)
    }

    override suspend fun formatting(params: DocumentFormattingParams): List<TextEdit> {
        val code = params.textDocument.content
        log.info{"Formatting ${params.textDocument.uri}"}
        return listOf(TextEdit(
            Range(Position(0, 0), position(code, code.length)),
            formatKotlinCode(code, params.options)
        ))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        with(params) {
            val parsedUri = parseURI(textDocument.uri)
            sp.applyManualEdit(parsedUri, textDocument.version, contentChanges)
            lintLater(parsedUri)
        }
    }

    override suspend fun references(position: ReferenceParams): List<Location>
         = position.textDocument.filePath
            ?.let { file ->
                val content = sp.content(parseURI(position.textDocument.uri))
                val offset = offset(content, position.position.line, position.position.character)
                findReferences(file, offset, sp)
            } ?: listOf<Location>()

    override suspend fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
        log.info{"Full semantic tokens in ${params.textDocument.uri}"}
        val file = sp.compileFile(parseURI(params.textDocument.uri)).asCompiledFile()
        val tokens = encodedSemanticTokens(file)
        log.info("Found ${tokens.size} tokens")
        return SemanticTokens(tokens)
    }

    override suspend fun semanticTokensRange(params: SemanticTokensRangeParams): SemanticTokens {
        log.info{"Ranged semantic tokens in ${params.textDocument.uri}"}

        val file = sp.compileFile(parseURI(params.textDocument.uri)).asCompiledFile()

        val tokens = encodedSemanticTokens(file, params.range)
        log.info("Found ${tokens.size} tokens")

        return SemanticTokens(tokens)
    }

    override suspend fun resolveCodeLens(unresolved: CodeLens): CodeLens {
        TODO()
    }

    private fun describePosition(position: TextDocumentPositionParams): String =
        "${position.textDocument.uri} ${position.position.line + 1}:${position.position.character + 1}"

    fun lintAll() {
        /* debounceLint.submitImmediately { */
        /*     sp.compileAllFiles() */
        /*     sp.refreshDependencyIndexes() */
        /* } */
    }

    private fun lintLater(uri: URI) {
        /* lintTodo.add(uri) */
        /* debounceLint.schedule(::doLint) */
    }

    private fun lintNow(file: URI) {
        /* lintTodo.add(file) */
        /* debounceLint.submitImmediately(::doLint) */
    }

    private fun doLint(cancelCallback: () -> Boolean) {
        log.info{"Linting ${describeURIs(lintTodo)}"}
        val files = lintTodo.toList().also{ lintTodo.clear() }
        val context = CompositeBindingContext.create( sp.compileFiles(files).map{it.context})
        if (!cancelCallback.invoke()) {
            reportDiagnostics(files, context.diagnostics)
        }
        lintCount++
    }

    private fun reportDiagnostics(compiled: Collection<URI>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics.flatMap(::convertDiagnostic)
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((uri, diagnostics) in byFile) {
            if (!sp.hasManualEdit(uri)) {
                log.info{"Ignore ${diagnostics.size} diagnostics in ${uri} because it's not open"}
                continue
            }
            client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), diagnostics))
            log.info{"Reported ${diagnostics.size} diagnostics in ${uri}"}
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file)

            log.info("No diagnostics in ${file}")
        }

        lintCount++
    }

    private fun clearDiagnostics(uri: URI) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), listOf()))
    }

    override fun close() {
        /* debounceLint.shutdown(true) */
    }
}
