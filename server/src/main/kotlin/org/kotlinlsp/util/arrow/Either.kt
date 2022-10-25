package org.kotlinlsp.util.arrow
import arrow.core.Either

/** Partition a list of Eithers into the lefts and the rights */
fun <L, R> List<Either<L, R>>.partitionEither(): Pair<List<L>, List<R>> =
    partition {it.isLeft()}
        .let { (l,r) ->
            val ll=l.map { it as Either.Left }.map { it.value }
            val rr=r.map { it as Either.Right }.map { it.value }
            ll to rr
        }

fun <A, L, R> List<A>.partitionEither(fn: (A)->Either<L,R>): Pair<List<L>, List<R>> =
    map { fn(it) }
    .partition {it.isLeft()}
        .let { (l,r) ->
            val ll=l.map { it as Either.Left }.map { it.value }
            val rr=r.map { it as Either.Right }.map { it.value }
            ll to rr
        }

fun <L, R> List<Either<L, R>>.consumeLeft(fn: (List<L>)-> Unit): List<R> =
    partitionEither()
    .also { fn(it.first) }
    .let { it.second }

