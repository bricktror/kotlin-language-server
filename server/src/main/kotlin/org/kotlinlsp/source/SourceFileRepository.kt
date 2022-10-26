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
import org.kotlinlsp.CompilerClassPath
import org.kotlinlsp.CompilationKind
import org.kotlinlsp.Compiler
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.SymbolTransaction
import org.kotlinlsp.index.receiverTypeFqn
import org.kotlinlsp.logging.*
import org.kotlinlsp.util.arrow.*
import org.kotlinlsp.util.fileExtension
import org.kotlinlsp.util.filePath
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter

// TODO thread safety? Make suspendable functions using worker thread?
class SourceFileRepository(
    private val compiler: (CompilationKind)->Compiler,
    private val contentProvider: FileContentProvider,
    val index : SymbolIndex,
) {
    private val log by findLogger

    private object files {
        private val log by findLogger

        private val inner = mutableMapOf<URI, SourceFile>()
        val keys get() = inner.keys
        operator fun get(uri: URI) = inner[uri.normalize()]

        fun remove(uri: URI) {
            update(uri) { null }
        }

        inline fun <reified T: SourceFile> ofType() =
            inner.filter{it is T }

        fun <T: SourceFile> update(uri: URI, updater: (SourceFile?) -> T?): T? =
            uri.normalize()
                .let { normalizedUri ->
                    inner.remove(normalizedUri)
                        .let { currentValue->
                            try{
                                updater(currentValue)
                                    .also { nextValue->
                                        if (nextValue != currentValue)
                                            currentValue?.onReplacedWith(nextValue)
                                    }
                            }
                            catch (ex: Error) {
                                currentValue?.let{inner[normalizedUri]=it}
                                log.error(ex, "Failed to update file ${normalizedUri}")
                                null
                            }
                        }
                        ?.also { inner[normalizedUri]=it }
                }


        fun <T: SourceFile> updateAll(updater: (SourceFile) -> T?): Collection<T> =
            inner.keys.toList().mapNotNull { files.update(it) { it?.let{ updater(it) } } }

        fun <T: SourceFile> updateAll(uris: Collection<URI>, updater: (SourceFile) -> T?): Collection<T> =
            uris.mapNotNull { files.update(it) { it?.let{ updater(it) } } }
    }

    private object ensure {
        fun isCompiledUnsafe(dependencies: Collection<KtFile>): (SourceFile)-> SourceFile.Compiled  = {
            when(it) {
                is SourceFile.Compiled -> it
                is SourceFile.Parsed -> it.compile(dependencies)
                else -> throw Error("Unhandled SourceFile type ${it}. Need to compile before")
            }
        }

        fun isParsed(compiler: (CompilationKind)->Compiler): (SourceFile)-> SourceFile.Parsed = {
            when(it) {
                is SourceFile.Parsed -> it
                is SourceFile.RawSource -> it.parse(compiler(it.kind))
                else -> throw Error("Unhandled SourceFile type ${it}")
            }
        }

        fun isRaw(src: SourceFile):SourceFile.RawSource = when(src) {
            is SourceFile.RawSource -> src
            else -> throw Error("Unhandled SourceFile type ${src}")
        }
    }

    val keys get() = files.keys

    fun readFromProvider(uri: URI) =
        files.update(uri) { rawSource(uri) }

    private fun updateUnlessOldVersion(
        uri:URI,
        version: Int,
        updater: (SourceFile?)->SourceFile.RawSource?
    ) = files.update(uri) {
        when {
            it !is SourceFile.RawSource
            || it.version < version
            -> updater(it)
            else -> {
                log.warning{"Attempt to update source with old version for ${uri}. was ${it.version} but got ${version}"}
                it
            }
        }
    }

    fun applyManualEdit(uri: URI, version: Int, content: String) =
        updateUnlessOldVersion(uri, version) {
            rawSource(uri, version, content)
        }

    fun applyManualEdit(uri: URI, version: Int, changes: List<TextDocumentContentChangeEvent>) =
        updateUnlessOldVersion(uri, version) {
            (it as? SourceFile.RawSource ?: rawSource(uri))
                .let { existing -> rawSource(
                    uri,
                    version,
                    isTransient=existing.isTransient,
                    content=changes.fold(existing.content) { acc, change ->
                        if (change.range == null) change.text
                        else patch(acc, change)
                    },
                ) }
        }

    fun hasManualEdit(uri:URI) =
        (files[uri] as? SourceFile.RawSource)?.version != -1

    fun remove(uri: URI)= files.remove(uri)

    fun removeMatching(predicate: (URI)->Boolean) {
        keys.filter{predicate(it)}.forEach(::remove)
    }

    private fun rawSource(
        uri: URI,
        version: Int=-1,
        content: String=contentProvider.read(uri.normalize()) ?: "",
        isTransient: Boolean=false
    )= SourceFile.RawSource(
            content=convertLineSeparators(content),
            path=uri.filePath ?: Paths.get("sourceFile.virtual.${uri.fileExtension ?: "kt"}"),
            isTransient=isTransient,
            version=version)

    fun closeTransient(uri: URI) {
        val file=files[uri]
        if (file !is SourceFile.RawSource || !file.isTransient) return
        log.info{"Removing transient source file ${uri} from source path"}
        files.remove(uri)
    }

    /** Get the latest content of a file */
    fun content(uri: URI): String =
        files.update(uri) { ensure.isRaw(it ?: rawSource(uri)) }!!.content

    /** Compile changed files */
    fun compileFiles(uris: Collection<URI>) =
            files.updateAll(ensure.isParsed(compiler))
                .map { it.ktFile }
                .let { dependencies -> files.updateAll(uris, ensure.isCompiledUnsafe(dependencies)) }

    fun compileFile(uri: URI) =
        compileFiles(listOf(uri)).first()

    /**
     * Refreshes the indexes. If already done, refreshes only the declarations in the files that were changed.
     */
    fun refreshDependencyIndexes() {
        compileAllFiles()
            .forEach { it.indexSymbols(index::updateIndex) }
    }

    fun compileAllFiles() =
        files.updateAll(ensure.isParsed(compiler))
            .map { it.ktFile }
            .let { dependencies -> files.updateAll(ensure.isCompiledUnsafe(dependencies)) }

    /** Recompiles all source files that are initialized. */
    fun refresh() {
        log.info("Refreshing source path")
        files.updateAll { it.forceInvalidate() }
        compileAllFiles()
    }

    /** Get parsed trees for all .kt files on source path */
    fun allFiles(): Collection<KtFile> =
        files
            .updateAll(ensure.isParsed(compiler))
            .map{it.ktFile}
}


