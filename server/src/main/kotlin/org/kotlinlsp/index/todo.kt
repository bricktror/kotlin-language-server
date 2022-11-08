package org.kotlinlsp.index

import arrow.core.Either
import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.FormattingOptions as KtfmtOptions
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiErrorElement
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
import org.jetbrains.kotlin.descriptors.SourceFile as XSourceFile
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
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.renderer.ClassifierNamePolicy
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.renderer.ParameterNameRenderingPolicy
import org.jetbrains.kotlin.renderer.RenderingFormat
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.typeBinding.*
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
import org.jetbrains.kotlin.incremental.components.NoLookupLocation

import org.kotlinlsp.elementAtPoint
import org.kotlinlsp.file.FileProvider
import org.kotlinlsp.file.TemporaryDirectory
import org.kotlinlsp.getImportTextEditEntry
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.index.receiverTypeFqn
import org.kotlinlsp.logging.*
import org.kotlinlsp.logging.findLogger
import org.kotlinlsp.lsp4kt.*
import org.kotlinlsp.prettyprint.*
import org.kotlinlsp.referenceAtPoint
import org.kotlinlsp.source.SourceFile
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.source.context.getDeclarationsInScope
import org.kotlinlsp.source.context.lexicalScopeAt
import org.kotlinlsp.source.context.textUntilCursor
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
import org.kotlinlsp.util.pair.*

private object pattern {
    val call = "(.*)\\((?:\\$\\d+)?\\)(?:\\$0)?".toRegex()
    val methodSignature = """(?:fun|constructor) (?:<(?:[a-zA-Z\?\!\: ]+)(?:, [A-Z])*> )?([a-zA-Z]+\(.*\))""".toRegex()
    val GOOD_IDENTIFIER = "[a-zA-Z]\\w*".toRegex()
    val property = "(get|set)?([A-Z]\\w*)".toRegex()
}

val PsiElement.prevSiblings get() = generateSequence(this) { it.prevSibling }.drop(1)
val PsiElement.nextSiblings get() = generateSequence(this) { it.nextSibling }.drop(1)

fun getTopLevelOperatorCompletions(
    path: List<PsiElement>,
    cursor: Int
): Sequence<CompletionItem> = sequence {
    val (element, parent) = path
        .takeIf { it.size==3}
        ?.let { (element, parent, root) ->
            when {
                root !is KtFile -> null
                parent !is PsiErrorElement -> null
                else -> element to parent
            }
        }
        ?: return@sequence
    val partial=element.textUntilCursor(cursor)
    val parentSibling = parent.prevSiblings.firstOrNull { it !is PsiWhiteSpace }

    suspend fun SequenceScope<CompletionItem>.yieldOp(label: String, text: String) {
        if(text.startsWith(partial)) {
            yield(CompletionItem(label).apply{
                kind=CompletionItemKind.Operator
                insertText = text
                insertTextFormat = PlainText
            })
        }
    }

    if (cursor==0) {
        yieldOp("package", "package")
    }
    if(parentSibling is KtImportList) {
        yieldOp("import", "import")
    }
    yieldOp("function", "fun")
    yieldOp("value", "val")
    yieldOp("variable", "var")
    yieldOp("constant", "const val")
    yieldOp("private", "private")
}

private inline fun <reified T> extractImportOrPackageDirectiveSegments(
    prefix:String,
    path: List<PsiElement>,
    cursor: Int
) = path
    .firstOrNull{it is T }
    ?.textUntilCursor(cursor)
    ?.removePrefix(prefix)
    ?.trimStart(' ')
    ?.split('.')
    ?.let { it.dropLast(1) to it.last() }

private fun searchModuleDescriptors(
    module:ModuleDescriptor,
    segments: List<String>,
    predicate: (DeclarationDescriptor, CompletionItem) -> Boolean = {_,_->true}
) = module
    .getPackage(FqName.fromSegments(segments))
    .memberScope
    .getContributedDescriptors()
    .map { it to CompletionItemBuilderVisitor.completionItem(it, nameOnly=true)}
    .filter { predicate(it.first, it.second) }
    .map {it.second}
    .also { log.finer {"Found ${it.size} import candidates. ${it.map{it.label}}"} }

