package dev.trace.android.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.trace.android.TraceAndroid
import dev.trace.android.TraceConfig
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import trace.core.SessionReader
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LifecycleTrackerTest {

    private lateinit var app: Application
    private lateinit var outDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        outDir = File(app.cacheDir, "lc-${System.nanoTime()}")
        TraceAndroid.resetForTesting()
    }

    @After
    fun tearDown() = TraceAndroid.resetForTesting()

    private fun start(config: TraceConfig = TraceConfig(outputDirectory = outDir)) =
        TraceAndroid.start(app, config)

    private fun readTypes(): List<String> {
        val f = TraceAndroid.currentSessionFile!!
        return SessionReader.open(f.toPath()).use { r ->
            val list = r.events().toList()
            assertTrue(r.isComplete)
            list.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
            list.map { it.type }
        }
    }

    @Test
    fun `full activity lifecycle maps to ordered events with screen enter on resume`() {
        start()
        val c = Robolectric.buildActivity(HomeTestActivity::class.java)
        c.create().start().resume()
        c.pause().stop().destroy()
        TraceAndroid.stop()

        val types = readTypes()
        val activityEvents = types.filter { it.startsWith("android.activity.") || it == "android.screen.enter" }
        assertEquals(
            listOf(
                "android.activity.create",
                "android.activity.start",
                "android.activity.resume",
                "android.screen.enter",
                "android.activity.pause",
                "android.activity.stop",
                "android.activity.destroy",
            ),
            activityEvents,
        )
        assertEquals("android.app.start", types.first())
        assertEquals("android.app.stop", types.last())
    }

    @Test
    fun `create carries restoredState false and screen enter names the activity`() {
        start()
        Robolectric.buildActivity(HomeTestActivity::class.java).create().start().resume()
        TraceAndroid.stop()

        val f = TraceAndroid.currentSessionFile!!
        SessionReader.open(f.toPath()).use { r ->
            val events = r.events().toList()
            val create = events.first { it.type == "android.activity.create" }
            assertEquals("false", create.payload["restoredState"]!!.jsonPrimitive.content)
            assertEquals("HomeTestActivity", create.payload["activity"]!!.jsonPrimitive.content)
            val screen = events.first { it.type == "android.screen.enter" }
            assertEquals("HomeTestActivity", screen.payload["screen"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `captureLifecycle=false records no activity events`() {
        start(TraceConfig(outputDirectory = outDir, captureLifecycle = false))
        Robolectric.buildActivity(HomeTestActivity::class.java).create().start().resume().pause().stop().destroy()
        TraceAndroid.stop()

        val types = readTypes()
        assertFalse(types.any { it.startsWith("android.activity.") })
        assertFalse(types.any { it == "android.screen.enter" })
        assertEquals(listOf("android.app.start", "android.app.stop"), types)
    }

    @Test
    fun `rapid activity churn keeps sequence contiguous and loses nothing`() {
        start()
        repeat(60) {
            Robolectric.buildActivity(HomeTestActivity::class.java)
                .create().start().resume().pause().stop().destroy()
        }
        TraceAndroid.stop()

        val f = TraceAndroid.currentSessionFile!!
        SessionReader.open(f.toPath()).use { r ->
            val events = r.events().toList()
            events.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
            assertTrue(r.isComplete)
            // 7 lifecycle events (incl screen.enter) per iteration
            assertEquals(60, events.count { it.type == "android.activity.create" })
            assertEquals(60, events.count { it.type == "android.activity.destroy" })
            assertEquals(60, events.count { it.type == "android.screen.enter" })
        }
    }

    @Test
    fun `navigating between two activities interleaves lifecycle correctly`() {
        start()
        val home = Robolectric.buildActivity(HomeTestActivity::class.java)
        home.create().start().resume()
        // navigate: new activity resumes, previous pauses then stops
        val details = Robolectric.buildActivity(DetailsTestActivity::class.java)
        details.create().start().resume()
        home.pause().stop()
        // back: details pause/stop/destroy, home restart/resume
        details.pause()
        home.start().resume()
        details.stop().destroy()
        TraceAndroid.stop()

        val f = TraceAndroid.currentSessionFile!!
        SessionReader.open(f.toPath()).use { r ->
            val events = r.events().toList()
            events.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
            val homeResumes = events.count {
                it.type == "android.activity.resume" && it.payload["activity"]?.jsonPrimitive?.content == "HomeTestActivity"
            }
            val detailsCreate = events.count {
                it.type == "android.activity.create" && it.payload["activity"]?.jsonPrimitive?.content == "DetailsTestActivity"
            }
            assertEquals(2, homeResumes)
            assertEquals(1, detailsCreate)
        }
    }
}
