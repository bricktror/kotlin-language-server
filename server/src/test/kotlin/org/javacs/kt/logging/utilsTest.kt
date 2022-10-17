package org.javacs.kt.logging

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

val topLevelLogger by findLogger.atToplevel(object{})

class UtilsTest {
    companion object {
        val companionLogger by findLogger
    }
    val classLogger by findLogger

    @Test fun `Class-logger get correct name`()
        = assertEquals(
            "org.javacs.kt.logging.UtilsTest",
            classLogger.getName())

    @Test fun `Companion-logger get correct name`()
        = assertEquals(
            "org.javacs.kt.logging.UtilsTest",
            classLogger.getName())

    @Test fun `Top-level-logger get correct name`()
        = assertEquals(
            "org.javacs.kt.logging.UtilsTestKt",
            topLevelLogger.getName())
}