/** Complete 'import x.y' */
fun getImportCompletion(
    path: List<PsiElement>,
    cursor:Int,
    module: ModuleDescriptor
): Sequence<CompletionItem> = sequence {
    val (segments, pattern) = extractImportOrPackageDirectiveSegments<KtImportDirective>("import", path, cursor)
        ?: return@sequence
    log.finer{"Searching package ${segments} for ${pattern}"}
    yieldAll(searchModuleDescriptors(module, segments) { _,item ->
        item.insertText.startsWith(pattern)
    })
}
/** Complete 'package x.y.' */
fun getPackageCompletion(
    path: List<PsiElement>,
    cursor:Int,
    module: ModuleDescriptor
): Sequence<CompletionItem> = sequence {
    val (segments, pattern) = extractImportOrPackageDirectiveSegments<KtPackageDirective>("package", path, cursor)
        ?: return@sequence
    log.finer{"Searching package ${segments} for ${pattern}"}
    yieldAll(searchModuleDescriptors(module, segments) { descr,item ->
        item.insertText.startsWith(pattern) &&  descr is KtPackageDirective
    })
}

fun filterVisibleAt(
    declaration: DeclarationDescriptor
): (DeclarationDescriptor) -> Boolean {
    fun isParentClass(decl: DeclarationDescriptor): ClassDescriptor? =
        if (!DescriptorUtils.isCompanionObject(decl))
            decl as? ClassDescriptor
        else null

    val sourceFile = DescriptorUtils.getContainingSourceFile(declaration)
    val parentClasses = declaration.parentsWithSelf.mapNotNull(::isParentClass).toList()

    return { other ->
        val declaringClass by lazy {
                    other.parentsWithSelf
                         .mapNotNull(::isParentClass)
                         .firstOrNull()}
        when ((other as? DeclarationDescriptorWithVisibility)?.visibility?.delegate) {
            Visibilities.Private,
            Visibilities.PrivateToThis ->
                if (DescriptorUtils.isTopLevelDeclaration(other))
                    DescriptorUtils.getContainingSourceFile(other).name ==
                        sourceFile.name
                else
                    declaringClass
                        ?.let { cls-> parentClasses.any { it.fqNameSafe == cls.fqNameSafe } }
                        ?: false
            Visibilities.Protected ->
                    declaringClass
                        ?.let { decl -> parentClasses.firstOrNull()?.let{decl to it}  }
                        ?.let { (subCls, superCls) -> DescriptorUtils.isSubclass(subCls, superCls) }
                        ?: false
           else -> true
       }
    }
}

