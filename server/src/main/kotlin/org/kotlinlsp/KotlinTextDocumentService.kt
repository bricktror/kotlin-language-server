package org.kotlinlsp

import arrow.core.Either
import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.FormattingOptions as KtfmtOptions
import com.google.common.cache.CacheBuilder
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.io.Closeable
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.coroutines.*
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.InsertTextFormat.PlainText
import org.eclipse.lsp4j.InsertTextFormat.Snippet
import org.eclipse.lsp4j.jsonrpc.messages.Either as Lsp4jEither
import org.eclipse.lsp4j.services.LanguageClient
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.kdoc.parser.KDocKnownTag
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.load.java.sources.JavaSourceElement
import org.jetbrains.kotlin.load.java.structure.JavaMethod
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

import org.kotlinlsp.source.context.unwrapReferenceExpression
import org.kotlinlsp.source.context.findDeclarationAt
import org.kotlinlsp.util.pair.*
import org.kotlinlsp.file.FileProvider
import org.kotlinlsp.file.TemporaryDirectory
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.index.receiverTypeFqn
import org.kotlinlsp.index.getCompletions
import org.kotlinlsp.logging.*
import org.kotlinlsp.logging.findLogger
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.source.SourceFile
import org.kotlinlsp.source.generate.*
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.util.arrow.*
import org.kotlinlsp.util.extractRange
import org.kotlinlsp.util.fileExtension
import org.kotlinlsp.util.fileName
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.getIndexIn
import org.kotlinlsp.util.indexToPosition
import org.kotlinlsp.util.isSubrangeOf
import org.kotlinlsp.util.isZero
import org.kotlinlsp.util.onEachIndexed
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.util.toLsp4jRange
import org.kotlinlsp.prettyprint.*
import org.kotlinlsp.source.context.lexicalScopeAt

