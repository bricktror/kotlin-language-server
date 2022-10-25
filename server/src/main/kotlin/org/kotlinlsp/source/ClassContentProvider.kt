package org.kotlinlsp.source

import java.io.BufferedReader
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.zip.ZipFile
import org.kotlinlsp.util.parseURI

class ClassContentProvider : FileContentProvider{
    override fun read(rawUri: URI): String? =
        rawUri
            .let{ if(it.scheme!="kls") return null else it }
            .let{ it.schemeSpecificPart.replace(" ", "%20") }
            .let{ it.split("!", limit=2) }
            .let{ if (it.size!=2) return null else it[0] to it[1] }
            .let{ (archive, path) -> Paths.get(parseURI(archive)) to path }
            .let{ (archivePath, innerPath)->
                ZipFile(File("${archivePath}"))
                    .use{ zipFile->
                        val entry=zipFile.getEntry(innerPath.trimStart('/'))
                        zipFile.getInputStream(entry)
                            .bufferedReader()
                            .use(BufferedReader::readText)
                    }
            }
}
