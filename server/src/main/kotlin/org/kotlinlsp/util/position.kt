package org.kotlinlsp.util

import org.eclipse.lsp4j.Position

fun Position.getIndexIn(content: String ) =
    content
        .also { assert(!it.contains('\r')) }
        .asIterable()
        .ordinalIndexOf(line-1){it=='\n'}
        ?.let{it+character+1}
        ?: -1

fun indexToPosition(content: String, offset: Int): Position =
    content
        .take(offset)
        .fold(0 to 0) { (l,c), char ->
            when (char) {
                '\n' -> (l+1) to 0
                else -> l to (c+1)
            }
        }
        .let { (l,c) -> Position(l,c) }

val Position.isZero: Boolean
    get() = (line == 0) && (character == 0)
