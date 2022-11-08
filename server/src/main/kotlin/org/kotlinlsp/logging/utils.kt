package org.kotlinlsp.logging

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

/** Run action and swallow and log any exception thrown */
inline fun <T> Logger.catching(description: String, action: () -> T): T? =
    try {
        action()
    }
    catch (e: Error) {
        error(e, "Error while ${description}")
        null
    }

/** Run action and swallow and log any exception thrown */
inline fun <T> Logger.catchingOr(
    description: String,
    valueOnError: ()->T,
    action: () -> T
): T =
    try {
        action()
    }
    catch (e: Error) {
        error(e, "Error while ${description}")
        valueOnError()
    }

/**
 * Run the provided action and log the duration of the operation.
 * If additionalMesage is provided then the resulting message will be appended
 * to the log declaring the total duration.
 * This is not to give a benchmark-safe duration, and is to be considered as
 * an aproximation.
 */
fun <T> Logger.duration(
    description: String,
    additionalMesage: (result: T) -> String? = {null},
    action: () -> T
): T {
    val started = System.currentTimeMillis()
    info("Starting ${description}")
    val result = action()
    val finished = System.currentTimeMillis()
    val duration = finished - started

    info{"Completed ${description} in ${duration}${additionalMesage(result)?.let{": ${it}"}}"}
    return result
}

fun <T> Logger.catchingWithDuration(
    description: String,
    additionalMesage: (result:T)-> String? = {null},
    action: () -> T
)  {
    catching(description) {
        duration(description, additionalMesage) {
            action()
        }
    }
}