fun findLexicalScopedCompletions(
    context: BindingContext,
    path: List<PsiElement>,
    cursor: Int,
) = sequence{
    val lexicalScope = context.lexicalScopeAt(cursor) ?: return@sequence
    val isVisible=filterVisibleAt(lexicalScope.ownerDescriptor)
    val thisCandidates=lexicalScope.parentsWithSelf
        .mapNotNull{(it as? LexicalScope)?.ownerDescriptor}
        .distinct()
        .filterIsInstance<org.jetbrains.kotlin.descriptors.impl.AbstractClassDescriptor>()
        .map{it.getDefaultType()}
        .also{log.finer{"using following candidates for this ${it.toList()}"}}
    val functionCandidates=lexicalScope
        .getDeclarationsInScope()
        .filter(isVisible)
        .filter {
            (it as? CallableDescriptor)
                ?.extensionReceiverParameter
                ?.let { extensionTarget->
                    thisCandidates
                        .any { t-> KotlinTypeChecker.DEFAULT.isSubtypeOf(extensionTarget.type, t) }
                } ?: true
        }
    val segments= path.toList()
        .takeWhile { it !is KtBlockExpression }
        .reversed()
        .also { log.finer{"current expression path ${it}"}}
    segments
        .dropLast(1)
        .fold(functionCandidates) { items, segment ->
            when (segment) {
                is KtQualifiedExpression -> {
                    val segmentPath = segment
                        .textUntilCursor(cursor)
                        .split('.')
                        .dropLast(1)
                        .also{log.finer{"selecting property path ${it}"}}
                    segmentPath.fold(items) { itms, p ->
                        itms.filter{ it.label() == p }
                            .flatMap{when(it) {
                                is PropertyDescriptor ->
                                    it.type.unwrap().memberScope.let { scope->
                                        sequence<DeclarationDescriptor> {
                                            scope.getFunctionNames()
                                                .flatMap{name->scope.getContributedFunctions(name, NoLookupLocation.FROM_IDE)}
                                                .let{yieldAll(it)}
                                            scope.getVariableNames()
                                                .flatMap{name->scope.getContributedVariables(name, NoLookupLocation.FROM_IDE)}
                                                .let{yieldAll(it)}
                                        }
                                    }
                                    .filter(isVisible)
                                else -> {
                                    log.fine{"Ignores children of ${it} ${it::class.java}"}
                                    emptySequence()
                                }
                            }}
                    }
                }
                is KtReferenceExpression -> {
                    val text=segment.textUntilCursor(cursor)
                    log.finer{"Filtering completions starting with ${text}"}
                    items.filter{it.label()?.startsWith(text) ?: false}
                }
                /* is PsiErrorElement -> { */
                /*     log.finer{"Got error node ${segment.textUntilCursor(cursor)} (${segment})"} */
                /*     items */
                /* } */
                else -> {
                    log.finer{"Other: ${segment} ${segment.textUntilCursor(cursor)}"}
                    items
                }
            }
        }
        .map{CompletionItemBuilderVisitor.completionItem(it, nameOnly=true) }
        .also{yieldAll(it)}
}


