package org.kotlinlsp

import java.nio.file.Path
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.kotlinlsp.location
import org.kotlinlsp.logging.*
import org.kotlinlsp.source.CompiledFile
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.toURIString
import org.kotlinlsp.util.emptyResult
import org.kotlinlsp.util.findParent
import org.kotlinlsp.util.preOrderTraversal
import org.kotlinlsp.util.toPath
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.util.OperatorNameConventions

private val log by findLogger.atToplevel(object{})

fun findReferences(file: Path, cursor: Int, sp: SourceFileRepository): List<Location> {
    return doFindReferences(file, cursor, sp)
            .map { location(it) }
            .filterNotNull()
            .toList()
            .sortedWith(compareBy({ it.getUri() }, { it.getRange().getStart().getLine() }))
}

fun findReferences(declaration: KtNamedDeclaration, sp: SourceFileRepository): List<Location> {
    return doFindReferences(declaration, sp)
        .map { location(it) }
        .filterNotNull()
        .toList()
        .sortedWith(compareBy({ it.getUri() }, { it.getRange().getStart().getLine() }))
}

private fun doFindReferences(file: Path, cursor: Int, sp: SourceFileRepository): Collection<KtElement> {
    val recover = sp.compileFile(file.toUri()).asCompiledFile()
    val element = recover.elementAtPoint(cursor)?.findParent<KtNamedDeclaration>() ?: return emptyResult("No declaration at ${recover.describePosition(cursor)}")
    return doFindReferences(element, sp)
}

private fun doFindReferences(element: KtNamedDeclaration, sp: SourceFileRepository): Collection<KtElement> {
    val declaration = sp.compileFile(element.containingFile.toPath().toUri()).asCompiledFile()
        .compile[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
        ?: return emptyResult("Declaration ${element.fqName} has no descriptor")
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

/**
 * Finds references to the named declaration in the given file. The declaration may or may not reside in another file.
 *
 * @returns ranges of references in the file. Empty list if none are found
 */
fun findReferencesToDeclarationInFile(declaration: KtNamedDeclaration, file: CompiledFile): List<Range> {
    val descriptor = file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration] ?: return emptyResult("Declaration ${declaration.fqName} has no descriptor")
    val bindingContext = file.compile

    val references = when {
        isComponent(descriptor) -> findComponentReferences(declaration, bindingContext) + findNameReferences(declaration, bindingContext)
        isIterator(descriptor) -> findIteratorReferences(declaration, bindingContext) + findNameReferences(declaration, bindingContext)
        isPropertyDelegate(descriptor) -> findDelegateReferences(declaration, bindingContext) + findNameReferences(declaration, bindingContext)
        else -> findNameReferences(declaration, bindingContext)
    }

    return references.map {
        location(it)?.range
    }.filterNotNull()
     .sortedWith(compareBy({ it.start.line }))
}

private fun findNameReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtReferenceExpression> {
    val references = recompile.getSliceContents(BindingContext.REFERENCE_TARGET)

    return references.filter { matchesReference(it.value, element) }.map { it.key }
}

private fun findDelegateReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.DELEGATED_PROPERTY_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) }
            .map { it.value.call.callElement }
}

private fun findIteratorReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL)

    return references
            .filter { matchesReference( it.value.candidateDescriptor, element) }
            .map { it.value.call.callElement }
}

private fun findComponentReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.COMPONENT_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) }
            .map { it.value.call.callElement }
}

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
        (declaration.name == OperatorNameConventions.GET_VALUE || declaration.name == OperatorNameConventions.SET_VALUE)

private fun hasPropertyDelegates(sp: SourceFileRepository): Set<KtFile> =
        sp.allFiles().filter(::hasPropertyDelegate).toSet()

fun hasPropertyDelegate(source: KtFile): Boolean =
        source.preOrderTraversal().filterIsInstance<KtPropertyDelegate>().any()

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
        sp.allFiles().filter { possibleInvokeReference(declaration, it) }.toSet()

// TODO this is not very selective
private fun possibleInvokeReference(@Suppress("UNUSED_PARAMETER") declaration: FunctionDescriptor, source: KtFile): Boolean =
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

private fun possibleNameReference(declaration: Name, source: KtFile): Boolean =
        source.preOrderTraversal()
                .filterIsInstance<KtSimpleNameExpression>()
                .any { it.getReferencedNameAsName() == declaration }

private fun matchesReference(found: DeclarationDescriptor, search: KtNamedDeclaration): Boolean {
    if (found is ConstructorDescriptor && found.isPrimary)
        return search is KtClass && found.constructedClass.fqNameSafe == search.fqName
    else
        return found.findPsi() == search
}

private fun operatorNames(name: Name): List<KtSingleValueToken> =
        when (name) {
            OperatorNameConventions.EQUALS -> listOf(KtTokens.EQEQ)
            OperatorNameConventions.COMPARE_TO -> listOf(KtTokens.GT, KtTokens.LT, KtTokens.LTEQ, KtTokens.GTEQ)
            else -> {
                val token = OperatorConventions.UNARY_OPERATION_NAMES.inverse()[name] ?:
                            OperatorConventions.BINARY_OPERATION_NAMES.inverse()[name] ?:
                            OperatorConventions.ASSIGNMENT_OPERATIONS.inverse()[name] ?:
                            OperatorConventions.BOOLEAN_OPERATIONS.inverse()[name]
                listOfNotNull(token)
            }
        }
