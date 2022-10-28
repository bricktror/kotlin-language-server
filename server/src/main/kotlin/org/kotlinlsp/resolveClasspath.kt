package org.kotlinlsp

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import org.kotlinlsp.logging.*
import org.kotlinlsp.source.FilesystemURIWalker
import org.kotlinlsp.util.execAndReadStdoutAndStderr
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.fileName
import org.kotlinlsp.util.findCommandOnPath
import org.kotlinlsp.util.TempFile

private val log by findLogger.atToplevel(object{})

// TODO more sources than gradle? Look in git history for maven and more

fun resolveClasspath(workspaceRoots: Collection<Path>) =
    copyResourceAsTempfile("kotlinDSLClassPathFinder.gradle.kts")
        .use { tmpFile->
            val helperScript=tmpFile.path
                .toAbsolutePath()
                .toString()
            workspaceRoots
                .flatMap {
                    resolveClasspathUsingGradle(it, helperScript)
                        .map { (a,b)->a to b }
                }
        }
    .toMap()
    .let { map->
        fun asPaths(key: String) =
            map.values // TODO per-project partitioning?
                .flatMap{it.get(key) ?: listOf()}
                .map { Paths.get(it) }
                .toSet()
                .also{log.debug("${key} classpath found ${it.size} dependencies")}
        asPaths("dependency") to asPaths("build-dependency")
    }

private fun resolveClasspathUsingGradle(path: Path, helperScript: String) =
        run {
            log.info("Resolving dependencies for '${path}' through Gradle's CLI")
            execAndReadStdoutAndStderr(path, listOf(
                    getGradleCommand(path).toString(),
                    "-I", helperScript,
                    "kotlin-lsp-deps",
                    "--console=plain",
                ))
        }
        .let { (result, errors) ->
            if(!errors.isBlank())
                log.warning("Gradle ran with errors: ${errors}")
            log.fine(result)
            result
        }
        .let{it.lines()}
        .mapNotNull { GradleKotlinLspRow.tryParse(it) }
        .let { GradleKotlinLspRow.inflateStructure(it) }


private data class GradleKotlinLspRow(
    val project: String,
    val key: String,
    val value: String,
) {
    companion object {
        private val pattern = "^kotlin-lsp ((?:\\ |[^ ])+) (\\S+) (.+)$".toRegex()
        fun tryParse(line: String) =
            pattern.find(line)
                ?.groups
                ?.let { GradleKotlinLspRow(
                    project=it[1]!!.value
                        .replace("\\ ", " ")
                        .replace("\\\\", "\\"),
                    key=it[2]!!.value,
                    value=it[3]!!.value)
                }

        fun inflateStructure(
            items: Collection<GradleKotlinLspRow>
        ): Map<String, Map<String, List<String>>> =
            items
                .groupBy{it.project}
                .map { (project, rows)-> project to rows.groupBy({it.key}, {it.value}) }
                .toMap()

    }
}

private fun copyResourceAsTempfile(scriptName: String) =
    TempFile.create("classpath-${scriptName.replace("\\W".toRegex(), "")}", ".gradle.kts") {
        log.debug("Creating temporary gradle file ${it} (${scriptName})")
        it.toFile()
            .bufferedWriter()
            .use { configWriter ->
                object{}::class.java
                    .getResourceAsStream("/$scriptName")
                    .bufferedReader()
                    .use { configReader -> configReader.copyTo(configWriter) }
            }
    }

private fun getGradleCommand(workspace: Path): Path {
    val wrapper = workspace.resolve("gradlew").toAbsolutePath()
    if (Files.isExecutable(wrapper)) {
        return wrapper
    } else {
        return workspace.parent?.let(::getGradleCommand)
            ?: findCommandOnPath("gradle")
            ?: throw Error("Could not find 'gradle' on PATH")
    }
}
