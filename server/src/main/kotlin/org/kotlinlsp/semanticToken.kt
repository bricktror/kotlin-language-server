package org.kotlinlsp

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokensLegend
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.lexer.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext

import org.kotlinlsp.util.asTextRange
import org.kotlinlsp.util.toLsp4jRange
import org.kotlinlsp.util.withPrevious

val semanticTokensLegend = SemanticTokensLegend(
    SemanticTokenType.values().map { it.typeName },
    SemanticTokenModifier.values().map { it.modifierName }
)

/**
 * Computes LSP-encoded semantic tokens for the given range in the
 * document. No range means the entire document.
 */
fun encodedSemanticTokens(
    ktFile: KtFile,
    context: BindingContext,
    range: Range? = null
) = run {
    val textRange = range?.asTextRange(ktFile.containingFile.text)
    ktFile
        .preOrderTraversal()
        .filter { elem -> textRange?.contains(elem.textRange) ?: true }
        .mapNotNull { elementToken(it, context) }
        // Tokens must be on a single line
        .filter{ it.range.start.line == it.range.end.line }
        .withPrevious()
        .flatMap{ (last, token)-> token.serialize(last?.range?.start) }
        .toList()
}

private enum class SemanticTokenType(val typeName: String) {
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

private enum class SemanticTokenModifier(val modifierName: String) {
    DECLARATION(SemanticTokenModifiers.Declaration),
    DEFINITION(SemanticTokenModifiers.Definition),
    ABSTRACT(SemanticTokenModifiers.Abstract),
    READONLY(SemanticTokenModifiers.Readonly)
}

private data class SemanticToken(
    val range: Range,
    val type: SemanticTokenType,
    val modifiers: Set<SemanticTokenModifier> = setOf()) {

    fun serialize(previous: Position?): List<Int> {
        val prevLine = previous?.line ?: 0
        val prevChar = previous?.character
            .takeIf{ range.start.line == prevLine }
            ?: 0
        return listOf(
            range.start.line - prevLine,
            range.start.character - prevChar,
            range.end.character - range.start.character,
            type.ordinal,
            modifiers
                .map { 1 shl it.ordinal }
                .fold(0, Int::or),
        )
    }
}

private fun elementToken(
    element: PsiElement,
    bindingContext: BindingContext
): SemanticToken? {
    val file = element.containingFile
    fun mkToken(
        type: SemanticTokenType,
        range: TextRange = element.textRange,
        modifiers: Set<SemanticTokenModifier> = setOf()
    ) = SemanticToken(
        range.toLsp4jRange(file.text),
        type,
        modifiers)

    return when (element) {
        // References (variables, types, functions, ...)
        is KtNameReferenceExpression -> {
            when (val target = bindingContext[BindingContext.REFERENCE_TARGET, element]) {
                is PropertyDescriptor ->
                    mkToken(SemanticTokenType.PROPERTY)
                is VariableDescriptor ->
                    mkToken(SemanticTokenType.VARIABLE,
                        modifiers=if (!target.isVar() || target.isConst())
                            setOf(SemanticTokenModifier.READONLY)
                        else setOf())
                is ConstructorDescriptor -> when (target.constructedClass.kind) {
                    ClassKind.ENUM_ENTRY -> SemanticTokenType.ENUM_MEMBER
                    ClassKind.ANNOTATION_CLASS -> SemanticTokenType.TYPE // annotations look nicer this way
                    else -> SemanticTokenType.FUNCTION
                }.let { mkToken(it) }
                is FunctionDescriptor -> mkToken(SemanticTokenType.FUNCTION)
                is ClassDescriptor -> when (target.kind) {
                    ClassKind.ENUM_ENTRY -> SemanticTokenType.ENUM_MEMBER
                    ClassKind.CLASS -> SemanticTokenType.CLASS
                    ClassKind.OBJECT -> SemanticTokenType.CLASS
                    ClassKind.INTERFACE -> SemanticTokenType.INTERFACE
                    ClassKind.ENUM_CLASS -> SemanticTokenType.ENUM
                    else -> SemanticTokenType.TYPE
                }.let{ mkToken(it) }
                else -> null
            }
        }

        // Literals and string interpolations
        is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry ->
            mkToken(SemanticTokenType.INTERPOLATION_ENTRY)
        is KtStringTemplateExpression ->
            mkToken(SemanticTokenType.STRING)
        is PsiLiteralExpression -> when (element.type) {
            PsiType.INT, PsiType.LONG, PsiType.DOUBLE ->
                mkToken(SemanticTokenType.NUMBER)
            PsiType.CHAR ->
                mkToken(SemanticTokenType.STRING)
            PsiType.BOOLEAN, PsiType.NULL ->
                mkToken(SemanticTokenType.KEYWORD)
            else -> null
        }

        // Declarations (variables, types, functions, ...)
        is PsiNameIdentifierOwner -> {
            val range = element.nameIdentifier?.textRange ?: return null
            fun mkDeclToken(type: SemanticTokenType, isReadonly: Boolean=false) = mkToken(
                type,
                range,
                sequence {
                    yield(SemanticTokenModifier.DECLARATION)
                    if(element is KtModifierListOwner
                        && element.hasModifier(KtTokens.ABSTRACT_KEYWORD))
                        yield(SemanticTokenModifier.ABSTRACT)
                    if(isReadonly) yield(SemanticTokenModifier.READONLY)
                }.toSet())

            when (element) {
                is KtParameter -> mkDeclToken(SemanticTokenType.PARAMETER, true)
                is KtProperty -> mkDeclToken(SemanticTokenType.PROPERTY)
                is KtEnumEntry -> mkDeclToken(SemanticTokenType.ENUM_MEMBER)
                is KtVariableDeclaration -> mkDeclToken(SemanticTokenType.VARIABLE,
                    isReadonly=(!element.isVar() || element.hasModifier(KtTokens.CONST_KEYWORD)))
                is KtClassOrObject -> mkDeclToken(SemanticTokenType.CLASS)
                is KtFunction -> mkDeclToken(SemanticTokenType.FUNCTION)
                else -> null
            }
        }

        else -> null
    }
}
