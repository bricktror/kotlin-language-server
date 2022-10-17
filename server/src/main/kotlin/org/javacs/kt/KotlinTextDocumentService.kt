package org.javacs.kt

import arrow.core.Either
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.javacs.kt.codeaction.codeActions
import org.javacs.kt.completion.*
import org.javacs.kt.definition.goToDefinition
import org.javacs.kt.diagnostic.convertDiagnostic
import org.javacs.kt.formatting.formatKotlinCode
import org.javacs.kt.hover.hoverAt
import org.javacs.kt.position.offset
import org.javacs.kt.position.extractRange
import org.javacs.kt.position.position
import org.javacs.kt.references.findReferences
import org.javacs.kt.semantictokens.encodedSemanticTokens
import org.javacs.kt.signaturehelp.fetchSignatureHelpAt
import org.javacs.kt.symbols.documentSymbols
import org.javacs.kt.util.noResult
import org.javacs.kt.util.Debouncer
import org.javacs.kt.util.filePath
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.describeURIs
import org.javacs.kt.rename.renameSymbol
import org.javacs.kt.highlight.documentHighlightsAt
import org.javacs.kt.lsp4kt.*
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.net.URI
import java.io.Closeable
import java.nio.file.Path
import java.time.Duration
import kotlin.coroutines.*
import kotlinx.coroutines.*
import org.javacs.kt.logging.*

