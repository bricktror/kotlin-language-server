package org.kotlinlsp.util

fun <T> List<T>.withPrevious(): List<Pair<T?, T>> =
    // prepend first null-value
    (listOf(null)+this)
        // traverse windowed
        .windowed(size=2, step=1)
        // Cast to tuple and force second param to be non-null
        .map { (a,b)-> a to b!! }

fun <T> Sequence<T>.withPrevious(): Sequence<Pair<T?, T>> =
    // prepend first null-value
    (sequenceOf(null)+this)
        // traverse windowed
        .windowed(size=2, step=1)
        // Cast to tuple and force second param to be non-null
        .map { (a,b)-> a to b!! }

