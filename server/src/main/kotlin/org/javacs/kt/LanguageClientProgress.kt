package org.javacs.kt

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
    private val label: String,
    private val token: Either<String, Int>,
    private val client: LanguageClient
) : Progress {

    init {
        reportProgress(WorkDoneProgressBegin().also {
            it.title = "Kotlin: $label"
            it.percentage = 0
        })
    }

    override fun reportProgress(message: String?, percent: Int?)
        = reportProgress(WorkDoneProgressReport().also {
            it.message = message
            it.percentage = percent
        })

    override fun close() = reportProgress(WorkDoneProgressEnd())

    private fun reportProgress(notification: WorkDoneProgressNotification)
        = client.notifyProgress(ProgressParams(token, Either.forLeft(notification)))

    class Factory(private val client: LanguageClient) : Progress.Factory {
        override fun create(label: String): CompletableFuture<Progress> {
            val token = Either.forLeft<String, Int>(UUID.randomUUID().toString())
            val xx = WorkDoneProgressCreateParams().also {
                it.token = token
            }
            val scope = CoroutineScope(Dispatchers.Default)
            return scope.async {
                client.createProgress(xx).await()
                LanguageClientProgress(label, token, client)
            }.asCompletableFuture()
        }
    }
}

/** A facility for emitting progress notifications. */
interface Progress : Closeable {
    /**
     * Updates the progress percentage. The
     * value should be in the range [0, 100].
     */
    fun reportProgress(message: String? = null, percent: Int? = null)

    object None : Progress {
        override fun reportProgress(message: String?, percent: Int?) {}

        override fun close() {}
    }

    @Deprecated("")
    interface Factory {
        /**
         * Creates a new progress listener with
         * the given label. The label is intended
         * to be human-readable.
         */
        fun create(label: String): CompletableFuture<Progress>

        object None : Factory {
            override fun create(label: String): CompletableFuture<Progress> = CompletableFuture.completedFuture(Progress.None)
        }
    }
}


fun <T> Progress.Factory.create(label: String, scope: Progress.() -> T)
    = CoroutineScope(Dispatchers.Default).launch {
        val instance=create(label).await()
        instance.use { scope(it) }
    }.asCompletableFuture()

fun <T> Progress.reportSequentially(
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
                    reportProgress(progressPrefix+message, progressPercent)
                }
            }
            action(x, item)
    }
}

interface SequentialProgressScope {
    fun report(message: String?)
}
