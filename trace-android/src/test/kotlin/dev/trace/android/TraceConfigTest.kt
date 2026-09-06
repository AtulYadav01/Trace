package dev.trace.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceConfigTest {

    @Test
    fun `defaults are privacy safe and low overhead`() {
        val c = TraceConfig()
        assertNull("no output dir by default (resolved from app files dir)", c.outputDirectory)
        assertTrue(c.captureLifecycle)
        assertTrue(c.captureInteractions)
        assertFalse("text capture must default OFF", c.captureText)
        assertTrue(c.captureExceptions)
    }

    @Test
    fun `flags are independently overridable`() {
        val c = TraceConfig(captureInteractions = false, captureExceptions = false)
        assertTrue(c.captureLifecycle)
        assertFalse(c.captureInteractions)
        assertFalse(c.captureText)
        assertFalse(c.captureExceptions)
    }
}
