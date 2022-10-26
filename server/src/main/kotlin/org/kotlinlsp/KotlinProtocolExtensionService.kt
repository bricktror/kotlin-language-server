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
    private val sp: SourceFileRepository,
) : ProtocolExtensions {

    override suspend fun jarClassContents(textDocument: TextDocumentIdentifier) =
        contentProvider.read(parseURI(textDocument.uri))

    override suspend fun overrideMember(position: TextDocumentPositionParams): List<CodeAction> {
        val compiledFile = sp
            .compileFile(parseURI(position.textDocument.uri))
            .asCompiledFile()
        return listOverridableMembers(
            compiledFile,
            offset(compiledFile.content, position.position)
        )
    }
}
