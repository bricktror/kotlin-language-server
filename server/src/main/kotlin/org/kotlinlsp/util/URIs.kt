package org.kotlinlsp.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.toPath

/**
 * Parse a possibly-percent-encoded URI string.
 * Decoding is necessary since some language clients
 * (including VSCode) invalidly percent-encode colons.
 */
fun parseURI(uri: String): URI =
    URI.create(runCatching { URLDecoder.decode(uri, StandardCharsets.UTF_8.toString()).replace(" ", "%20") }.getOrDefault(uri))

val URI.filePath: Path? get() = runCatching { Paths.get(this) }.getOrNull()

val URI.fileName: String
    get() = toPath().getFileName().toString()

val URI.queryMap:Map<String, String> get()=
    getQuery()
        .let{it?:""}
        .split("&")
        .mapNotNull {
            val parts = it.split("=")
            if (parts.size != 2) null
            else parts[0] to parts[1]
        }
        .toMap()

/** Fetches the file extension WITHOUT the dot. */
val URI.fileExtension: String?
    get() {
        val str = toString()
        val dotOffset = str.lastIndexOf(".")
        val queryStart = str.indexOf("?")
        val end = if (queryStart != -1) queryStart else str.length
        return if (dotOffset < 0) null else str.substring(dotOffset + 1, end)
    }

@Deprecated("")
fun describeURIs(uris: Collection<URI>): String =
    if (uris.isEmpty()) "0 files"
    else if (uris.size > 5) "${uris.size} files"
    else uris.map{it.toString()}.joinToString(", ")

@Deprecated("")
fun describeURI(uri: String): String = describeURI(parseURI(uri))

@Deprecated("")
fun describeURI(uri: URI): String =
    uri.path
      ?.let { it.partitionAroundLast("/") }
      ?.let { (parent, fileName) -> ".../" + parent.substringAfterLast("/") + fileName }
      ?: uri.toString()
