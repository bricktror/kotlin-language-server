package org.kotlinlsp.source

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.nio.file.Paths
import org.eclipse.lsp4j.Location
import org.kotlinlsp.CompilationKind
import org.kotlinlsp.Compiler
import org.kotlinlsp.changedRegion
import org.kotlinlsp.location
import org.kotlinlsp.logging.*
import org.kotlinlsp.position
import org.kotlinlsp.range
import org.kotlinlsp.util.findParent
import org.kotlinlsp.util.nullResult
import org.kotlinlsp.util.toPath
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.types.KotlinType

class CompiledFile(
    val content: String,
    val parse: KtFile,
    val compile: BindingContext,
    val module: ModuleDescriptor,
    private val sourcePath: Collection<KtFile>,
    private val compiler: Compiler,
    private val isScript: Boolean = false,
) {
    private val log by findLogger
    /**
     * Find the type of the expression at `cursor`
     */
    fun typeAtPoint(cursor: Int): KotlinType? {
        val cursorExpr = parseAtPoint(cursor, asReference = true)?.findParent<KtExpression>() ?: return nullResult("Couldn't find expression at ${describePosition(cursor)}")
        val surroundingExpr = expandForType(cursor, cursorExpr)
        val scope = scopeAtPoint(cursor) ?: return nullResult("Couldn't find scope at ${describePosition(cursor)}")
        return typeOfExpression(surroundingExpr, scope)
    }

    fun typeOfExpression(expression: KtExpression, scopeWithImports: LexicalScope): KotlinType? =
        bindingContextOf(expression, scopeWithImports).getType(expression)

    fun bindingContextOf(expression: KtExpression, scopeWithImports: LexicalScope): BindingContext =
        compiler.compileKtExpression(expression, scopeWithImports, sourcePath).first

    private fun expandForType(cursor: Int, surroundingExpr: KtExpression): KtExpression {
        val dotParent = surroundingExpr.parent as? KtDotQualifiedExpression
            ?: return surroundingExpr
        dotParent.selectorExpression?.textRange?.let {
            if(it.contains(cursor))
            return expandForType(cursor, dotParent)
        }
        return surroundingExpr
    }

    /**
     * Looks for a reference expression at the given cursor.
     * This is currently used by many features in the language server.
     * Unfortunately, it fails to find declarations for JDK symbols.
     * [referenceExpressionAtPoint] provides an alternative implementation that can find JDK symbols.
     * It cannot, however, replace this method at the moment.
     * TODO: Investigate why this method doesn't find JDK symbols.
     */
    fun referenceAtPoint(cursor: Int): Pair<KtExpression, DeclarationDescriptor>? {
        val element = parseAtPoint(cursor, asReference = true)
        val cursorExpr = element?.findParent<KtExpression>() ?: return nullResult("Couldn't find expression at ${describePosition(cursor)} (only found $element)")
        val surroundingExpr = expandForReference(cursor, cursorExpr)
        val scope = scopeAtPoint(cursor) ?: return nullResult("Couldn't find scope at ${describePosition(cursor)}")
        val context = bindingContextOf(surroundingExpr, scope)
        log.info("Hovering ${surroundingExpr}")
        return referenceFromContext(cursor, context)
    }

    /**
     * Looks for a reference expression at the given cursor.
     * This method is similar to [referenceAtPoint], but the latter fails to find declarations for JDK symbols.
     * This method should not be used for anything other than finding definitions (at least for now).
     */
    fun referenceExpressionAtPoint(cursor: Int): Pair<KtExpression, DeclarationDescriptor>? =
        referenceFromContext(cursor, compile)

    private fun referenceFromContext(cursor: Int, context: BindingContext): Pair<KtExpression, DeclarationDescriptor>? {
        val targets = context.getSliceContents(BindingContext.REFERENCE_TARGET)
        return targets.asSequence()
                .filter { cursor in it.key.textRange }
                .sortedBy { it.key.textRange.length }
                .map { it.toPair() }
                .firstOrNull()
    }

    private fun expandForReference(cursor: Int, surroundingExpr: KtExpression): KtExpression {
        val parent: KtExpression? =
            surroundingExpr.parent as? KtDotQualifiedExpression // foo.bar
            ?: surroundingExpr.parent as? KtSafeQualifiedExpression // foo?.bar
            ?: surroundingExpr.parent as? KtCallExpression // foo()
        return parent?.let { expandForReference(cursor, it) } ?: surroundingExpr
    }

    /**
     * Parse the expression at `cursor`.
     *
     * If the `asReference` flag is set, the method will attempt to
     * convert a declaration (e.g. of a class or a function) to a referencing
     * expression before parsing it.
     */
    fun parseAtPoint(cursor: Int, asReference: Boolean = false): KtElement? {
        val oldCursor = oldOffset(cursor)
        val oldChanged = changedRegion(parse.text, content)?.first ?: TextRange(cursor, cursor)
        val psi = parse.findElementAt(oldCursor) ?: return nullResult("Couldn't find anything at ${describePosition(cursor)}")
        val oldParent = psi.parentsWithSelf
                .filterIsInstance<KtDeclaration>()
                .firstOrNull { it.textRange.contains(oldChanged) } ?: parse

        log.debug { "PSI path: ${psi.parentsWithSelf.toList()}" }

        val (surroundingContent, offset) = contentAndOffsetFromElement(psi, oldParent, asReference)
        val padOffset = " ".repeat(offset)
        val recompile = compiler.createKtFile(padOffset + surroundingContent, Paths.get("dummy.virtual" + if (isScript) ".kts" else ".kt"))
        return recompile.findElementAt(cursor)?.findParent<KtElement>()
    }

    /**
     * Extracts the surrounding content and the text offset from a
     * PSI element.
     *
     * See `parseAtPoint` for documentation of the `asReference` flag.
     */
    private fun contentAndOffsetFromElement(psi: PsiElement, parent: KtElement, asReference: Boolean): Pair<String, Int> {

        if (asReference && parent is KtClass && psi.node.elementType == KtTokens.IDENTIFIER) {
            // Convert the declaration into a fake reference expression
            // Converting class name identifier: Use a fake property with the class name as type
            //                                   Otherwise the compiler/analyzer would throw an exception due to a missing TopLevelDescriptorProvider
            val prefix = "val x: "
            val surroundingContent = prefix + psi.text
            return surroundingContent to (psi.textRange.startOffset - prefix.length)
        }

        // Otherwise just use the expression
        val recoveryRange = parent.textRange
        log.info("Re-parsing ${describeRange(recoveryRange)}")

        var surroundingContent = content.substring(recoveryRange.startOffset, content.length - (parse.text.length - recoveryRange.endOffset))
        var offset = recoveryRange.startOffset

        if (asReference && !((parent as? KtParameter)?.hasValOrVar() ?: true)) {
            // Prepend 'val' to (e.g. function) parameters
            val prefix = "val "
            surroundingContent = prefix + surroundingContent
            offset -= prefix.length
        }
        return surroundingContent to offset
    }

    private fun describeRange(range: TextRange): String {
        val start = position(parse.text, range.startOffset)
        val end = position(parse.text, range.endOffset)
        val file = parse.toPath().fileName
        return "${file} ${start.line}:${start.character + 1}-${end.line + 1}:${end.character + 1}"
    }

    /**
     * Get the typed, compiled element at `cursor`.
     * This may be out-of-date if the user is typing quickly.
     */
    fun elementAtPoint(cursor: Int): KtElement? {
        val oldCursor = oldOffset(cursor)
        val psi = parse.findElementAt(oldCursor) ?: return nullResult("Couldn't find anything at ${describePosition(cursor)}")
        return psi.findParent<KtElement>()
    }


    /**
     * Find the declaration of the element at the cursor.
     */
    fun findDeclaration(cursor: Int): Pair<KtNamedDeclaration, Location>? = findDeclarationReference(cursor) ?: findDeclarationCursorSite(cursor)

    /**
     * Find the declaration of the element at the cursor. Only works if the element at the cursor is a reference.
     */
    private fun findDeclarationReference(cursor: Int): Pair<KtNamedDeclaration, Location>? {
        val (_, target) = referenceAtPoint(cursor) ?: return null
        val psi = target.findPsi()

        if (psi !is KtNamedDeclaration) return null
        return psi.nameIdentifier
            ?.let { location(it) }
            ?.let { location -> Pair(psi, location) }
    }

    /**
     * Find the declaration of the element at the cursor.
     * Works even in cases where the element at the cursor might not be a reference, so works for declaration sites.
     */
    private fun findDeclarationCursorSite(cursor: Int): Pair<KtNamedDeclaration, Location>? {
        // current symbol might be a declaration. This function is used as a fallback when
        // findDeclaration fails
        val declaration = elementAtPoint(cursor)?.findParent<KtNamedDeclaration>()

        return declaration?.let {
            Pair(it,
                 Location(it.containingFile.name,
                          range(content, it.nameIdentifier?.textRange ?: return null)))
        }
    }

    /**
     * Find the lexical-scope surrounding `cursor`.
     * This may be out-of-date if the user is typing quickly.
     */
    fun scopeAtPoint(cursor: Int): LexicalScope? {
        val oldCursor = oldOffset(cursor)
        return compile.getSliceContents(BindingContext.LEXICAL_SCOPE).asSequence()
                .filter { it.key.textRange.startOffset <= oldCursor && oldCursor <= it.key.textRange.endOffset }
                .sortedBy { it.key.textRange.length  }
                .map { it.value }
                .firstOrNull()
    }

    fun lineBefore(cursor: Int): String = content.substring(0, cursor).substringAfterLast('\n')

    fun lineAfter(cursor: Int): String = content.substring(cursor).substringBefore('\n')

    private fun oldOffset(cursor: Int): Int {
        val (oldChanged, newChanged) = changedRegion(parse.text, content) ?: return cursor

        return when {
            cursor <= newChanged.startOffset -> cursor
            cursor < newChanged.endOffset -> {
                val newRelative = cursor - newChanged.startOffset
                val oldRelative = newRelative * oldChanged.length / newChanged.length
                oldChanged.startOffset + oldRelative
            }
            else -> parse.text.length - (content.length - cursor)
        }
    }

    fun describePosition(offset: Int, oldContent: Boolean = false): String {
        val c = if (oldContent) parse.text else content
        val pos = position(c, offset)
        val file = parse.toPath().fileName

        return "$file ${pos.line + 1}:${pos.character + 1}"
    }

}
