package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import org.javacs.kt.util.parseURI
import java.net.URI

class URIsTest {
    @Test
    fun `parseURI should work with different paths`() {
        assertEquals(
            URI.create("/home/ws%201"),
            parseURI("/home/ws 1")
        )

        assertEquals(
            URI.create("/home/ws-1"),
            parseURI("/home/ws-1")
        )

        assertEquals(
            URI.create("file:/home/ws%201"),
            parseURI("file:///home/ws%201")
        )

        assertEquals(
            URI.create("file:/home/ws%201"),
            parseURI("file%3A%2F%2F%2Fhome%2Fws%201")
        )
    }
}
