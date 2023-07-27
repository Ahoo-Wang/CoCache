package me.ahoo.cache.util

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ProcessIdTest {

    @Test
    fun getCurrent() {
        assertThat(ProcessId.current, greaterThan(0))
    }
}
