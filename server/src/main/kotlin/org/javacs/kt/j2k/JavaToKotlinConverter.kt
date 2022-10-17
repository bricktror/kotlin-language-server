package org.javacs.kt.j2k

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiFileFactory
import com.intellij.openapi.project.Project
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.compiler.CompilationKind
import org.javacs.kt.util.nonNull

@Deprecated("Use compile.transpileJavaToKotlin(code)")
fun convertJavaToKotlin(javaCode: String, compiler: Compiler): String =
    compiler.transpileJavaToKotlin(javaCode) ?: ""
