package org.javacs.kt.classpath

import org.javacs.kt.util.execAndReadStdoutAndStderr
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.isOSWindows
import org.javacs.kt.util.findCommandOnPath
import org.javacs.kt.logging.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class GradleClassPathResolver(private val path: Path): ClassPathResolver {
    private val log by findLogger
    private val projectDirectory: Path get() = path.getParent()

    override val classpath: Set<ClassPathEntry> get() =
        readDependenciesViaGradleCLI(
                projectDirectory,
                gradleScripts=listOf("kotlinDSLClassPathFinder.gradle.kts"),
                gradleTasks=listOf("kotlinLSPProjectDeps"))
            .apply { if (isNotEmpty()) log.info("Successfully resolved dependencies for '${projectDirectory.fileName}' using Gradle") }
            .map { ClassPathEntry(it) }
            .toSet()

    override val buildScriptClasspath: Set<ClassPathEntry> get() =
        readDependenciesViaGradleCLI(
                projectDirectory,
                gradleScripts=listOf("kotlinDSLClassPathFinder.gradle.kts"),
                gradleTasks=listOf("kotlinLSPKotlinDSLDeps"))
            .apply { if (isNotEmpty()) log.info("Successfully resolved build script dependencies for '${projectDirectory.fileName}' using Gradle") }
            .map { ClassPathEntry(it) }
            .toSet()

    companion object {
        /** Create a Gradle resolver if a file is a pom. */
        fun maybeCreate(file: Path): GradleClassPathResolver? =
            file.takeIf { file.endsWith("build.gradle") || file.endsWith("build.gradle.kts") }
                ?.let(::GradleClassPathResolver)
    }

    private fun gradleScriptToTempFile(scriptName: String, deleteOnExit: Boolean = false): File {
        val config = File.createTempFile("classpath-${scriptName.replace("\\W".toRegex(), "")}", ".gradle.kts")
        if (deleteOnExit) {
            config.deleteOnExit()
        }

        log.debug("Creating temporary gradle file ${config.absolutePath} (${scriptName})")

        config.bufferedWriter().use { configWriter ->
            GradleClassPathResolver::class.java.getResourceAsStream("/$scriptName").bufferedReader().use { configReader ->
                configReader.copyTo(configWriter)
            }
        }

        return config
    }

    private fun getGradleCommand(workspace: Path): Path {
        val wrapperName = if (isOSWindows()) "gradlew.bat" else "gradlew"
        val wrapper = workspace.resolve(wrapperName).toAbsolutePath()
        if (Files.isExecutable(wrapper)) {
            return wrapper
        } else {
            return workspace.parent?.let(::getGradleCommand)
                ?: findCommandOnPath("gradle")
                ?: throw KotlinLSException("Could not find 'gradle' on PATH")
        }
    }

    private fun readDependenciesViaGradleCLI(
        projectDirectory: Path,
        gradleScripts: List<String>,
        gradleTasks: List<String>
    ): Set<Path> {
        log.info("Resolving dependencies for '${projectDirectory.fileName}' through Gradle's CLI using tasks ${gradleTasks}...")

        val tmpScripts = gradleScripts.map { gradleScriptToTempFile(it, deleteOnExit = false).toPath().toAbsolutePath() }
        val gradle = getGradleCommand(projectDirectory)

        val command = listOf(gradle.toString()) + tmpScripts.flatMap { listOf("-I", it.toString()) } + gradleTasks + listOf("--console=plain")
        val dependencies = findGradleCLIDependencies(command, projectDirectory)
            ?.also { log.debug("Classpath for task ${it}") }
            .orEmpty()
            .filter { it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it) } // Some Gradle plugins seem to cause this to output POMs, therefore filter JARs
            .toSet()

        tmpScripts.forEach(Files::delete)
        return dependencies
    }

    private fun findGradleCLIDependencies(command: List<String>, projectDirectory: Path): Set<Path>? {
        val (result, errors) = execAndReadStdoutAndStderr(command, projectDirectory)
        if ("FAILURE: Build failed" in errors) {
            log.warning("Gradle task failed: ${errors}")
        } else {
            for (error in errors.lines()) {
                if ("ERROR: " in error) {
                    log.warning("Gradle error: ${error}")
                }
            }
        }
        log.fine(result)
        return parseGradleCLIDependencies(result)
    }

    private val artifactPattern by lazy { "kotlin-lsp-gradle (.+)(?:\r?\n)".toRegex() }
    private val gradleErrorWherePattern by lazy { "\\*\\s+Where:[\r\n]+(\\S\\.*)".toRegex() }

    private fun parseGradleCLIDependencies(output: String): Set<Path>? =
        artifactPattern.findAll(output)
            .mapNotNull { Paths.get(it.groups[1]?.value) }
            .filterNotNull()
            .toSet()
}
