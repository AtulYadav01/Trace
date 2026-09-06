package dev.trace.android.internal

import dev.trace.android.TraceConfig
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import trace.core.SessionReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Plain-JVM: ExceptionTracker + a real trace-core SessionRecorder (via
 * AndroidTraceSession). The "previous" handler is a capturing fake so the test
 * JVM is never actually killed.
 */
class ExceptionTrackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null
    private val delegated = AtomicReference<Throwable?>(null)
    private val fakePrevious = Thread.UncaughtExceptionHandler { _, t -> delegated.set(t) }

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakePrevious)
        delegated.set(null)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private fun session() = AndroidTraceSession.start(tmp.newFolder(), TraceConfig())

    @Test
    fun `install captures the handler that was in place`() {
        val tracker = ExceptionTracker(session())
        tracker.install()
        assertSame(fakePrevious, tracker.previousHandler())
        assertSame(tracker, Thread.getDefaultUncaughtExceptionHandler())
        tracker.uninstall()
        assertSame(fakePrevious, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `crash is not swallowed - previous handler is always invoked`() {
        val s = session()
        val tracker = ExceptionTracker(s)
        tracker.install()
        val boom = IllegalStateException("kaboom")

        tracker.uncaughtException(Thread.currentThread(), boom)

        assertSame("previous handler must receive the original throwable", boom, delegated.get())
    }

    @Test
    fun `crash records exactly one android_exception then closes a complete session`() {
        val s = session()
        val tracker = ExceptionTracker(s)
        tracker.install()
        s.record(TraceEvent("android.ui.click", kotlinx.serialization.json.JsonObject(emptyMap())))

        tracker.uncaughtException(Thread("worker-9"), RuntimeException("late"))

        SessionReader.open(s.file.toPath()).use { r ->
            val events = r.events().toList()
            events.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
            assertTrue("crash path still closes the session", r.isComplete)
            val ex = events.single { it.type == "android.exception" }
            assertEquals("java.lang.RuntimeException", ex.payload["exceptionType"]!!.jsonPrimitive.content)
            assertEquals("worker-9", ex.payload["thread"]!!.jsonPrimitive.content)
            assertEquals("true", ex.payload["fatal"]!!.jsonPrimitive.content)
            assertTrue(ex.payload["stackTrace"]!!.jsonPrimitive.content.contains("RuntimeException"))
        }
    }

    @Test
    fun `crash while many events are queued keeps the file valid and seq contiguous`() {
        val s = session()
        val tracker = ExceptionTracker(s)
        tracker.install()
        repeat(4_000) { s.record(TraceEvent("android.ui.scroll", kotlinx.serialization.json.JsonObject(emptyMap()))) }

        tracker.uncaughtException(Thread.currentThread(), IllegalArgumentException("mid-flight"))

        SessionReader.open(s.file.toPath()).use { r ->
            val events = r.events().toList()
            events.forEachIndexed { i, e -> assertEquals(i.toLong(), e.seq) }
            var lastTs = -1L
            events.forEach { assertTrue(it.timestampNanos >= lastTs); lastTs = it.timestampNanos }
            assertTrue(r.isComplete)
            assertEquals(1, events.count { it.type == "android.exception" })
        }
    }

    @Test
    fun `crash immediately after an interaction event orders exception after it`() {
        val s = session()
        val tracker = ExceptionTracker(s)
        tracker.install()
        s.record(EventFactory.click(activity = "HomeActivity", viewId = "crash_button", viewClass = "android.widget.Button"))

        tracker.uncaughtException(Thread.currentThread(), IllegalStateException("from crash button"))

        SessionReader.open(s.file.toPath()).use { r ->
            val types = r.events().toList().map { it.type }
            val clickIdx = types.indexOf("android.ui.click")
            val exIdx = types.indexOf("android.exception")
            assertTrue(clickIdx >= 0 && exIdx > clickIdx)
        }
    }

    @Test
    fun `uninstall does not clobber a handler installed after us`() {
        val tracker = ExceptionTracker(session())
        tracker.install()
        val laterHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(laterHandler)

        tracker.uninstall()

        assertSame("must not restore over a newer handler", laterHandler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `crash after session already stopped writes no exception event`() {
        val s = session()
        val tracker = ExceptionTracker(s)
        tracker.install()
        s.stop(EventFactory.appStop())

        tracker.uncaughtException(Thread.currentThread(), RuntimeException("after stop"))

        assertEquals("after stop", delegated.get()?.message)
        SessionReader.open(s.file.toPath()).use { r ->
            assertFalse(r.events().toList().any { it.type == "android.exception" })
        }
    }
}
