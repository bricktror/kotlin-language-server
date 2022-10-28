package org.kotlinlsp

import arrow.core.Either
import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.FormattingOptions as KtfmtOptions
import com.google.common.cache.CacheBuilder
import com.intellij.lang.Language
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiType
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.*
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Diagnostic as LangServerDiagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InsertTextFormat.PlainText
import org.eclipse.lsp4j.InsertTextFormat.Snippet
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either as Lsp4jEither
import org.eclipse.lsp4j.services.LanguageClient
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SourceFile as XSourceFile
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.diagnostics.Diagnostic as KotlinDiagnostic
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
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtVariableDeclaration
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.isAbstract
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi.psiUtil.unwrapNullability
import org.jetbrains.kotlin.renderer.ClassifierNamePolicy
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.renderer.ParameterNameRenderingPolicy
import org.jetbrains.kotlin.renderer.RenderingFormat
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.UnresolvedType
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection
import org.jetbrains.kotlin.types.typeUtil.replaceArgumentsWithStarProjections
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.kotlinlsp.CompilationKind
import org.kotlinlsp.Compiler
import org.kotlinlsp.createFunctionStub
import org.kotlinlsp.createVariableStub
import org.kotlinlsp.findReferences
import org.kotlinlsp.findReferencesToDeclarationInFile
import org.kotlinlsp.getClassDescriptor
import org.kotlinlsp.getDeclarationPadding
import org.kotlinlsp.getNewMembersStartPosition
import org.kotlinlsp.getSuperClassTypeProjections
import org.kotlinlsp.hasNoBody
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.index.receiverTypeFqn
import org.kotlinlsp.logging.*
import org.kotlinlsp.logging.findLogger
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.overridesDeclaration
import org.kotlinlsp.referenceAt
import org.kotlinlsp.file.FileProvider
import org.kotlinlsp.source.SourceFile
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.file.TemporaryDirectory
import org.kotlinlsp.util.arrow.*
import org.kotlinlsp.util.containsCharactersInOrder
import org.kotlinlsp.util.extractRange
import org.kotlinlsp.util.fileExtension
import org.kotlinlsp.util.fileName
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.findParent
import org.kotlinlsp.util.getIndexIn
import org.kotlinlsp.util.indexToPosition
import org.kotlinlsp.util.isSubrangeOf
import org.kotlinlsp.util.isZero
import org.kotlinlsp.util.locationInFile
import org.kotlinlsp.util.onEachIndexed
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.util.preOrderTraversal
import org.kotlinlsp.util.stringDistance
import org.kotlinlsp.util.toLsp4jRange
import org.kotlinlsp.util.toPath

private val log by findLogger.atToplevel(object{})

