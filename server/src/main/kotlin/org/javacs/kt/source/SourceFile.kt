package org.javacs.kt.source

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
import org.javacs.kt.CompilationKind
import org.javacs.kt.Compiler
import org.javacs.kt.index.Symbol
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.index.SymbolTransaction
import org.javacs.kt.index.receiverTypeFqn
import org.javacs.kt.util.arrow.*
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.fileExtension
import org.javacs.kt.util.filePath
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

        val kind get()= if (path.fileName.toString().endsWith(".gradle.kts"))
                CompilationKind.BUILD_SCRIPT
            else
                CompilationKind.DEFAULT
        val isScript: Boolean get()= path.getFileName().endsWith(".kt")

        fun parse(compiler: Compiler) =
            Parsed(this, compiler.createKtFile(content, path, kind), compiler)

        override fun forceInvalidate() =
            RawSource(this)
    }

    open class Parsed(
        parent: RawSource,
        val ktFile: KtFile,
        protected val compiler: Compiler,
    ): RawSource(parent) {
        constructor(parent: Parsed): this(parent, parent.ktFile, parent.compiler) { }

        fun compile(
            dependencies: Collection<KtFile>,
        ): Compiled {
            var sourcePath = dependencies.let {
                if (isTransient) it+listOf(ktFile)
                else it
            }
            val (context, module) = compiler.compileKtFile(ktFile, sourcePath, kind)
            return Compiled(this, module, context, sourcePath)
        }
    }

    class Compiled(
        parent: Parsed,
        val module: ModuleDescriptor,
        val context: BindingContext,
        val sourcePath: Collection<KtFile>
    ): Parsed(parent) {
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
            cleanupGenerated= compiler.generateCode(module, context, listOf(ktFile))
        }

        fun asCompiledFile()=
            CompiledFile(content, ktFile, context, module, sourcePath, compiler, isScript, kind)
    }
}
