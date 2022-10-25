package org.javacs.kt.logging

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.logging.*

class Lsp4jLoggerTarget(
    private val client: LanguageClient,
): LoggerTarget {
    override fun write(record: LogRecord) =
        client?.logMessage(MessageParams().apply {
            type = when(record.level)
            {
                Level.SEVERE -> MessageType.Error
                Level.WARNING -> MessageType.Warning
                Level.INFO -> MessageType.Info
                else -> MessageType.Log
            }
            message = "${record.loggerName}: ${record.message}"
        })
}

