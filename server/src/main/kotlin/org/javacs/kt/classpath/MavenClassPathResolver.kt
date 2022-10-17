package org.javacs.kt.classpath

import org.javacs.kt.logging.*
import org.javacs.kt.util.findCommandOnPath
import org.javacs.kt.util.execAndReadStdoutAndStderr
import org.javacs.kt.util.TempFile
import org.javacs.kt.util.isOSWindows
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.io.File
import java.io.Closeable

private val log by findLogger.atToplevel(object{})

/** Resolver for reading maven dependencies */
internal class MavenClassPathResolver private constructor(private val pom: Path) : ClassPathResolver {
    override val classpath: Set<ClassPathEntry> get() {
        return generateMavenDependencyList(pom)
            .mapNotNull{mavenHome.resolveMavenArtifacts(it)}
            .map { ClassPathEntry(it.first, it.second) }
            .toSet()
    }

    companion object {
        /** Create a maven resolver if a file is a pom. */
        fun maybeCreate(file: Path): MavenClassPathResolver? =
            file.takeIf { it.endsWith("pom.xml") }?.let { MavenClassPathResolver(it) }
    }
}

fun findProjectCommandWithName(name: String, directory: Path): Path?
    = (if (isOSWindows()) listOf("${name}.cmd") else listOf(name))
    .let {
        it
            .asSequence()
            .mapNotNull {
                directory
                    .resolve(name)
                    .toFile()
                    .takeIf { it.isFile }
                    ?.takeIf { it.canExecute() }
                    ?.let {it.absolutePath}
                    ?.let(Paths::get)
          }
          .first()
    }

private fun generateMavenDependencyList(pom: Path)
    = TempFile
    .linesFrom(prefix="maven-dependencies") {
        val workingDirectory = pom.toAbsolutePath().parent
        val mavenExe=(
             requireNotNull(
                mvnCommandFromPath
                ?: findProjectCommandWithName("mvnw", pom)
                    ?.also { log.info("Using mvn wrapper (mvnw) in place of mvn command") }
            ) { "Unable to find the 'mvn' command or suitable wrapper" }
        ).toString()
        val command= listOf(
            mavenExe,
            "dependency:list",
            "-DincludeScope=test",
            "-DoutputFile=$it",
            "-Dstyle.color=never")
        log.info("Run ${command} in ${workingDirectory}")
        val (result, errors) = execAndReadStdoutAndStderr(command, workingDirectory)
        log.debug(result)
        if ("BUILD FAILURE" in errors) {
            log.warning("Maven task failed: {errors.lines().firstOrNull()}")
        }
    }
    .let {
        it.mapNotNull(MavenArtifact::fromMvnDependencyList)
          .toSet()
    }


private fun runCommand(pom: Path, command: List<String>) {
    val workingDirectory = pom.toAbsolutePath().parent
    log.info("Run ${command} in ${workingDirectory}")
    val (result, errors) = execAndReadStdoutAndStderr(command, workingDirectory)
    log.debug(result)
    if ("BUILD FAILURE" in errors) {
        log.warning("Maven task failed: {errors.lines().firstOrNull()}")
    }
}

private val mvnCommandFromPath: Path? by lazy { findCommandOnPath("mvn") }


private val artifactPattern = "^[^:]+:(?:[^:]+:)+[^:]+".toRegex()
data class MavenArtifact(
    val group: String,
    val artifact: String,
    val packaging: String?,
    val classifier: String?,
    val version: String,
    val scope: String?,
    var hasSource: Boolean = false
) {
    override fun toString() = "$group:$artifact:$version"

    companion object {
        fun fromMvnDependencyList(identifier: String)
            = identifier.trim().split(':')
            .let { it ->
                when (it.size) {
                    3 -> MavenArtifact(
                        group = it[0],
                        artifact = it[1],
                        packaging = null,
                        classifier = null,
                        version =  it[2],
                        scope = null,
                    )
                    4 -> MavenArtifact(
                        group = it[0],
                        artifact = it[1],
                        packaging = it[2],
                        classifier = null,
                        version =  it[3],
                        scope = null,
                    )
                    5 -> MavenArtifact(
                        group = it[0],
                        artifact = it[1],
                        packaging = it[2],
                        classifier = null,
                        version =  it[3],
                        scope = it[4],
                    )
                    6 -> MavenArtifact(
                        group = it[0],
                        artifact = it[1],
                        packaging = it[2],
                        classifier = it[3],
                        version =  it[4],
                        scope = it[5],
                    )
                    else -> null
                }
            }

        fun fromMvnDependencySources(identifier: String)
            = identifier.trim().split(':')
            .let {
                if (it.size==5 && it[3]=="sources")
                    MavenArtifact(
                        group = it[0],
                        artifact = it[1],
                        packaging = it[2],
                        classifier = null,
                        version = it[4].split(" ")[0],
                        scope = null,
                        hasSource = true)
                else  null
            }
    }
}

private fun Path.resolveMavenArtifacts(a: MavenArtifact)
    = this
        .resolve("repository")
        .resolve(a.group.replace('.', File.separatorChar))
        .resolve(a.artifact)
        .resolve(a.version)
        .let {
            fun file(filename: String)
                = it.resolve(filename)
                .let { if (Files.exists(it)) it else null }
            file("${a.artifact}-${a.version}.jar")
                ?.let{ it to file("${a.artifact}-${a.version}-sources.jar") }
        }
