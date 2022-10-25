package org.kotlinlsp.lsp4kt

import arrow.core.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either as JavaEither

fun <L, R> JavaEither<L, R>.asArrowEither() =
        if (isLeft()) Either.Left(getLeft()) else Either.Right(getRight())

fun <L, R> Either<L, R>.asLsp4jEither(): JavaEither<L, R> =
        fold<JavaEither<L, R>>({ JavaEither.forLeft(it) }, { JavaEither.forRight(it) })

