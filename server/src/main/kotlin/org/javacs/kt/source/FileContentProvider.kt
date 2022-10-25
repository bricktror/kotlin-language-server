package org.javacs.kt.source

import java.net.URI
import java.nio.file.Paths
import org.javacs.kt.logging.*

fun interface FileContentProvider {
    fun read(uri: URI): String?
}

class SchemeDelegatingFileContentProvider(
    private val schemeLookup: Map<String, FileContentProvider>
): FileContentProvider {
    private val log by findLogger

    override fun read(uri: URI) =
        schemeLookup.get(uri.scheme)
            .also { if(it==null) log.warning{"Unrecognized scheme for uri ${uri}"} }
            ?.let{it.read(uri)}
}

val localFileSystemContentProvider =
    FileContentProvider { Paths.get(it).toFile().readText() }

class CompisiteFileContentProvider(
    private vararg val providers: FileContentProvider
): FileContentProvider {
    override fun read(uri:URI) =
        providers.firstNotNullOfOrNull { it.read(uri) }
}
