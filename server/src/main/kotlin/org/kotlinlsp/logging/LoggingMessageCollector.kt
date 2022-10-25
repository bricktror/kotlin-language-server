package org.kotlinlsp.logging

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import java.util.logging.Logger

class LoggingMessageCollector(
    val logger: Logger
): MessageCollector {
	override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
		logger.debug{"Kotlin compiler: [${severity}] ${message} @ ${location}"}
	}

	override fun clear() {}
	override fun hasErrors() = false
}
