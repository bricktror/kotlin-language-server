package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import org.javacs.kt.compiler.Compiler
import java.io.File
import java.nio.file.Files

class CompiledFileTest {
    val compiledFile = compileFile()

    companion object {
        lateinit var outputDirectory: File

        @JvmStatic @BeforeAll fun setup() {
            LOG.connectStdioBackend()
            outputDirectory = Files.createTempDirectory("klsBuildOutput").toFile()
        }

        @JvmStatic @AfterAll fun tearDown() {
            outputDirectory.delete()
        }
    }

    fun compileFile(): CompiledFile = Compiler(setOf(), setOf(), outputDirectory = outputDirectory).use { compiler ->
        val file = testResourcesRoot().resolve("compiledFile/CompiledFileExample.kt")
        val content = Files.readAllLines(file).joinToString("\n")
        val parse = compiler.createKtFile(content, file)
        val classPath = CompilerClassPath(CompilerConfiguration())
        val sourcePath = listOf(parse)
        val (context, container) = compiler.compileKtFiles(sourcePath, sourcePath)
        CompiledFile(content, parse, context, container, sourcePath, classPath)
    }

    @Test fun `typeAtPoint should return type for x`() {
        val type = compiledFile.typeAtPoint(87)!!

        assertThat(type.toString(), equalTo("Int"))
    }
}
