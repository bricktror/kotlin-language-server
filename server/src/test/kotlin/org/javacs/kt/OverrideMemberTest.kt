package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import com.google.gson.Gson
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams

class OverrideMemberTest : SingleFileTestFixture("overridemember", "OverrideMembers.kt") {

    val root = testResourcesRoot().resolve(workspaceRoot)
    val fileUri = root.resolve(file).toUri().toString()

    @Test
    fun `should show all overrides for class`() {
        val result = languageServer.getProtocolExtensionService().overrideMember(TextDocumentPositionParams(TextDocumentIdentifier(fileUri), position(9, 8))).get()

        val titles = result.map { it.title }
        val edits = result.flatMap { it.edit.changes[fileUri]!! }
        val newTexts = edits.map { it.newText }
        val ranges = edits.map { it.range }

        assertThat(titles, containsInAnyOrder("override val text: String = TODO(\"SET VALUE\")",
                                              "override fun print() { }",
                                              "override fun equals(other: Any?): Boolean { }",
                                              "override fun hashCode(): Int { }",
                                              "override fun toString(): String { }"))

        val padding = System.lineSeparator() + System.lineSeparator() + "    "
        assertThat(newTexts, containsInAnyOrder(padding + "override val text: String = TODO(\"SET VALUE\")",
                                                padding + "override fun print() { }",
                                                padding + "override fun equals(other: Any?): Boolean { }",
                                                padding + "override fun hashCode(): Int { }",
                                                padding + "override fun toString(): String { }"))


        assertThat(ranges, everyItem(equalTo(range(9, 31, 9, 31))))
    }

    @Test
    fun `should show one less override for class where one member is already implemented`() {
        val result = languageServer.getProtocolExtensionService().overrideMember(TextDocumentPositionParams(TextDocumentIdentifier(fileUri), position(11, 8))).get()

        val titles = result.map { it.title }
        val edits = result.flatMap { it.edit.changes[fileUri]!! }
        val newTexts = edits.map { it.newText }
        val ranges = edits.map { it.range }

        assertThat(titles, containsInAnyOrder("override fun print() { }",
                                              "override fun equals(other: Any?): Boolean { }",
                                              "override fun hashCode(): Int { }",
                                              "override fun toString(): String { }"))

        val padding = System.lineSeparator() + System.lineSeparator() + "    "
        assertThat(newTexts, containsInAnyOrder(padding + "override fun print() { }",
                                                padding + "override fun equals(other: Any?): Boolean { }",
                                                padding + "override fun hashCode(): Int { }",
                                                padding + "override fun toString(): String { }"))

        assertThat(ranges, everyItem(equalTo(range(12, 56, 12, 56))))
    }

    @Test
    fun `should show NO overrides for class where all other alternatives are already implemented`() {
        val result = languageServer.getProtocolExtensionService().overrideMember(TextDocumentPositionParams(TextDocumentIdentifier(fileUri), position(15, 8))).get()

        assertThat(result, hasSize(0))
    }

    @Test
    fun `should find method in open class`() {
        val result = languageServer.getProtocolExtensionService().overrideMember(TextDocumentPositionParams(TextDocumentIdentifier(fileUri), position(37, 8))).get()

        val titles = result.map { it.title }
        val edits = result.flatMap { it.edit.changes[fileUri]!! }
        val newTexts = edits.map { it.newText }
        val ranges = edits.map { it.range }

        assertThat(titles, containsInAnyOrder("override fun numOpenDoorsWithName(input: String): Int { }",
                                              "override fun equals(other: Any?): Boolean { }",
                                              "override fun hashCode(): Int { }",
                                              "override fun toString(): String { }"))

        val padding = System.lineSeparator() + System.lineSeparator() + "    "
        assertThat(newTexts, containsInAnyOrder(padding + "override fun numOpenDoorsWithName(input: String): Int { }",
                                                padding + "override fun equals(other: Any?): Boolean { }",
                                                padding + "override fun hashCode(): Int { }",
                                                padding + "override fun toString(): String { }"))

        assertThat(ranges, everyItem(equalTo(range(37, 25, 37, 25))))
    }

    @Test
    fun `should find members in jdk object`() {
        val result = languageServer
            .getProtocolExtensionService()
            .overrideMember(TextDocumentPositionParams(TextDocumentIdentifier(fileUri), position(39, 9)))
            .get()

        val titles = result.map { it.title }
        val edits = result.flatMap { it.edit.changes[fileUri]!! }
        val newTexts = edits.map { it.newText }
        val ranges = edits.map { it.range }

        val expected = listOf(
                "override fun alive(): Boolean { }",
                "override fun asyncGetStackTrace(): (Array<(StackTraceElement..StackTraceElement?)>..Array<out (StackTraceElement..StackTraceElement?)>) { }",
                "override fun clearReferences() { }",
                "override fun clone(): Any { }",
                "override fun countStackFrames(): Int { }",
                "override fun daemon(on: Boolean) { }",
                "override fun dispatchUncaughtException(e: Throwable) { }",
                "override fun equals(other: Any?): Boolean { }",
                "override fun getAndClearInterrupt(): Boolean { }",
                "override fun getContextClassLoader(): ClassLoader { }",
                "override fun getContinuation(): Continuation { }",
                "override fun getId(): Long { }",
                "override fun getStackTrace(): (Array<(StackTraceElement..StackTraceElement?)>..Array<out (StackTraceElement..StackTraceElement?)>) { }",
                "override fun getState(): State { }",
                "override fun getUncaughtExceptionHandler(): UncaughtExceptionHandler { }",
                "override fun hashCode(): Int { }",
                "override fun headStackableScopes(): StackableScope { }",
                "override fun inheritExtentLocalBindings(container: ThreadContainer) { }",
                "override fun interrupt() { }",
                "override fun isInterrupted(): Boolean { }",
                "override fun isTerminated(): Boolean { }",
                "override fun priority(newPriority: Int) { }",
                "override fun run() { }",
                "override fun setContextClassLoader(cl: ClassLoader) { }",
                "override fun setContinuation(cont: Continuation) { }",
                "override fun setCurrentThread(p0: Thread) { }",
                "override fun setThreadContainer(container: ThreadContainer) { }",
                "override fun setUncaughtExceptionHandler(ueh: UncaughtExceptionHandler) { }",
                "override fun start() { }",
                "override fun start(container: ThreadContainer) { }",
                "override fun threadContainer(): ThreadContainer { }",
                "override fun threadState(): State { }",
                "override fun toString(): String { }",
                "override fun uncaughtExceptionHandler(ueh: UncaughtExceptionHandler) { }",
        )

        assertThat(titles, containsInAnyOrder(expected.map(::equalTo).toList()))

        val padding = System.lineSeparator() + System.lineSeparator() + "    "
        assertThat(newTexts, containsInAnyOrder(expected.map{padding+it}.map(::equalTo).toList()))

        assertThat(ranges, everyItem(equalTo(range(39, 25, 39, 25))))
    }
}
