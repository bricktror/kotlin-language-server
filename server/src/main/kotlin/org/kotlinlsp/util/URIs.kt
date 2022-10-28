package org.kotlinlsp.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.toPath

/**
 * Parse a possibly-percent-encoded URI string.
 * Decoding is necessary since some language clients
 * (including VSCode) invalidly percent-encode colons.
 */
fun parseURI(uri: String): URI =
    runCatching {
        URLDecoder.decode(uri, StandardCharsets.UTF_8.toString())
            .replace(" ", "%20")
    }
    .getOrDefault(uri)
    .let { URI.create(it) }

val URI.filePath: Path? get() =
    toPath()
val URI.fileName: String get() =
    toPath()
        .getFileName()
        .toString()

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
val URI.fileExtension: String? get() =
    getPath()
        .split(".")
        .takeIf{it.size > 1}
        ?.takeLast(1)
        ?.first()

val URI.greedyFileExtension: String? get() =
    schemeSpecificPart
        .dropWhile{it!='.'}
        .drop(1)
        .ifEmpty { null }

@Deprecated("")
fun describeURIs(uris: Collection<URI>): String =
    if (uris.isEmpty()) "0 files"
    else if (uris.size > 5) "${uris.size} files"
    else uris.map{it.toString()}.joinToString(", ")

