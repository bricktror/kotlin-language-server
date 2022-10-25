package org.javacs.kt

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Location
import org.javacs.kt.range
import org.javacs.kt.findReferencesToDeclarationInFile
import org.javacs.kt.source.CompiledFile
import org.javacs.kt.util.findParent
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

fun documentHighlightsAt(file: CompiledFile, cursor: Int): List<DocumentHighlight> {
    val (declaration, declarationLocation) = file.findDeclaration(cursor)
        ?: return emptyList()
    val references = findReferencesToDeclarationInFile(declaration, file)

    return if (declaration.isInFile(file.parse)) {
        listOf(DocumentHighlight(declarationLocation.range, DocumentHighlightKind.Text))
    } else {
        emptyList()
    } + references.map { DocumentHighlight(it, DocumentHighlightKind.Text) }
}

private fun KtNamedDeclaration.isInFile(file: KtFile) = this.containingFile == file
