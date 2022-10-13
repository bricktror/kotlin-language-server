package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import java.nio.file.Paths

import org.javacs.kt.j2k.convertJavaToKotlin

class JavaToKotlinTest : LanguageServerTestFixture("j2k") {
    // TODO: Seems to throw the same exception as
    // https://github.com/Kotlin/dokka/issues/1660 currently
    @Disabled @Test fun `test j2k conversion`() {
        val javaCode = workspaceRoot
            .resolve("JavaJSONConverter.java")
            .toFile()
            .readText()
            .trim()
        val expectedKotlinCode = workspaceRoot
            .resolve("JavaJSONConverter.kt")
            .toFile()
            .readText()
            .trim()
            .replace("\r\n", "\n")
        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler).replace("\r\n", "\n")
        assertThat(convertedKotlinCode, equalTo(expectedKotlinCode))
    }
}
