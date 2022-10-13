package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import java.util.concurrent.CancellationException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentIdentifier

class LintTest : SingleFileTestFixture("lint", "LintErrors.kt") {
    @Test fun `report error on open`() {
        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        assertThat(diagnostics, not(empty<Diagnostic>()))
    }

    @Test fun `only lint once for many edits in a short period`() {
        var text = "1"
        for (i in 1..10) {
            val newText = text + "1"

            replace(file, 3, 16, text, newText)
            text = newText
        }

        languageServer.textDocumentService.debounceLint.waitForPendingTask()

        assertThat(diagnostics, not(empty<Diagnostic>()))
        assertThat(languageServer.textDocumentService.lintCount, lessThan(5))
    }

    @Test fun `linting should not be dropped if another linting is running`() {
        var callbackCount = 0
        languageServer.textDocumentService.debounceLint.waitForPendingTask()
        languageServer.textDocumentService.lintRecompilationCallback = {
            if (callbackCount++ == 0) {
                diagnostics.clear()
                replace(file, 3, 9, "return 11", "")
                languageServer.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri(file).toString()))).get()
            }
        }
        replace(file, 3, 16, "", "1")

        while (callbackCount < 2) {
            try {
                languageServer.textDocumentService.debounceLint.waitForPendingTask()
            } catch (ex: CancellationException) {}
        }

        languageServer.textDocumentService.debounceLint.waitForPendingTask()
        assertThat(diagnostics, empty<Diagnostic>())
        languageServer.textDocumentService.lintRecompilationCallback = {}
    }
}