class KotlinTextDocumentService(
    private val sp: SourceFileRepository,
    private val tempDirectory: TemporaryDirectory,
    private val fileProvider: FileProvider,
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

    private fun recover(uriString: String, position: Position): Pair<SourceFile.Compiled, Int> =
        sp.compileFile(parseURI(uriString)).let{
            it to position.getIndexIn(it.content)
        }

    override suspend fun codeAction(params: CodeActionParams): List<Either<Command, CodeAction>> =
        codeActions(
            recover(params.textDocument.uri, params.range.start).first,
            sp.index,
            params.range,
            params.context)

    override suspend fun hover(position: HoverParams): Hover? {
        log.info{"Hovering at ${position.describePosition()}"}
        return recover(position)
            .let{ (file, cursor)-> hoverAt(file, cursor) }
            ?: run {log.info{"No hover found at ${position.describePosition()}"}; null}
    }

    override suspend fun documentHighlight(position: DocumentHighlightParams): List<DocumentHighlight> {
        val (file, cursor) = recover(position)
        return documentHighlightsAt(file, cursor)
    }
    fun documentHighlightsAt(file: SourceFile.Compiled, cursor: Int): List<DocumentHighlight> {
        val (declaration, declarationLocation) = file.findDeclaration(cursor)
            ?: return emptyList()
        val references = findReferencesToDeclarationInFile(declaration, file)

        return if (declaration.isInFile(file.ktFile)) {
            listOf(DocumentHighlight(declarationLocation.range, DocumentHighlightKind.Text))
        } else {
            emptyList()
        } + references.map { DocumentHighlight(it, DocumentHighlightKind.Text) }
    }

    private fun KtNamedDeclaration.isInFile(file: KtFile) = this.containingFile == file

    override suspend fun onTypeFormatting(params: DocumentOnTypeFormattingParams): List<TextEdit> {
        TODO()
    }

    override suspend fun definition(position: DefinitionParams): Either<List<Location>, List<LocationLink>> {
        log.info{"Go-to-definition at ${position.describePosition()}"}
        val (file, cursor) = recover(position)
        return goToDefinition(file, cursor, fileProvider, tempDirectory)
            ?.let(::listOf)
            ?.let { Either.Left(it) }
            ?: run{log.info{"Couldn't find definition at ${position.describePosition()}"}; Either.Left(emptyList())}
    }

    override suspend fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit> {
        val code = params.range.extractRange(params.textDocument.content)
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
    fun renameSymbol(file: SourceFile.Compiled, cursor: Int, sp: SourceFileRepository, newName: String): WorkspaceEdit? {
        val (declaration, location) = file.findDeclaration(cursor) ?: return null
        return declaration.let {
            val declarationEdit = Lsp4jEither.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(
                VersionedTextDocumentIdentifier().apply { uri = location.uri },
                listOf(TextEdit(location.range, newName))
            ))

            val referenceEdits = findReferences(declaration, sp).map {
                Lsp4jEither.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(
                    VersionedTextDocumentIdentifier().apply { uri = it.uri },
                    listOf(TextEdit(it.range, newName))
                ))
            }

            WorkspaceEdit(listOf(declarationEdit) + referenceEdits)
        }
    }

    override suspend fun completion(position: CompletionParams):Either<List<CompletionItem>, CompletionList> {
        log.info{"Completing at ${position.describePosition()}"}
        val (file, cursor) = recover(position) // TODO: Investigate when to recompile
        val completions = completions(file, cursor, sp.index, true)
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
        log.info{"Signature help at ${position.describePosition()}"}
        val (file, cursor) = recover(position)
        return file.parseAtPoint(cursor)
            ?.findParent<KtCallExpression>()
            ?.let { call ->
                candidates(call, file)
                    .let { candidates->
                        SignatureHelp(
                            candidates.map(::toSignature),
                            activeDeclaration(call, candidates),
                            activeParameter(call, cursor))
                    }
            }
            .also {
                if(it == null) log.info{"No call around ${file.describePosition(cursor)}"}
            }
            ?: run{log.info{"No function call around ${position.describePosition()}"}; null}
    }

    private fun activeDeclaration(call: KtCallExpression, candidates: List<CallableDescriptor>): Int =
        candidates
            .indexOfFirst{ isCompatibleWith(call, it) }

    private fun isCompatibleWith(call: KtCallExpression, candidate: CallableDescriptor): Boolean {
        val nArguments = call.valueArgumentList
            .let { it ?: return true }
            .let { it.text.count { it == ',' } + 1 }
        if (nArguments > candidate.valueParameters.size)
            return false
        return call.valueArguments
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
        val uri = parseURI(params.textDocument.uri)
        sp.closeTransient(uri)
        clearDiagnostics(uri)
    }

    override suspend fun formatting(params: DocumentFormattingParams): List<TextEdit> {
        val code = params.textDocument.content
        log.info{"Formatting ${params.textDocument.uri}"}
        return listOf(TextEdit(
            Range(Position(0, 0), indexToPosition(code, code.length)),
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
                val offset = position.position.getIndexIn(content)
                findReferences(file, offset, sp)
            } ?: listOf<Location>()

    override suspend fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
        log.info{"Full semantic tokens in ${params.textDocument.uri}"}
        val file = sp.compileFile(parseURI(params.textDocument.uri))
        val tokens = encodedSemanticTokens(file)
        log.info("Found ${tokens.size} tokens")
        return SemanticTokens(tokens)
    }

    override suspend fun semanticTokensRange(params: SemanticTokensRangeParams): SemanticTokens {
        log.info{"Ranged semantic tokens in ${params.textDocument.uri}"}

        val file = sp.compileFile(parseURI(params.textDocument.uri))

        val tokens = encodedSemanticTokens(file, params.range)
        log.info("Found ${tokens.size} tokens")

        return SemanticTokens(tokens)
    }

    override suspend fun resolveCodeLens(unresolved: CodeLens): CodeLens {
        TODO()
    }

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
        log.info{"Linting ${lintTodo}"}
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

private fun TextDocumentPositionParams. describePosition(): String =
    "${textDocument.uri} ${position.line + 1}:${position.character + 1}"

/**
 * Find the declaration of the element at the cursor.
 */
private fun SourceFile.Compiled.findDeclaration(cursor: Int): Pair<KtNamedDeclaration, Location>? =
    findDeclarationReference(cursor)
        ?: findDeclarationCursorSite(cursor)
/**
 * Find the declaration of the element at the cursor. Only works if the element at the cursor is a reference.
 */
private fun SourceFile.Compiled.findDeclarationReference(cursor: Int): Pair<KtNamedDeclaration, Location>? =
    referenceAtPoint(cursor)
        ?.second
        ?.findPsi()
        ?.let { it as? KtNamedDeclaration }
        ?.let { psi ->
            psi.nameIdentifier
                ?.let { it.locationInFile() }
                ?.let { location -> Pair(psi, location) }
        }

/**
 * Find the declaration of the element at the cursor.
 * Works even in cases where the element at the cursor might not be a reference, so works for declaration sites.
 */
private fun  SourceFile.Compiled.findDeclarationCursorSite(cursor: Int): Pair<KtNamedDeclaration, Location>? =
    elementAtPoint(cursor)
        ?.findParent<KtNamedDeclaration>()
        ?.let {
            it to Location(
                    it.containingFile.name,
                    toLsp4jRange(content, it.nameIdentifier?.textRange ?: return null))
        }

private fun hoverAt(file: SourceFile.Compiled, cursor: Int): Hover? {
    val (ref, target) = file.referenceAtPoint(cursor)
        ?: return typeHoverAt(file, cursor)
    val location = ref.textRange
    val hoverText = DECL_RENDERER.render(target)
    return Hover(
        markup(hoverText, getDocString(file, cursor)),
        Range(
            indexToPosition(file.content, location.startOffset),
            indexToPosition(file.content, location.endOffset)))
}

private fun markup(hoverText: String, body: String?) =
    MarkupContent(
        "markdown",
        "```kotlin\n$hoverText\n```".let{
            if (body.isNullOrBlank()) it
            else "${it}\n---\n${body}"
        })

/**
 * Returns the doc string of the first found CallableDescriptor
 *
 * Avoids fetching the SignatureHelp triplet due to an OutOfBoundsException that can occur due to the offset difference math.
 * When hovering, the cursor param is set to the doc offset where the mouse is hovering over, rather than where the actual cursor is,
 * hence this is seen to cause issues when slicing the param list string
 */
private fun getDocString(file: SourceFile.Compiled, cursor: Int): String =
    file
        .parseAtPoint(cursor)
        ?.findParent<KtCallExpression>()
        ?.let { candidates(it, file) }
        ?.map(::toSignature)
        ?.let { it.firstOrNull() }
        ?.let { it.documentation }
        ?.takeIf { it.isLeft() }
        ?.let { it.left }
        ?: ""

private fun typeHoverAt(file: SourceFile.Compiled, cursor: Int): Hover? =
    file.parseAtPoint(cursor)
        ?.findParent<KtExpression>()
        ?.let { expression ->
            val hoverText = file.scopeAtPoint(cursor)
                ?.let { file.bindingContextOf(expression, it) }
                ?.let { renderTypeOf(expression, it) }
                ?: return null
            val javaDoc = expression
                .children
                .mapNotNull{ (it as? PsiDocCommentBase)?.text }
                .firstOrNull()
            Hover(markup(hoverText, javaDoc))
        }

// Source: https://github.com/JetBrains/kotlin/blob/master/idea/src/org/jetbrains/kotlin/idea/codeInsight/KotlinExpressionTypeProvider.kt
private val typeRenderer: DescriptorRenderer by lazy {
    /* DescriptorRenderer.COMPACT.withOptions { */
    DescriptorRenderer.FQ_NAMES_IN_TYPES.withOptions {
        textFormat = RenderingFormat.PLAIN
        classifierNamePolicy = object: ClassifierNamePolicy {
            override fun renderClassifier(classifier: ClassifierDescriptor, renderer: DescriptorRenderer): String {
                if (DescriptorUtils.isAnonymousObject(classifier)) {
                    return "<anonymous object>"
                }
                return ClassifierNamePolicy.SHORT.renderClassifier(classifier, renderer)
            }
        }
    }
}

private fun renderTypeOf(element: KtExpression, context: BindingContext): String? {
    if (element is KtCallableDeclaration) {
        val descriptor = context[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
            .let {it as? CallableDescriptor}
        if (descriptor != null) {
            return descriptor.returnType?.let(typeRenderer::renderType)
        }
    }
    val result = context[BindingContext.EXPRESSION_TYPE_INFO, element]
        ?.type
        ?.let{typeRenderer.renderType(it)}
        ?: return null

    val smartCast = context[BindingContext.SMARTCAST, element]
    if (smartCast != null && element is KtReferenceExpression) {
        return context[BindingContext.REFERENCE_TARGET, element]
            .let{it as? CallableDescriptor}
            ?.returnType
            ?.let{typeRenderer.renderType(it)}
            ?.let{"${result} (smart cast from ${it})"}
            ?: result
    }
    return result
}
private fun convertDiagnostic(diagnostic: KotlinDiagnostic): List<Pair<URI, LangServerDiagnostic>> {
    val uri = diagnostic.psiFile.toPath().toUri()
    val content = diagnostic.psiFile.text

    return diagnostic.textRanges.map {
        val d = LangServerDiagnostic(
            toLsp4jRange(content, it),
            message(diagnostic),
            severity(diagnostic.severity),
            "kotlin",
            code(diagnostic)
        ).apply {
            val factoryName = diagnostic.factory.name
            tags = mutableListOf<DiagnosticTag>()

            if ("UNUSED_"     in factoryName) tags.add(DiagnosticTag.Unnecessary)
            if ("DEPRECATION" in factoryName) tags.add(DiagnosticTag.Deprecated)
        }
        Pair(uri, d)
    }
}

private fun code(diagnostic: KotlinDiagnostic) =
        diagnostic.factory.name

private fun message(diagnostic: KotlinDiagnostic) =
        DefaultErrorMessages.render(diagnostic)

private fun severity(severity: Severity): DiagnosticSeverity =
        when (severity) {
            Severity.INFO -> DiagnosticSeverity.Information
            Severity.ERROR -> DiagnosticSeverity.Error
            Severity.WARNING -> DiagnosticSeverity.Warning
        }

private fun formatKotlinCode(
    code: String,
    options: FormattingOptions = FormattingOptions(4, true)
): String = Formatter.format(KtfmtOptions(
    style = KtfmtOptions.Style.GOOGLE,
    blockIndent = options.tabSize,
    continuationIndent = 2 * options.tabSize
), code)
/* private val definitionPattern = "(?:class|interface|object|fun)\\s+(\\w+)".toRegex() */

private fun goToDefinition(
    file: SourceFile.Compiled,
    cursor: Int,
    classContentProvider: FileProvider,
    tempDir: TemporaryDirectory,
): Location? {
    TODO()
    /* val (_, target) = file.context.referenceAt(cursor) ?: return null */

    /* log.info("Found declaration descriptor ${target}") */
    /* var destination = location(target) */
    /* val psi = target.findPsi() */

    /* if (psi is KtNamedDeclaration) { */
    /*     destination = psi.nameIdentifier?.let(::location) ?: destination */
    /* } */

    /* if(destination==null) return null */

    /* val rawClassURI = destination.uri */

    /* if (!isInsideArchive(rawClassURI, classPath)) return null */
    /* parseURI(rawClassURI) */
    /*     .let { classContentProvider.read(it) } */
    /*     ?.let { (klsSourceURI, content) -> */

    /*         // Return the path to a temporary file */
    /*         // since the client has not opted into */
    /*         // or does not support KLS URIs */
    /*         val name = klsSourceURI.fileName.partitionAroundLast(".").first */
    /*         val extensionWithoutDot = klsSourceURI.fileExtension */
    /*         val extension = if (extensionWithoutDot != null) ".$extensionWithoutDot" else "" */
    /*         val tmpFile=tempDir.createTempFile(name, extension) */
    /*             .also { it.toFile().writeText(content) } */

    /*         destination.uri = tmpFile.toUri().toString() */

    /*         if (destination.range.isZero) { */
    /*             // Try to find the definition inside the source directly */
    /*             val name = when (target) { */
    /*                 is ConstructorDescriptor -> target.constructedClass.name.toString() */
    /*                 else -> target.name.toString() */
    /*             } */
    /*             definitionPattern.findAll(content) */
    /*                 .map { it.groups[1]!! } */
    /*                 .find { it.value == name } */
    /*                 ?.let { it.range } */
    /*                 ?.let { destination.range = Range(indexToPosition(content, it.first), indexToPosition(content, it.last)) } */
    /*         } */
    /*     } */

    /* return destination */
}

    /* val javaHome: String? = System.getProperty("java.home", null) */
/* private fun isInsideArchive(uri: String, classPath: CompilerClassPath) = */
/*     uri.contains(".jar!") || uri.contains(".zip!") || classPath.javaHome?.let { */
/*         Paths.get(parseURI(uri)).toString().startsWith(File(it).path) */
/*     } ?: false */

private fun toSignature(desc: CallableDescriptor) =
    SignatureInformation(
        DECL_RENDERER.render(desc),
        docstring(desc),
        desc.valueParameters.map {
            ParameterInformation(
                DECL_RENDERER.renderValueParameters(listOf(it), false)
                    .let { it.substring(1, it.length-1) /* Remove parens */ },
                docstring(it))
        })

private fun docstring(declaration: DeclarationDescriptorWithSource): String =
    when(val source = DescriptorToSourceUtils.descriptorToDeclaration(declaration)?.navigationElement) {
        is KtParameter -> source
            .parents
            .filterIsInstance<KtDeclaration>()
            .firstOrNull()
            ?.let {
                if (it is KtPrimaryConstructor)
                    it.parents.filterIsInstance<KtDeclaration>().firstOrNull()
                else it
            }
            ?.docComment
            ?.preOrderTraversal()
            ?.filterIsInstance<KDocTag>()
            ?.filter { it.knownTag == KDocKnownTag.PARAM }
            ?.filter { it.getSubjectName() == declaration.name.toString() }
            ?.firstOrNull()
        is KtPrimaryConstructor -> source
            .parents
            .filterIsInstance<KtDeclaration>()
            .firstOrNull()
            ?.docComment
            ?.let {
                it.findSectionByTag(KDocKnownTag.CONSTRUCTOR)
                    ?: it.getDefaultSection()
            }
        is KtDeclaration -> source
            .docComment
            ?.getDefaultSection()
        else -> null
    }
    ?.getContent()
    ?.trim()
    ?: ""

private fun candidates(call: KtCallExpression, file: SourceFile.Compiled): List<CallableDescriptor> =
    call.calleeExpression
        ?.let { target ->
            target
                .findParent<KtDotQualifiedExpression>()
                ?.let { dotParent->
                    file
                        .typeAtPoint(dotParent.receiverExpression.startOffset)
                        ?.let { memberOverloads(it, target.text).toList() }
                } ?: run {
                    target
                        .findParent<KtNameReferenceExpression>()
                        ?.let { file.scopeAtPoint(it.startOffset) }
                        ?.let { identifierOverloads(it, target.text).toList() }
                }
        }
        ?: emptyList()

private fun SourceFile.Compiled.typeAtPoint(cursor: Int): KotlinType? {
    fun expandForType( surroundingExpr: KtExpression): KtExpression =
        surroundingExpr
            .parent
            .let { it as? KtDotQualifiedExpression }
            .let { dotParent ->
                dotParent
                    ?.selectorExpression
                    ?.textRange
                    ?.takeIf{it.contains(cursor)}
                    ?.let { expandForType( dotParent) }
            } ?: surroundingExpr
    val surroundingExpr = parseAtPoint(cursor, asReference = true)
        ?.findParent<KtExpression>()
        .let {it ?: run {
            log.info{"Couldn't find expression at ${describePosition(cursor)}"}
            return null
        } }
        .let { expandForType(it) }
    val scope = scopeAtPoint(cursor)
        ?: run {
            log.info {"Couldn't find scope at ${describePosition(cursor)}"}
            return null
        }
    return bindingContextOf(surroundingExpr, scope)
        .getType(surroundingExpr)
}

private fun findReferences(file: Path, cursor: Int, sp: SourceFileRepository): List<Location> =
    doFindReferences(file, cursor, sp)
            .map { it.locationInFile() }
            .filterNotNull()
            .toList()
            .sortedWith(compareBy({ it.getUri() }, { it.getRange().getStart().getLine() }))

private fun findReferences(declaration: KtNamedDeclaration, sp: SourceFileRepository): List<Location> =
    doFindReferences(declaration, sp)
        .map { it.locationInFile() }
        .filterNotNull()
        .toList()
        .sortedWith(compareBy({ it.getUri() }, { it.getRange().getStart().getLine() }))

/**
 * Finds references to the named declaration in the given file. The declaration may or may not reside in another file.
 *
 * @returns ranges of references in the file. Empty list if none are found
 */
private fun findReferencesToDeclarationInFile(declaration: KtNamedDeclaration, file: SourceFile.Compiled): List<Range> {
    val descriptor = file.context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
        ?: return run{log.info{"Declaration ${declaration.fqName} has no descriptor"}; emptyList()}
    val bindingContext = file.context

    val references = when {
        isComponent(descriptor) -> findComponentReferences(declaration, bindingContext) + findNameReferences(declaration, bindingContext)
        isIterator(descriptor) -> findIteratorReferences(declaration, bindingContext) + findNameReferences(declaration, bindingContext)
        isPropertyDelegate(descriptor) -> findDelegateReferences(declaration, bindingContext) + findNameReferences(declaration, bindingContext)
        else -> findNameReferences(declaration, bindingContext)
    }

    return references
        .mapNotNull { it.locationInFile()?.range }
        .sortedWith(compareBy({ it.start.line }))
}

private fun hasPropertyDelegate(source: KtFile): Boolean =
        source.preOrderTraversal().filterIsInstance<KtPropertyDelegate>().any()

private fun doFindReferences(file: Path, cursor: Int, sp: SourceFileRepository): Collection<KtElement> {
    val recover = sp.compileFile(file.toUri())
    val element = recover.elementAtPoint(cursor)
        ?.findParent<KtNamedDeclaration>()
        ?: run {
            log.info{"No declaration at ${recover.describePosition(cursor)}"}
            return emptyList()
        }
    return doFindReferences(element, sp)
}

private fun doFindReferences(element: KtNamedDeclaration, sp: SourceFileRepository): Collection<KtElement> {
    val declaration = sp.compileFile(element.containingFile.toPath().toUri())
        .context[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
        ?: return run{log.info{"Declaration ${element.fqName} has no descriptor"}; emptyList()}
    val recompile = possibleReferences(declaration, sp)
        .map { it.toPath().toUri() }
        .also { log.debug("Scanning ${it.size} files for references to ${element.fqName}") }
        .let{ sp.compileFiles(it) }
        .let { CompositeBindingContext.create(it.map{it.context})}

    return when {
        isComponent(declaration) -> findComponentReferences(element, recompile) + findNameReferences(element, recompile)
        isIterator(declaration) -> findIteratorReferences(element, recompile) + findNameReferences(element, recompile)
        isPropertyDelegate(declaration) -> findDelegateReferences(element, recompile) + findNameReferences(element, recompile)
        else -> findNameReferences(element, recompile)
    }
}

private fun findNameReferences(
    element: KtNamedDeclaration,
    recompile: BindingContext
): List<KtReferenceExpression> = recompile
    .getSliceContents(BindingContext.REFERENCE_TARGET)
    .filter { matchesReference(it.value, element) }
    .map { it.key }

private fun findDelegateReferences(
    element: KtNamedDeclaration,
    recompile: BindingContext
): List<KtElement> = recompile
    .getSliceContents(BindingContext.DELEGATED_PROPERTY_RESOLVED_CALL)
    .filter { matchesReference(it.value.candidateDescriptor, element) }
    .map { it.value.call.callElement }

private fun findIteratorReferences(
    element: KtNamedDeclaration,
    recompile: BindingContext
): List<KtElement> = recompile
    .getSliceContents(BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL)
    .filter { matchesReference( it.value.candidateDescriptor, element) }
    .map { it.value.call.callElement }

private fun findComponentReferences(
    element: KtNamedDeclaration,
    recompile: BindingContext
): List<KtElement> = recompile
    .getSliceContents(BindingContext.COMPONENT_RESOLVED_CALL)
    .filter { matchesReference(it.value.candidateDescriptor, element) }
    .map { it.value.call.callElement }

// TODO use imports to limit search
private fun possibleReferences(declaration: DeclarationDescriptor, sp: SourceFileRepository): Set<KtFile> {
    if (declaration is ClassConstructorDescriptor) {
        return possibleNameReferences(declaration.constructedClass.name, sp)
    }
    if (isComponent(declaration)) {
        return possibleComponentReferences(sp) + possibleNameReferences(declaration.name, sp)
    }
    if (isPropertyDelegate(declaration)) {
        return hasPropertyDelegates(sp) + possibleNameReferences(declaration.name, sp)
    }
    if (isGetSet(declaration)) {
        return possibleGetSets(sp) + possibleNameReferences(declaration.name, sp)
    }
    if (isIterator(declaration)) {
        return hasForLoops(sp) + possibleNameReferences(declaration.name, sp)
    }
    if (declaration is FunctionDescriptor && declaration.isOperator && declaration.name == OperatorNameConventions.INVOKE) {
        return possibleInvokeReferences(declaration, sp) + possibleNameReferences(declaration.name, sp)
    }
    if (declaration is FunctionDescriptor) {
        val operators = operatorNames(declaration.name)
        return possibleTokenReferences(operators, sp) + possibleNameReferences(declaration.name, sp)
    }
    return possibleNameReferences(declaration.name, sp)
}

private fun isPropertyDelegate(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        (declaration.name == OperatorNameConventions.GET_VALUE
         || declaration.name == OperatorNameConventions.SET_VALUE)

private fun hasPropertyDelegates(sp: SourceFileRepository): Set<KtFile> =
        sp.allFiles().filter(::hasPropertyDelegate).toSet()

private fun isIterator(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        declaration.name == OperatorNameConventions.ITERATOR

private fun hasForLoops(sp: SourceFileRepository): Set<KtFile> =
        sp.allFiles().filter(::hasForLoop).toSet()

private fun hasForLoop(source: KtFile): Boolean =
        source.preOrderTraversal().filterIsInstance<KtForExpression>().any()

private fun isGetSet(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        (declaration.name == OperatorNameConventions.GET || declaration.name == OperatorNameConventions.SET)

private fun possibleGetSets(sp: SourceFileRepository): Set<KtFile> =
        sp.allFiles().filter(::possibleGetSet).toSet()

private fun possibleGetSet(source: KtFile) =
        source.preOrderTraversal().filterIsInstance<KtArrayAccessExpression>().any()

private fun possibleInvokeReferences(declaration: FunctionDescriptor, sp: SourceFileRepository) =
        sp.allFiles().filter { possibleInvokeReference( it) }.toSet()

// TODO this is not very selective
private fun possibleInvokeReference(source: KtFile): Boolean =
        source.preOrderTraversal().filterIsInstance<KtCallExpression>().any()

private fun isComponent(declaration: DeclarationDescriptor): Boolean =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        OperatorNameConventions.COMPONENT_REGEX.matches(declaration.name.identifier)

private fun possibleComponentReferences(sp: SourceFileRepository): Set<KtFile> =
        sp.allFiles().filter { possibleComponentReference(it) }.toSet()

private fun possibleComponentReference(source: KtFile): Boolean =
        source.preOrderTraversal()
                .filterIsInstance<KtDestructuringDeclarationEntry>()
                .any()

private fun possibleTokenReferences(find: List<KtSingleValueToken>, sp: SourceFileRepository): Set<KtFile> =
        sp.allFiles().filter { possibleTokenReference(find, it) }.toSet()

private fun possibleTokenReference(find: List<KtSingleValueToken>, source: KtFile): Boolean =
        source.preOrderTraversal()
                .filterIsInstance<KtOperationReferenceExpression>()
                .any { it.operationSignTokenType in find }

private fun possibleNameReferences(declaration: Name, sp: SourceFileRepository): Set<KtFile> =
        sp.allFiles().filter { possibleNameReference(declaration, it) }.toSet()

private fun possibleNameReference(declaration: Name, source: KtFile): Boolean = source
    .preOrderTraversal()
    .filterIsInstance<KtSimpleNameExpression>()
    .any { it.getReferencedNameAsName() == declaration }

private fun matchesReference(found: DeclarationDescriptor, search: KtNamedDeclaration) =
    if (found is ConstructorDescriptor && found.isPrimary)
        search is KtClass && found.constructedClass.fqNameSafe == search.fqName
    else
        found.findPsi() == search

private fun operatorNames(name: Name): List<KtSingleValueToken> =
    when (name) {
        OperatorNameConventions.EQUALS -> listOf(KtTokens.EQEQ)
        OperatorNameConventions.COMPARE_TO -> listOf(KtTokens.GT, KtTokens.LT, KtTokens.LTEQ, KtTokens.GTEQ)
        else ->
            listOfNotNull(
             OperatorConventions.UNARY_OPERATION_NAMES.inverse()[name]
                ?: OperatorConventions.BINARY_OPERATION_NAMES.inverse()[name]
                ?: OperatorConventions.ASSIGNMENT_OPERATIONS.inverse()[name]
                ?: OperatorConventions.BOOLEAN_OPERATIONS.inverse()[name])
    }

private fun codeActions(
    file: SourceFile.Compiled,
    index: SymbolIndex?,
    range: Range,
    context: CodeActionContext
): List<Either<Command, CodeAction>> {
    // context.only does not work when client is emacs...
    val requestedKinds = context.only ?: listOf(CodeActionKind.Refactor, CodeActionKind.QuickFix)
    return requestedKinds.map {
        when (it) {
            CodeActionKind.Refactor -> getRefactors(file, range)
            CodeActionKind.QuickFix -> getQuickFixes(file, index, range, context.diagnostics)
            else -> listOf()
        }
    }.flatten()
}

private fun getRefactors(file: SourceFile.Compiled, range: Range): List<Either<Command, CodeAction>> {
    val hasSelection = (range.end.line - range.start.line) != 0 || (range.end.character - range.start.character) != 0
    return if (hasSelection) {
        listOf(
            Either.Left(
                Command("Convert Java to Kotlin", "convertJavaToKotlin", listOf(
                    file.ktFile.toPath().toUri().toString(),
                    range
                ))
            )
        )
    } else {
        emptyList()
    }
}

private fun getQuickFixes(
    file: SourceFile.Compiled,
    index: SymbolIndex?,
    range: Range,
    diagnostics: List<Diagnostic>
): List<Either<Command, CodeAction>> =
    listOf(
        ImplementAbstractMembersQuickFix(),
        AddMissingImportsQuickFix()
    )
    .flatMap {
        it.compute(file, index, range, diagnostics)
    }

private fun interface QuickFix {
    // Computes the quickfix. Return empty list if the quickfix is not valid or no alternatives exist.
    fun compute(file: SourceFile.Compiled, index: SymbolIndex?, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>>
}

private fun diagnosticMatch(diagnostic: Diagnostic, range: Range, diagnosticTypes: Set<String>): Boolean =
    range.isSubrangeOf(diagnostic.range) && diagnosticTypes.contains(diagnostic.code.left)

private fun diagnosticMatch(diagnostic: KotlinDiagnostic, startCursor: Int, endCursor: Int, diagnosticTypes: Set<String>): Boolean =
    diagnostic.textRanges.any { it.startOffset <= startCursor && it.endOffset >= endCursor } && diagnosticTypes.contains(diagnostic.factory.name)

private fun findDiagnosticMatch(diagnostics: List<Diagnostic>, range: Range, diagnosticTypes: Set<String>) =
    diagnostics.find { diagnosticMatch(it, range, diagnosticTypes) }

private fun anyDiagnosticMatch(diagnostics: Diagnostics, startCursor: Int, endCursor: Int, diagnosticTypes: Set<String>) =
    diagnostics.any { diagnosticMatch(it, startCursor, endCursor, diagnosticTypes) }

private class AddMissingImportsQuickFix: QuickFix {
    override fun compute(file: SourceFile.Compiled, index: SymbolIndex?, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>> {
        val uri = file.ktFile.toPath().toUri().toString()
        val unresolvedReferences = getUnresolvedReferencesFromDiagnostics(diagnostics)

        return unresolvedReferences.flatMap { diagnostic ->
            val diagnosticRange = diagnostic.range
            val startCursor = diagnosticRange.start.getIndexIn(file.content)
            val endCursor = diagnosticRange.end.getIndexIn(file.content)
            val symbolName = file.content.substring(startCursor, endCursor)

            getImportAlternatives(symbolName, file.ktFile, index).map { (importStr, edit) ->
                val codeAction = CodeAction()
                codeAction.title = "Import ${importStr}"
                codeAction.kind = CodeActionKind.QuickFix
                codeAction.diagnostics = listOf(diagnostic)
                codeAction.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))

                Either.Right(codeAction)
            }
        }
    }

    private fun getUnresolvedReferencesFromDiagnostics(diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.filter {
            "UNRESOLVED_REFERENCE" == it.code.left.trim()
        }

    private fun getImportAlternatives(symbolName: String, file: KtFile, index: SymbolIndex?): List<Pair<String, TextEdit>> {
        return index
            ?.query(symbolName, exact = true)
            ?.filter {
                it.kind != Symbol.Kind.MODULE &&
                // TODO: Visibility checker should be less liberal
                (it.visibility == Symbol.Visibility.PUBLIC
                 || it.visibility == Symbol.Visibility.PROTECTED
                 || it.visibility == Symbol.Visibility.INTERNAL)
            }
            ?.map { Pair(it.fqName.toString(), getImportTextEditEntry(file, it.fqName)) }
            ?: listOf()
    }
}
private class ImplementAbstractMembersQuickFix : QuickFix {
    override fun compute(file: SourceFile.Compiled, index: SymbolIndex?, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>> {
        val diagnostic = findDiagnosticMatch(diagnostics, range)

        val startCursor = range.start.getIndexIn(file.content)
        val endCursor = range.end.getIndexIn(file.content)
        val kotlinDiagnostics = file.context.diagnostics

        // If the client side and the server side diagnostics contain a valid diagnostic for this range.
        if (diagnostic != null && anyDiagnosticMatch(kotlinDiagnostics, startCursor, endCursor)) {
            // Get the class with the missing members
            val kotlinClass = file.parseAtPoint(startCursor)
            if (kotlinClass is KtClass) {
                // Get the functions that need to be implemented
                val membersToImplement = getAbstractMembersStubs(file, kotlinClass)

                val uri = file.ktFile.toPath().toUri().toString()
                // Get the padding to be introduced before the member declarations
                val padding = getDeclarationPadding(file, kotlinClass)

                // Get the location where the new code will be placed
                val newMembersStartPosition = getNewMembersStartPosition(file, kotlinClass)
                val bodyAppendBeginning = listOf(TextEdit(Range(newMembersStartPosition, newMembersStartPosition), "{")).takeIf { kotlinClass.hasNoBody() } ?: emptyList()
                val bodyAppendEnd = listOf(TextEdit(Range(newMembersStartPosition, newMembersStartPosition), System.lineSeparator() + "}")).takeIf { kotlinClass.hasNoBody() } ?: emptyList()

                val textEdits = bodyAppendBeginning + membersToImplement.map {
                    // We leave two new lines before the member is inserted
                    val newText = System.lineSeparator() + System.lineSeparator() + padding + it
                    TextEdit(Range(newMembersStartPosition, newMembersStartPosition), newText)
                } + bodyAppendEnd

                val codeAction = CodeAction()
                codeAction.edit = WorkspaceEdit(mapOf(uri to textEdits))
                codeAction.kind = CodeActionKind.QuickFix
                codeAction.title = "Implement abstract members"
                codeAction.diagnostics = listOf(diagnostic)
                return listOf(Either.Right(codeAction))
            }
        }
        return listOf()
    }
}

private fun findDiagnosticMatch(diagnostics: List<Diagnostic>, range: Range) =
    diagnostics.find { diagnosticMatch(it, range, hashSetOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED")) }

private fun anyDiagnosticMatch(diagnostics: Diagnostics, startCursor: Int, endCursor: Int) =
    diagnostics.any { diagnosticMatch(it, startCursor, endCursor, hashSetOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED")) }

private fun getAbstractMembersStubs(file: SourceFile.Compiled, kotlinClass: KtClass) =
    // For each of the super types used by this class
    kotlinClass.superTypeListEntries.mapNotNull {
        // Find the definition of this super type
        val referenceAtPoint = file.context.referenceAt(it.startOffset)
        val descriptor = referenceAtPoint?.second

        val classDescriptor = getClassDescriptor(descriptor)

        // If the super class is abstract or an interface
        if (null != classDescriptor && (classDescriptor.kind.isInterface || classDescriptor.modality == Modality.ABSTRACT)) {
            val superClassTypeArguments = getSuperClassTypeProjections(file, it)
            classDescriptor.getMemberScope(superClassTypeArguments).getContributedDescriptors().filter { classMember ->
               (classMember is FunctionDescriptor && classMember.modality == Modality.ABSTRACT && !overridesDeclaration(kotlinClass, classMember)) || (classMember is PropertyDescriptor && classMember.modality == Modality.ABSTRACT && !overridesDeclaration(kotlinClass, classMember))
            }.mapNotNull { member ->
                when (member) {
                    is FunctionDescriptor -> createFunctionStub(member)
                    is PropertyDescriptor -> createVariableStub(member)
                    else -> null
                }
            }
        } else {
            null
        }
    }.flatten()

// The maximum number of completion items
private const val MAX_COMPLETION_ITEMS = 75

// The minimum length after which completion lists are sorted
private const val MIN_SORT_LENGTH = 3

/** Finds completions at the specified position. */
private fun completions(file: SourceFile.Compiled, cursor: Int, index: SymbolIndex?, allowSnippets: Boolean): CompletionList {
    val partial = findPartialIdentifier(file, cursor)
    log.debug("Looking for completions that match '${partial}'")

    val (elementItems, element) = elementCompletionItems(file, cursor, allowSnippets, partial)
    val elementItemList = elementItems.toList()
    val elementItemLabels = elementItemList.mapNotNull { it.label }.toSet()

    val isExhaustive = element !is KtNameReferenceExpression
                    && element !is KtTypeElement
                    && element !is KtQualifiedExpression

    val items = sequence {
        yieldAll(elementItemList)
        if (!isExhaustive)
            indexCompletionItems(file, cursor, element, index, partial)
                .filter { it.label !in elementItemLabels }
                .let {yieldAll(it)}
        if (elementItemList.isEmpty())
            keywordCompletionItems(partial)
                .let {yieldAll(it)}
    }
    val itemList = items
        .take(MAX_COMPLETION_ITEMS)
        .toList()
        .onEachIndexed { i, item -> item.sortText = i.toString().padStart(2, '0') }
    val isIncomplete = itemList.size >= MAX_COMPLETION_ITEMS || elementItemList.isEmpty()

    return CompletionList(isIncomplete, itemList)
}

private fun getQueryNameFromExpression(receiver: KtExpression?, cursor: Int, file: SourceFile.Compiled): FqName? =
    receiver
        ?.let { expr ->
            file.scopeAtPoint(cursor)
                ?.let { file.typeOfExpression(expr, it) }
        }
        ?.constructor
        ?.declarationDescriptor
        ?.fqNameSafe


/** Finds completions in the global symbol index, for potentially unimported symbols. */
private fun indexCompletionItems(
    file: SourceFile.Compiled,
    cursor: Int,
    element: KtElement?,
    index: SymbolIndex?,
    partial: String
): Sequence<CompletionItem> {
    val parsedFile = file.ktFile
    val imports = parsedFile.importDirectives
    // TODO: Deal with alias imports
    val wildcardPackages = imports
        .mapNotNull { it.importPath }
        .filter { it.isAllUnder }
        .map { it.fqName }
        .toSet()
    val importedNames = imports
        .mapNotNull { it.importedFqName?.shortName() }
        .toSet()

    val queryName = when (element) {
        is KtQualifiedExpression ->
            getQueryNameFromExpression(element.receiverExpression, element.receiverExpression.startOffset, file)
        is KtSimpleNameExpression ->
            element.getReceiverExpression()
                ?.let{ getQueryNameFromExpression(it, it.startOffset, file) }
        is KtUserType ->
            file.referenceAtPoint(element.qualifier?.startOffset ?: cursor)
                ?.second
                ?.fqNameSafe
        is KtTypeElement ->
            file.referenceAtPoint(element.startOffsetInParent)
                ?.second
                ?.fqNameOrNull()
        else -> null
    }

    return (index
        ?.query(partial, queryName?.asString(), limit = MAX_COMPLETION_ITEMS)
        ?.asSequence()
        ?: sequenceOf())
        .filter { it.kind != Symbol.Kind.MODULE } // Ignore global module/package name completions for now, since they cannot be 'imported'
        .filter { it.fqName.shortName() !in importedNames && it.fqName.parent() !in wildcardPackages }
        .filter {
            // TODO: Visibility checker should be less liberal
               it.visibility == Symbol.Visibility.PUBLIC
            || it.visibility == Symbol.Visibility.PROTECTED
            || it.visibility == Symbol.Visibility.INTERNAL
        }
        .map { CompletionItem().apply {
            label = it.fqName.shortName().toString()
            kind = when (it.kind) {
                Symbol.Kind.CLASS -> CompletionItemKind.Class
                Symbol.Kind.INTERFACE -> CompletionItemKind.Interface
                Symbol.Kind.FUNCTION -> CompletionItemKind.Function
                Symbol.Kind.VARIABLE -> CompletionItemKind.Variable
                Symbol.Kind.MODULE -> CompletionItemKind.Module
                Symbol.Kind.ENUM -> CompletionItemKind.Enum
                Symbol.Kind.ENUM_MEMBER -> CompletionItemKind.EnumMember
                Symbol.Kind.CONSTRUCTOR -> CompletionItemKind.Constructor
                Symbol.Kind.FIELD -> CompletionItemKind.Field
                Symbol.Kind.UNKNOWN -> CompletionItemKind.Text
            }
            detail = "(import from ${it.fqName.parent()})"
            additionalTextEdits = listOf(getImportTextEditEntry(parsedFile, it.fqName)) // TODO: CRLF?
        } }
}

/** Finds keyword completions starting with the given partial identifier. */
private fun keywordCompletionItems(partial: String): Sequence<CompletionItem> =
    (KtTokens.SOFT_KEYWORDS.getTypes() + KtTokens.KEYWORDS.getTypes()).asSequence()
        .mapNotNull { (it as? KtKeywordToken)?.value }
        .filter { it.startsWith(partial) }
        .map { CompletionItem().apply {
            label = it
            kind = CompletionItemKind.Keyword
        } }

data class ElementCompletionItems(val items: Sequence<CompletionItem>, val element: KtElement? = null)

/** Finds completions based on the element around the user's cursor. */
private fun elementCompletionItems(file: SourceFile.Compiled, cursor: Int, allowSnippets: Boolean, partial: String): ElementCompletionItems {
    val surroundingElement = completableElement(file, cursor) ?: return ElementCompletionItems(emptySequence())
    val completions = elementCompletions(file, cursor, surroundingElement)

    val matchesName = completions.filter { containsCharactersInOrder(name(it), partial, caseSensitive = false) }
    val sorted = matchesName.takeIf { partial.length >= MIN_SORT_LENGTH }?.sortedBy { stringDistance(name(it), partial) }
        ?: matchesName.sortedBy { if (name(it).startsWith(partial)) 0 else 1 }
    val visible = sorted.filter(isVisible(file, cursor))

    return ElementCompletionItems(visible.map { completionItem(it, surroundingElement, file, allowSnippets) }, surroundingElement)
}

private val callPattern = Regex("(.*)\\((?:\\$\\d+)?\\)(?:\\$0)?")
private val methodSignature = Regex("""(?:fun|constructor) (?:<(?:[a-zA-Z\?\!\: ]+)(?:, [A-Z])*> )?([a-zA-Z]+\(.*\))""")

private fun completionItem(d: DeclarationDescriptor, surroundingElement: KtElement, file: SourceFile.Compiled, allowSnippets: Boolean): CompletionItem {
    val renderWithSnippets = allowSnippets
        && surroundingElement !is KtCallableReferenceExpression
        && surroundingElement !is KtImportDirective
    val result = d.accept(RenderCompletionItem(renderWithSnippets), null)

    result.label = methodSignature.find(result.detail)?.groupValues?.get(1) ?: result.label

    if (isNotStaticJavaMethod(d) && (isGetter(d) || isSetter(d))) {
        val name = extractPropertyName(d)

        result.detail += " (from ${result.label})"
        result.label = name
        result.insertText = name
        result.filterText = name
    }

    if (KotlinBuiltIns.isDeprecated(d)) {
        result.tags = listOf(CompletionItemTag.Deprecated)
    }

    val matchCall = callPattern.matchEntire(result.insertText)
    if (file.lineAfter(surroundingElement.endOffset).startsWith("(") && matchCall != null) {
        result.insertText = matchCall.groups[1]!!.value
    }

    return result
}
private fun SourceFile.Compiled.lineAfter(cursor: Int): String = content.substring(cursor).substringBefore('\n')

private fun isNotStaticJavaMethod(
    descriptor: DeclarationDescriptor
): Boolean {
    val javaMethodDescriptor = descriptor as? JavaMethodDescriptor ?: return true
    val source = javaMethodDescriptor.source as? JavaSourceElement ?: return true
    val javaElement = source.javaElement
    return javaElement is JavaMethod && !javaElement.isStatic
}

private fun extractPropertyName(d: DeclarationDescriptor): String {
    val match = Regex("(get|set)?((?:(?:is)|[A-Z])\\w*)").matchEntire(d.name.identifier)!!
    val upper = match.groups[2]!!.value

    return upper[0].lowercaseChar() + upper.substring(1)
}

private fun isGetter(d: DeclarationDescriptor): Boolean =
        d is CallableDescriptor &&
        !d.name.isSpecial &&
        d.name.identifier.matches(Regex("(get|is)[A-Z]\\w+")) &&
        d.valueParameters.isEmpty()

private fun isSetter(d: DeclarationDescriptor): Boolean =
        d is CallableDescriptor &&
        !d.name.isSpecial &&
        d.name.identifier.matches(Regex("set[A-Z]\\w+")) &&
        d.valueParameters.size == 1

private fun completableElement(file: SourceFile.Compiled, cursor: Int): KtElement? {
    val el = file.parseAtPoint(cursor - 1) ?: return null
            // import x.y.?
    return el.findParent<KtImportDirective>()
            // package x.y.?
            ?: el.findParent<KtPackageDirective>()
            // :?
            ?: el as? KtUserType
            ?: el.parent as? KtTypeElement
            // .?
            ?: el as? KtQualifiedExpression
            ?: el.parent as? KtQualifiedExpression
            // something::?
            ?: el as? KtCallableReferenceExpression
            ?: el.parent as? KtCallableReferenceExpression
            // something.foo() with cursor in the method
            ?: el.parent?.parent as? KtQualifiedExpression
            // ?
            ?: el as? KtNameReferenceExpression
}

private fun elementCompletions(
    file: SourceFile.Compiled,
    cursor: Int,
    surroundingElement: KtElement
): Sequence<DeclarationDescriptor> {
    return when (surroundingElement) {
        // import x.y.?
        is KtImportDirective -> {
            log.info("Completing import '${surroundingElement.text}'")
            val module = file.module
            val match = "import ((\\w+\\.)*)[\\w*]*"
                .toRegex()
                .matchEntire(surroundingElement.text)
                ?: return run {
                    log.debug("${surroundingElement.text} doesn't look like import a.b...")
                    emptySequence()
                }

            val parentDot = if (match.groupValues[1].isNotBlank()) match.groupValues[1] else "."
            val parent = parentDot.substring(0, parentDot.length - 1)
            log.debug("Looking for members of package '${parent}'")
            val parentPackage = module.getPackage(FqName.fromSegments(parent.split('.')))
            parentPackage.memberScope.getContributedDescriptors().asSequence()
        }
        // package x.y.?
        is KtPackageDirective -> {
            log.info("Completing package '${surroundingElement.text}'")
            val module = file.module
            val match = Regex("package ((\\w+\\.)*)[\\w*]*").matchEntire(surroundingElement.text)
                ?: return run {
                    log.debug("${surroundingElement.text} doesn't look like package a.b...")
                    emptySequence()
                }
            val parentDot = if (match.groupValues[1].isNotBlank()) match.groupValues[1] else "."
            val parent = parentDot.substring(0, parentDot.length - 1)
            log.debug("Looking for members of package '${parent}'")
            val parentPackage = module.getPackage(FqName.fromSegments(parent.split('.')))
            parentPackage.memberScope.getDescriptorsFiltered(DescriptorKindFilter.PACKAGES).asSequence()
        }
        // :?
        is KtTypeElement -> {
            // : Outer.?
            if (surroundingElement is KtUserType && surroundingElement.qualifier != null) {
                val referenceTarget = file.referenceAtPoint(surroundingElement.qualifier!!.startOffset)?.second
                if (referenceTarget is ClassDescriptor) {
                    log.info("Completing members of ${referenceTarget.fqNameSafe}")
                    return referenceTarget.getDescriptors()
                } else {
                    log.warning("No type reference in '${surroundingElement.text}'")
                    return emptySequence()
                }
            } else {
                // : ?
                log.info("Completing type identifier '${surroundingElement.text}'")
                val scope = file.scopeAtPoint(cursor) ?: return emptySequence()
                scopeChainTypes(scope)
            }
        }
        // .?
        is KtQualifiedExpression -> {
            log.info("Completing member expression '${surroundingElement.text}'")
            completeMembers(file, cursor, surroundingElement.receiverExpression, surroundingElement is KtSafeQualifiedExpression)
        }
        is KtCallableReferenceExpression -> {
            // something::?
            if (surroundingElement.receiverExpression != null) {
                log.info("Completing method reference '${surroundingElement.text}'")
                completeMembers(file, cursor, surroundingElement.receiverExpression!!)
            }
            // ::?
            else {
                log.info("Completing function reference '${surroundingElement.text}'")
                val scope = file.scopeAtPoint(surroundingElement.startOffset)
                    ?: return run{log.info{"No scope at ${file.describePosition(cursor)}"}; emptySequence()}
                identifiers(scope)
            }
        }
        // ?
        is KtNameReferenceExpression -> {
            log.info("Completing identifier '${surroundingElement.text}'")
            val scope = file.scopeAtPoint(surroundingElement.startOffset)
                ?: return run{log.info{"No scope at ${file.describePosition(cursor)}"}; emptySequence()}
            identifiers(scope)
        }
        else -> {
            log.info("${surroundingElement::class.simpleName} ${surroundingElement.text} didn't look like a type, a member, or an identifier")
            emptySequence()
        }
    }
}

private fun completeMembers(
    file: SourceFile.Compiled,
    cursor: Int,
    receiverExpr: KtExpression,
    unwrapNullable: Boolean = false
): Sequence<DeclarationDescriptor> {
    // thingWithType.?
    var descriptors = emptySequence<DeclarationDescriptor>()
    file.scopeAtPoint(cursor)?.let { lexicalScope ->
        file.typeOfExpression(receiverExpr, lexicalScope)?.let { expressionType ->
            val receiverType = if (unwrapNullable) try {
                TypeUtils.makeNotNullable(expressionType)
            } catch (e: Exception) {
                log.error(e, "Exception compiling member")
                expressionType
            } else expressionType

            log.debug("Completing members of instance '${receiverType}'")
            val members = receiverType.memberScope.getContributedDescriptors().asSequence()
            val extensions = extensionFunctions(lexicalScope).filter { isExtensionFor(receiverType, it) }
            descriptors = members + extensions

            if (!isCompanionOfEnum(receiverType) && !isCompanionOfSealed(receiverType)) {
                return descriptors
            }
        }
    }

    // JavaClass.?
    val referenceTarget = file.referenceAtPoint(receiverExpr.endOffset - 1)?.second
    if (referenceTarget is ClassDescriptor) {
        log.debug("Completing members of '${referenceTarget.fqNameSafe}'")
        return descriptors + referenceTarget.getDescriptors()
    }

    log.debug("Can't find member scope for ${receiverExpr.text}")
    return emptySequence()
}

private fun ClassDescriptor.getDescriptors(): Sequence<DeclarationDescriptor> {
    val statics = staticScope.getContributedDescriptors().asSequence()
    val classes = unsubstitutedInnerClassesScope.getContributedDescriptors().asSequence()
    val types = unsubstitutedMemberScope.getContributedDescriptors().asSequence()
    val companionDescriptors = if (hasCompanionObject && companionObjectDescriptor != null) companionObjectDescriptor!!.getDescriptors() else emptySequence()

    return (statics + classes + types + companionDescriptors).toSet().asSequence()

}

private fun isCompanionOfEnum(kotlinType: KotlinType): Boolean {
    val classDescriptor = TypeUtils.getClassDescriptor(kotlinType)
    val isCompanion = DescriptorUtils.isCompanionObject(classDescriptor)
    if (!isCompanion) {
        return false
    }
    return DescriptorUtils.isEnumClass(classDescriptor?.containingDeclaration)
}

private fun isCompanionOfSealed(kotlinType: KotlinType): Boolean {
    val classDescriptor = TypeUtils.getClassDescriptor(kotlinType)
    val isCompanion = DescriptorUtils.isCompanionObject(classDescriptor)
    if (!isCompanion) {
        return false
    }

    return DescriptorUtils.isSealedClass(classDescriptor?.containingDeclaration)
}

private fun findPartialIdentifier(file: SourceFile.Compiled, cursor: Int): String {
    val line = file.lineBefore(cursor)
    if (line.matches(Regex(".*\\."))) return ""
    else if (line.matches(Regex(".*\\.\\w+"))) return line.substringAfterLast(".")
    else return Regex("\\w+").findAll(line).lastOrNull()?.value ?: ""
}
private fun SourceFile.Compiled.lineBefore(cursor: Int): String = content.substring(0, cursor).substringAfterLast('\n')

private fun memberOverloads(type: KotlinType, identifier: String): Sequence<CallableDescriptor> {
    val nameFilter = equalsIdentifier(identifier)

    return type.memberScope
            .getContributedDescriptors(Companion.CALLABLES).asSequence()
            .filterIsInstance<CallableDescriptor>()
            .filter(nameFilter)
}

private fun completeTypeMembers(type: KotlinType): Sequence<DeclarationDescriptor> =
    type.memberScope.getDescriptorsFiltered(TYPES_FILTER).asSequence()

private fun scopeChainTypes(scope: LexicalScope): Sequence<DeclarationDescriptor> =
        scope.parentsWithSelf.flatMap(::scopeTypes)

private val TYPES_FILTER = DescriptorKindFilter(DescriptorKindFilter.NON_SINGLETON_CLASSIFIERS_MASK or DescriptorKindFilter.TYPE_ALIASES_MASK)

private fun scopeTypes(scope: HierarchicalScope): Sequence<DeclarationDescriptor> =
        scope.getContributedDescriptors(TYPES_FILTER).asSequence()

private fun identifierOverloads(scope: LexicalScope, identifier: String): Sequence<CallableDescriptor> {
    val nameFilter = equalsIdentifier(identifier)

    return identifiers(scope)
            .filterIsInstance<CallableDescriptor>()
            .filter(nameFilter)
}

private fun extensionFunctions(scope: LexicalScope): Sequence<CallableDescriptor> =
    scope.parentsWithSelf.flatMap(::scopeExtensionFunctions)

private fun scopeExtensionFunctions(scope: HierarchicalScope): Sequence<CallableDescriptor> =
    scope.getContributedDescriptors(DescriptorKindFilter.CALLABLES).asSequence()
            .filterIsInstance<CallableDescriptor>()
            .filter { it.isExtension }

private fun identifiers(scope: LexicalScope): Sequence<DeclarationDescriptor> =
    scope.parentsWithSelf
            .flatMap(::scopeIdentifiers)
            .flatMap(::explodeConstructors)

private fun scopeIdentifiers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    val locals = scope.getContributedDescriptors().asSequence()
    val members = implicitMembers(scope)

    return locals + members
}

private fun explodeConstructors(declaration: DeclarationDescriptor): Sequence<DeclarationDescriptor> =
    when (declaration) {
        is ClassDescriptor ->
            declaration.constructors.asSequence() + declaration
        else ->
            sequenceOf(declaration)
    }

private fun implicitMembers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    if (scope !is LexicalScope) return emptySequence()
    val implicit = scope.implicitReceiver ?: return emptySequence()
    return implicit.type.memberScope.getContributedDescriptors().asSequence()
}

private fun equalsIdentifier(identifier: String): (DeclarationDescriptor) -> Boolean =
    { name(it) == identifier }

private fun name(d: DeclarationDescriptor): String =
    if (d is ConstructorDescriptor)
        d.constructedClass.name.identifier
    else
        d.name.identifier

private fun isVisible(file: SourceFile.Compiled, cursor: Int): (DeclarationDescriptor) -> Boolean {
    val el = file.elementAtPoint(cursor) ?: return { true }
    val from = el.parentsWithSelf
        .mapNotNull { file.context[BindingContext.DECLARATION_TO_DESCRIPTOR, it] }
        .firstOrNull()
        ?: return { true }
    fun check(target: DeclarationDescriptor): Boolean {
        val visible = isDeclarationVisible(target, from)

        if (!visible) logHidden(target, from)

        return visible
    }
    return ::check
}

// We can't use the implementations in Visibilities because they don't work with our type of incremental compilation
// Instead, we implement our own "liberal" visibility checker that defaults to visible when in doubt
private fun isDeclarationVisible(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean =
    target.parentsWithSelf
            .filterIsInstance<DeclarationDescriptorWithVisibility>()
            .none { isNotVisible(it, from) }

private fun isNotVisible(target: DeclarationDescriptorWithVisibility, from: DeclarationDescriptor): Boolean {
    when (target.visibility.delegate) {
        Visibilities.Private, Visibilities.PrivateToThis -> {
            if (DescriptorUtils.isTopLevelDeclaration(target))
                return !sameFile(target, from)
            else
                return !sameParent(target, from)
        }
        Visibilities.Protected -> {
            return !subclassParent(target, from)
        }
        else -> return false
    }
}

private fun sameFile(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetFile = DescriptorUtils.getContainingSourceFile(target)
    val fromFile = DescriptorUtils.getContainingSourceFile(from)
    if (targetFile == XSourceFile.NO_SOURCE_FILE || fromFile == XSourceFile.NO_SOURCE_FILE) return true
    else return targetFile.name == fromFile.name
}

private fun sameParent(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetParent = target.parentsWithSelf.mapNotNull(::isParentClass).firstOrNull() ?: return true
    val fromParents = from.parentsWithSelf.mapNotNull(::isParentClass).toList()
    return fromParents.any { it.fqNameSafe == targetParent.fqNameSafe }
}

private fun subclassParent(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetParent = target.parentsWithSelf.mapNotNull(::isParentClass).firstOrNull() ?: return true
    val fromParents = from.parentsWithSelf.mapNotNull(::isParentClass).toList()
    if (fromParents.isEmpty()) return true
    else return fromParents.any { DescriptorUtils.isSubclass(it, targetParent) }
}

private fun isParentClass(declaration: DeclarationDescriptor): ClassDescriptor? =
    if (declaration is ClassDescriptor && !DescriptorUtils.isCompanionObject(declaration))
        declaration
    else null

private fun isExtensionFor(type: KotlinType, extensionFunction: CallableDescriptor): Boolean {
    val receiverType = extensionFunction.extensionReceiverParameter?.type?.replaceArgumentsWithStarProjections() ?: return false
    return KotlinTypeChecker.DEFAULT.isSubtypeOf(type, receiverType)
        || (TypeUtils.getTypeParameterDescriptorOrNull(receiverType)?.isGenericExtensionFor(type) ?: false)
}

private fun TypeParameterDescriptor.isGenericExtensionFor(type: KotlinType): Boolean =
    upperBounds.all { KotlinTypeChecker.DEFAULT.isSubtypeOf(type, it) }

private val loggedHidden = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build<Pair<Name, Name>, Unit>()

private fun logHidden(target: DeclarationDescriptor, from: DeclarationDescriptor) {
    loggedHidden.get(from.name to target.name, {
        log.debug{"Hiding ${describeDeclaration(target)} because it's not visible from ${describeDeclaration(from)}"}
    })
}

private fun describeDeclaration(declaration: DeclarationDescriptor): String {
    val file = declaration.findPsi()?.containingFile?.toPath()?.fileName?.toString() ?: "<unknown-file>"
    val container = declaration.containingDeclaration?.name?.toString() ?: "<top-level>"
    return "($file $container.${declaration.name})"
}

val DECL_RENDERER = DescriptorRenderer.withOptions {
    withDefinedIn = false
    modifiers = emptySet()
    classifierNamePolicy = ClassifierNamePolicy.SHORT
    parameterNameRenderingPolicy = ParameterNameRenderingPolicy.ONLY_NON_SYNTHESIZED
    typeNormalizer = {
        when (it) {
            is UnresolvedType ->  ErrorUtils.createErrorTypeWithCustomDebugName(it.presentableName)
            else -> it
        }
    }
}

private val GOOD_IDENTIFIER = "[a-zA-Z]\\w*".toRegex()

class RenderCompletionItem(val snippetsEnabled: Boolean) : DeclarationDescriptorVisitor<CompletionItem, Unit> {
    private val result = CompletionItem()

    private val functionInsertFormat
        get() = if (snippetsEnabled) Snippet else PlainText

    private fun escape(id: String): String =
        if (id.matches(GOOD_IDENTIFIER)) id
        else "`$id`"

    private fun setDefaults(declaration: DeclarationDescriptor) {
        result.label = declaration.label()
        result.filterText = declaration.label()
        result.insertText = escape(declaration.label()!!)
        result.insertTextFormat = PlainText
        result.detail = DECL_RENDERER.render(declaration)
    }

    override fun visitPropertySetterDescriptor(desc: PropertySetterDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Field
        return result
    }

    override fun visitConstructorDescriptor(desc: ConstructorDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Constructor
        result.insertText = functionInsertText(desc)
        result.insertTextFormat = functionInsertFormat
        return result
    }

    override fun visitReceiverParameterDescriptor(desc: ReceiverParameterDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Variable
        return result
    }

    override fun visitPackageViewDescriptor(desc: PackageViewDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Module
        return result
    }

    override fun visitFunctionDescriptor(desc: FunctionDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Function
        result.insertText = functionInsertText(desc)
        result.insertTextFormat = functionInsertFormat
        return result
    }

    private fun functionInsertText(desc: FunctionDescriptor): String {
        val name = escape(desc.label()!!)
        return if (snippetsEnabled) {
            val parameters = desc.valueParameters
            val hasTrailingLambda = parameters.lastOrNull()?.type?.isFunctionType ?: false
            if (hasTrailingLambda) {
                val parenthesizedParams = parameters.dropLast(1).ifEmpty { null }?.let { "(${valueParametersSnippet(it)})" } ?: ""
                "$name$parenthesizedParams { \${${parameters.size}:${parameters.last().name}} }"
            } else {
                "$name(${valueParametersSnippet(parameters)})"
            }
        } else {
            name
        }
    }

    private fun valueParametersSnippet(parameters: List<ValueParameterDescriptor>) = parameters
        .asSequence()
        .filterNot { it.declaresDefaultValue() }
        .mapIndexed { index, vpd -> "\${${index + 1}:${vpd.name}}" }
        .joinToString()

    override fun visitModuleDeclaration(desc: ModuleDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Module
        return result
    }

    override fun visitClassDescriptor(desc: ClassDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = when (desc.kind) {
            ClassKind.INTERFACE -> CompletionItemKind.Interface
            ClassKind.ENUM_CLASS -> CompletionItemKind.Enum
            ClassKind.ENUM_ENTRY -> CompletionItemKind.EnumMember
            else -> CompletionItemKind.Class
        }
        return result
    }

    override fun visitPackageFragmentDescriptor(desc: PackageFragmentDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Module
        return result
    }

    override fun visitValueParameterDescriptor(desc: ValueParameterDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Variable
        return result
    }

    override fun visitTypeParameterDescriptor(desc: TypeParameterDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Variable
        return result
    }

    override fun visitScriptDescriptor(desc: ScriptDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Module
        return result
    }

    override fun visitTypeAliasDescriptor(desc: TypeAliasDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Variable
        return result
    }

    override fun visitPropertyGetterDescriptor(desc: PropertyGetterDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Field
        return result
    }

    override fun visitVariableDescriptor(desc: VariableDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Variable
        return result
    }

    override fun visitPropertyDescriptor(desc: PropertyDescriptor, nothing: Unit?): CompletionItem {
        setDefaults(desc)
        result.kind = CompletionItemKind.Field
        return result
    }
}

private fun DeclarationDescriptor.label(): String? {
    return when {
        this is ConstructorDescriptor -> this.containingDeclaration.name.identifier
        this.name.isSpecial -> null
        else -> this.name.identifier
    }
}

private fun getImportTextEditEntry(parsedFile: KtFile, fqName: FqName) =
    parsedFile.packageDirective
        ?.let{ it.locationInFile() }
        ?.range
        ?.end
        .let {it ?: Position(0, 0)}
        .let{Range(it,it)}
        .let{TextEdit(it, "\nimport ${fqName}")}

// TODO: see where this should ideally be placed
private const val DEFAULT_TAB_SIZE = 4

fun listOverridableMembers(file: SourceFile.Compiled, cursor: Int): List<CodeAction> {
    val kotlinClass = file.parseAtPoint(cursor)
    if (kotlinClass is KtClass) {
        return createOverrideAlternatives(file, kotlinClass)
    }
    return emptyList()
}

private fun createOverrideAlternatives(file: SourceFile.Compiled, kotlinClass: KtClass): List<CodeAction> {
    // Get the functions that need to be implemented
    val membersToImplement = getUnimplementedMembersStubs(file, kotlinClass)

    val uri = file.ktFile.toPath().toUri().toString()

    // Get the padding to be introduced before the member declarations
    val padding = getDeclarationPadding(file, kotlinClass)

    // Get the location where the new code will be placed
    val newMembersStartPosition = getNewMembersStartPosition(file, kotlinClass)

    // loop through the memberstoimplement and create code actions
    return membersToImplement.map { member ->
        val newText = System.lineSeparator() + System.lineSeparator() + padding + member
        val textEdit = TextEdit(Range(newMembersStartPosition, newMembersStartPosition), newText)

        val codeAction = CodeAction()
        codeAction.edit = WorkspaceEdit(mapOf(uri to listOf(textEdit)))
        codeAction.title = member

        codeAction
    }
}

// TODO: any way can repeat less code between this and the getAbstractMembersStubs in the ImplementAbstractMembersQuickfix?
private fun getUnimplementedMembersStubs(file: SourceFile.Compiled, kotlinClass: KtClass): List<String> =
    // For each of the super types used by this class
    // TODO: does not seem to handle the implicit Any and Object super types that well. Need to find out if that is easily solvable. Finds the methods from them if any super class or interface is present
    kotlinClass
        .superTypeListEntries
        .mapNotNull {
            // Find the definition of this super type
            val referenceAtPoint = file.context.referenceAt(it.startOffset)
            val descriptor = referenceAtPoint?.second
            val classDescriptor = getClassDescriptor(descriptor)

            // If the super class is abstract, interface or just plain open
            if (null != classDescriptor && classDescriptor.canBeExtended()) {
                val superClassTypeArguments = getSuperClassTypeProjections(file, it)
                classDescriptor
                    .getMemberScope(superClassTypeArguments)
                    .getContributedDescriptors()
                    .filter { classMember ->
                        classMember is MemberDescriptor &&
                         classMember.canBeOverriden() &&
                         !overridesDeclaration(kotlinClass, classMember)
                    }
                    .mapNotNull { member ->
                        when (member) {
                            is FunctionDescriptor -> createFunctionStub(member)
                            is PropertyDescriptor -> createVariableStub(member)
                            else -> null
                        }
                    }
            } else {
                null
            }
        }
        .flatten()

private fun ClassDescriptor.canBeExtended() = this.kind.isInterface ||
    this.modality == Modality.ABSTRACT ||
    this.modality == Modality.OPEN

private fun MemberDescriptor.canBeOverriden() = (Modality.ABSTRACT == this.modality || Modality.OPEN == this.modality) && Modality.FINAL != this.modality && this.visibility != DescriptorVisibilities.PRIVATE && this.visibility != DescriptorVisibilities.PROTECTED

// interfaces are ClassDescriptors by default. When calling AbstractClass super methods, we get a ClassConstructorDescriptor
fun getClassDescriptor(descriptor: DeclarationDescriptor?): ClassDescriptor? =
        if (descriptor is ClassDescriptor) {
            descriptor
        } else if (descriptor is ClassConstructorDescriptor) {
            descriptor.containingDeclaration
        } else {
            null
        }

fun getSuperClassTypeProjections(
        file: SourceFile.Compiled,
        superType: KtSuperTypeListEntry
): List<TypeProjection> =
        superType
                .typeReference
                ?.typeElement
                ?.children
                ?.filter { it is KtTypeArgumentList }
                ?.flatMap { (it as KtTypeArgumentList).arguments }
                ?.mapNotNull {
                    (file.context.referenceAt(it?.startOffset ?: 0)?.second as?
                                    ClassDescriptor)
                            ?.defaultType?.asTypeProjection()
                }
                ?: emptyList()

// Checks if the class overrides the given declaration
fun overridesDeclaration(kotlinClass: KtClass, descriptor: MemberDescriptor): Boolean =
    when (descriptor) {
        is FunctionDescriptor -> kotlinClass.declarations.any {
            it.name == descriptor.name.asString()
            && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
            && ((it as? KtNamedFunction)?.let { parametersMatch(it, descriptor) } ?: true)
        }
        is PropertyDescriptor -> kotlinClass.declarations.any {
            it.name == descriptor.name.asString() && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        }
        else -> false
    }

// Checks if two functions have matching parameters
private fun parametersMatch(
        function: KtNamedFunction,
        functionDescriptor: FunctionDescriptor
): Boolean {
    if (function.valueParameters.size == functionDescriptor.valueParameters.size) {
        for (index in 0 until function.valueParameters.size) {
            if (function.valueParameters[index].name !=
                    functionDescriptor.valueParameters[index].name.asString()
            ) {
                return false
            } else if (function.valueParameters[index].typeReference?.typeName() !=
                            functionDescriptor.valueParameters[index]
                                    .type
                                    .unwrappedType()
                                    .toString() && function.valueParameters[index].typeReference?.typeName() != null
            ) {
                // Any and Any? seems to be null for Kt* psi objects for some reason? At least for equals
                // TODO: look further into this

                // Note: Since we treat Java overrides as non nullable by default, the above test
                // will fail when the user has made the type nullable.
                // TODO: look into this
                return false
            }
        }

        if (function.typeParameters.size == functionDescriptor.typeParameters.size) {
            for (index in 0 until function.typeParameters.size) {
                if (function.typeParameters[index].variance !=
                        functionDescriptor.typeParameters[index].variance
                ) {
                    return false
                }
            }
        }

        return true
    }

    return false
}

private fun KtTypeReference.typeName(): String? =
        this.name
                ?: this.typeElement
                        ?.children
                        ?.filter { it is KtSimpleNameExpression }
                        ?.map { (it as KtSimpleNameExpression).getReferencedName() }
                        ?.firstOrNull()

fun createFunctionStub(function: FunctionDescriptor): String {
    val name = function.name
    val arguments =
            function.valueParameters
                    .map { argument ->
                        val argumentName = argument.name
                        val argumentType = argument.type.unwrappedType()

                        "$argumentName: $argumentType"
                    }
                    .joinToString(", ")
    val returnType = function.returnType?.unwrappedType()?.toString()?.takeIf { "Unit" != it }

    return "override fun $name($arguments)${returnType?.let { ": $it" } ?: ""} { }"
}

fun createVariableStub(variable: PropertyDescriptor): String {
    val variableType = variable.returnType?.unwrappedType()?.toString()?.takeIf { "Unit" != it }
    return "override val ${variable.name}${variableType?.let { ": $it" } ?: ""} = TODO(\"SET VALUE\")"
}

// about types: regular Kotlin types are marked T or T?, but types from Java are (T..T?) because
// nullability cannot be decided.
// Therefore we have to unpack in case we have the Java type. Fortunately, the Java types are not
// marked nullable, so we default to non nullable types. Let the user decide if they want nullable
// types instead. With this implementation Kotlin types also keeps their nullability
private fun KotlinType.unwrappedType(): KotlinType =
        this.unwrap().makeNullableAsSpecified(this.isMarkedNullable)

fun getDeclarationPadding(file: SourceFile.Compiled, kotlinClass: KtClass): String {
    // If the class is not empty, the amount of padding is the same as the one in the last
    // declaration of the class
    val paddingSize =
            if (kotlinClass.declarations.isNotEmpty()) {
                val lastFunctionStartOffset = kotlinClass.declarations.last().startOffset
                indexToPosition(file.content, lastFunctionStartOffset).character
            } else {
                // Otherwise, we just use a default tab size in addition to any existing padding
                // on the class itself (note that the class could be inside another class, for
                // example)
                indexToPosition(file.content, kotlinClass.startOffset).character + DEFAULT_TAB_SIZE
            }

    return " ".repeat(paddingSize)
}

fun getNewMembersStartPosition(file: SourceFile.Compiled, kotlinClass: KtClass): Position? =
        // If the class is not empty, the new member will be put right after the last declaration
        if (kotlinClass.declarations.isNotEmpty()) {
            val lastFunctionEndOffset = kotlinClass.declarations.last().endOffset
            indexToPosition(file.content, lastFunctionEndOffset)
        } else { // Otherwise, the member is put at the beginning of the class
            val body = kotlinClass.body
            if (body != null) {
                indexToPosition(file.content, body.startOffset + 1)
            } else {
                // function has no body. We have to create one. New position is right after entire
                // kotlin class text (with space)
                val newPosCorrectLine = indexToPosition(file.content, kotlinClass.startOffset + 1)
                newPosCorrectLine.character = (kotlinClass.text.length + 2)
                newPosCorrectLine
            }
        }

fun KtClass.hasNoBody() = null == this.body

/**
 * Looks for a reference expression at the given cursor.
 * This method is similar to [referenceAtPoint], but the latter fails to find declarations for JDK symbols.
 * This method should not be used for anything other than finding definitions (at least for now).
 */
fun BindingContext.referenceAt(
    cursor: Int
) = getSliceContents(BindingContext.REFERENCE_TARGET)
    .asSequence()
    .filter { cursor in it.key.textRange }
    .sortedBy { it.key.textRange.length }
    .map { it.toPair() }
    .firstOrNull()

enum class SemanticTokenType(val typeName: String) {
    KEYWORD(SemanticTokenTypes.Keyword),
    VARIABLE(SemanticTokenTypes.Variable),
    FUNCTION(SemanticTokenTypes.Function),
    PROPERTY(SemanticTokenTypes.Property),
    PARAMETER(SemanticTokenTypes.Parameter),
    ENUM_MEMBER(SemanticTokenTypes.EnumMember),
    CLASS(SemanticTokenTypes.Class),
    INTERFACE(SemanticTokenTypes.Interface),
    ENUM(SemanticTokenTypes.Enum),
    TYPE(SemanticTokenTypes.Type),
    STRING(SemanticTokenTypes.String),
    NUMBER(SemanticTokenTypes.Number),
    // Since LSP does not provide a token type for string interpolation
    // entries, we use Variable as a fallback here for now
    INTERPOLATION_ENTRY(SemanticTokenTypes.Variable)
}

enum class SemanticTokenModifier(val modifierName: String) {
    DECLARATION(SemanticTokenModifiers.Declaration),
    DEFINITION(SemanticTokenModifiers.Definition),
    ABSTRACT(SemanticTokenModifiers.Abstract),
    READONLY(SemanticTokenModifiers.Readonly)
}

val semanticTokensLegend = SemanticTokensLegend(
    SemanticTokenType.values().map { it.typeName },
    SemanticTokenModifier.values().map { it.modifierName }
)

data class SemanticToken(val range: Range, val type: SemanticTokenType, val modifiers: Set<SemanticTokenModifier> = setOf())

/**
 * Computes LSP-encoded semantic tokens for the given range in the
 * document. No range means the entire document.
 */
fun encodedSemanticTokens(file: SourceFile.Compiled, range: Range? = null): List<Int> =
    encodeTokens(semanticTokens(file, range))

/**
 * Computes semantic tokens for the given range in the document.
 * No range means the entire document.
 */
private fun semanticTokens(file: SourceFile.Compiled, range: Range? = null): Sequence<SemanticToken> =
    elementTokens(file.ktFile, file.context, range)

private fun encodeTokens(tokens: Sequence<SemanticToken>): List<Int> {
    val encoded = mutableListOf<Int>()
    var last: SemanticToken? = null

    for (token in tokens) {
        // Tokens must be on a single line
        if (token.range.start.line == token.range.end.line) {
            val length = token.range.end.character - token.range.start.character
            val deltaLine = token.range.start.line - (last?.range?.start?.line ?: 0)
            val deltaStart = token.range.start.character - (last?.takeIf { deltaLine == 0 }?.range?.start?.character ?: 0)

            encoded.add(deltaLine)
            encoded.add(deltaStart)
            encoded.add(length)
            encoded.add(encodeType(token.type))
            encoded.add(encodeModifiers(token.modifiers))

            last = token
        }
    }

    return encoded
}

private fun encodeType(type: SemanticTokenType): Int = type.ordinal

private fun encodeModifiers(modifiers: Set<SemanticTokenModifier>): Int = modifiers
    .map { 1 shl it.ordinal }
    .fold(0, Int::or)

private fun elementTokens(element: PsiElement, bindingContext: BindingContext, range: Range? = null): Sequence<SemanticToken> {
    val file = element.containingFile
    val textRange = range?.let { TextRange(it.start.getIndexIn(file.text), it.end.getIndexIn(file.text)) }
    return element
        // TODO: Ideally we would like to cut-off subtrees outside our range, but this doesn't quite seem to work
        // .preOrderTraversal { elem -> textRange?.let { it.contains(elem.textRange) } ?: true }
        .preOrderTraversal()
        .filter { elem -> textRange?.let { it.contains(elem.textRange) } ?: true }
        .mapNotNull { elementToken(it, bindingContext) }
}

private fun elementToken(element: PsiElement, bindingContext: BindingContext): SemanticToken? {
    val file = element.containingFile
    val elementRange = toLsp4jRange(file.text, element.textRange)

    return when (element) {
        // References (variables, types, functions, ...)

        is KtNameReferenceExpression -> {
            val target = bindingContext[BindingContext.REFERENCE_TARGET, element]
            val tokenType = when (target) {
                is PropertyDescriptor -> SemanticTokenType.PROPERTY
                is VariableDescriptor -> SemanticTokenType.VARIABLE
                is ConstructorDescriptor -> when (target.constructedClass.kind) {
                    ClassKind.ENUM_ENTRY -> SemanticTokenType.ENUM_MEMBER
                    ClassKind.ANNOTATION_CLASS -> SemanticTokenType.TYPE // annotations look nicer this way
                    else -> SemanticTokenType.FUNCTION
                }
                is FunctionDescriptor -> SemanticTokenType.FUNCTION
                is ClassDescriptor -> when (target.kind) {
                    ClassKind.ENUM_ENTRY -> SemanticTokenType.ENUM_MEMBER
                    ClassKind.CLASS -> SemanticTokenType.CLASS
                    ClassKind.OBJECT -> SemanticTokenType.CLASS
                    ClassKind.INTERFACE -> SemanticTokenType.INTERFACE
                    ClassKind.ENUM_CLASS -> SemanticTokenType.ENUM
                    else -> SemanticTokenType.TYPE
                }
                else -> return null
            }
            val isConstant = (target as? VariableDescriptor)?.let { !it.isVar() || it.isConst() } ?: false
            val modifiers = if (isConstant) setOf(SemanticTokenModifier.READONLY) else setOf()

            SemanticToken(elementRange, tokenType, modifiers)
        }

        // Declarations (variables, types, functions, ...)

        is PsiNameIdentifierOwner -> {
            val tokenType = when (element) {
                is KtParameter -> SemanticTokenType.PARAMETER
                is KtProperty -> SemanticTokenType.PROPERTY
                is KtEnumEntry -> SemanticTokenType.ENUM_MEMBER
                is KtVariableDeclaration -> SemanticTokenType.VARIABLE
                is KtClassOrObject -> SemanticTokenType.CLASS
                is KtFunction -> SemanticTokenType.FUNCTION
                else -> return null
            }
            val identifierRange = element.nameIdentifier?.let { toLsp4jRange(file.text, it.textRange) } ?: return null
            val modifiers = mutableSetOf(SemanticTokenModifier.DECLARATION)

            if (element is KtVariableDeclaration && (!element.isVar() || element.hasModifier(KtTokens.CONST_KEYWORD)) || element is KtParameter) {
                modifiers.add(SemanticTokenModifier.READONLY)
            }

            if (element is KtModifierListOwner) {
                if (element.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
                    modifiers.add(SemanticTokenModifier.ABSTRACT)
                }
            }

            SemanticToken(identifierRange, tokenType, modifiers)
        }

        // Literals and string interpolations

        is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry ->
            SemanticToken(elementRange, SemanticTokenType.INTERPOLATION_ENTRY)
        is KtStringTemplateExpression -> SemanticToken(elementRange, SemanticTokenType.STRING)
        is PsiLiteralExpression -> {
            val tokenType = when (element.type) {
                PsiType.INT, PsiType.LONG, PsiType.DOUBLE -> SemanticTokenType.NUMBER
                PsiType.CHAR -> SemanticTokenType.STRING
                PsiType.BOOLEAN, PsiType.NULL -> SemanticTokenType.KEYWORD
                else -> return null
            }
            SemanticToken(elementRange, tokenType)
        }
        else -> null
    }
}
