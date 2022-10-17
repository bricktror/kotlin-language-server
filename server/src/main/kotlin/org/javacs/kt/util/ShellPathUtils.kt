package org.javacs.kt.util

import java.nio.file.Paths
import java.nio.file.Path
import java.io.File
import org.javacs.kt.logging.*

private val log by findLogger.atToplevel(object{})

internal val userHome = Paths.get(System.getProperty("user.home"))

fun isOSWindows() = (File.separatorChar == '\\')

fun findCommandOnPath(name: String): Path? =
        if (isOSWindows()) windowsCommand(name)
        else unixCommand(name)

private fun windowsCommand(name: String) =
        findExecutableOnPath("$name.cmd")
        ?: findExecutableOnPath("$name.bat")
        ?: findExecutableOnPath("$name.exe")

private fun unixCommand(name: String) = findExecutableOnPath(name)

private fun findExecutableOnPath(fileName: String): Path? {
    for (dir in System.getenv("PATH").split(File.pathSeparator)) {
        val file = File(dir, fileName)

        if (file.isFile && file.canExecute()) {
            log.info("Found ${fileName} at ${file.absolutePath}")

            return Paths.get(file.absolutePath)
        }
    }

    return null
}