class KotlinTextDocumentService(
    private val sourceFiles: SourceFileRepository,
    private val tempDirectory: TemporaryDirectory,
    private val fileProvider: FileProvider,
) : TextDocumentService {
    private val log by findLogger
    private lateinit var client: LanguageClient
    fun connect(client: LanguageClient) { this.client = client }

    private val TextDocumentIdentifier.typedUri get()=parseURI(uri)
    private val TextDocumentItem.typedUri get()=parseURI(uri)

    override suspend fun codeAction(params: CodeActionParams): List<Either<Command, CodeAction>> {
        val file = sourceFiles.compileFile(params.textDocument.typedUri)
            ?: return listOf()
        return (params.context.only ?:listOf(CodeActionKind.Refactor, CodeActionKind.QuickFix))
            .flatMap {
                when (it) {
                    CodeActionKind.Refactor -> sequence {
                        val hasSelection = params.range.let {
                            (it.end.line != it.start.line) || (it.end.character != it.start.character)
                        }
                        if (hasSelection) {
                            yield(Either.Left(
                                    Command("Convert Java to Kotlin", "convertJavaToKotlin", listOf(
                                        file.ktFile.toPath().toUri().toString(),
                                        params.range
                                    ))
                                ))
                        }
                    }.toList()
                    CodeActionKind.QuickFix ->
                        listOf(
                            ImplementAbstractMembersQuickFix(),
                            AddMissingImportsQuickFix(sourceFiles.index)
                        )
                        .flatMap {
                            it.compute(file, params.range, params.context.diagnostics)
                        }
                    else -> listOf()
                }
            }
    }

    override suspend fun hover(params: HoverParams): Hover? {
        log.info{"Hovering at ${params.describe()}"}
        return sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let{ file->
                val content=file.content
                val ktFile=file.ktFile
                val context=file.context
                val cursor = params.position.getIndexIn(content)
                log.finer{"Hover cursor at ${ktFile.describePosition(cursor)} content: '${content.peekCursorPosition(cursor)}'"}
                val expr= ktFile.findElementAt(cursor)
                    ?.findParent<KtExpression>()
                    ?: run {
                        log.info{"Couldn't find expression at ${ktFile.describePosition(cursor)}"}
                        return null
                    }
                val lexicalScope=context.lexicalScopeAt(cursor) ?: run {
                    log.info{"Couldn't find lexicalScope at ${ktFile.describePosition(cursor)}"}
                    return null
                }
                fun recompile(expression: KtExpression) =
                    file.compiler.compileKtExpression(expression, lexicalScope, file.sourcePath)
                expr
                    .unwrapReferenceExpression()
                    .also { log.finer{"Probing reference expression ${it}"} }
                    .let { expression -> recompile(expression).findDeclarationAt(cursor) }
                    ?.let { target ->
                        val docstring = ktFile
                            .findElementAt(cursor)
                            ?.findParent<KtCallExpression>()
                            ?.let { getCandidates(it, file) }
                            ?.firstOrNull()
                            ?.let { extractDocstring(it) }
                        Hover(markup(target.describeAlt(), docstring))
                    }
                ?: expr
                    .let { it to it }
                    .mapFirstNotNull { expression ->
                        recompile(expression)
                            .let { context->
                                expression.describeCallReturn(context)
                                ?: expression.describeExpressionType(context)
                            }
                    }
                    ?.mapSecondNotNull { expression->
                        expression
                            .children
                            .mapNotNull{ (it as? PsiDocCommentBase)?.text }
                            .firstOrNull()
                    }
                    ?.let { (hoverText, docstring) -> Hover(markup(hoverText, docstring)) }
                ?: run {
                    log.info{"No hover found at ${params.describe()}"}
                    Hover(markup("<???>", ""))
                }
            }
    }

    override suspend fun documentHighlight(params: DocumentHighlightParams): List<DocumentHighlight> =
         sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let{ file->
                val cursor= params.position.getIndexIn(file.content)
                val (declaration, declarationLocation) = file.findDeclaration(cursor)
                    ?: return emptyList()
                sequence{

                    if (declaration.containingFile==file.ktFile)
                        yield(DocumentHighlight(declarationLocation.range, DocumentHighlightKind.Text))
                    findReferencesToDeclarationInFile(declaration, file)
                        .map { DocumentHighlight(it, DocumentHighlightKind.Text) }
                        .let { yieldAll(it) }
                }.toList()
            } ?: emptyList()

    override suspend fun onTypeFormatting(params: DocumentOnTypeFormattingParams): List<TextEdit> {
        TODO()
    }

    override suspend fun definition(params: DefinitionParams): Either<List<Location>, List<LocationLink>> {
        log.info{"Go-to-definition at ${params.describe()}"}
        return sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let { file->
                val cursor= params.position.getIndexIn(file.content)
                goToDefinition(file, cursor, fileProvider, tempDirectory)
            }
            ?.let(::listOf)
            ?.let { Either.Left(it) }
            ?: run{log.info{"Couldn't find definition at ${params.describe()}"}; Either.Left(emptyList())}
    }

    override suspend fun codeLens(params: CodeLensParams): List<CodeLens> {
        TODO()
    }

    override suspend fun rename(params: RenameParams): WorkspaceEdit? {
        val (declaration, location) = sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let{ it to params.position.getIndexIn(it.content)}
            ?.let { (file, cursor) -> file.findDeclaration(cursor) }
            .let { it?:return null}
        return WorkspaceEdit(sequence{
            yield(Lsp4jEither.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(
                VersionedTextDocumentIdentifier().apply { uri = location.uri },
                listOf(TextEdit(location.range, params.newName))
            )))
            yieldAll(findReferences(declaration, sourceFiles)
                .map {
                    Lsp4jEither.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(
                        VersionedTextDocumentIdentifier().apply { uri = it.uri },
                        listOf(TextEdit(it.range, params.newName))
                    ))
                })
        }.toList())
    }

    override suspend fun completion(params: CompletionParams): CompletionList {
        log.info{"Completing at ${params.describe()}"}
        return sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let{ it to params.position.getIndexIn(it.content)}
            ?.let { (file, cursor) ->
                getCompletions(file, cursor, sourceFiles.index)
                    .also { log.fine("Found ${it.items.size} items") }
            } ?: CompletionList(emptyList())
    }

    override suspend fun resolveCompletionItem(unresolved: CompletionItem): CompletionItem {
        log.info{"resolveCompletionItem"}
        TODO()
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        params.textDocument.run {
            sourceFiles.applyManualEdit(typedUri, version, text)
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        lint(params.textDocument.typedUri)
    }

    override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp? {
        log.info{"Signature help at ${params.describe()}"}
        return sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let{ it to params.position.getIndexIn(it.content)}
            ?.let{ (file, cursor)->
                file.ktFile.findElementAt(cursor)
                    ?.findParent<KtCallExpression>()
                    ?.let { call ->
                        getCandidates(call, file)
                            ?.let { candidates->
                                SignatureHelp(
                                    candidates.map{desc->
                                        SignatureInformation(
                                            desc.describeAlt(),
                                            extractDocstring(desc),
                                            desc.valueParameters.map {
                                                ParameterInformation(it.describeParameter(), extractDocstring(it))
                                            })
                                    },
                                    activeDeclaration(call, candidates),
                                    activeParameter(call, cursor))
                            }
                    }
                    .also {
                        if(it == null) log.info{"No function call around ${file.describePosition(cursor)}"}
                    }
            }
    }

    private fun activeDeclaration(call: KtCallExpression, candidates: List<CallableDescriptor>): Int =
        candidates
            .indexOfFirst{ candidate->
                val nArguments = call.valueArgumentList
                    ?.let { it.text.count { it == ',' } + 1 }
                    ?: return@indexOfFirst true
                if (nArguments > candidate.valueParameters.size)
                    return@indexOfFirst false
                call.valueArguments
                    .filter { it.isNamed() }
                    .none { arg ->
                        candidate.valueParameters
                            .none { arg.name == it.name.identifier }
                    }
            }

    private fun activeParameter(call: KtCallExpression, cursor: Int): Int =
        call.valueArgumentList
            .let { it ?: return -1 }
            .also { if (it.text.length == 2) return 0 }
            .let { it.text to it.textRange.startOffset }
            .let { (text, startOffset) ->
                text.subSequence(0, kotlin.math.abs(startOffset - cursor))
            }
            .count { it == ','}

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.typedUri
        sourceFiles.closeTransient(uri)
        client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), listOf()))
    }

    override suspend fun formatting(params: DocumentFormattingParams): List<TextEdit> {
        val code = sourceFiles.content(params.textDocument.typedUri)
        log.info{"Formatting ${params.textDocument.uri}"}
        return listOf(TextEdit(
            Range(Position(0, 0), indexToPosition(code, code.length)),
            formatKotlinCode(code, params.options)
        ))
    }

    override suspend fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit> {
        val code = params.range.extractRange(
            sourceFiles.content(params.textDocument.typedUri))
        return listOf(TextEdit(
            params.range,
            formatKotlinCode(code, params.options)
        ))
    }

    private fun formatKotlinCode(
        code: String,
        options: FormattingOptions = FormattingOptions(4, true)
    ): String = Formatter.format(KtfmtOptions(
        style = KtfmtOptions.Style.GOOGLE,
        blockIndent = options.tabSize,
        continuationIndent = 2 * options.tabSize
    ), code)

    override fun didChange(params: DidChangeTextDocumentParams) {
        sourceFiles.applyManualEdit(
            params.textDocument.typedUri,
            params.textDocument.version,
            params.contentChanges)
    }

    override suspend fun references(params: ReferenceParams): List<Location> {
         val uri = params.textDocument.typedUri
         return uri.filePath
            ?.let { file ->
                findReferences(file, params.position.getIndexIn(sourceFiles.content(uri)), sourceFiles)
            } ?: listOf<Location>()
    }

    override suspend fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
        log.info{"Full semantic tokens in ${params.textDocument.uri}"}
        return sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let{encodedSemanticTokens(it.ktFile, it.context)}
            ?.also{ log.info("Found ${it.size} tokens")}
            .let{ SemanticTokens(it ?: emptyList()) }
    }

    override suspend fun semanticTokensRange(params: SemanticTokensRangeParams): SemanticTokens {
        log.info{"Ranged semantic tokens in ${params.textDocument.uri}"}
        return sourceFiles.compileFile(params.textDocument.typedUri)
            ?.let{encodedSemanticTokens(it.ktFile, it.context, params.range)}
            ?.also{ log.info("Found ${it.size} tokens")}
            .let{ SemanticTokens(it ?: emptyList()) }
    }

    override suspend fun resolveCodeLens(unresolved: CodeLens): CodeLens = CodeLens()

    private fun lint(file: URI) {
        log.info{"Linting for ${file}"}
        sourceFiles.compileFile(file)
            ?.context
            ?.diagnostics
            ?.flatMap{ diagnostic->
                val content = diagnostic.psiFile.text
                diagnostic.textRanges
                    .map {
                        Diagnostic(
                            it.toLsp4jRange(content),
                            DefaultErrorMessages.render(diagnostic),
                            when (diagnostic.severity) {
                                Severity.INFO -> DiagnosticSeverity.Information
                                Severity.ERROR -> DiagnosticSeverity.Error
                                Severity.WARNING -> DiagnosticSeverity.Warning
                            },
                            "kotlin",
                            diagnostic.factory.name
                        ).apply {
                            val factoryName = diagnostic.factory.name
                            tags = mutableListOf<DiagnosticTag>().apply{
                                if ("UNUSED_"     in factoryName)
                                    add(DiagnosticTag.Unnecessary)
                                if ("DEPRECATION" in factoryName)
                                    add(DiagnosticTag.Deprecated)
                            }
                        }
                    }
            }
            ?.also{ log.info{"Reported ${it.size} diagnostics in ${file}"} }
            ?.also{ client.publishDiagnostics(PublishDiagnosticsParams(file.toString(), it)) }
    }
}
