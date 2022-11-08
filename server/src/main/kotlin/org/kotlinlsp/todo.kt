package org.kotlinlsp

import arrow.core.Either
import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.FormattingOptions as KtfmtOptions
import com.google.common.cache.CacheBuilder
import com.intellij.openapi.util.TextRange
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
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope
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
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice

import org.kotlinlsp.util.pair.*
import org.kotlinlsp.file.FileProvider
import org.kotlinlsp.file.TemporaryDirectory
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.index.receiverTypeFqn
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
import org.kotlinlsp.util.asTextRange
import org.kotlinlsp.prettyprint.*
import org.kotlinlsp.source.context.lexicalScopeAt
import org.kotlinlsp.source.context.getDeclarationsInScope
import org.kotlinlsp.source.context.findDeclarationAt
import org.kotlinlsp.source.context.unwrapReferenceExpression

private val log by findLogger.atToplevel(object{})

/**
 * Find the declaration of the element at the cursor.
 */
fun SourceFile.Compiled.findDeclaration(cursor: Int): Pair<KtNamedDeclaration, Location>? =
    (referenceAtPoint(cursor)
        ?.findPsi()
        ?.let { it as? KtNamedDeclaration }
    ?: elementAtPoint(cursor)
        ?.findParent<KtNamedDeclaration>())

        ?.let{it to it}
        ?.mapSecondNotNull { decl->
            decl.nameIdentifier
                ?.locationInFile()
        }

inline fun<reified T> PsiElement.findParent() =
        this.parentsWithSelf.filterIsInstance<T>().firstOrNull()

fun PsiElement.locationInFile(): Location? =
    containingFile
        ?.text
        ?.let { Location(
            containingFile.toPath().toUri().toString(),
            textRange.toLsp4jRange(it))
        }

fun PsiFile.toPath(): Path =
    this.originalFile.viewProvider.virtualFile.path.let{ path->
        if (path.get(2) == ':' && path.get(0) == '/') {
            // Strip leading '/' when dealing with paths on Windows
            return Paths.get(path.substring(1))
        } else {
            return Paths.get(path)
        }
    }

fun SourceFile.Compiled.referenceAtPoint(
    cursor: Int
): DeclarationDescriptor? =
    ktFile.findElementAt(cursor)
        ?.findParent<KtExpression>()
        ?.unwrapReferenceExpression()
        .let {it?: run{
            log.info{"Couldn't find expression at ${describePosition(cursor)}"}
            return null
        }}
        .let { expression->
            compiler
                .compileKtExpression(
                    expression=expression,
                    scopeWithImports=context.lexicalScopeAt(cursor)
                        ?: run{
                            log.info{"Couldn't find lexicalScope at ${describePosition(cursor)}"}
                            return null
                        },
                    sourcePath=sourcePath)
                .findDeclarationAt(cursor)
        }

/**
 * Get the typed, compiled element at `cursor`.
 * This may be out-of-date if the user is typing quickly.
 */
fun SourceFile.Compiled.elementAtPoint(cursor: Int): KtElement? =
    ktFile
        .findElementAt(cursor)
        ?.findParent<KtElement>()
        .also {if (it==null) log.info{"Couldn't find anything at ${describePosition(cursor)}"} }

fun markup(hoverText: String, body: String?) =
    MarkupContent(
        "markdown",
        "```kotlin\n$hoverText\n```".let{
            if (body.isNullOrBlank()) it
            else "${it}\n---\n${body}"
        })

fun extractDocstring(declaration: DeclarationDescriptorWithSource): String =
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

@Suppress("UNUSED_PARAMETER")
fun goToDefinition(
    file: SourceFile.Compiled,
    cursor: Int,
    classContentProvider: FileProvider,
    tempDir: TemporaryDirectory,
): Location? {
    /* val definitionPattern = "(?:class|interface|object|fun)\\s+(\\w+)".toRegex() */
    /* val target = file.context.findDeclarationAt(cursor) ?: return null */

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
    /*                 ?.let { destination.range = it.toLsp4jRange(content) } */
    /*         } */
    /*     } */

    /* return destination */
    TODO()
}

    /* val javaHome: String? = System.getProperty("java.home", null) */
