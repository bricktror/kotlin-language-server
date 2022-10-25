package org.kotlinlsp.logging

import java.util.Queue
import java.util.ArrayDeque
import java.util.logging.LogRecord

interface LoggerTarget {
    fun write(message: LogRecord)
    fun onReplaceWith(other: LoggerTarget) { }
}

class QueueLoggerTarget : LoggerTarget {
    private val queue: Queue<LogRecord> = ArrayDeque()

    override fun write(message:LogRecord){
        queue.offer(message)
    }

    override fun onReplaceWith(other:LoggerTarget) {
        while (queue.isNotEmpty()) {
            other.write(queue.poll())
        }
    }
}

class FunctionLoggerTarget(
    private var delegate: ((LogRecord) -> Unit)
) : LoggerTarget {
    override fun write(message: LogRecord) = delegate(message)
}

class DelegateLoggerTarget(
    var inner: LoggerTarget
): LoggerTarget {
    override fun write(message:LogRecord) = inner.write(message)
    override fun onReplaceWith(other: LoggerTarget)
        = inner.onReplaceWith(other)
}

