package org.kotlinlsp.source

import arrow.core.Either
import com.intellij.lang.Language
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import java.io.BufferedReader
import java.io.Closeable
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.*
import kotlinx.coroutines.*
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.kotlinlsp.CompilationKind
import org.kotlinlsp.Compiler
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.index.receiverTypeFqn
import org.kotlinlsp.util.arrow.*
import org.kotlinlsp.util.describeURI
import org.kotlinlsp.util.fileExtension
import org.kotlinlsp.util.filePath
import org.kotlinlsp.logging.findLogger
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter

abstract class SourceFile {
    private constructor(){}
    abstract fun forceInvalidate(): SourceFile
    open fun onReplacedWith(source: SourceFile?) {}

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
            log.fine("Parsing file ${path}")
            return Parsed(this, compiler.createKtFile(content, path), compiler)
        }

        override fun forceInvalidate() =
            RawSource(this)
    }

    open class Parsed(
        parent: RawSource,
        val ktFile: KtFile,
        protected val compiler: Compiler,
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

        override fun onReplacedWith(source: SourceFile?) {
            cleanupIndex?.close()
            cleanupIndex=null
            cleanupGenerated?.close()
            cleanupGenerated=null
            super.onReplacedWith(source)
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

        fun generateCode() {
            cleanupGenerated?.close()
            cleanupGenerated= compiler.generateCode(module, context, ktFile)
        }

        fun asCompiledFile()=
            CompiledFile(content, ktFile, context, module, sourcePath, compiler, isScript)
    }
}
