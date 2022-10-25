package org.kotlinlsp

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.kotlinlsp.CompilerClassPath
import org.kotlinlsp.source.FileContentProvider
import org.kotlinlsp.logging.*
import org.kotlinlsp.isZero
import org.kotlinlsp.location
import org.kotlinlsp.position
import org.kotlinlsp.source.CompiledFile
import org.kotlinlsp.util.TemporaryDirectory
import org.kotlinlsp.util.fileExtension
import org.kotlinlsp.util.fileName
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.util.partitionAroundLast
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtNamedDeclaration

private val log by findLogger.atToplevel(object{})

private val definitionPattern = Regex("(?:class|interface|object|fun)\\s+(\\w+)")

fun goToDefinition(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: FileContentProvider,
    tempDir: TemporaryDirectory,
    cp: CompilerClassPath
): Location? {
    TODO()
    /* val (_, target) = file.referenceExpressionAtPoint(cursor) ?: return null */

    /* log.info("Found declaration descriptor ${target}") */
    /* var destination = location(target) */
    /* val psi = target.findPsi() */

    /* if (psi is KtNamedDeclaration) { */
    /*     destination = psi.nameIdentifier?.let(::location) ?: destination */
    /* } */

    /* if(destination==null) return null */

    /* val rawClassURI = destination.uri */

    /* if (!isInsideArchive(rawClassURI, cp)) return null */
    /* parseURI(rawClassURI) */
    /*     .let { classContentProvider.read(it) } */
    /*     ?.let { (klsSourceURI, content) -> */

    /*         // Return the path to a temporary file */
    /*         // since the client has not opted into */
    /*         // or does not support KLS URIs */
    /*         val name = klsSourceURI.fileName.partitionAroundLast(".").first */
    /*         val extensionWithoutDot = klsSourceURI.fileExtension */
    /*         val extension = if (extensionWithoutDot != null) ".$extensionWithoutDot" else "" */
    /*         val tmpFile=tempDir.createTempFile(name, extension) */
    /*             .also { it.toFile().writeText(content) } */

    /*         destination.uri = tmpFile.toUri().toString() */

    /*         if (destination.range.isZero) { */
    /*             // Try to find the definition inside the source directly */
    /*             val name = when (target) { */
    /*                 is ConstructorDescriptor -> target.constructedClass.name.toString() */
    /*                 else -> target.name.toString() */
    /*             } */
    /*             definitionPattern.findAll(content) */
    /*                 .map { it.groups[1]!! } */
    /*                 .find { it.value == name } */
    /*                 ?.let { it.range } */
    /*                 ?.let { destination.range = Range(position(content, it.first), position(content, it.last)) } */
    /*         } */
    /*     } */

    /* return destination */
}

private fun isInsideArchive(uri: String, cp: CompilerClassPath) =
    uri.contains(".jar!") || uri.contains(".zip!") || cp.javaHome?.let {
        Paths.get(parseURI(uri)).toString().startsWith(File(it).path)
    } ?: false
