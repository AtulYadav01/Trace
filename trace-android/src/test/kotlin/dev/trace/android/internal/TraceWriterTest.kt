package dev.trace.android.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import trace.core.SessionReader
import trace.core.SessionRecorder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * TraceWriter drives a real trace-core SessionRecorder; no Android needed.
 * Every test reads the produced .trace.jsonl back with trace-core's SessionReader.
 */
class TraceWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRecorder(): SessionRecorder = SessionRecorder.create(tmp.newFolder().toPath())

    private fun ev(i: Int): TraceEvent = TraceEvent("android.test.event", buildJsonObject { put("i", i) })

    private fun readEvents(writer: TraceWriter, recorder: SessionRecorder): List<trace.core.Event> {
        // caller has already shut the writer down
        val dir = recorder.let { it } // recorder exposes only sessionId; find the file via tmp
        val file = tmp.root.walkTopDown().first { it.name == "${recorder.sessionId}.trace.jsonl" }
        return SessionReader.open(file.toPath()).use { r ->
            val list = r.events().toList()
            assertTrue("session must be complete after shutdownAndClose", r.isComplete)
            list
        }
    }

    @Test
    fun `accepted events are all written before close, seq contiguous`() {
        val recorder = newRecorder()
        val writer = TraceWriter(recorder)

        val n = 5_000
        repeat(n) { assertTrue(writer.submit(ev(it))) }
        writer.shutdownAndClose(TraceEvent("android.app.stop", JsonObject(emptyMap())), timeoutMs = 5_000)

        val events = readEvents(writer, recorder)
        assertEquals(n + 1, events.size) // n test events + app.stop
        events.forEachIndexed { idx, e -> assertEquals(idx.toLong(), e.seq) }
        assertEquals("android.app.stop", events.last().type)

        val stats = writer.stats()
        assertEquals(n.toLong() + 1, stats.written)
        assertEquals(0L, stats.rejected)
    }

    @Test
    fun `the big one - many rapid events from many threads, stop, read, seq == 0 to N-1`() {
        val recorder = newRecorder()
        val writer = TraceWriter(recorder)

        val threads = 8
        val perThread = 4_000
        val total = threads * perThread
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        var accepted = 0L
        val acceptLock = Any()

        repeat(threads) { t ->
            pool.submit {
                start.await()
                var localAccepted = 0
                repeat(perThread) { i ->
                    if (writer.submit(TraceEvent("android.ui.click", buildJsonObject { put("t", t); put("i", i) }))) {
                        localAccepted++
                    }
                }
                synchronized(acceptLock) { accepted += localAccepted }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()

        writer.shutdownAndClose(TraceEvent("android.app.stop", JsonObject(emptyMap())), timeoutMs = 10_000)

        val events = readEvents(writer, recorder)
        // Every accepted event, plus app.stop, written; nothing lost, nothing duplicated.
        assertEquals(accepted + 1, events.size.toLong())
        assertEquals(total.toLong(), accepted) // nothing was rejected while RUNNING
        events.forEachIndexed { idx, e -> assertEquals(idx.toLong(), e.seq) }
        var lastTs = -1L
        events.forEach { assertTrue(it.timestampNanos >= lastTs); lastTs = it.timestampNanos }
    }

    @Test
    fun `events submitted after shutdown begins are rejected, not silently lost`() {
        val recorder = newRecorder()
        val writer = TraceWriter(recorder)

        repeat(100) { assertTrue(writer.submit(ev(it))) }
        writer.shutdownAndClose(null, timeoutMs = 5_000)

        // After shutdown, submit must return false and nothing must be written.
        repeat(50) { assertFalse("post-shutdown submit must be rejected", writer.submit(ev(1000 + it))) }

        val events = readEvents(writer, recorder)
        assertEquals(100, events.size)
        events.forEachIndexed { idx, e -> assertEquals(idx.toLong(), e.seq) }
        assertEquals(50L, writer.stats().rejected)
    }

    @Test
    fun `shutdownAndClose is idempotent`() {
        val recorder = newRecorder()
        val writer = TraceWriter(recorder)
        repeat(10) { writer.submit(ev(it)) }
        writer.shutdownAndClose(TraceEvent("android.app.stop", JsonObject(emptyMap())), timeoutMs = 5_000)
        writer.shutdownAndClose(TraceEvent("android.app.stop", JsonObject(emptyMap())), timeoutMs = 5_000)
        writer.shutdownAndClose(null, timeoutMs = 5_000)

        val events = readEvents(writer, recorder)
        assertEquals(11, events.size) // 10 + one app.stop only
        assertEquals(1, events.count { it.type == "android.app.stop" })
    }

    @Test
    fun `crash path - writes exception, closes, file valid and seq contiguous even with queued events`() {
        val recorder = newRecorder()
        val writer = TraceWriter(recorder)

        // Flood the queue, then crash-close from "another thread" without draining.
        repeat(3_000) { writer.submit(ev(it)) }
        writer.crashCloseDirect(
            TraceEvent("android.exception", buildJsonObject { put("exceptionType", "java.lang.IllegalStateException") }),
        )

        val file = tmp.root.walkTopDown().first { it.name == "${recorder.sessionId}.trace.jsonl" }
        SessionReader.open(file.toPath()).use { r ->
            val events = r.events().toList()
            // Whatever made it in is contiguous, monotonic, and the file is complete.
            events.forEachIndexed { idx, e -> assertEquals(idx.toLong(), e.seq) }
            var lastTs = -1L
            events.forEach { assertTrue(it.timestampNanos >= lastTs); lastTs = it.timestampNanos }
            assertTrue("crash path still closes the session", r.isComplete)
            assertEquals("exactly one exception event", 1, events.count { it.type == "android.exception" })
        }
    }

    @Test
    fun `crash path does not block the calling thread`() {
        val recorder = newRecorder()
        val writer = TraceWriter(recorder)
        repeat(2_000) { writer.submit(ev(it)) }

        val elapsed = kotlin.system.measureTimeMillis {
            writer.crashCloseDirect(TraceEvent("android.exception", JsonObject(emptyMap())))
        }
        assertTrue("crash close must be fast (no awaitTermination), was ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun `payload content survives the round trip`() {
        val recorder = newRecorder()
        val writer = TraceWriter(recorder)
        writer.submit(TraceEvent("android.ui.click", buildJsonObject { put("viewId", "submit_button"); put("n", 42) }))
        writer.shutdownAndClose(null, timeoutMs = 5_000)

        val e = readEvents(writer, recorder).single()
        assertEquals("android.ui.click", e.type)
        assertEquals("submit_button", e.payload["viewId"]!!.jsonPrimitive.content)
        assertEquals(42, e.payload["n"]!!.jsonPrimitive.int)
    }
}
