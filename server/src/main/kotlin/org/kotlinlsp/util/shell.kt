package org.kotlinlsp.util

import java.nio.file.Paths
import java.nio.file.Path
import java.io.File
import org.kotlinlsp.logging.*

private val log by findLogger.atToplevel(object{})

fun findCommandOnPath(name: String): Path? =
    System.getenv("PATH")
        .split(File.pathSeparator)
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
        ?.also { log.info("Resolved command ${name} at ${it}") }
        ?.let { Paths.get(it) }


