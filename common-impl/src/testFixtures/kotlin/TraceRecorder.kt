package com.github.mayblock.easylib.impl.util

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TraceRecorder {

    private val traces = mutableListOf<String>()

    fun record(trace: String) {
        traces += trace
    }

    fun assertEquals(vararg expected: String) {
        assertEquals(expected.toList(), traces)
    }

    fun assertNotEquals(vararg expected: String) {
        assertNotEquals(expected.toList(), traces)
    }
}