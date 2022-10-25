package org.kotlinlsp.classpath

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.kotlinlsp.util.userHome
import org.kotlinlsp.logging.*

/** Executes a shell script to determine the classpath */
internal class ShellClassPathResolver(
    private val script: Path,
    private val workingDir: Path? = null
) : ClassPathResolver {
    private val log by findLogger
    override val classpath: Set<ClassPathEntry> get() {
        val workingDirectory = workingDir?.toFile() ?: script.toAbsolutePath().parent.toFile()
        val cmd = script.toString()
        log.info("Run ${cmd} in ${workingDirectory}")
        val process = ProcessBuilder(cmd).directory(workingDirectory).start()

        return process.inputStream.bufferedReader().readText()
            .split(File.pathSeparator)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ClassPathEntry(Paths.get(it)) }
            .toSet()
    }

    companion object {
        private val scriptExtensions = listOf("sh", "bat", "cmd")

        /** Create a shell resolver if a file is a pom. */
        fun maybeCreate(file: Path): ShellClassPathResolver? =
            file.takeIf { scriptExtensions.any { file.endsWith("kotlinLspClasspath.$it") } }
                ?.let (::ShellClassPathResolver)

        /** The root directory for config files. */
        private val globalConfigRoot: Path =
            System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) } ?: userHome.resolve(".config")

        /** Returns the ShellClassPathResolver for the global home directory shell script. */
        fun global(workingDir: Path?): ClassPathResolver =
            globalConfigRoot.resolve("KotlinLanguageServer")
                ?.let { root ->
                    scriptExtensions
                        .map { root.resolve("classpath.$it") }
                        .firstOrNull { Files.exists(it) }
                }
                ?.let { ShellClassPathResolver(it, workingDir) }
                ?: ClassPathResolver.empty
    }
}
