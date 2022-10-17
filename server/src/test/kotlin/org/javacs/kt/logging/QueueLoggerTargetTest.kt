package org.javacs.kt.logging

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import java.util.logging.*

class QueueLoggerTargetTest {
    @Test fun `Emitts the queue when being replaced`() {
        // Arrange
        val records = listOf(
            LogRecord(Level.INFO, "some message"),
            LogRecord(Level.WARNING, "another message"),
            LogRecord(Level.FINE, "more messages"),
            LogRecord(Level.INFO, "bye"),
        )
        val result = mutableListOf<LogRecord>()
        val target = QueueLoggerTarget()
        // Act
        records.forEach(target::write)
        target.onReplaceWith(object : LoggerTarget {
            override fun write(message: LogRecord) {
                result.add(message)
            }
        })
        // Assert
        assertEquals(records, result)
    }
}
