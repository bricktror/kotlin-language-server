package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import org.javacs.kt.util.Debouncer

class DebouncerTest {
    val debounce = Debouncer(Duration.ofSeconds(1))
    var counter = 0

    @Test fun callQuickly() {
        for (i in 1..10) {
            debounce.schedule {
                counter++
            }
        }

        debounce.waitForPendingTask()

        assertThat(counter, equalTo(1))
    }
}
