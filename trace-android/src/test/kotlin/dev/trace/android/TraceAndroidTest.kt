package dev.trace.android

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import trace.core.SessionReader
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraceAndroidTest {

    private lateinit var app: Application
    private lateinit var outDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        outDir = File(app.cacheDir, "trace-test-${System.nanoTime()}")
        TraceAndroid.resetForTesting()
    }

    @After
    fun tearDown() {
        TraceAndroid.resetForTesting()
    }

    private fun config() = TraceConfig(outputDirectory = outDir)

    private fun readCurrent(): List<trace.core.Event> {
        val file = TraceAndroid.currentSessionFile!!
        return SessionReader.open(file.toPath()).use { r ->
            val list = r.events().toList()
            assertTrue("session complete", r.isComplete)
            list
        }
    }

    @Test
    fun `start then stop produces a readable session beginning with app_start`() {
        assertTrue(TraceAndroid.start(app, config()))
        assertTrue(TraceAndroid.isRunning)
        assertNotNull(TraceAndroid.currentSessionFile)

        TraceAndroid.recordScreen("Home")
        TraceAndroid.recordClick("submit_button")
        TraceAndroid.stop()

        assertFalse(TraceAndroid.isRunning)
        val events = readCurrent()
        events.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
        assertEquals("android.app.start", events.first().type)
        assertEquals("android.app.stop", events.last().type)
        assertTrue(events.any { it.type == "android.screen.enter" && it.payload["screen"]?.jsonPrimitive?.content == "Home" })
        assertTrue(events.any { it.type == "android.ui.click" && it.payload["viewId"]?.jsonPrimitive?.content == "submit_button" })
    }

    @Test
    fun `app_start payload has package and taxonomy version`() {
        TraceAndroid.start(app, config())
        TraceAndroid.stop()
        val start = readCurrent().first { it.type == "android.app.start" }
        assertEquals(app.packageName, start.payload["package"]!!.jsonPrimitive.content)
        assertEquals("1", start.payload["taxonomyVersion"]!!.jsonPrimitive.content)
        assertEquals("0.2.0", start.payload["traceAndroidVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `double start returns false and keeps the first session`() {
        assertTrue(TraceAndroid.start(app, config()))
        val first = TraceAndroid.currentSessionFile
        assertFalse(TraceAndroid.start(app, config()))
        assertEquals(first, TraceAndroid.currentSessionFile)
        TraceAndroid.stop()
    }

    @Test
    fun `stop before start is a no-op`() {
        TraceAndroid.stop()
        assertFalse(TraceAndroid.isRunning)
        assertNull(TraceAndroid.currentSessionFile)
    }

    @Test
    fun `double stop is a no-op`() {
        TraceAndroid.start(app, config())
        TraceAndroid.stop()
        TraceAndroid.stop()
        assertEquals(1, readCurrent().count { it.type == "android.app.stop" })
    }

    @Test
    fun `recordScreen and recordClick are ignored when not running`() {
        TraceAndroid.recordScreen("Ghost")
        TraceAndroid.recordClick("ghost_button")
        assertNull(TraceAndroid.currentSessionFile)
    }

    @Test
    fun `restart after stop creates a new session file`() {
        TraceAndroid.start(app, config())
        val first = TraceAndroid.currentSessionFile
        TraceAndroid.stop()

        assertTrue(TraceAndroid.start(app, TraceConfig(outputDirectory = outDir)))
        val second = TraceAndroid.currentSessionFile
        TraceAndroid.stop()

        assertNotNull(first); assertNotNull(second)
        assertFalse(first == second)
    }

    @Test
    fun `concurrent start and stop never throw and end consistent`() {
        val threads = (0 until 12).map { i ->
            Thread {
                repeat(20) {
                    if (i % 2 == 0) TraceAndroid.start(app, TraceConfig(outputDirectory = outDir))
                    else TraceAndroid.stop()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        TraceAndroid.stop()
        assertFalse(TraceAndroid.isRunning)
        // whatever the last session was, it must be a complete, readable file
        TraceAndroid.currentSessionFile?.let { f ->
            SessionReader.open(f.toPath()).use { r ->
                val events = r.events().toList()
                events.forEachIndexed { idx, e -> assertEquals(idx.toLong(), e.seq) }
            }
        }
    }
}
