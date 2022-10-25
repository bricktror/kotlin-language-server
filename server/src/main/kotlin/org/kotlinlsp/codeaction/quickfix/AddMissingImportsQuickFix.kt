package org.kotlinlsp.codeaction.quickfix

import arrow.core.Either
import org.eclipse.lsp4j.*
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.source.CompiledFile
import org.kotlinlsp.index.SymbolIndex
import org.kotlinlsp.index.Symbol
import org.kotlinlsp.offset
import org.kotlinlsp.util.toPath
import org.kotlinlsp.codeaction.quickfix.diagnosticMatch
import org.kotlinlsp.getImportTextEditEntry

class AddMissingImportsQuickFix: QuickFix {
    override fun compute(file: CompiledFile, index: SymbolIndex?, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>> {
        val uri = file.parse.toPath().toUri().toString()
        val unresolvedReferences = getUnresolvedReferencesFromDiagnostics(diagnostics)

        return unresolvedReferences.flatMap { diagnostic ->
            val diagnosticRange = diagnostic.range
            val startCursor = offset(file.content, diagnosticRange.start)
            val endCursor = offset(file.content, diagnosticRange.end)
            val symbolName = file.content.substring(startCursor, endCursor)

            getImportAlternatives(symbolName, file.parse, index).map { (importStr, edit) ->
                val codeAction = CodeAction()
                codeAction.title = "Import ${importStr}"
                codeAction.kind = CodeActionKind.QuickFix
                codeAction.diagnostics = listOf(diagnostic)
                codeAction.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))

                Either.Right(codeAction)
            }
        }
    }

    private fun getUnresolvedReferencesFromDiagnostics(diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.filter {
            "UNRESOLVED_REFERENCE" == it.code.left.trim()
        }

    private fun getImportAlternatives(symbolName: String, file: KtFile, index: SymbolIndex?): List<Pair<String, TextEdit>> {
        return index
            ?.query(symbolName, exact = true)
            ?.filter {
                it.kind != Symbol.Kind.MODULE &&
                // TODO: Visibility checker should be less liberal
                (it.visibility == Symbol.Visibility.PUBLIC
                 || it.visibility == Symbol.Visibility.PROTECTED
                 || it.visibility == Symbol.Visibility.INTERNAL)
            }
            ?.map { Pair(it.fqName.toString(), getImportTextEditEntry(file, it.fqName)) }
            ?: listOf()
    }
}