/** Finds completions at the specified position. */
fun getCompletions(
    file: SourceFile.Compiled,
    cursor: Int,
    index: SymbolIndex?,
): CompletionList = sequence {
    file.ktFile.findElementAt(kotlin.math.max(0, cursor-1))
        .also {
            if(it==null)
                log.finer{"Found no element at ${file.ktFile.describePosition(cursor)}"}
            else
                log.fine{"Attempting to complete ${file.ktFile.describePosition(cursor)} '${it.text}'"}
        }
        ?.let { element->
            val path=element.parentsWithSelf.toList()
            log.finer{"Completing path: ${path}"}
            yieldAll(getTopLevelOperatorCompletions(path, cursor))
            yieldAll(getPackageCompletion(path, cursor, file.module))
            yieldAll(getImportCompletion(path, cursor, file.module))
            yieldAll(findLexicalScopedCompletions(file.context, path, cursor))

                        /* file.context.lexicalScopeAt(cursor) */
                        /*     ?.let{it.getDeclarationsInScope()} */
                        /*     ?.map{CompletionItemBuilderVisitor.completionItem(it)} */
                        /*     ?.also{yieldAll(it)} */
            /* (path[0] as? KtUserType) */
            /*     /1* ?.takeIf { it.qualifier!=null } *1/ */
            /*     ?.let { */
            /*         log.finer {"test ${it}"} */
                    /* file */
                    /*     .referenceAtPoint(it.qualifier!!.startOffset) */
                    /*     ?.let { it as? ClassDescriptor } */
                    /*     ?.also{ log.finer("Completing members of ${it.fqNameSafe}") } */
                    /*     ?.getDescriptors() */
                    /*     ?.let {log.finer{"test test ${it}"}} */
                /* } */

            /* element */
            /*     .let { */
            /*             // :? */
            /*             it as? KtUserType */
            /*             ?: it.parent as? KtTypeElement */
            /*             // .? */
            /*             ?: it as? KtQualifiedExpression */
            /*             ?: it.parent as? KtQualifiedExpression */
            /*             // something.foo() with cursor in the method */
            /*             ?: it.parent?.parent as? KtQualifiedExpression */
            /*             // something::? */
            /*             ?: it as? KtCallableReferenceExpression */
            /*             ?: it.parent as? KtCallableReferenceExpression */
            /*             // ? */
            /*             ?: it as? KtNameReferenceExpression */
            /*     } */
            /*     ?.let { element -> */
            /*         log.finer{"Matching completable element ${element.text}"} */
            /*         log.finer("Completing '${element .peekCursorPosition(cursor, hws=30)}'") */
            /*         when (element) { */
            /*             // :? */
            /*             is KtTypeElement -> { */
            /*                 // : Outer.? */
            /*                 if (element  is KtUserType && element .qualifier != null) { */
            /*                 } else { */
            /*                     // : ? */
            /*                     /1* log.finer("Completing type identifier '${element .text}'") *1/ */
            /*                     file.context.lexicalScopeAt(cursor) */
            /*                         ?.parentsWithSelf */
            /*                         ?.flatMap { */
            /*                             it.getContributedDescriptors( */
            /*                                 DescriptorKindFilter( */
            /*                                     DescriptorKindFilter.NON_SINGLETON_CLASSIFIERS_MASK */
            /*                                     or DescriptorKindFilter.TYPE_ALIASES_MASK)) */
            /*                         } */
            /*                         ?: emptySequence() */
            /*                 } */
            /*             } */
            /*             // .? */
            /*             is KtQualifiedExpression -> { */
            /*                 /1* log.finer("Completing member expression '${element .text}'") *1/ */
            /*                 completeMembers(file, cursor, element .receiverExpression, element  is KtSafeQualifiedExpression) */
            /*             } */
            /*             is KtCallableReferenceExpression -> { */
            /*                 // something::? */
            /*                 if (element .receiverExpression != null) { */
            /*                     /1* log.finer("Completing method reference '${element .text}'") *1/ */
            /*                     completeMembers(file, cursor, element .receiverExpression!!) */
            /*                 } */
            /*                 // ::? */
            /*                 else { */
            /*                     /1* log.finer("Completing function reference '${element .text}'") *1/ */
            /*                     file.context.lexicalScopeAt(element .startOffset) */
            /*                         ?.let{ it.getDeclarationsInScope() } */
            /*                         ?: run{ */
            /*                             log.finer{"No scope at ${file.describePosition(cursor)}"} */
            /*                             emptySequence() */
            /*                         } */
            /*                 } */
            /*             } */
            /*             // ? */
            /*             is KtNameReferenceExpression -> { */
            /*                 /1* log.finer("Completing identifier '${element .text}'") *1/ */
            /*                 file.context.lexicalScopeAt(element .startOffset) */
            /*                     ?.let{it.getDeclarationsInScope() } */
            /*                     ?: run{ */
            /*                         log.finer{"No scope at ${file.describePosition(cursor)}"} */
            /*                         emptySequence() */
            /*                     } */
            /*             } */
            /*             else -> emptySequence() */
            /*         } */
            /*     } */
            /*     ?.filter { */
            /*         val name= */
            /*             if (it is ConstructorDescriptor) */
            /*                 it.constructedClass.name.identifier */
            /*             else */
            /*                 it.name.identifier */
            /*         val partial = file.content */
            /*             .take(cursor) */
            /*             .takeLastWhile{ it.isLetter() } */
            /*             .also{log.debug("Looking for completions that match '${it}'")} */
            /*         name.startsWith(partial, ignoreCase=true) */
            /*     } */
            /*     ?.let { */
            /*         val from = file.elementAtPoint(cursor) */
            /*             ?.parentsWithSelf */
            /*             ?.mapNotNull { file.context[BindingContext.DECLARATION_TO_DESCRIPTOR, it] } */
            /*             ?.firstOrNull() */

            /*         if (from==null) it */
            /*         else it.filter({ target-> */
            /*             target.parentsWithSelf */
            /*                .filterIsInstance<DeclarationDescriptorWithVisibility>() */
            /*                .none { target-> */
            /*                    when (target.visibility.delegate) { */
            /*                         Visibilities.Private, Visibilities.PrivateToThis -> */
            /*                             if (DescriptorUtils.isTopLevelDeclaration(target)) */
            /*                                 DescriptorUtils.getContainingSourceFile(target).name != */
            /*                                     DescriptorUtils.getContainingSourceFile(from).name */
            /*                             else { */
            /*                                 target.parentsWithSelf */
            /*                                      .mapNotNull(::isParentClass) */
            /*                                      .firstOrNull() */
            /*                                      ?.let {targetParent-> */
            /*                                          from.parentsWithSelf */
            /*                                              .mapNotNull(::isParentClass) */
            /*                                              .none { it.fqNameSafe == targetParent.fqNameSafe } */
            /*                                      } */
            /*                                      ?: true */
            /*                             } */
            /*                         Visibilities.Protected -> { */
            /*                             target.parentsWithSelf */
            /*                                 .mapNotNull(::isParentClass) */
            /*                                 .firstOrNull() */
            /*                                 ?.let { targetParent-> */
            /*                                     from.parentsWithSelf */
            /*                                         .mapNotNull(::isParentClass) */
            /*                                         .toList() */
            /*                                         .takeIf{!it.isEmpty()} */
            /*                                         ?.any { DescriptorUtils.isSubclass(it, targetParent) } */
            /*                                 } */
            /*                                 ?: true */
            /*                             } */
            /*                        else -> false */
            /*                    } */
            /*                } */
            /*         }) */
            /*     } */
            /*     ?.map { CompletionItemBuilderVisitor.completionItem(it) } */
            /*     ?.let { yieldAll(it) } */
        }
}
    .takeWithExhaustiveCheck(75)
    .let { (isExhaustive, items)->
        CompletionList(
            isExhaustive,
            items.onEachIndexed { i, item ->
                item.sortText = i.toString().padStart(2, '0')
            })
    }

