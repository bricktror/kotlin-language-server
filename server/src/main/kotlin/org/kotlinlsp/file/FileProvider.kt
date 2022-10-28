package org.kotlinlsp.file

import java.io.BufferedReader
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.zip.ZipFile
import org.kotlinlsp.logging.*
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.util.greedyFileExtension

private val log by findLogger.atToplevel(object{})

interface FileProvider {
    fun read(uri: URI): String?

    fun getFile(uri: URI): TemporaryFile? =
        read(uri)?.let { content->
            log.finer { "Reading ${uri} as temporary file" }
            TemporaryFile.create("file", uri.greedyFileExtension?.let{".${it}"}) {
                it.toFile()
                    .writeText(content)
            }
        }
}

val localFileSystemProvider = object: FileProvider {
    override fun read(uri:URI): String? =
        Paths.get(uri).toFile().readText()
}

class BootstrappingFileProvider(
    bootstrap: (FileProvider) -> FileProvider
): FileProvider {
    private val inner by lazy { bootstrap(this) }
    override fun read(uri: URI)= inner.read(uri)
}

class CompisiteFileProvider(
    private vararg val providers: FileProvider
): FileProvider {
    override fun read(uri:URI) =
        providers.firstNotNullOfOrNull { it.read(uri) }
}

class SchemeDelegatingFileProvider(
    private val schemeLookup: Map<String, FileProvider>
): FileProvider {
    private val log by findLogger

    override fun read(uri: URI) =
        schemeLookup.get(uri.scheme)
            .also { if(it==null) log.warning{"Unrecognized scheme for uri ${uri}"} }
            ?.let{it.read(uri)}
}

class ZipFileProvider(
    private val provider: FileProvider
) : FileProvider {
    override fun read(rawUri: URI): String? = rawUri
        .let{ it.schemeSpecificPart.replace(" ", "%20") }
        .let{ it.split("!", limit=2) }
        .let{ if (it.size!=2) return null else it[0] to it[1] }
        .let{ (archive, path) -> parseURI(archive) to path }
        .let{ (archivePath, innerPath)->
            provider
                ?.getFile(archivePath)
                ?.use { tmp ->
                    ZipFile(tmp.file)
                        .use{ zipFile->
                            zipFile.getInputStream(
                                    zipFile.getEntry(innerPath.trimStart('/')))
                                .bufferedReader()
                                .use(BufferedReader::readText)
                        }
                }
        }
}

class ResourceFileProvider: FileProvider {
    override fun read(rawUri: URI): String? =
        // TODO how to handle scheme?
        object{}.javaClass
            .getResourceAsStream("/${rawUri.schemeSpecificPart}")
            .bufferedReader()
            .use(BufferedReader::readText)
}

/* private fun copyResourceAsTempfile(scriptName: String) = */
/*     TemporaryFile.create("classpath-${scriptName.replace("\\W".toRegex(), "")}", ".gradle.kts") { */
/*         log.debug("Creating temporary gradle file ${it} (${scriptName})") */
/*         it.toFile() */
/*             .bufferedWriter() */
/*             .use { configWriter -> */
/*                 object{}::class.java */
/*                     .getResourceAsStream("/$scriptName") */
/*                     .bufferedReader() */
/*                     .use { configReader -> configReader.copyTo(configWriter) } */
/*             } */
/*     } */
