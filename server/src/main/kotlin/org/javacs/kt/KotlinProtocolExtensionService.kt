package org.javacs.kt

import org.eclipse.lsp4j.*
import org.javacs.kt.util.parseURI
import org.javacs.kt.resolve.resolveMain
import org.javacs.kt.position.offset
import org.javacs.kt.overridemembers.listOverridableMembers
import java.util.concurrent.CompletableFuture
import java.nio.file.Paths

import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture

class KotlinProtocolExtensionService(
    private val uriContentProvider: URIContentProvider,
    private val cp: CompilerClassPath,
    private val sp: SourcePath,
    private val scope: CoroutineScope,
) : KotlinProtocolExtensions {

    override fun jarClassContents(textDocument: TextDocumentIdentifier): CompletableFuture<String?> = scope.async {
        uriContentProvider.contentOf(parseURI(textDocument.uri))
    }.asCompletableFuture()

    override fun buildOutputLocation(): CompletableFuture<String?> = scope.async {
        cp.outputDirectory.absolutePath
    }.asCompletableFuture()

    override fun mainClass(textDocument: TextDocumentIdentifier): CompletableFuture<Map<String, Any?>> = scope.async {
        val fileUri = parseURI(textDocument.uri)
        val filePath = Paths.get(fileUri)

        // we find the longest one in case both the root and submodule are included
        val workspacePath = cp.workspaceRoots.filter {
            filePath.startsWith(it)
        }.map {
            it.toString()
        }.maxByOrNull(String::length) ?: ""

        val compiledFile = sp.currentVersion(fileUri)

        resolveMain(compiledFile) + mapOf(
            "projectRoot" to workspacePath
        )
    }.asCompletableFuture()

    override fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>> = scope.async {
        val fileUri = parseURI(position.textDocument.uri)
        val compiledFile = sp.currentVersion(fileUri)
        val cursorOffset = offset(compiledFile.content, position.position)

        listOverridableMembers(compiledFile, cursorOffset)
    }.asCompletableFuture()
}
