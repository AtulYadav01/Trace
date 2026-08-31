package trace.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `full round-trip - record close open read verify`() {
        // Record a session
        val recorder = SessionRecorder.create(tempDir)
        val sessionId = recorder.sessionId

        val testEvents = listOf(
            "app.lifecycle.start" to buildJsonObject { put("version", "1.0.0") },
            "app.ui.tap" to buildJsonObject { put("x", 120); put("y", 340) },
            "app.network.request" to buildJsonObject { put("method", "POST"); put("url", "/api/data") },
            "app.ui.scroll" to buildJsonObject { put("direction", "down"); put("offset", 500) },
            "app.lifecycle.pause" to buildJsonObject { }
        )

        testEvents.forEach { (type, payload) ->
            recorder.record(type, payload)
        }

        recorder.close()

        // Read and verify
        val file = tempDir.resolve("$sessionId.trace.jsonl")
        assertTrue(Files.exists(file))

        SessionReader.open(file).use { reader ->
            assertEquals(sessionId, reader.sessionId)
            assertEquals(CURRENT_SCHEMA_VERSION, reader.schemaVersion)
            assertEquals(SessionSource.RECORDING, reader.source)

            val events = reader.events().toList()
            assertEquals(testEvents.size, events.size)

            events.forEachIndexed { index, event ->
                assertEquals(index.toLong(), event.seq)
                assertEquals(testEvents[index].first, event.type)
                assertEquals(testEvents[index].second, event.payload)
                assertTrue(event.timestampNanos >= 0)
            }
        }
    }

    @Test
    fun `replay session links to original`() {
        // Create original session
        val original = SessionRecorder.create(tempDir)
        original.record("app.event", buildJsonObject { put("data", "original") })
        original.close()

        // Create replay session
        val replay = SessionRecorder.create(tempDir, replayOf = original.sessionId)
        replay.record("app.event", buildJsonObject { put("data", "replay") })
        replay.close()

        // Verify replay metadata
        val replayFile = tempDir.resolve("${replay.sessionId}.trace.jsonl")
        SessionReader.open(replayFile).use { reader ->
            assertEquals(SessionSource.REPLAY, reader.source)
            assertEquals(original.sessionId, reader.replayOf)
        }

        // Verify original still readable and unaffected
        val originalFile = tempDir.resolve("${original.sessionId}.trace.jsonl")
        SessionReader.open(originalFile).use { reader ->
            assertEquals(SessionSource.RECORDING, reader.source)
            assertEquals(null, reader.replayOf)
        }
    }

    @Test
    fun `multiple sessions in directory`() {
        val sessionIds = mutableListOf<String>()

        // Create multiple sessions
        repeat(5) { i ->
            val recorder = SessionRecorder.create(tempDir)
            sessionIds.add(recorder.sessionId)

            repeat(10) { j ->
                recorder.record("session.$i.event", buildJsonObject { put("eventIndex", j) })
            }

            recorder.close()
        }

        // Verify all sessions are readable
        sessionIds.forEachIndexed { sessionIndex, sessionId ->
            val file = tempDir.resolve("$sessionId.trace.jsonl")
            assertTrue(Files.exists(file))

            SessionReader.open(file).use { reader ->
                assertEquals(sessionId, reader.sessionId)

                val events = reader.events().toList()
                assertEquals(10, events.size)

                events.forEach { event ->
                    assertEquals("session.$sessionIndex.event", event.type)
                }
            }
        }

        // Verify correct number of files
        val traceFiles = Files.list(tempDir)
            .filter { it.toString().endsWith(".trace.jsonl") }
            .count()
        assertEquals(5L, traceFiles)
    }

    @Test
    fun `timestamps are monotonically increasing across events`() {
        val recorder = SessionRecorder.create(tempDir)

        repeat(100) {
            recorder.record("rapid.event", buildJsonObject { })
        }

        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            var lastTimestamp = -1L

            reader.events().forEach { event ->
                assertTrue(
                    event.timestampNanos >= lastTimestamp,
                    "Timestamp ${event.timestampNanos} should be >= $lastTimestamp"
                )
                lastTimestamp = event.timestampNanos
            }
        }
    }

    @Test
    fun `event payload integrity preserved`() {
        val complexPayload = buildJsonObject {
            put("string", "hello world")
            put("int", 42)
            put("long", Long.MAX_VALUE)
            put("double", 3.14159)
            put("boolean", true)
            put("null_field", null as String?)
            put("nested", buildJsonObject {
                put("level2", buildJsonObject {
                    put("deep", "value")
                })
            })
        }

        val recorder = SessionRecorder.create(tempDir)
        recorder.record("complex.event", complexPayload)
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            val event = reader.events().first()
            assertEquals(complexPayload, event.payload)
        }
    }

    @Test
    fun `session file format is valid JSONL`() {
        val recorder = SessionRecorder.create(tempDir)
        repeat(10) {
            recorder.record("test.event", buildJsonObject { put("index", it) })
        }
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val lines = Files.readAllLines(file)

        // Each line should be valid JSON
        lines.forEach { line ->
            assertNotNull(kotlinx.serialization.json.Json.parseToJsonElement(line))
        }

        // First line should be session_start
        assertTrue(lines[0].contains("\"recordType\":\"session_start\""))

        // Last line should be session_end
        assertTrue(lines.last().contains("\"recordType\":\"session_end\""))

        // Lines 1 to n-1 should be events
        for (i in 1 until lines.size - 1) {
            assertTrue(lines[i].contains("\"recordType\":\"event\""))
        }
    }
}
