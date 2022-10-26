package org.kotlinlsp.util

import org.kotlinlsp.logging.*

import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.io.InputStream

private val log by findLogger.atToplevel(object{})

fun execAndReadStdoutAndStderr(directory: Path, shellCommand: List<String>): Pair<String, String>
    = ProcessBuilder(shellCommand)
    .directory(directory.toFile())
    .start()
    .let {
        fun read(stream: InputStream) = stream.bufferedReader().use{it.readText()}
        read(it.inputStream) to read(it.errorStream)
    }

fun String.partitionAroundLast(separator: String): Pair<String, String> = lastIndexOf(separator)
    .let { Pair(substring(0, it), substring(it, length)) }

fun Path.replaceExtensionWith(newExtension: String): Path {
    val oldName = fileName.toString()
    val newName = oldName.substring(0, oldName.lastIndexOf(".")) + newExtension
    return resolveSibling(newName)
}

inline fun <T, C : Iterable<T>> C.onEachIndexed(transform: (index: Int, T) -> Unit): C = apply {
    var i = 0
    for (element in this) {
        transform(i, element)
        i++
    }
}

fun <T> noResult(message: String, result: T): T {
    log.info(message)
    return result
}

fun <T> emptyResult(message: String): List<T> = noResult(message, emptyList())

fun <T> nullResult(message: String): T? = noResult(message, null)