private fun <T> Sequence<T>.takeWithExhaustiveCheck(cnt: Int) =
    take(cnt+1)
    .toList()
    .let { (it.size>= cnt) to it.take(cnt) }

/** Finds completions in the global symbol index, for potentially unimported symbols. */
private fun indexCompletionItems(
    file: SourceFile.Compiled,
    cursor: Int,
    element: KtElement?,
    index: SymbolIndex?,
    partial: String,
    maxCompletionItems: Int
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
            file.getQueryNameFromExpression(element.receiverExpression, element.receiverExpression.startOffset)
        is KtSimpleNameExpression ->
            element.getReceiverExpression()
                ?.let{ file.getQueryNameFromExpression(it, it.startOffset) }
        is KtUserType ->
            file.referenceAtPoint(element.qualifier?.startOffset ?: cursor)
                ?.fqNameSafe
        is KtTypeElement ->
            file.referenceAtPoint(element.startOffsetInParent)
                ?.fqNameOrNull()
        else -> null
    }?.toString()

    return (index
        ?.query(partial, queryName, limit = maxCompletionItems)
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

private class CompletionItemBuilderVisitor(
    val nameOnly: Boolean
): DeclarationDescriptorVisitor<CompletionItem, Unit> {
    companion object {
        fun completionItem(
            declaration: DeclarationDescriptor,
            nameOnly: Boolean = false
        ): CompletionItem = declaration.accept(CompletionItemBuilderVisitor(nameOnly), null)
    }

    override fun visitConstructorDescriptor(desc: ConstructorDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) {
            kind = CompletionItemKind.Constructor
            insertText = functionInsertText(desc)
            insertTextFormat = if (nameOnly) PlainText else Snippet
        }

    override fun visitFunctionDescriptor(desc: FunctionDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) {
            kind = CompletionItemKind.Function
            insertText = functionInsertText(desc)
            insertTextFormat = if (nameOnly) PlainText else Snippet
        }

    override fun visitClassDescriptor(desc: ClassDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) {
            kind = when (desc.kind) {
                ClassKind.INTERFACE -> CompletionItemKind.Interface
                ClassKind.ENUM_CLASS -> CompletionItemKind.Enum
                ClassKind.ENUM_ENTRY -> CompletionItemKind.EnumMember
                else -> CompletionItemKind.Class
            }
        }

    override fun visitPropertySetterDescriptor(desc: PropertySetterDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Field }
    override fun visitModuleDeclaration(desc: ModuleDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Module }
    override fun visitReceiverParameterDescriptor(desc: ReceiverParameterDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Variable }
    override fun visitPackageViewDescriptor(desc: PackageViewDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Module }
    override fun visitPackageFragmentDescriptor(desc: PackageFragmentDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Module }
    override fun visitValueParameterDescriptor(desc: ValueParameterDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Variable }
    override fun visitTypeParameterDescriptor(desc: TypeParameterDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Variable }
    override fun visitScriptDescriptor(desc: ScriptDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Module }
    override fun visitTypeAliasDescriptor(desc: TypeAliasDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Variable }
    override fun visitPropertyGetterDescriptor(desc: PropertyGetterDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Field }
    override fun visitVariableDescriptor(desc: VariableDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Variable }
    override fun visitPropertyDescriptor(desc: PropertyDescriptor, nothing: Unit?): CompletionItem =
        withDefaults(desc) { kind = CompletionItemKind.Field }

    private fun withDefaults(
        declaration: DeclarationDescriptor,
        configure: CompletionItem.()->Unit
    ) = CompletionItem()
        .apply {
            label = declaration.label()
            filterText = declaration.label()
            insertText = escape(declaration.label()!!)
            insertTextFormat = PlainText
            detail = declaration.describeAlt()
        }
        .apply { configure() }
        .apply {
            // Check if it is a property
            if (declaration.isNotStaticJavaMethod()
                    && declaration is CallableDescriptor
                    && !declaration.name.isSpecial) {
                pattern.property.matchEntire(declaration.name.identifier)
                    ?.groups
                    ?.let { it[1]?.value to it[2]!!.value.replaceFirstChar{it.lowercaseChar()} }
                    ?.also { (type, name)->
                        when (type) {
                            null, "get" -> if(declaration.valueParameters.size!=0) return@also
                            "set" -> if(declaration.valueParameters.size!=1) return@also
                            else -> throw Error("Property pattern should not match ${declaration.name.identifier}! Something went wrong here")
                        }
                        detail += " (from ${label})"
                        label = name
                        insertText = name
                        filterText = name
                    }
            }
            if (KotlinBuiltIns.isDeprecated(declaration)) {
                tags = listOf(CompletionItemTag.Deprecated)
            }
            pattern.call.matchEntire(insertText)
                ?.groups
                ?.get(1)
                ?.value
                ?.let { insertText = it }
        }

    private fun DeclarationDescriptor.isNotStaticJavaMethod(): Boolean =
        this
            .let{it as? JavaMethodDescriptor}
            ?.let{it.source as? JavaSourceElement }
            ?.javaElement
            ?.let { it is JavaMethod && !it.isStatic}
            ?: true

    private fun escape(id: String): String =
        if (id.matches(pattern.GOOD_IDENTIFIER)) id
        else "`$id`"

    private fun functionInsertText(desc: FunctionDescriptor): String {
        val name = escape(desc.label()!!)
        return if (nameOnly) {
            name
        } else {
            val parameters = desc.valueParameters
            val hasTrailingLambda = parameters.lastOrNull()?.type?.isFunctionType ?: false
            if (hasTrailingLambda) {
                val parenthesizedParams = parameters
                    .dropLast(1)
                    .ifEmpty { null }
                    ?.let { "(${valueParametersSnippet(it)})" }
                    ?: ""
                "$name${parenthesizedParams} { \${${parameters.size}:${parameters.last().name}} }"
            } else {
                "$name(${valueParametersSnippet(parameters)})"
            }
        }
    }

    private fun valueParametersSnippet(parameters: List<ValueParameterDescriptor>) = parameters
        .asSequence()
        .filterNot { it.declaresDefaultValue() }
        .mapIndexed { index, vpd -> "\${${index + 1}:${vpd.name}}" }
        .joinToString()
}

private fun DeclarationDescriptor.label(): String? = when {
    this is ConstructorDescriptor -> this.containingDeclaration.name.identifier
    this.name.isSpecial -> null
    else -> this.name.identifier
}

private val log by findLogger.atToplevel(object{})


private fun completeMembers(
    file: SourceFile.Compiled,
    cursor: Int,
    receiverExpr: KtExpression,
    unwrapNullable: Boolean = false
): Sequence<DeclarationDescriptor> = sequence {
    // thingWithType.?
    file.context.lexicalScopeAt(cursor)
        ?.also { lexicalScope ->
            file.compiler
                .compileKtExpression(receiverExpr, lexicalScope, file.sourcePath)
                .getType(receiverExpr)
                ?.let { expressionType ->
                    if (unwrapNullable) try {
                        TypeUtils.makeNotNullable(expressionType)
                    } catch (e: Exception) {
                        log.error(e, "Exception compiling member")
                        expressionType
                    } else expressionType
                }
                ?.also { receiverType ->
                    log.debug("Completing members of instance '${receiverType}'")
                    yieldAll(receiverType.memberScope.getContributedDescriptors())
                    yieldAll(lexicalScope.parentsWithSelf
                        .flatMap{ it.getContributedDescriptors(DescriptorKindFilter.CALLABLES) }
                        .filterIsInstance<CallableDescriptor>()
                        .filter { it.isExtension }
                        .filter {
                            it.extensionReceiverParameter
                                ?.type
                                ?.replaceArgumentsWithStarProjections()
                                ?.let{
                                    KotlinTypeChecker.DEFAULT.isSubtypeOf(receiverType, it)
                                        || (TypeUtils.getTypeParameterDescriptorOrNull(it)
                                    ?.isGenericExtensionFor(receiverType) ?: false)
                                }
                                ?: false
                            })

                    val isCompanionOfEnumOrSealed=TypeUtils.getClassDescriptor(receiverType).let {
                        DescriptorUtils.isCompanionObject(it)
                            && (DescriptorUtils.isEnumClass(it?.containingDeclaration)
                               || DescriptorUtils.isSealedClass(it?.containingDeclaration))
                    }

                    if (!(isCompanionOfEnumOrSealed)) {
                        return@sequence
                    }
                }
        }

    // JavaClass.?
    val referenceTarget = file.referenceAtPoint(receiverExpr.endOffset - 1)
    if (referenceTarget is ClassDescriptor) {
        log.debug("Completing members of '${referenceTarget.fqNameSafe}'")
        yieldAll(referenceTarget.getDescriptors())
    }
    log.debug("Can't find member scope for ${receiverExpr.text}")
}

private fun TypeParameterDescriptor.isGenericExtensionFor(type: KotlinType): Boolean =
    upperBounds.all { KotlinTypeChecker.DEFAULT.isSubtypeOf(type, it) }

private fun ClassDescriptor.getDescriptors(
): Sequence<DeclarationDescriptor> = sequence{
    yieldAll(staticScope.getContributedDescriptors())
    yieldAll(unsubstitutedInnerClassesScope.getContributedDescriptors())
    yieldAll(unsubstitutedMemberScope.getContributedDescriptors())
    if (hasCompanionObject)
        companionObjectDescriptor
            ?.getDescriptors()
            ?.also { yieldAll(it)}
}.toSet().asSequence()

private fun SourceFile.Compiled.getQueryNameFromExpression(
    receiver: KtExpression?,
    cursor: Int,
): FqName? =
    receiver
        ?.let { expression ->
            this.context.lexicalScopeAt(cursor)
                ?.let { scopeWithImports->
                    this.compiler
                        .compileKtExpression(expression, scopeWithImports, this.sourcePath)
                        .getType(expression)
                }
        }
        ?.constructor
        ?.declarationDescriptor
        ?.fqNameSafe

private fun isParentClass(declaration: DeclarationDescriptor): ClassDescriptor? =
    if (declaration is ClassDescriptor && !DescriptorUtils.isCompanionObject(declaration))
        declaration
    else null
