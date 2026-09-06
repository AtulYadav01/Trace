package dev.trace.android.internal

import dev.trace.android.TraceConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import trace.core.SessionReader

class AndroidTraceSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun start(): AndroidTraceSession =
        AndroidTraceSession.start(tmp.newFolder(), TraceConfig())

    private fun ev(type: String, i: Int = 0) =
        TraceEvent(type, buildJsonObject { put("i", i) })

    private fun read(session: AndroidTraceSession): List<trace.core.Event> =
        SessionReader.open(session.file.toPath()).use { r ->
            val list = r.events().toList()
            assertTrue(r.isComplete)
            list
        }

    @Test
    fun `starts RUNNING`() {
        val s = start()
        assertEquals(SessionState.RUNNING, s.currentState)
        assertTrue(s.isRunning)
        s.stop(null)
    }

    @Test
    fun `stop drains queued events then writes stop event then closes`() {
        val s = start()
        repeat(2_000) { s.record(ev("android.ui.click", it)) }
        s.stop(EventFactory.appStop())

        assertEquals(SessionState.STOPPED, s.currentState)
        val events = read(s)
        assertEquals(2_000 + 1, events.size)
        events.forEachIndexed { idx, e -> assertEquals(idx.toLong(), e.seq) }
        assertEquals("android.app.stop", events.last().type)
    }

    @Test
    fun `record after stop is ignored, no throw`() {
        val s = start()
        s.record(ev("android.ui.click", 1))
        s.stop(EventFactory.appStop())
        repeat(100) { s.record(ev("android.ui.click", it)) } // must not throw or write

        val events = read(s)
        assertEquals(2, events.size) // one click + app.stop
    }

    @Test
    fun `double stop is a no-op`() {
        val s = start()
        s.record(ev("android.ui.click", 1))
        s.stop(EventFactory.appStop())
        s.stop(EventFactory.appStop())
        s.stop(null)

        val events = read(s)
        assertEquals(1, events.count { it.type == "android.app.stop" })
    }

    @Test
    fun `crashStop moves to STOPPED, writes exception, session complete`() {
        val s = start()
        repeat(500) { s.record(ev("android.ui.click", it)) }
        s.crashStop(EventFactory.exception(IllegalStateException("boom"), "main", fatal = true))

        assertEquals(SessionState.STOPPED, s.currentState)
        SessionReader.open(s.file.toPath()).use { r ->
            val events = r.events().toList()
            events.forEachIndexed { idx, e -> assertEquals(idx.toLong(), e.seq) }
            assertTrue(r.isComplete)
            assertEquals(1, events.count { it.type == "android.exception" })
        }
        // record after crashStop is ignored
        s.record(ev("android.ui.click", 999))
    }

    @Test
    fun `crashStop after normal stop is a no-op`() {
        val s = start()
        s.stop(EventFactory.appStop())
        s.crashStop(EventFactory.exception(RuntimeException("late"), "main", fatal = true))
        val events = read(s)
        assertEquals(0, events.count { it.type == "android.exception" })
    }
}
