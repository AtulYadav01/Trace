package dev.trace.android.internal

import android.app.Activity
import android.app.Application
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import dev.trace.android.TraceAndroid
import dev.trace.android.TraceConfig
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import trace.core.Event
import trace.core.SessionReader
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InteractionTrackerTest {

    private lateinit var app: Application
    private lateinit var outDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        outDir = File(app.cacheDir, "it-${System.nanoTime()}")
        TraceAndroid.resetForTesting()
    }

    @After
    fun tearDown() = TraceAndroid.resetForTesting()

    private fun events(): List<Event> {
        val f = TraceAndroid.currentSessionFile!!
        return SessionReader.open(f.toPath()).use { r ->
            val list = r.events().toList()
            assertTrue(r.isComplete)
            list.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
            list
        }
    }

    private fun forceLayout(v: View, w: Int = 1080, h: Int = 1920) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    /** Full-screen clickable button as the content view of a resumed, laid-out activity. */
    private fun resumedActivityWithFullScreenButton(
        config: TraceConfig,
    ): org.robolectric.android.controller.ActivityController<HomeTestActivity> {
        TraceAndroid.start(app, config)
        val controller = Robolectric.buildActivity(HomeTestActivity::class.java).setup()
        val activity = controller.get()
        val button = Button(activity).apply {
            isClickable = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        activity.setContentView(button)
        // Re-run resume so the tracker wraps this window and scans this content.
        controller.pause().resume()
        forceLayout(activity.window.decorView)
        forceLayout(button)
        assertTrue("button must be laid out for hit-testing", button.width > 0 && button.height > 0)
        return controller
    }

    private fun tap(activity: Activity, x: Float, y: Float) {
        val cb = activity.window.callback
        val t = android.os.SystemClock.uptimeMillis()
        cb.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        cb.dispatchTouchEvent(MotionEvent.obtain(t, t + 1, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun tapCenterOf(activity: Activity, view: View) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        tap(activity, (loc[0] + view.width / 2).toFloat(), (loc[1] + view.height / 2).toFloat())
    }

    private fun contentButton(controller: org.robolectric.android.controller.ActivityController<HomeTestActivity>): Button =
        (controller.get().findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0) as Button

    // ---- hit-testing ----

    @Test
    fun `deepestClickable returns the deepest clickable view under the point`() {
        val tracker = InteractionTracker(dummySession(), TraceConfig())
        val activity = Robolectric.buildActivity(HomeTestActivity::class.java).setup().get()

        val outer = LinearLayout(activity)
        val clickableRow = FrameLayout(activity).apply { isClickable = true }
        val innerButton = Button(activity).apply { isClickable = true }
        clickableRow.addView(innerButton)
        outer.addView(clickableRow)
        activity.setContentView(outer)
        forceLayout(activity.window.decorView)

        // Point inside the button -> button (deepest), not the clickable row.
        val loc = IntArray(2)
        innerButton.getLocationOnScreen(loc)
        val hit = tracker.deepestClickable(activity.window.decorView, loc[0] + 1, loc[1] + 1)
        assertEquals(innerButton, hit)
    }

    @Test
    fun `deepestClickable returns null when nothing clickable is under the point`() {
        val tracker = InteractionTracker(dummySession(), TraceConfig())
        val activity = Robolectric.buildActivity(HomeTestActivity::class.java).setup().get()
        val plain = View(activity)
        activity.setContentView(plain)
        forceLayout(activity.window.decorView)
        assertNull(tracker.deepestClickable(activity.window.decorView, 5, 5))
    }

    // ---- clicks via the wrapped window callback ----

    @Test
    fun `tap on a full-screen button records a click`() {
        val controller = resumedActivityWithFullScreenButton(TraceConfig(outputDirectory = outDir))
        tapCenterOf(controller.get(), contentButton(controller))
        TraceAndroid.stop()

        val clicks = events().filter { it.type == "android.ui.click" }
        assertEquals(1, clicks.size)
        assertEquals("android.widget.Button", clicks[0].payload["viewClass"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multiple rapid taps produce one click each with contiguous seq`() {
        val controller = resumedActivityWithFullScreenButton(TraceConfig(outputDirectory = outDir))
        repeat(50) { tapCenterOf(controller.get(), contentButton(controller)) }
        TraceAndroid.stop()

        val all = events()
        assertEquals(50, all.count { it.type == "android.ui.click" })
        all.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
    }

    @Test
    fun `click then immediate pause keeps both events and contiguous seq`() {
        val controller = resumedActivityWithFullScreenButton(TraceConfig(outputDirectory = outDir))
        tapCenterOf(controller.get(), contentButton(controller))
        controller.pause()
        TraceAndroid.stop()

        val all = events()
        assertTrue(all.any { it.type == "android.ui.click" })
        assertTrue(all.any { it.type == "android.activity.pause" })
        all.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
    }

    @Test
    fun `captureInteractions=false records no clicks`() {
        val controller = resumedActivityWithFullScreenButton(
            TraceConfig(outputDirectory = outDir, captureInteractions = false),
        )
        tapCenterOf(controller.get(), contentButton(controller))
        TraceAndroid.stop()

        assertFalse(events().any { it.type == "android.ui.click" })
    }

    // ---- text ----

    @Test
    fun `text change on a normal field carries only length by default`() {
        TraceAndroid.start(app, TraceConfig(outputDirectory = outDir))
        val controller = Robolectric.buildActivity(HomeTestActivity::class.java)
        val activity = controller.setup().get()
        val field = EditText(activity)
        activity.setContentView(field)
        controller.pause().resume()

        field.setText("Alfredo")
        TraceAndroid.stop()

        val tc = events().last { it.type == "android.ui.text_change" }
        assertEquals(7, tc.payload["length"]!!.jsonPrimitive.int)
        assertNull(tc.payload["text"])
    }

    @Test
    fun `text change carries text when captureText is enabled`() {
        TraceAndroid.start(app, TraceConfig(outputDirectory = outDir, captureText = true))
        val controller = Robolectric.buildActivity(HomeTestActivity::class.java)
        val activity = controller.setup().get()
        val field = EditText(activity)
        activity.setContentView(field)
        controller.pause().resume()

        field.setText("hello")
        TraceAndroid.stop()

        val tc = events().last { it.type == "android.ui.text_change" }
        assertEquals("hello", tc.payload["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `password field by inputType produces no text_change`() {
        assertNoTextChangeFor { field ->
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }

    @Test
    fun `password field by transformation method produces no text_change`() {
        assertNoTextChangeFor { field ->
            field.transformationMethod = PasswordTransformationMethod.getInstance()
        }
    }

    @Test
    fun `password field by hint keyword produces no text_change`() {
        assertNoTextChangeFor { field ->
            field.hint = "Enter your Password"
        }
    }

    private fun assertNoTextChangeFor(configureAsPassword: (EditText) -> Unit) {
        TraceAndroid.start(app, TraceConfig(outputDirectory = outDir, captureText = true))
        val controller = Robolectric.buildActivity(HomeTestActivity::class.java)
        val activity = controller.setup().get()
        val field = EditText(activity)
        configureAsPassword(field)
        activity.setContentView(field)
        controller.pause().resume()

        field.setText("s3cr3t-value")
        TraceAndroid.stop()

        assertFalse(
            "password field must produce no text_change events",
            events().any { it.type == "android.ui.text_change" },
        )
    }

    private fun dummySession(): AndroidTraceSession =
        AndroidTraceSession.start(File(app.cacheDir, "dummy-${System.nanoTime()}"), TraceConfig())
}