/* fun walkModule( */
/*     module: ModuleDescriptor, */
/*     pkgName: FqName= FqName.ROOT, */
/* ): Sequence<PackageViewDescriptor> = module */
/*     .getSubPackagesOf(pkgName) { it.toString() != "META-INF" } */
/*     .asSequence() */
/*     .flatMap { sequenceOf(module.getPackage(it)) + walkModule(module, it) } */

private fun patch(sourceText: String, change: TextDocumentContentChangeEvent): String {
    val range = change.range
    val reader = BufferedReader(StringReader(sourceText))
    val writer = StringWriter()

    // Skip unchanged lines
    var line = 0

    while (line < range.start.line) {
        writer.write(reader.readLine() + '\n')
        line++
    }

    // Skip unchanged chars
    for (character in 0 until range.start.character) {
        writer.write(reader.read())
    }

    // Write replacement text
    writer.write(change.text)

    // Skip replaced text
    for (i in 0 until (range.end.line - range.start.line)) {
        reader.readLine()
    }
    if (range.start.line == range.end.line) {
        reader.skip((range.end.character - range.start.character).toLong())
    } else {
        reader.skip(range.end.character.toLong())
    }

    // Write remaining text
    while (true) {
        val next = reader.read()

        if (next == -1) break
        else writer.write(next)
    }
    return writer.toString()
}
