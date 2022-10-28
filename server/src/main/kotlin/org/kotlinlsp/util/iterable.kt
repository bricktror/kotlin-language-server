package org.kotlinlsp.util

/** Find the index of the nth match */
fun <T> Iterable<T>.ordinalIndexOf(n: Int, predicate: (T)->Boolean) =
    withIndex()
        .filter{predicate(it.value)}
        .elementAtOrNull(n)
        ?.index

