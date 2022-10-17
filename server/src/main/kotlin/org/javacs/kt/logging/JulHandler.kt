package org.javacs.kt.logging

import java.util.logging.LogRecord
import java.util.logging.Handler

class LoggerTargetJulHandler(
    private val target: LoggerTarget
): Handler() {
    override fun publish(record: LogRecord) {
        target.write(record)
    }

    override fun flush() {}

    override fun close() {}

    companion object {
        fun install(target: LoggerTarget) {
            java.util.logging.Logger.getLogger("")
                .addHandler(LoggerTargetJulHandler(target))
        }
    }
}
