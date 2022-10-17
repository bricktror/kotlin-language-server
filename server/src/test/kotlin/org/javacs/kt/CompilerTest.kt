package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.javacs.kt.compiler.Compiler
import java.io.File
import java.nio.file.Files

fun runWithCompiler(runTest: (compiler: Compiler)-> Unit) {
    val outputDirectory = Files.createTempDirectory("klsBuildOutput").toFile()
    try {
        val compiler = Compiler(setOf(), setOf(), outputDirectory = outputDirectory)
        runTest(compiler)
    }
    finally {
        outputDirectory.delete()
    }
}

class CompilerTest {
    val file = testResourcesRoot()
        .resolve("compiler")
        .resolve("FileToEdit.kt")
    val editedText = """
private class FileToEdit {
    val someVal = 1
}"""

    @Test fun compileFile() = runWithCompiler { compiler ->
        val content = Files.readAllLines(file).joinToString("\n")
        val original = compiler.createKtFile(content, file)
        val (context, _) = compiler.compileKtFile(original, listOf(original))
        val psi = original.findElementAt(45)!!
        val kt = psi.parentsWithSelf.filterIsInstance<KtExpression>().first()

        assertThat(context.getType(kt), hasToString("String"))
    }

    @Test fun newFile() = runWithCompiler { compiler ->
        val original = compiler.createKtFile(editedText, file)
        val (context, _) = compiler.compileKtFile(original, listOf(original))
        val psi = original.findElementAt(46)!!
        val kt = psi.parentsWithSelf.filterIsInstance<KtExpression>().first()

        assertThat(context.getType(kt), hasToString("Int"))
    }

    @Test fun editFile() = runWithCompiler { compiler ->
        val content = Files.readAllLines(file).joinToString("\n")
        val original = compiler.createKtFile(content, file)
        var (context, _) = compiler.compileKtFile(original, listOf(original))
        var psi = original.findElementAt(46)!!
        var kt = psi.parentsWithSelf.filterIsInstance<KtExpression>().first()

        assertThat(context.getType(kt), hasToString("String"))

        val edited = compiler.createKtFile(editedText, file)
        context = compiler.compileKtFile(edited, listOf(edited)).first
        psi = edited.findElementAt(46)!!
        kt = psi.parentsWithSelf.filterIsInstance<KtExpression>().first()

        assertThat(context.getType(kt), hasToString("Int"))
    }

    @Test fun editRef() = runWithCompiler { compiler ->
        val file1 = testResourcesRoot().resolve("hover/Recover.kt")
        val content = Files.readAllLines(file1).joinToString("\n")
        val original = compiler.createKtFile(content, file1)
        val (context, _) = compiler.compileKtFile(original, listOf(original))
        val function = original.findElementAt(49)!!.parentsWithSelf.filterIsInstance<KtNamedFunction>().first()
        val scope = context.get(BindingContext.LEXICAL_SCOPE, function.bodyExpression)!!
        val recompile = compiler.createKtDeclaration("""private fun singleExpressionFunction() = intFunction()""")
        val (recompileContext, _) = compiler.compileKtExpression(recompile, scope, setOf(original))
        val intFunctionRef = recompile.findElementAt(41)!!.parentsWithSelf.filterIsInstance<KtReferenceExpression>().first()
        val target = recompileContext.get(BindingContext.REFERENCE_TARGET, intFunctionRef)!!

        assertThat(target.name, hasToString("intFunction"))
    }
}
