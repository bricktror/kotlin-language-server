package org.javacs.kt.logging

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.companionObject
import java.util.function.Supplier
import java.util.logging.Logger
import java.util.logging.Level

object findLogger {
    operator fun <T: Any> getValue(thisRef: T, property: KProperty<*>)
        = Logger.getLogger(getClassForLogging(thisRef.javaClass).getCanonicalName())

    private fun <T: Any> getClassForLogging(javaClass: Class<T>): Class<*>
        = javaClass.enclosingClass
        ?.takeIf {it.kotlin.companionObject?.java == javaClass }
        ?: javaClass

    fun <T: Any> atToplevel(topLevelObject: T)= object : ReadOnlyProperty<Nothing?, Logger> {
        override operator fun getValue(thisRef: Nothing?, property: KProperty<*>)
            = topLevelObject.javaClass.getName()
            .substringBefore('$')
            .let{Logger.getLogger(it)}
    }
}


fun Logger.error(error: Throwable, message: String) = log(Level.SEVERE, error) {message}
fun Logger.error(error: Throwable, message: Supplier<String>) = log(Level.SEVERE, error, message)

fun Logger.debug(message: String) = config(message)
fun Logger.debug(message: Supplier<String>) = config(message)
