package org.kotlinlsp.source

import com.intellij.psi.PsiElement
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.output.writeAllTo
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.kotlinlsp.CompilationKind
import org.kotlinlsp.Compiler
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.logging.findLogger

abstract class SourceFile: Closeable {
    private constructor(){}
    abstract fun forceInvalidate(): SourceFile
    override fun close() {}

    open class RawSource(
        val path: Path,
        val version: Int,
        val content: String,
        val isTransient: Boolean,
    ): SourceFile() {
        constructor(parent: RawSource): this(
            path=parent.path,
            version=parent.version,
            content=parent.content,
            isTransient=parent.isTransient,
        ) {}
        private val log by findLogger

        val kind get()= if (path.fileName.toString().endsWith(".gradle.kts"))
                CompilationKind.BUILD_SCRIPT
            else
                CompilationKind.DEFAULT

        val isScript: Boolean get()= path.getFileName().endsWith(".kts")

        fun parse(compiler: Compiler): Parsed{
            log.finer("Parsing file ${path}")
            return Parsed(this, compiler.parseKt(content, path.toString()), compiler)
        }

        override fun forceInvalidate() =
            RawSource(this)
    }

    open class Parsed(
        parent: RawSource,
        val ktFile: KtFile,
        val compiler: Compiler,
    ): RawSource(parent) {
        constructor(parent: Parsed): this(parent, parent.ktFile, parent.compiler) { }
        private val log by findLogger

        fun compile(
            dependencies: Collection<KtFile>,
        ): Compiled {
            log.fine("Compiling ${path} with ${dependencies.size} dependencies.")
            var sourcePath = dependencies.let {
                // TODO does it need itself as sourcepath really?
                if (isTransient) it+listOf(ktFile)
                else it
            }
            val (context, module) = compiler.compileKtFile(ktFile, sourcePath)
            return Compiled(this, module, context, sourcePath)
        }
    }

    class Compiled(
        parent: Parsed,
        val module: ModuleDescriptor,
        val context: BindingContext,
        val sourcePath: Collection<KtFile>
    ): Parsed(parent) {
        private val log by findLogger
        val isIndexed get() = cleanupIndex!=null

        private var cleanupGenerated: Closeable? = null
        private var cleanupIndex: Closeable? = null

        override fun close() {
            cleanupIndex?.close()
            cleanupIndex=null
            cleanupGenerated?.close()
            cleanupGenerated=null
            super.close()
        }

        fun indexSymbols(updateIndex: ((SymbolTransaction.()->Unit)->Unit)) {
            log.fine("Adding symbols from ${path} to index")
            cleanupIndex?.close()
            cleanupIndex = module
                .getPackage(ktFile.packageFqName)
                .memberScope
                .getContributedDescriptors(DescriptorKindFilter.ALL) { name ->
                    ktFile.declarations.map { it.name }.contains(name.toString())
                }
                .map { Symbol.fromDeclaration(it) }
                .let {
                    updateIndex {
                        it.forEach { add(it) }
                    }
                    object : Closeable {
                        override fun close() {
                            log.fine("Removing symbols from ${path} to index")
                            updateIndex {
                                it.forEach {
                                    remove(it.fqName.asString(), it.extensionReceiverType?.asString())
                                }
                            }
                        }
                    }
                }
        }

        fun generateCode(outputDirectory: File) {
            cleanupGenerated?.close()
            compiler
                .generateClassFiles(module, context, ktFile)
                .writeAllTo(outputDirectory)
            cleanupGenerated = object: Closeable {
                override fun close() {
                    ktFile.declarations
                        .map {
                            ktFile.packageFqName
                                .asString()
                                .replace(".", File.separator) + File.separator + it.name + ".class"
                        }
                        .forEach { outputDirectory.resolve(it).delete() }
                }
            }
        }
    }
}
