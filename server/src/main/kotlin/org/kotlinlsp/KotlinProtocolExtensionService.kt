package org.kotlinlsp

import org.eclipse.lsp4j.*
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.lsp4kt.ProtocolExtensions
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.file.FileProvider
import org.kotlinlsp.util.getIndexIn
import java.util.concurrent.CompletableFuture
import java.nio.file.Paths

import kotlin.io.path.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture

class KotlinProtocolExtensionService(
    private val contentProvider: FileProvider,
    private val sp: SourceFileRepository,
) : ProtocolExtensions {

    override suspend fun jarClassContents(textDocument: TextDocumentIdentifier) =
        contentProvider.read(parseURI(textDocument.uri))

    override suspend fun overrideMember(position: TextDocumentPositionParams): List<CodeAction> =
        sp.compileFile(parseURI(position.textDocument.uri))
            .let { listOverridableMembers( it, position.position.getIndexIn(it.content)) }
}
