package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbolParams

class WorkspaceSymbolsTest : SingleFileTestFixture("symbols", "DocumentSymbols.kt") {
    @Test fun `find symbols in OtherFileSymbols`() {
        val found = languageServer.workspaceService.symbol(WorkspaceSymbolParams("")).get().right
        val byKind = found.groupBy({ it.kind }, { it.name })
        val all = found.map { it.name }.toList()

        assertThat(byKind[SymbolKind.Class], hasItem("OtherFileSymbols"))
        assertThat(byKind[SymbolKind.Constructor], hasItem("OtherFileSymbols"))
        assertThat(byKind[SymbolKind.Property], hasItem("otherFileProperty"))
        assertThat(byKind[SymbolKind.Function], hasItem("otherFileFunction"))
        assertThat(all, not(hasItem("aFunctionArg")))
        assertThat(all, not(hasItem("aConstructorArg")))
        assertThat(all, not(hasItem("otherFileLocalVariable")))
    }
}
