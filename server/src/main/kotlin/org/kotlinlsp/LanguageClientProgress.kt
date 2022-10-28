package org.kotlinlsp

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressNotification
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressReport
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.future.*

class LanguageClientProgress(
    private val client: LanguageClient,
    private val label: String,
    private val token: Either<String, Int>,
) : Closeable {

    companion object {
        suspend fun create(
            client: LanguageClient,
            label: String,
            token: Either<String, Int>?=null
        ): LanguageClientProgress {
            if(token!=null)
                return LanguageClientProgress(client, label, token)
            val tt=Either.forLeft<String, Int>(UUID.randomUUID().toString())
            client.createProgress(
                    WorkDoneProgressCreateParams()
                        .also { it.token = tt })
                .await()
            return LanguageClientProgress(client, label, tt)
        }
    }

    init {
        reportProgress(WorkDoneProgressBegin().also {
            it.title = "Kotlin: $label"
            it.percentage = 0
        })
    }

    fun reportProgress(percent: Int, message: String?) =
        reportProgress(WorkDoneProgressReport().also {
            it.message = message
            it.percentage = percent
        })

    override fun close() = reportProgress(WorkDoneProgressEnd())

    private fun reportProgress(notification: WorkDoneProgressNotification) =
        client.notifyProgress(ProgressParams(token, Either.forLeft(notification)))
}

fun <T> LanguageClientProgress.reportSequentially(
    items: Collection<T>,
    nameSelector: ((item:T)-> String?)={null},
    action: SequentialProgressScope.(item: T)->Unit
) {
    items.forEachIndexed { index, item ->
            val name = nameSelector(item)?.let {" "+it}
            val progressPrefix = "[${index + 1}/${items.size}${name}]:"
            val progressPercent = (100 * index) / items.size
            val x = object: SequentialProgressScope {
                override fun report(message: String?) {
                    reportProgress(progressPercent, progressPrefix+message)
                }
            }
            action(x, item)
    }
}

interface SequentialProgressScope {
    fun report(message: String?)
}
