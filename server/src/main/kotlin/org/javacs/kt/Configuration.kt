package org.javacs.kt

public data class Configuration(
    /** Whether external classes should be automatically converted to Kotlin. */
    var autoConvertToKotlin: Boolean = false,

    /** Which JVM target the Kotlin compiler uses. See Compiler.jvmTargetFrom for possible values. */
    var jvmTarget: String = "default",
    /** Whether code completion should return VSCode-style snippets. */
    var snippets: Boolean = true,
    /** The time interval between subsequent lints in ms. */
    var lintDebounceTime: Long = 250L,
    /** Whether an index of global symbols should be built in the background. */
    var indexEnabled: Boolean = true,
)