/* private fun isInsideArchive(uri: String, classPath: CompilerClassPath) = */
/*     uri.contains(".jar!") || uri.contains(".zip!") || classPath.javaHome?.let { */
/*         Paths.get(parseURI(uri)).toString().startsWith(File(it).path) */
/*     } ?: false */

fun PsiElement.preOrderTraversal(): Sequence<PsiElement> =
    sequenceOf(this) + this.children.flatMap{ it.preOrderTraversal() }

private fun KtExpression.expandForType(cursor: Int): KtExpression =
    this.parent
        .let { it as? KtDotQualifiedExpression }
        .let { dotParent ->
            dotParent
                ?.selectorExpression
                ?.textRange
                ?.takeIf{it.contains(cursor)}
                ?.let { dotParent.expandForType(cursor) }
        } ?: this

fun getCandidates(call: KtCallExpression, file: SourceFile.Compiled) =
    call.calleeExpression
        ?.let { it.text to it }
        ?.mapSecondNotNull { target ->
            target
                .findParent<KtDotQualifiedExpression>()
                ?.let { dotParent->
                    val cursor= dotParent.receiverExpression.startOffset
                    val surroundingExpr = file.ktFile.findElementAt(cursor)
                        ?.findParent<KtExpression>()
                        ?.expandForType(cursor)
                    val scope = file.context.lexicalScopeAt(cursor)
                    if (surroundingExpr!=null && scope!=null)
                        file.compiler.compileKtExpression(surroundingExpr, scope, file.sourcePath)
                            .getType(surroundingExpr)
                    else null
                }
                ?.let { type->
                    type.memberScope
                        .getContributedDescriptors(Companion.CALLABLES)
                        .asSequence()
                }
                ?.filterIsInstance<CallableDescriptor>()
            ?: target
                .findParent<KtNameReferenceExpression>()
                ?.let { file.context.lexicalScopeAt(it.startOffset) }
                ?.let {
                    it.getDeclarationsInScope()
                }
                ?.filterIsInstance<CallableDescriptor>()
        }
        ?.let { (targetText, it)->
            it.filter { targetText==
                if (it is ConstructorDescriptor)
                    it.constructedClass.name.identifier
                else
                    it.name.identifier
            }
        }
        ?.toList()


fun findReferences(file: Path, cursor: Int, sp: SourceFileRepository): List<Location> =
    sp.compileFile(file.toUri())
        .let{it?: run {
            log.info{"Unable to compile file ${file}"}
            return emptyList()
        }}
        .let { compiled ->
            compiled.elementAtPoint(cursor)
                ?.findParent<KtNamedDeclaration>()
                ?.let { doFindReferences(it, sp) }
                ?: run {
                    log.info{"No declaration at ${compiled.describePosition(cursor)}"}
                    emptyList()
                }
        }
        .map { it.locationInFile() }
        .filterNotNull()
        .toList()
        .sortedWith(compareBy({ it.getUri() }, { it.getRange().getStart().getLine() }))

fun findReferences(declaration: KtNamedDeclaration, sp: SourceFileRepository): List<Location> =
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
fun findReferencesToDeclarationInFile(declaration: KtNamedDeclaration, file: SourceFile.Compiled): List<Range> {
    val descriptor = file.context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
        ?: run{
            log.info{"Declaration ${declaration.fqName} has no descriptor"}
            return emptyList()
        }
    return innerFindReferences(file.context, declaration, descriptor)
        .mapNotNull { it.locationInFile()?.range }
        .sortedWith(compareBy({ it.start.line }))
        .toList()
}

