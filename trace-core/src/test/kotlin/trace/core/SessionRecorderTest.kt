package trace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionRecorderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "recordType"
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `creates file with session_start`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        assertTrue(Files.exists(file))

        val lines = Files.readAllLines(file)
        assertTrue(lines.isNotEmpty())

        val firstRecord = json.decodeFromString<TraceRecord>(lines[0])
        assertTrue(firstRecord is SessionStart)
        assertEquals(recorder.sessionId, (firstRecord as SessionStart).id)
        assertEquals(CURRENT_SCHEMA_VERSION, firstRecord.schemaVersion)
        assertEquals(SessionSource.RECORDING, firstRecord.source)
    }

    @Test
    fun `records events with increasing seq`() {
        val recorder = SessionRecorder.create(tempDir)

        recorder.record("test.event", buildJsonObject { put("index", 0) })
        recorder.record("test.event", buildJsonObject { put("index", 1) })
        recorder.record("test.event", buildJsonObject { put("index", 2) })

        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        // Lines: session_start, event0, event1, event2, session_end
        assertEquals(5, lines.size)

        val event0 = json.decodeFromString<EventRecord>(lines[1])
        val event1 = json.decodeFromString<EventRecord>(lines[2])
        val event2 = json.decodeFromString<EventRecord>(lines[3])

        assertEquals(0L, event0.seq)
        assertEquals(1L, event1.seq)
        assertEquals(2L, event2.seq)
    }

    @Test
    fun `records events with monotonic timestamps`() {
        val recorder = SessionRecorder.create(tempDir)

        repeat(10) {
            recorder.record("test.event", JsonObject(emptyMap()))
            Thread.sleep(1) // Ensure some time passes
        }

        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        var lastTimestamp = -1L
        for (i in 1 until lines.size - 1) { // Skip session_start and session_end
            val event = json.decodeFromString<EventRecord>(lines[i])
            assertTrue(event.timestampNanos >= lastTimestamp,
                "Timestamps must be monotonically non-decreasing")
            lastTimestamp = event.timestampNanos
        }
    }

    @Test
    fun `close writes session_end`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("test.event", JsonObject(emptyMap()))
        recorder.record("test.event", JsonObject(emptyMap()))
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        val lastRecord = json.decodeFromString<TraceRecord>(lines.last())
        assertTrue(lastRecord is SessionEnd)
        assertEquals(2L, (lastRecord as SessionEnd).eventCount)
    }

    @Test
    fun `close is idempotent`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("test.event", JsonObject(emptyMap()))

        // Multiple closes should not throw
        recorder.close()
        recorder.close()
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        // Should still have exactly one session_end
        val sessionEnds = lines.filter { it.contains("session_end") }
        assertEquals(1, sessionEnds.size)
    }

    @Test
    fun `record after close throws`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.close()

        assertThrows<IllegalStateException> {
            recorder.record("test.event", JsonObject(emptyMap()))
        }
    }

    @Test
    fun `empty type rejected`() {
        val recorder = SessionRecorder.create(tempDir)

        assertThrows<IllegalArgumentException> {
            recorder.record("", JsonObject(emptyMap()))
        }

        recorder.close()
    }

    @Test
    fun `thread-safe recording`() {
        val recorder = SessionRecorder.create(tempDir)
        val threadCount = 10
        val eventsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { threadId ->
            executor.submit {
                try {
                    repeat(eventsPerThread) { eventId ->
                        recorder.record(
                            "thread.$threadId.event",
                            buildJsonObject { put("eventId", eventId) }
                        )
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        recorder.close()
        executor.shutdown()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        // session_start + (threadCount * eventsPerThread) events + session_end
        val expectedLines = 1 + (threadCount * eventsPerThread) + 1
        assertEquals(expectedLines, lines.size)

        // Verify all seq numbers are unique and cover 0 to (total-1)
        val seqNumbers = lines
            .drop(1)  // Skip session_start
            .dropLast(1)  // Skip session_end
            .map { json.decodeFromString<EventRecord>(it).seq }
            .sorted()

        val expectedSeq = (0L until (threadCount * eventsPerThread).toLong()).toList()
        assertEquals(expectedSeq, seqNumbers)
    }

    @Test
    fun `replay session has correct source and replayOf`() {
        val originalId = "original-session-123"
        val recorder = SessionRecorder.create(tempDir, replayOf = originalId)
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        val sessionStart = json.decodeFromString<SessionStart>(lines[0])
        assertEquals(SessionSource.REPLAY, sessionStart.source)
        assertEquals(originalId, sessionStart.replayOf)
    }

    @Test
    fun `recording session has RECORDING source`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        val sessionStart = json.decodeFromString<SessionStart>(lines[0])
        assertEquals(SessionSource.RECORDING, sessionStart.source)
        assertEquals(null, sessionStart.replayOf)
    }

    @Test
    fun `sessionId is a valid UUID`() {
        val recorder = SessionRecorder.create(tempDir)

        assertNotNull(java.util.UUID.fromString(recorder.sessionId))

        recorder.close()
    }
}
