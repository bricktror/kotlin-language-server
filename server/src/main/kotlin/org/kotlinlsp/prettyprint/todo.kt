package org.kotlinlsp.prettyprint

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.UnresolvedType
import org.jetbrains.kotlin.renderer.ParameterNameRenderingPolicy
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.renderer.ClassifierNamePolicy
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.renderer.RenderingFormat
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.kotlinlsp.source.SourceFile
import org.kotlinlsp.util.indexToPosition
import org.jetbrains.kotlin.resolve.BindingContext

fun DeclarationDescriptor.describe(): String {
    // TODO path from containing file?
    val file = findPsi()?.containingFile ?: "<unknown-file>"
    val container = containingDeclaration?.name?.toString() ?: "<top-level>"
    return "($file ${container}.${name})"
}

fun KtFile.describePosition(offset: Int): String =
    indexToPosition(viewProvider.contents.toString(), offset)
        .let{"${name} ${it.line + 1}:${it.character + 1}"}

fun KtExpression.describeCallReturn(context: BindingContext) =
    context[BindingContext.DECLARATION_TO_DESCRIPTOR, this]
        .let {it as? CallableDescriptor }
        ?.returnType
        ?.describe()

fun KtExpression.describeSmartCastSource(context: BindingContext) =
    this
        .takeIf {context[BindingContext.SMARTCAST, this]!=null}
        ?.let { it as? KtReferenceExpression }
        ?.let { context[BindingContext.REFERENCE_TARGET, it] as? CallableDescriptor }
        ?.returnType
        ?.describe()
fun KtExpression.describeExpressionType(context: BindingContext) =
    context[BindingContext.EXPRESSION_TYPE_INFO, this]
        ?.type
        ?.let { type->
            this.describeSmartCastSource(context)
                ?.let{"${type.describe()} (smart cast from ${it})"}
                ?: type.describe()
        }

fun SourceFile.Compiled.describePosition(offset: Int): String =
    indexToPosition(content, offset)
        .let{"${path.fileName} ${it.line + 1}:${it.character + 1}"}

fun TextDocumentPositionParams.describe(): String =
    "${textDocument.uri} ${position.line + 1}:${position.character + 1}"

fun KotlinType.describe(): String? =
    typeRenderer.renderType(this)

fun DeclarationDescriptor.describeAlt(): String = DECL_RENDERER.render(this)
fun ValueParameterDescriptor.describeParameter(): String =
    DECL_RENDERER
        .renderValueParameters(listOf(this), false)
        .let { it.substring(1, it.length-1) /* Remove parens */ }

// Source: https://github.com/JetBrains/kotlin/blob/master/idea/src/org/jetbrains/kotlin/idea/codeInsight/KotlinExpressionTypeProvider.kt
private val typeRenderer = DescriptorRenderer.FQ_NAMES_IN_TYPES.withOptions {
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

private val DECL_RENDERER = DescriptorRenderer.withOptions {
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

fun String.peekCursorPosition(index: Int, hws:Int=10) =
    substring(kotlin.math.max(0, index-hws), index)+
    "|" +
    substring(index, kotlin.math.min(length, index+hws))


fun KtElement.peekCursorPosition(index:Int, hws: Int=10) =
    text.peekCursorPosition(index-textRange.startOffset, hws=hws)
