package org.kotlinlsp.file

import java.net.URI
import java.nio.file.Path
import org.kotlinlsp.logging.findLogger

interface URIWalker {
    fun walk(shouldRecurse: (URI)->Boolean= {true}): Sequence<URI>
    fun contains(uri: URI): Boolean
}

class FilesystemURIWalker(
    val root: Path
): URIWalker {
    private val log by findLogger
    override fun walk(shouldRecurse: (URI)->Boolean): Sequence<URI> =
        root.toFile()
            .also {
                when {
                    !it.exists()->  log.warning("Path ${root} does not exist. Unable to walk path")
                    it.isFile()->  log.warning("Path ${root} is a file. Unable to walk path")
                    else -> return@also
                }
                return emptySequence()
            }
            .walk()
            .onEnter { shouldRecurse(it.toURI()) }
            .map{it.toURI()}
    override fun contains(uri: URI): Boolean {
        if(uri.getScheme()!= "file") return false
        return root.startsWith(uri.getSchemeSpecificPart())
    }
}

class CompositeURIWalker(
    private val inner: Collection<URIWalker>
): URIWalker {
    override fun walk(shouldRecurse: (URI)->Boolean): Sequence<URI> =
        inner.asSequence().flatMap{ it.walk(shouldRecurse) }

    override fun contains(uri: URI) =
        inner.asSequence().any{it.contains(uri)}
}

class DirectoryIgnoringURIWalker(
    private val inner: URIWalker,
    private val ignoredDirectories: Collection<String>,
): URIWalker {
    override fun walk(shouldRecurse: (URI)->Boolean): Sequence<URI> =
        inner.walk { uri->
            val segments = uri.getPath().split('/')
            ignoredDirectories.none{ segments.contains(it) }
        }

    override fun contains(uri: URI) =
        inner.contains(uri)
}

class GitIgnoreURIWalker(
    private val inner: URIWalker,
    private val contentProvider: FileProvider,
): URIWalker {

    override fun walk(shouldRecurse: (URI)->Boolean): Sequence<URI> {
        return inner.walk (shouldRecurse)
    }

    // TODO honnor gitignore
    override fun contains(uri: URI) =
        inner.contains(uri)

    /* val ignored: List<PathMatcher> = ignoredPathPatterns(workspaceRoot, workspaceRoot.resolve(".gitignore")) */
/* private fun ignoredPathPatterns(root: Path, gitignore: Path): List<PathMatcher> = */
    /* gitignore.toFile() */
    /*     .takeIf { it.exists() } */
    /*     ?.readLines() */
    /*     ?.map { it.trim() } */
    /*     ?.filter { it.isNotEmpty() && !it.startsWith("#") } */
    /*     ?.map { it.removeSuffix("/") } */
    /*     ?.let { it + listOf(".git") } */
    /*     ?.also { log.debug("Adding ignore pattern(s) from ${gitignore}: ${it}") } */
    /*     ?.mapNotNull { try { */
    //*         FileSystems.getDefault().getPathMatcher("glob:$root**/$it") */
    /*     } catch (e: Exception) { */
    /*         log.warning("Did not recognize gitignore pattern: '${it}' (${e.message})") */
    /*         null */
    /*     } } */
        /* ?: emptyList() */
}
