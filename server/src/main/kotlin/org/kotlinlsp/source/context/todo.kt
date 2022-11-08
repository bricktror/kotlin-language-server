package org.kotlinlsp.source.context

import kotlin.collections.minByOrNull
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf

/**
 * Find the lexical-scope surrounding `cursor`.
 * This may be out-of-date if the user is typing quickly.
 */
fun BindingContext.lexicalScopeAt(
    cursor: Int
): LexicalScope? =
    getSliceContents(BindingContext.LEXICAL_SCOPE)
        .asSequence()
        .filter { it.key.textRange.startOffset <= cursor && cursor  <= it.key.textRange.endOffset }
        .minByOrNull { it.key.textRange.length  }
        ?.value

fun BindingContext.findDeclarationAt(
    cursor: Int
): DeclarationDescriptor? =
    getSliceContents(BindingContext.REFERENCE_TARGET)
        .asSequence()
        .filter { cursor in it.key.textRange }
        .minByOrNull { it.key.textRange.length }
        ?.value


fun LexicalScope.getDeclarationsInScope(): Sequence<DeclarationDescriptor> =
    parentsWithSelf
        .flatMap { s->
            sequence {
                yieldAll(s.getContributedDescriptors())
                s.let{it as? LexicalScope}
                    ?.implicitReceiver
                    ?.type
                    ?.memberScope
                    ?.getContributedDescriptors()
                    ?.let{yieldAll(it)}
            }
        }
        .flatMap {
            sequence {
                if (it is ClassDescriptor)
                    yieldAll(it.constructors)
                yield(it)
            }
        }

/**
 * TODO describe
 */
fun KtExpression.unwrapReferenceExpression(): KtExpression =
    when(val p = this.parent) {
        is KtDotQualifiedExpression -> p.unwrapReferenceExpression() // foo.bar
        is KtSafeQualifiedExpression -> p.unwrapReferenceExpression() // foo?.bar
        is KtCallExpression -> p.unwrapReferenceExpression() // foo()
        else -> this
    }

fun PsiElement.textUntilCursor(cursor: Int) =
    text.take(cursor-textRange.startOffset)
