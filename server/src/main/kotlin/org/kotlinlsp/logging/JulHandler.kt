package org.kotlinlsp.logging

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

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
            val logger=java.util.logging.Logger.getLogger("")
            logger.getHandlers().forEach{logger.removeHandler(it)}
            logger.setLevel(Level.FINE)
            logger.addHandler(LoggerTargetJulHandler(target))
        }
    }
}
