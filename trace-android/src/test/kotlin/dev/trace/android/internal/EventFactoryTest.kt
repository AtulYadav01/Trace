package dev.trace.android.internal

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EventFactory methods that take only primitives are pure JVM and need no
 * Android. Every assertion checks the actual generated payload.
 */
class EventFactoryTest {

    @Test
    fun `activity create carries activity, instanceId and restoredState`() {
        val e = EventFactory.activity(ActivityPhase.CREATE, "MainActivity", "MainActivity@1a", restoredState = true)
        assertEquals("android.activity.create", e.type)
        assertEquals("MainActivity", e.payload["activity"]!!.jsonPrimitive.content)
        assertEquals("MainActivity@1a", e.payload["instanceId"]!!.jsonPrimitive.content)
        assertTrue(e.payload["restoredState"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test
    fun `activity resume omits restoredState`() {
        val e = EventFactory.activity(ActivityPhase.RESUME, "MainActivity", "MainActivity@1a")
        assertEquals("android.activity.resume", e.type)
        assertNull(e.payload["restoredState"])
    }

    @Test
    fun `all activity phases map to stable type strings`() {
        assertEquals("android.activity.create", ActivityPhase.CREATE.type)
        assertEquals("android.activity.start", ActivityPhase.START.type)
        assertEquals("android.activity.resume", ActivityPhase.RESUME.type)
        assertEquals("android.activity.pause", ActivityPhase.PAUSE.type)
        assertEquals("android.activity.stop", ActivityPhase.STOP.type)
        assertEquals("android.activity.destroy", ActivityPhase.DESTROY.type)
    }

    @Test
    fun `screen enter`() {
        val e = EventFactory.screenEnter("Home", activity = "HomeActivity")
        assertEquals("android.screen.enter", e.type)
        assertEquals("Home", e.payload["screen"]!!.jsonPrimitive.content)
        assertEquals("HomeActivity", e.payload["activity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `click without text carries only id and class`() {
        val e = EventFactory.click(activity = "HomeActivity", viewId = "submit_button", viewClass = "android.widget.Button")
        assertEquals("android.ui.click", e.type)
        assertEquals("submit_button", e.payload["viewId"]!!.jsonPrimitive.content)
        assertEquals("android.widget.Button", e.payload["viewClass"]!!.jsonPrimitive.content)
        assertNull("no text when captureText off", e.payload["text"])
        assertNull("no contentDescription when captureText off", e.payload["contentDescription"])
    }

    @Test
    fun `click with text includes text and contentDescription`() {
        val e = EventFactory.click(
            activity = "HomeActivity",
            viewId = "submit_button",
            viewClass = "android.widget.Button",
            text = "Submit",
            contentDescription = "Submit the form",
        )
        assertEquals("Submit", e.payload["text"]!!.jsonPrimitive.content)
        assertEquals("Submit the form", e.payload["contentDescription"]!!.jsonPrimitive.content)
    }

    @Test
    fun `text change without captureText carries only length`() {
        val e = EventFactory.textChange(activity = "HomeActivity", viewId = "name_field", length = 7)
        assertEquals("android.ui.text_change", e.type)
        assertEquals(7, e.payload["length"]!!.jsonPrimitive.int)
        assertNull(e.payload["text"])
    }

    @Test
    fun `text change with captureText carries text`() {
        val e = EventFactory.textChange(activity = "HomeActivity", viewId = "name_field", length = 7, text = "Alfredo")
        assertEquals("Alfredo", e.payload["text"]!!.jsonPrimitive.content)
        assertEquals(7, e.payload["length"]!!.jsonPrimitive.int)
    }

    @Test
    fun `scroll`() {
        val e = EventFactory.scroll(activity = "HomeActivity", viewId = "list", dx = 0, dy = 240)
        assertEquals("android.ui.scroll", e.type)
        assertEquals(0, e.payload["dx"]!!.jsonPrimitive.int)
        assertEquals(240, e.payload["dy"]!!.jsonPrimitive.int)
    }

    @Test
    fun `exception carries type, message, thread, fatal and truncated stack`() {
        val boom = IllegalStateException("nope")
        val e = EventFactory.exception(boom, threadName = "main", fatal = true)
        assertEquals("android.exception", e.type)
        assertEquals("java.lang.IllegalStateException", e.payload["exceptionType"]!!.jsonPrimitive.content)
        assertEquals("nope", e.payload["message"]!!.jsonPrimitive.content)
        assertEquals("main", e.payload["thread"]!!.jsonPrimitive.content)
        assertEquals(true, e.payload["fatal"]!!.jsonPrimitive.booleanOrNull)
        val stack = e.payload["stackTrace"]!!.jsonPrimitive.content
        assertTrue(stack.contains("IllegalStateException"))
        assertTrue("stack must be bounded", stack.length <= 8_100)
    }

    @Test
    fun `exception with null message omits message field`() {
        val e = EventFactory.exception(RuntimeException(), threadName = "worker-1", fatal = false)
        assertNull(e.payload["message"])
        assertEquals(false, e.payload["fatal"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `app stop is an empty payload`() {
        val e = EventFactory.appStop()
        assertEquals("android.app.stop", e.type)
        assertTrue(e.payload.isEmpty())
    }
}
