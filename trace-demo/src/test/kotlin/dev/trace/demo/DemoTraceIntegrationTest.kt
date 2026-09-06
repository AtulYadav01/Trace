package dev.trace.demo

import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import dev.trace.android.TraceAndroid
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import trace.core.Event
import trace.core.SessionReader

/**
 * End-to-end: DemoApp starts a TRACE session in onCreate; this test drives the
 * real demo activities through a realistic interaction sequence, then reads the
 * produced `.trace.jsonl` with trace-core's SessionReader and verifies the
 * event stream.
 *
 * Note: trace-android's internal test reset hook is not visible here, so the
 * session under test is exactly the one DemoApp created.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = DemoApp::class)
class DemoTraceIntegrationTest {

    /** The handler DemoApp installed (an opaque Thread.UncaughtExceptionHandler). */
    private lateinit var traceCrashHandler: Thread.UncaughtExceptionHandler

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<DemoApp>() // ensure onCreate ran
        assertTrue("DemoApp should have started a session", TraceAndroid.isRunning)
        traceCrashHandler = requireNotNull(Thread.getDefaultUncaughtExceptionHandler()) {
            "DemoApp installs an uncaught exception handler"
        }
    }

    private fun forceLayout(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun touch(activity: android.app.Activity, view: View) {
        forceLayout(activity.window.decorView)
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = (loc[0] + view.width / 2).coerceAtLeast(1).toFloat()
        val y = (loc[1] + view.height / 2).coerceAtLeast(1).toFloat()
        val t = android.os.SystemClock.uptimeMillis()
        val cb = activity.window.callback
        cb.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        cb.dispatchTouchEvent(MotionEvent.obtain(t, t + 1, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun `realistic session yields a complete trace with the expected ordered events`() {
        // --- Home ---
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val homeA = home.get()
        touch(homeA, homeA.findViewById(R.id.button_a))
        touch(homeA, homeA.findViewById(R.id.button_b))
        homeA.findViewById<EditText>(R.id.name_field).setText("Sam")
        homeA.findViewById<EditText>(R.id.password_field).setText("hunter2secret") // must NOT be captured

        // --- navigate to Details ---
        homeA.findViewById<Button>(R.id.go_to_details).performClick()
        home.pause().stop()
        val details = Robolectric.buildActivity(DetailsActivity::class.java).setup()
        val detailsA = details.get()
        touch(detailsA, detailsA.findViewById(R.id.details_action))

        // --- back to Home ---
        detailsA.findViewById<Button>(R.id.back_button).performClick()
        details.pause().stop().destroy()
        home.start().resume()

        // --- crash on the main thread ---
        try {
            homeA.findViewById<Button>(R.id.crash_button).performClick()
            throw AssertionError("crash button should have thrown")
        } catch (expected: IllegalStateException) {
            traceCrashHandler.uncaughtException(Thread.currentThread(), expected)
        }

        // --- read the trace with trace-core ---
        val file = TraceAndroid.currentSessionFile!!
        val events: List<Event> = SessionReader.open(file.toPath()).use { r ->
            val list = r.events().toList()
            assertTrue("crash path still closed the session", r.isComplete)
            list
        }

        events.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
        var lastTs = -1L
        events.forEach { assertTrue(it.timestampNanos >= lastTs); lastTs = it.timestampNanos }

        val types = events.map { it.type }
        assertEquals("android.app.start", types.first())
        assertEquals("android.exception", types.last())

        assertTrue(types.indexOf("android.activity.create") < types.indexOf("android.activity.resume"))
        assertTrue(types.indexOf("android.activity.resume") < types.indexOf("android.screen.enter"))
        assertTrue(types.indexOf("android.screen.enter") < types.indexOf("android.ui.click"))
        assertTrue(types.indexOf("android.ui.click") < types.lastIndexOf("android.exception"))

        val clickTargets = events.filter { it.type == "android.ui.click" }
            .mapNotNull { it.payload["viewId"]?.jsonPrimitive?.content }
        assertTrue("button_a" in clickTargets)
        assertTrue("button_b" in clickTargets)
        assertTrue("details_action" in clickTargets)

        val textChanges = events.filter { it.type == "android.ui.text_change" }
        assertTrue(textChanges.any { it.payload["viewId"]?.jsonPrimitive?.content == "name_field" })
        assertFalse(
            "password field must never appear in the trace",
            events.any { it.payload["viewId"]?.jsonPrimitive?.content == "password_field" },
        )
        textChanges.forEach { assertFalse("no text content by default", it.payload.containsKey("text")) }

        val activitiesSeen = events.filter { it.type.startsWith("android.activity.") }
            .mapNotNull { it.payload["activity"]?.jsonPrimitive?.content }.toSet()
        assertTrue("HomeActivity" in activitiesSeen)
        assertTrue("DetailsActivity" in activitiesSeen)

        val ex = events.last { it.type == "android.exception" }
        assertEquals("java.lang.IllegalStateException", ex.payload["exceptionType"]!!.jsonPrimitive.content)
        assertTrue(ex.payload["message"]!!.jsonPrimitive.content.contains("Demo crash"))
        assertEquals("true", ex.payload["fatal"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clean flow finishing via the button produces a complete session and prints it`() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val homeA = home.get()
        touch(homeA, homeA.findViewById(R.id.button_a))
        homeA.findViewById<EditText>(R.id.name_field).setText("Sam")
        homeA.findViewById<Button>(R.id.go_to_details).performClick()
        home.pause().stop()
        val details = Robolectric.buildActivity(DetailsActivity::class.java).setup()
        touch(details.get(), details.get().findViewById(R.id.details_action))
        details.get().findViewById<Button>(R.id.back_button).performClick()
        details.pause().stop().destroy()
        home.start().resume()

        val file = TraceAndroid.currentSessionFile!!
        homeA.findViewById<Button>(R.id.finish_session).performClick() // calls TraceAndroid.stop()

        val raw = String(java.nio.file.Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        println("----- SAMPLE .trace.jsonl -----")
        println(raw)
        println("----- END SAMPLE -----")

        SessionReader.open(file.toPath()).use { r ->
            val types = r.events().toList().map { it.type }
            assertTrue(r.isComplete)
            assertEquals("android.app.start", types.first())
            assertEquals("android.app.stop", types.last())
            assertTrue(types.contains("android.ui.click"))
        }
    }
}
