package org.javacs.kt.progress

import java.io.Closeable

/** A facility for emitting progress notifications. */
interface Progress : Closeable {
    /**
     * Updates the progress percentage. The
     * value should be in the range [0, 100].
     */
    fun update(percent: Int): Unit

    interface Factory {
        /**
         * Creates a new progress listener with
         * the given label. The label is intended
         * to be human-readable.
         */
        fun create(label: String): Progress
    }
}