private fun doFindReferences(declaration: KtNamedDeclaration, sp: SourceFileRepository): Collection<KtElement> {
    val file=sp.compileFile(declaration.containingFile.toPath().toUri())
        ?: run {
            log.info{"Failed to compile ${declaration.fqName}"}
            return emptyList()
        }

    val descriptor = file.context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
        ?: run {
            log.info{"Declaration ${declaration.fqName} has no descriptor"}
            return emptyList()
        }
    val context = possibleReferences(descriptor, sp)
        .map { it.toPath().toUri() }
        .toSet()
        .also { log.debug("Scanning ${it.size} files for references to ${declaration.fqName}") }
        .let{ sp.compileFiles(it) }
        .map{it.context}
        .distinct()
        .let { CompositeBindingContext.create(it)}

    return innerFindReferences(context, declaration, descriptor)
        .toList()
}

private fun innerFindReferences(
    context: BindingContext,
    element: KtNamedDeclaration,
    descriptor: DeclarationDescriptor,
): Sequence<KtElement> = sequence {
    fun matchesReference(found: DeclarationDescriptor) =
        if (found is ConstructorDescriptor && found.isPrimary)
            element is KtClass && found.constructedClass.fqNameSafe == element.fqName
        else
            found.findPsi() == element
    fun <K: Any, V: ResolvedCall<FunctionDescriptor>> find(
        slice: ReadOnlySlice<K, V>
    ): List<KtElement> = context
        .getSliceContents(slice)
        .map{it.value}
        .filter { matchesReference(it.candidateDescriptor) }
        .map { it.call.callElement }
    when {
        isComponent(descriptor) ->
            yieldAll(find(BindingContext.COMPONENT_RESOLVED_CALL))
        isIterator(descriptor) ->
            yieldAll(find(BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL))
        isPropertyDelegate(descriptor) ->
            yieldAll(find(BindingContext.DELEGATED_PROPERTY_RESOLVED_CALL))
    }
    yieldAll(context
        .getSliceContents(BindingContext.REFERENCE_TARGET)
        .filter { matchesReference(it.value) }
        .map { it.key })
}

private fun possibleReferences(
    declaration: DeclarationDescriptor,
    sp: SourceFileRepository
): Sequence<KtFile> = sequence {
    fun findMatching(filter: (Sequence<PsiElement>)->Boolean) =
        sp.allFiles().filter { it.preOrderTraversal().let{filter(it)} }
    when {
        declaration is ClassConstructorDescriptor-> {}
        isComponent(declaration) ->
            yieldAll(findMatching{it.filterIsInstance<KtDestructuringDeclarationEntry>().any()})
        isPropertyDelegate(declaration) ->
            yieldAll(findMatching{it.filterIsInstance<KtPropertyDelegate>().any()})
        isGetSet(declaration) ->
            yieldAll(findMatching{it.filterIsInstance<KtArrayAccessExpression>().any()})
        isIterator(declaration) ->
            yieldAll(findMatching{it.filterIsInstance<KtForExpression>().any()})
        declaration is FunctionDescriptor-> {
            if (declaration.isOperator && declaration.name == OperatorNameConventions.INVOKE) {
                yieldAll(findMatching{it.filterIsInstance<KtCallExpression>().any()})
            } else {
                val find = when (val name=declaration.name) {
                    OperatorNameConventions.EQUALS ->
                        listOf(KtTokens.EQEQ)
                    OperatorNameConventions.COMPARE_TO ->
                        listOf(KtTokens.GT, KtTokens.LT, KtTokens.LTEQ, KtTokens.GTEQ)
                    else ->
                        listOfNotNull(
                            OperatorConventions.UNARY_OPERATION_NAMES.inverse()[name]
                            ?: OperatorConventions.BINARY_OPERATION_NAMES.inverse()[name]
                            ?: OperatorConventions.ASSIGNMENT_OPERATIONS.inverse()[name]
                            ?: OperatorConventions.BOOLEAN_OPERATIONS.inverse()[name])
                }
                yieldAll(findMatching{
                    it.filterIsInstance<KtOperationReferenceExpression>()
                        .any { it.operationSignTokenType in find }})
            }
        }
    }
    val name = if(declaration is ClassConstructorDescriptor)
                    declaration.constructedClass.name
               else declaration.name
    yieldAll(findMatching{
        it.filterIsInstance<KtSimpleNameExpression>()
            .any { it.getReferencedNameAsName() == name }})
}

