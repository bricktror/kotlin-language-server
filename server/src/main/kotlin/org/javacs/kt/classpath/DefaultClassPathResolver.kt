package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.FileSystems

fun defaultClassPathResolver(workspaceRoots: Collection<Path>): ClassPathResolver
    = withUniqueStdlibs(
        workspaceRoots.firstOrNull().let(ShellClassPathResolver::global)
            .or(workspaceRoots.asSequence().flatMap(::workspaceResolvers).flatten())
    ).or(BackupClassPathResolver)

/** Searches the workspace for all files that could provide classpath info. */
private fun workspaceResolvers(workspaceRoot: Path): Sequence<ClassPathResolver> {
    val ignored: List<PathMatcher> = ignoredPathPatterns(workspaceRoot, workspaceRoot.resolve(".gitignore"))
    return workspaceRoot.toFile()
        .walk()
        .onEnter { file -> ignored.none { it.matches(file.toPath()) } }
        .mapNotNull { asClassPathProvider(it.toPath()) }
        .asSequence()
}

/** Tries to read glob patterns from a gitignore. */
private fun ignoredPathPatterns(root: Path, gitignore: Path): List<PathMatcher> =
    gitignore.toFile()
        .takeIf { it.exists() }
        ?.readLines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.map { it.removeSuffix("/") }
        ?.let { it + listOf(".git") }
        ?.also { LOG.debug("Adding ignore pattern(s) from {}: {}", gitignore, it) }
        ?.mapNotNull { try {
            FileSystems.getDefault().getPathMatcher("glob:$root**/$it")
        } catch (e: Exception) {
            LOG.warn("Did not recognize gitignore pattern: '{}' ({})", it, e.message)
            null
        } }
        ?: emptyList()

/** Tries to create a classpath resolver from a file using as many sources as possible */
private fun asClassPathProvider(path: Path): ClassPathResolver?
    =  MavenClassPathResolver.maybeCreate(path)
    ?: GradleClassPathResolver.maybeCreate(path)
    ?: ShellClassPathResolver.maybeCreate(path)
