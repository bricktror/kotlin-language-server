package org.kotlinlsp.codeaction

import arrow.core.Either
import org.eclipse.lsp4j.*
import org.kotlinlsp.source.CompiledFile
import org.kotlinlsp.codeaction.quickfix.ImplementAbstractMembersQuickFix
import org.kotlinlsp.codeaction.quickfix.AddMissingImportsQuickFix
import org.kotlinlsp.util.toPath
import org.kotlinlsp.index.SymbolIndex

val QUICK_FIXES = listOf(
    ImplementAbstractMembersQuickFix(),
    AddMissingImportsQuickFix()
)

fun codeActions(
    file: CompiledFile,
    index: SymbolIndex?,
    range: Range,
    context: CodeActionContext
): List<Either<Command, CodeAction>> {
    // context.only does not work when client is emacs...
    val requestedKinds = context.only ?: listOf(CodeActionKind.Refactor, CodeActionKind.QuickFix)
    return requestedKinds.map {
        when (it) {
            CodeActionKind.Refactor -> getRefactors(file, range)
            CodeActionKind.QuickFix -> getQuickFixes(file, index, range, context.diagnostics)
            else -> listOf()
        }
    }.flatten()
}

fun getRefactors(file: CompiledFile, range: Range): List<Either<Command, CodeAction>> {
    val hasSelection = (range.end.line - range.start.line) != 0 || (range.end.character - range.start.character) != 0
    return if (hasSelection) {
        listOf(
            Either.Left(
                Command("Convert Java to Kotlin", "convertJavaToKotlin", listOf(
                    file.parse.toPath().toUri().toString(),
                    range
                ))
            )
        )
    } else {
        emptyList()
    }
}

fun getQuickFixes(file: CompiledFile, index: SymbolIndex?, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>> {
    return QUICK_FIXES.flatMap {
        it.compute(file, index, range, diagnostics)
    }
}