// TODO use imports to limit search

private fun isPropertyDelegate(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        (declaration.name == OperatorNameConventions.GET_VALUE
         || declaration.name == OperatorNameConventions.SET_VALUE)


private fun isIterator(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        declaration.name == OperatorNameConventions.ITERATOR

private fun isGetSet(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        (declaration.name == OperatorNameConventions.GET || declaration.name == OperatorNameConventions.SET)

private fun isComponent(declaration: DeclarationDescriptor): Boolean =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        OperatorNameConventions.COMPONENT_REGEX.matches(declaration.name.identifier)


fun interface QuickFix {
    // Computes the quickfix. Return empty list if the quickfix is not valid or no alternatives exist.
    fun compute(file: SourceFile.Compiled, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>>
}


class AddMissingImportsQuickFix(
    val index: SymbolIndex?
): QuickFix {
    override fun compute(file: SourceFile.Compiled, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>> {
        val uri = file.ktFile.toPath().toUri().toString()
        return diagnostics
            .filter { "UNRESOLVED_REFERENCE" == it.code.left.trim() }
            .flatMap { diagnostic ->
                index
                    ?.query(diagnostic.range.extractRange(file.content), exact = true)
                    ?.filter {
                        it.kind != Symbol.Kind.MODULE &&
                        // TODO: Visibility checker should be less liberal
                        (it.visibility == Symbol.Visibility.PUBLIC
                         || it.visibility == Symbol.Visibility.PROTECTED
                         || it.visibility == Symbol.Visibility.INTERNAL)
                    }
                    ?.map { it.fqName.toString() to getImportTextEditEntry(file.ktFile, it.fqName) }
                    .let{it?: listOf()}
                    .map { (importStr, edit) ->
                        CodeAction().apply {
                            title = "Import ${importStr}"
                            kind = CodeActionKind.QuickFix
                            this.diagnostics = listOf(diagnostic)
                            this.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                        }.let{Either.Right(it)}
                    }
            }
    }
}

class ImplementAbstractMembersQuickFix : QuickFix {
    override fun compute(
        file: SourceFile.Compiled,
        range: Range,
        diagnostics: List<Diagnostic>
    ): List<Either<Command, CodeAction>> {
        val diag = diagnostics
            .filter{ range.isSubrangeOf(it.range) }
            .find { it->
                hashSetOf(
                    "ABSTRACT_MEMBER_NOT_IMPLEMENTED",
                    "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED"
                ).contains(it.code.left)
            }
            ?: return listOf()

        val textRange=range.asTextRange(file.content)

        // If the client side and the server side diagnostics contain a valid diagnostic for this range.
        file.context.diagnostics
            .any { diagnostic->
                hashSetOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED").contains(diagnostic.factory.name)
                && diagnostic.textRanges.any {
                    it.startOffset <= textRange.startOffset && it.endOffset >= textRange.endOffset
                }
            }
            .let { if(!it) return listOf() }

        // Get the class with the missing members
        val kotlinClass = file.ktFile.findElementAt(textRange.startOffset)
            .let{it as? KtClass}
            ?: return listOf()
        val uri = file.ktFile.toPath().toUri().toString()
        // Get the padding to be introduced before the member declarations
        val padding = getDeclarationPadding(file.content, kotlinClass)

        // Get the location where the new code will be placed
        val newMembersStartPosition = getNewMembersStartPosition(file, kotlinClass)
            .let { Range(it, it) }

        val textEdits = sequence {
            if(kotlinClass.body==null)
                yield(TextEdit(newMembersStartPosition, "{"))
            getAbstractMembersStubs(file, kotlinClass)
                .forEach {
                    yield(TextEdit(
                        newMembersStartPosition,
                        System.lineSeparator() + System.lineSeparator() + padding + it))
                }
            if(kotlinClass.body==null)
                yield(TextEdit(newMembersStartPosition, System.lineSeparator() + "}"))
        }.toList()

        return CodeAction()
            .apply{
                edit = WorkspaceEdit(mapOf(uri to textEdits))
                kind = CodeActionKind.QuickFix
                title = "Implement abstract members"
                this.diagnostics = listOf(diag)
            }
            .let {Either.Right(it)}
            .let {listOf(it)}
    }


    private fun getAbstractMembersStubs(file: SourceFile.Compiled, kotlinClass: KtClass) =
        // For each of the super types used by this class
        kotlinClass.superTypeListEntries
            .mapNotNull { ent->
                // Find the definition of this super type
                 file.context.findDeclarationAt(ent.startOffset)
                    ?.let{getClassDescriptor(it)}
                    ?.takeIf{it.kind.isInterface || it.modality == Modality.ABSTRACT }
                    ?.let{
                        it
                            .getMemberScope(getSuperClassTypeProjections(file, ent))
                            .getContributedDescriptors()
                            .filterIsInstance<MemberDescriptor>()
                            .filter{ it.modality == Modality.ABSTRACT }
                            .filter { !overridesDeclaration(kotlinClass, it) }
                            .mapNotNull { tryCreateStub(it) }
                    }
            }
            .flatten()
}

// Checks if the class overrides the given declaration
fun overridesDeclaration(kotlinClass: KtClass, descriptor: MemberDescriptor): Boolean {
    fun List<KtDeclaration>.anyOverrides() = this
        .filter{ it.hasModifier(KtTokens.OVERRIDE_KEYWORD) }
        .filter{ it.name == descriptor.name.asString() }
        .any()
    return when (descriptor) {
        is FunctionDescriptor -> kotlinClass.declarations
            .filter { function->
                if (function is KtNamedFunction) {
                    if (function.valueParameters.size != descriptor.valueParameters.size)
                        return@filter false
                    function.typeParameters.zip(descriptor.typeParameters)
                        .forEach { (l,r)->
                            if(l.variance==r.variance )
                                return@filter false
                        }
                    function.valueParameters.zip(descriptor.valueParameters)
                        .forEach { (l,r)->
                            if (l.name!= r.name.asString())
                                return@filter false
                            l.typeReference
                                ?.let {
                                    it.name
                                        ?: it.typeElement
                                            ?.children
                                            ?.mapNotNull { it as? KtSimpleNameExpression }
                                            ?.map { it.getReferencedName() }
                                            ?.firstOrNull()
                                }
                                ?.takeIf { it != r.type
                                    .let{it.unwrap().makeNullableAsSpecified(it.isMarkedNullable)}
                                    .toString() }
                                ?.also { return@filter false }
                        }
                    return@filter true
                }
                true
            }
            .anyOverrides()
        is PropertyDescriptor -> kotlinClass.declarations
            .anyOverrides()
        else -> false
    }
}

fun getImportTextEditEntry(parsedFile: KtFile, fqName: FqName) =
    parsedFile.packageDirective
        ?.let{ it.locationInFile() }
        ?.range
        ?.end
        .let {it ?: Position(0, 0)}
        .let{Range(it,it)}
        .let{TextEdit(it, "\nimport ${fqName}")}

// interfaces are ClassDescriptors by default. When calling AbstractClass super methods, we get a ClassConstructorDescriptor
fun getClassDescriptor(descriptor: DeclarationDescriptor): ClassDescriptor? =
        if (descriptor is ClassDescriptor)
            descriptor
        else if (descriptor is ClassConstructorDescriptor)
            descriptor.containingDeclaration
        else
            null

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
            (file.context.findDeclarationAt(it?.startOffset ?: 0) as?
                            ClassDescriptor)
                    ?.defaultType?.asTypeProjection()
        }
        ?: emptyList()


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
                .also { it.character = (kotlinClass.text.length + 2) }
            newPosCorrectLine
        }
    }

