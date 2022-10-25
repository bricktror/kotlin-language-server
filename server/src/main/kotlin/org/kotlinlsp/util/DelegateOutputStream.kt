package org.kotlinlsp.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.OutputStream
import java.io.StringWriter
import java.util.function.Consumer

inline fun withStdout(delegateOut: PrintStream, task: () -> Unit) {
    val actualOut = System.out
    try{
        System.setOut(delegateOut)
        task()
    }
    finally {
        System.setOut(actualOut)
    }
}

class DelegateOutputStream(
    ignore: Set<Char> = setOf(),
    private val delegate: (String) -> Unit
) : OutputStream() {

    private val NEWLINE: Int =  '\n'.code
    private val ignore = setOf('\r')+ignore.map{it.code}
    private val buffer = StringWriter(128)

    override fun write(b: Int) {
        if(b==NEWLINE) {
            flush()
            return
        }
        if(ignore.contains(b)) return
        buffer.write(b)
    }
    override fun flush() {
        val str = buffer.toString()
        val b = buffer.getBuffer()
        if (b.length == 0) return
        b.setLength(0)
        delegate(str)
    }
}
