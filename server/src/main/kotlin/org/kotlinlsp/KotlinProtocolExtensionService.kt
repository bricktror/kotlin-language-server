package org.kotlinlsp

import org.eclipse.lsp4j.*
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.lsp4kt.ProtocolExtensions
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.source.FileContentProvider
import java.util.concurrent.CompletableFuture
import java.nio.file.Paths

import kotlin.io.path.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture

class KotlinProtocolExtensionService(
    private val contentProvider: FileContentProvider,
    private val cp: CompilerClassPath,
    private val sp: SourceFileRepository,
    private val scope: CoroutineScope,
) : ProtocolExtensions {

    override suspend fun jarClassContents(textDocument: TextDocumentIdentifier) =
        contentProvider.read(parseURI(textDocument.uri))

    override suspend fun buildOutputLocation() = cp.outputDirectory.file.absolutePath

    override suspend fun mainClass(textDocument: TextDocumentIdentifier): Map<String, Any?> {
        val fileUri = parseURI(textDocument.uri)

        // we find the longest one in case both the root and submodule are included
        val workspacePath = cp.workspaceRoots.filter {
            fileUri.toPath().startsWith(it.toPath())
        }.map {
            it.toString()
        }.maxByOrNull(String::length) ?: ""

        val compiledFile = sp.currentVersion(fileUri)!!

        return resolveMain(compiledFile) + mapOf("projectRoot" to workspacePath)
    }

    override suspend fun overrideMember(position: TextDocumentPositionParams): List<CodeAction> {
        val fileUri = parseURI(position.textDocument.uri)
        val compiledFile = sp.currentVersion(fileUri)!!
        val cursorOffset = offset(compiledFile.content, position.position)

        return listOverridableMembers(compiledFile, cursorOffset)
    }
}
