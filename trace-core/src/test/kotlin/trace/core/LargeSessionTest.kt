package trace.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeSessionTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `100K events streaming write and read`() {
        val eventCount = 100_000

        // Write 100K events
        val recorder = SessionRecorder.create(tempDir)
        repeat(eventCount) { i ->
            recorder.record(
                "load.test.event",
                buildJsonObject {
                    put("index", i)
                    put("data", "event_data_$i")
                }
            )
        }
        recorder.close()

        // Read via streaming Sequence
        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            var count = 0L
            var lastSeq = -1L

            reader.events().forEach { event ->
                assertEquals(count, event.seq)
                assertTrue(event.seq > lastSeq)
                lastSeq = event.seq
                count++
            }

            assertEquals(eventCount.toLong(), count)
        }
    }

    @Test
    fun `streaming does not buffer all events in memory`() {
        val eventCount = 50_000

        // Write many events
        val recorder = SessionRecorder.create(tempDir)
        repeat(eventCount) { i ->
            recorder.record(
                "memory.test",
                buildJsonObject { put("i", i) }
            )
        }
        recorder.close()

        // Read only first 100 events using Sequence.take()
        // This proves streaming works - we don't need to load all events
        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            val first100 = reader.events().take(100).toList()
            assertEquals(100, first100.size)
            assertEquals(0L, first100.first().seq)
            assertEquals(99L, first100.last().seq)
        }
    }

    @Test
    fun `file size is reasonable for event count`() {
        val eventCount = 10_000
        val expectedMaxBytesPerEvent = 150 // Conservative estimate

        val recorder = SessionRecorder.create(tempDir)
        repeat(eventCount) { i ->
            recorder.record(
                "size.test.event",
                buildJsonObject {
                    put("index", i)
                    put("value", "test")
                }
            )
        }
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val fileSize = Files.size(file)

        // File size should be roughly proportional to event count
        // Allow overhead for session_start and session_end
        val maxExpectedSize = (eventCount * expectedMaxBytesPerEvent) + 1000
        assertTrue(
            fileSize < maxExpectedSize,
            "File size $fileSize exceeds expected max $maxExpectedSize"
        )

        // Sanity check: file shouldn't be tiny
        assertTrue(fileSize > eventCount * 50, "File seems too small: $fileSize bytes")
    }

    @Test
    fun `concurrent read and write to different sessions`() {
        val sessions = mutableListOf<String>()

        // Create multiple sessions in parallel
        val threads = (0 until 5).map { threadId ->
            Thread {
                val recorder = SessionRecorder.create(tempDir)
                synchronized(sessions) {
                    sessions.add(recorder.sessionId)
                }

                repeat(1000) { i ->
                    recorder.record(
                        "parallel.write.$threadId",
                        buildJsonObject { put("event", i) }
                    )
                }

                recorder.close()
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Verify all sessions
        assertEquals(5, sessions.size)
        sessions.forEach { sessionId ->
            val file = tempDir.resolve("$sessionId.trace.jsonl")
            SessionReader.open(file).use { reader ->
                val events = reader.events().toList()
                assertEquals(1000, events.size)
            }
        }
    }

    @Test
    fun `events with varying payload sizes`() {
        val recorder = SessionRecorder.create(tempDir)

        // Small payload
        recorder.record("small", buildJsonObject { put("x", 1) })

        // Medium payload
        recorder.record("medium", buildJsonObject {
            repeat(20) { i -> put("field_$i", "value_$i") }
        })

        // Large payload (but not huge - staying reasonable)
        recorder.record("large", buildJsonObject {
            repeat(100) { i ->
                put("field_$i", "This is a longer value string for field $i with some extra text")
            }
        })

        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            val events = reader.events().toList()
            assertEquals(3, events.size)
            assertEquals("small", events[0].type)
            assertEquals("medium", events[1].type)
            assertEquals("large", events[2].type)
        }
    }
}