class KotlinTextDocumentService(
    private val sf: SourceFiles,
    private val sp: SourcePath,
    private val config: Configuration,
    private val tempDirectory: TemporaryDirectory,
    private val uriContentProvider: URIContentProvider,
    private val cp: CompilerClassPath,
) : TextDocumentService, Closeable {
    private val log by findLogger
    private lateinit var client: LanguageClient

    var debounceLint = Debouncer(Duration.ofMillis(config.linting.debounceTime))
    fun updateDebouncer() {
        debounceLint = Debouncer(Duration.ofMillis(config.linting.debounceTime))
    }

    val lintTodo = mutableSetOf<URI>()
    var lintCount = 0

    var lintRecompilationCallback: () -> Unit
        get() = sp.beforeCompileCallback
        set(callback) { sp.beforeCompileCallback = callback }

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

    private fun recover(position: TextDocumentPositionParams, recompile: Recompile): Pair<CompiledFile, Int> {
        return recover(position.textDocument.uri, position.position, recompile)
    }

    private fun recover(uriString: String, position: Position, recompile: Recompile): Pair<CompiledFile, Int> {
        val uri = parseURI(uriString)
        val content = sp.content(uri)
        val offset = offset(content, position.line, position.character)
        val shouldRecompile = when (recompile) {
            Recompile.ALWAYS -> true
            Recompile.AFTER_DOT -> offset > 0 && content[offset - 1] == '.'
            Recompile.NEVER -> false
        }
        val compiled = if (shouldRecompile) sp.currentVersion(uri) else sp.latestCompiledVersion(uri)
        return Pair(compiled, offset)
    }

    override suspend fun codeAction(params: CodeActionParams): List<Either<Command, CodeAction>> {
        val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.NEVER)
        return codeActions(file, sp.index, params.range, params.context)
    }

    override suspend fun hover(position: HoverParams): Hover? {
        reportTime {
            log.info{"Hovering at ${describePosition(position)}"}

            val (file, cursor) = recover(position, Recompile.NEVER)
            return hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
        }
    }

    override suspend fun documentHighlight(position: DocumentHighlightParams): List<DocumentHighlight> {
        val (file, cursor) = recover(position.textDocument.uri, position.position, Recompile.NEVER)
        return documentHighlightsAt(file, cursor)
    }

    override suspend fun onTypeFormatting(params: DocumentOnTypeFormattingParams): List<TextEdit> {
        TODO("not implemented")
    }

    override suspend fun definition(position: DefinitionParams): Either<List<Location>, List<LocationLink>> {
        reportTime {
            log.info{"Go-to-definition at ${describePosition(position)}"}

            val (file, cursor) = recover(position, Recompile.NEVER)
            return goToDefinition(file, cursor, uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp)
                ?.let(::listOf)
                ?.let { Either.Left(it) }
                ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.Left(emptyList()))
        }
    }

    override suspend fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit> {
        val code = extractRange(params.textDocument.content, params.range)
        return listOf(TextEdit(
            params.range,
            formatKotlinCode(code, params.options)
        ))
    }

    override suspend fun codeLens(params: CodeLensParams): List<CodeLens> {
        TODO("not implemented")
    }

    override suspend fun rename(params: RenameParams) :WorkspaceEdit? {
        val (file, cursor) = recover(params, Recompile.NEVER)
        return renameSymbol(file, cursor, sp, params.newName)
    }

    override suspend fun completion(position: CompletionParams):Either<List<CompletionItem>, CompletionList> {
        reportTime {
            log.info{"Completing at ${describePosition(position)}"}

            val (file, cursor) = recover(position, Recompile.NEVER) // TODO: Investigate when to recompile
            val completions = completions(file, cursor, sp.index, config.completion)
            log.info("Found ${completions.items.size} items")

            return Either.Right(completions)
        }
    }

    override suspend fun resolveCompletionItem(unresolved: CompletionItem): CompletionItem {
        TODO("not implemented")
    }

    /* @Suppress("DEPRECATION") */
    /* override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = scope.async { */
    /*     log.info{"Find symbols in ${describeURI(params.textDocument.uri)}"} */

    /*     reportTime { */
    /*         val uri = parseURI(params.textDocument.uri) */
    /*         val parsed = sp.parsedFile(uri) */

    /*         documentSymbols(parsed) */
    /*     } */
    /* }.asCompletableFuture() */

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.open(uri, params.textDocument.text, params.textDocument.version)
        lintNow(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        val uri = parseURI(params.textDocument.uri)
        lintNow(uri)
        debounceLint.schedule {
            sp.save(uri)
        }
    }

    override suspend fun signatureHelp(position: SignatureHelpParams): SignatureHelp? {
        reportTime {
            log.info{"Signature help at ${describePosition(position)}"}

            val (file, cursor) = recover(position, Recompile.NEVER)
            return fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.close(uri)
        clearDiagnostics(uri)
    }

    override suspend fun formatting(params: DocumentFormattingParams): List<TextEdit> {
        val code = params.textDocument.content
        log.info{"Formatting ${describeURI(params.textDocument.uri)}"}
        return listOf(TextEdit(
            Range(Position(0, 0), position(code, code.length)),
            formatKotlinCode(code, params.options)
        ))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.edit(uri, params.textDocument.version, params.contentChanges)
        lintLater(uri)
    }

    override suspend fun references(position: ReferenceParams): List<Location>
         = position.textDocument.filePath
            ?.let { file ->
                val content = sp.content(parseURI(position.textDocument.uri))
                val offset = offset(content, position.position.line, position.position.character)
                findReferences(file, offset, sp)
            } ?: listOf<Location>()

    override suspend fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
        log.info{"Full semantic tokens in ${describeURI(params.textDocument.uri)}"}

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val file = sp.currentVersion(uri)

            val tokens = encodedSemanticTokens(file)
            log.info("Found ${tokens.size} tokens")

            return SemanticTokens(tokens)
        }
    }

    override suspend fun semanticTokensRange(params: SemanticTokensRangeParams): SemanticTokens {
        log.info{"Ranged semantic tokens in ${describeURI(params.textDocument.uri)}"}

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val file = sp.currentVersion(uri)

            val tokens = encodedSemanticTokens(file, params.range)
            log.info("Found ${tokens.size} tokens")

            return SemanticTokens(tokens)
        }
    }

    override suspend fun resolveCodeLens(unresolved: CodeLens): CodeLens {
        TODO("not implemented")
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        return "${describeURI(position.textDocument.uri)} ${position.position.line + 1}:${position.position.character + 1}"
    }

    fun lintAll() {
        debounceLint.submitImmediately {
            sp.compileAllFiles()
            sp.saveAllFiles()
            sp.refreshDependencyIndexes()
        }
    }

    private fun clearLint(): List<URI> {
        val result = lintTodo.toList()
        lintTodo.clear()
        return result
    }

    private fun lintLater(uri: URI) {
        lintTodo.add(uri)
        debounceLint.schedule(::doLint)
    }

    private fun lintNow(file: URI) {
        lintTodo.add(file)
        debounceLint.submitImmediately(::doLint)
    }

    private fun doLint(cancelCallback: () -> Boolean) {
        log.info{"Linting ${describeURIs(lintTodo)}"}
        val files = clearLint()
        val context = sp.compileFiles(files)
        if (!cancelCallback.invoke()) {
            reportDiagnostics(files, context.diagnostics)
        }
        lintCount++
    }

    private fun reportDiagnostics(compiled: Collection<URI>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics.flatMap(::convertDiagnostic)
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((uri, diagnostics) in byFile) {
            if (sf.isOpen(uri)) {
                client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), diagnostics))
                log.info{"Reported ${diagnostics.size} diagnostics in ${describeURI(uri)}"}
            }
            else log.info{"Ignore ${diagnostics.size} diagnostics in ${describeURI(uri)} because it's not open"}
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
        debounceLint.shutdown(true)
    }

    private inline fun<T> reportTime(block: () -> T): T {
        val started = System.currentTimeMillis()
        try {
            return block()
        } finally {
            val finished = System.currentTimeMillis()
            log.info("Finished in ${finished-started} ms")
        }
    }
}
