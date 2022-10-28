package org.kotlinlsp.source

import arrow.core.Either
import com.intellij.lang.Language
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import com.intellij.psi.PsiElement
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.*
import kotlinx.coroutines.*
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.jetbrains.kotlin.cli.common.output.writeAllTo
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.types.KotlinType
import org.kotlinlsp.CompilationKind
import org.kotlinlsp.Compiler
import org.kotlinlsp.util.changedRegion
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.index.receiverTypeFqn
import org.kotlinlsp.logging.*
import org.kotlinlsp.logging.findLogger
import org.kotlinlsp.util.indexToPosition
import org.kotlinlsp.util.arrow.*
import org.kotlinlsp.util.describeURI
import org.kotlinlsp.util.fileExtension
import org.kotlinlsp.util.filePath
import org.kotlinlsp.util.findParent
import org.kotlinlsp.util.nullResult
import org.kotlinlsp.util.toPath
import org.kotlinlsp.referenceAt

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
            return Parsed(this, compiler.parseKt(content, path.toString()), compiler)
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

        fun typeOfExpression(expression: KtExpression, scopeWithImports: LexicalScope): KotlinType? =
            compiler
                .compileKtExpression(expression, scopeWithImports, sourcePath)
                .getType(expression)

        fun bindingContextOf(expression: KtExpression, scopeWithImports: LexicalScope): BindingContext =
            compiler.compileKtExpression(expression, scopeWithImports, sourcePath)

        /**
         * Looks for a reference expression at the given cursor.
         * This is currently used by many features in the language server.
         * Unfortunately, it fails to find declarations for JDK symbols.
         * [referenceExpressionAtPoint] provides an alternative implementation that can find JDK symbols.
         * It cannot, however, replace this method at the moment.
         * TODO: Investigate why this method doesn't find JDK symbols.
         */
        fun referenceAtPoint(cursor: Int): Pair<KtExpression, DeclarationDescriptor>? {
            val element = parseAtPoint(cursor, asReference = true)
            val cursorExpr = element
                ?.findParent<KtExpression>()
                ?: return nullResult("Couldn't find expression at ${describePosition(cursor)} (only found $element)")
            val surroundingExpr = expandForReference(cursor, cursorExpr)
                .also { log.info("Hovering ${it}") }
            val scope = scopeAtPoint(cursor)
                ?: return nullResult("Couldn't find scope at ${describePosition(cursor)}")
            return compiler.compileKtExpression(surroundingExpr, scope, sourcePath)
                .let { it.referenceAt(cursor) }
        }
        private fun expandForReference(cursor: Int, surroundingExpr: KtExpression): KtExpression =
            (surroundingExpr.parent as? KtDotQualifiedExpression // foo.bar
                ?: surroundingExpr.parent as? KtSafeQualifiedExpression // foo?.bar
                ?: surroundingExpr.parent as? KtCallExpression // foo()
                )?.let { expandForReference(cursor, it) }
                ?: surroundingExpr


        /**
         * Parse the expression at `cursor`.
         *
         * If the `asReference` flag is set, the method will attempt to
         * convert a declaration (e.g. of a class or a function) to a referencing
         * expression before parsing it.
         */
        fun parseAtPoint(cursor: Int, asReference: Boolean=false): KtElement? {
            val oldChanged = changedRegion(ktFile.text, content)
                ?.first
                ?: TextRange(cursor, cursor)
            val psi = ktFile.findElementAt(oldOffset(cursor))
                ?: return nullResult("Couldn't find anything at ${describePosition(cursor)}")
            val oldParent = psi.parentsWithSelf
                    .filterIsInstance<KtDeclaration>()
                    .firstOrNull { it.textRange.contains(oldChanged) }
                     ?: ktFile

            log.debug { "PSI path: ${psi.parentsWithSelf.toList()}" }

            val (surroundingContent, offset) = contentAndOffsetFromElement(
                psi,
                oldParent,
                asReference)
            return compiler
                .parseKt(
                    " ".repeat(offset) + surroundingContent,
                    "dummy.virtual.${if (isScript) "kts" else "kt"}"
                )
                .findElementAt(cursor)?.findParent<KtElement>()
        }

        private fun contentAndOffsetFromElement(
            psi: PsiElement,
            parent: KtElement,
            asReference: Boolean
        ): Pair<String, Int> {
            if (asReference && parent is KtClass && psi.node.elementType == KtTokens.IDENTIFIER) {
                // Convert the declaration into a fake reference expression
                // Converting class name identifier: Use a fake property with the class name as type
                //                                   Otherwise the compiler/analyzer would throw an exception due to a missing TopLevelDescriptorProvider
                val prefix = "val x: "
                val surroundingContent = prefix + psi.text
                return surroundingContent to (psi.textRange.startOffset - prefix.length)
            }

            // Otherwise just use the expression
            val recoveryRange = parent.textRange
            log.info{"Re-parsing ${recoveryRange.describeRange(ktFile)}"}

            var surroundingContent = content
                .substring(recoveryRange.startOffset, content.length - (ktFile.text.length - recoveryRange.endOffset))
            var offset = recoveryRange.startOffset

            if (asReference && !((parent as? KtParameter)?.hasValOrVar() ?: true)) {
                // Prepend 'val' to (e.g. function) parameters
                val prefix = "val "
                surroundingContent = prefix + surroundingContent
                offset -= prefix.length
            }
            return surroundingContent to offset
        }


        /**
         * Get the typed, compiled element at `cursor`.
         * This may be out-of-date if the user is typing quickly.
         */
        fun elementAtPoint(cursor: Int): KtElement? {
            val oldCursor = oldOffset(cursor)
            val psi = ktFile.findElementAt(oldCursor) ?: return nullResult("Couldn't find anything at ${describePosition(cursor)}")
            return psi.findParent<KtElement>()
        }


        /**
         * Find the lexical-scope surrounding `cursor`.
         * This may be out-of-date if the user is typing quickly.
         */
        fun scopeAtPoint(cursor: Int): LexicalScope? {
            val oldCursor = oldOffset(cursor)
            return context.getSliceContents(BindingContext.LEXICAL_SCOPE).asSequence()
                    .filter { it.key.textRange.startOffset <= oldCursor && oldCursor <= it.key.textRange.endOffset }
                    .sortedBy { it.key.textRange.length  }
                    .map { it.value }
                    .firstOrNull()
        }

        fun describePosition(offset: Int, oldContent: Boolean=false): String {
            val pos = indexToPosition(if (oldContent) ktFile.text else content, offset)
            val file = ktFile.toPath().fileName

            return "$file ${pos.line + 1}:${pos.character + 1}"
        }

        private fun oldOffset(cursor: Int): Int =
            changedRegion(ktFile.text, content)
                ?.let { (oldChanged, newChanged) ->
                    when {
                        cursor <= newChanged.startOffset -> cursor
                        cursor < newChanged.endOffset -> {
                            val newRelative = cursor - newChanged.startOffset
                            val oldRelative = newRelative * oldChanged.length / newChanged.length
                            oldChanged.startOffset + oldRelative
                        }
                        else -> ktFile.text.length - (content.length - cursor)
                    }
                }
                ?: cursor
    }
}


fun TextRange.describeRange(ktFile: KtFile): String {
    val start = indexToPosition(ktFile.text, startOffset)
    val end = indexToPosition(ktFile.text, endOffset)
    val file = ktFile.toPath().fileName
    return "${file} ${start.line}:${start.character + 1}-${end.line + 1}:${end.character + 1}"
}
