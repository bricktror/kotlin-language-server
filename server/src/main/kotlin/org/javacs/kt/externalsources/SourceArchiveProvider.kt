package org.javacs.kt.externalsources

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import org.javacs.kt.CompilerClassPath

fun interface SourceArchiveProvider {
    fun fetchSourceArchive(compiledArchive: Path): Path?
}

interface Decompiler {
    fun decompileClass(compiledClass: Path): Path

    fun decompileJar(compiledJar: Path): Path
}

class DecompilerArciveProvider(private val decompiler:Decompiler): SourceArchiveProvider {
    override fun fetchSourceArchive(compiledArchive: Path) = decompiler.decompileJar(compiledArchive)
}

class CompositeSourceArchiveProvider(
        private vararg val providers: SourceArchiveProvider
) : SourceArchiveProvider {
    override fun fetchSourceArchive(compiledArchive: Path): Path? =
        providers.firstNotNullOfOrNull { it.fetchSourceArchive(compiledArchive) }
}

class ClassPathSourceArchiveProvider(private val cp: CompilerClassPath) : SourceArchiveProvider {
    override fun fetchSourceArchive(compiledArchive: Path): Path? =
            cp.classPath.firstOrNull { it.compiledJar == compiledArchive }?.sourceJar
}

class JdkSourceArchiveProvider(private val javaHome: String) : SourceArchiveProvider {

    /**
     * Checks if the given path is inside the JDK. If it is, we return the corresponding source zip.
     * Note that this method currently doesn't take into the account the JDK version, which means
     * JDK source code is only available for JDK 9+ builds. TODO: improve this resolution logic to
     * work for older JDK versions as well.
     */
    override fun fetchSourceArchive(compiledArchive: Path): Path? {
        val javaHomePath = File(javaHome).toPath()
        if (compiledArchive == javaHomePath) {
            return Paths.get(compiledArchive.toString(), "lib", "src.zip")
        }
        return null
    }
}
