package org.kotlinlsp.util.pair

inline fun <A, B, C> Pair<A, B>.mapFirst(fn: (A)->C) =
    fn(first) to second
inline fun <A: Any, B, C: Any> Pair<A, B>.mapFirstNotNull(fn: (A)->C?) =
    fn(first)?.let{it to second}

inline fun <A, B, C> Pair<A, B>.mapSecond(fn: (B)->C) =
    first to fn(second)

inline fun <A, B: Any, C: Any> Pair<A, B>.mapSecondNotNull(fn: (B)->C?) =
    fn(second)?.let{first to it}
