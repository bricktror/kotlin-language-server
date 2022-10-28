package org.kotlinlsp.util

import org.kotlinlsp.logging.*

import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.io.InputStream
import com.intellij.openapi.util.TextRange

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

@Deprecated("Replace with run{ log.info { message }; result }")
fun <T> noResult(message: String, result: T): T {
    log.info(message)
    return result
}

@Deprecated("Replace with run{ log.info { message }; emptyList() }")
fun <T> emptyResult(message: String): List<T> = noResult(message, emptyList())

@Deprecated("Replace with run{ log.info { message }; null }")
fun <T> nullResult(message: String): T? = noResult(message, null)

/** Region that has been changed */
fun changedRegion(oldContent: String, newContent: String): Pair<TextRange, TextRange>? {
    if (oldContent == newContent) return null
    val prefix = oldContent.commonPrefixWith(newContent).length
    val suffix = oldContent.commonSuffixWith(newContent).length
    val oldEnd = kotlin.math.max(oldContent.length - suffix, prefix)
    val newEnd = kotlin.math.max(newContent.length - suffix, prefix)
    return TextRange(prefix, oldEnd) to TextRange(prefix, newEnd)
}
