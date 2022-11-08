package org.kotlinlsp.util

import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.psi.KtFile
import org.eclipse.lsp4j.Range

// checks if the current range is within the other range (same lines, within the character bounds)
fun Range.isSubrangeOf(otherRange: Range): Boolean =
    otherRange.start.line == this.start.line && otherRange.end.line == this.end.line &&
        otherRange.start.character <= this.start.character && otherRange.end.character >= this.end.character

fun Range.extractRange(content: String) =
    content.substring(
        start.getIndexIn(content),
        end.getIndexIn(content))

fun TextRange.toLsp4jRange(content: String) =
    Range(indexToPosition(content, startOffset), indexToPosition(content, endOffset))

fun Range.asTextRange(content: String) =
    TextRange(start.getIndexIn(content), end.getIndexIn(content))

fun TextRange.describeRange(ktFile: KtFile): String {
    val start = indexToPosition(ktFile.text, startOffset)
    val end = indexToPosition(ktFile.text, endOffset)
    val file = ktFile. originalFile.viewProvider.virtualFile.path
    return "${file} ${start.line}:${start.character + 1}-${end.line + 1}:${end.character + 1}"
}
