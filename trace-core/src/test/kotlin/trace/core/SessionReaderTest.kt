package trace.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private fun writeSession(lines: List<String>): Path {
        val file = tempDir.resolve("test.trace.jsonl")
        Files.write(file, lines)
        return file
    }

    @Test
    fun `opens valid session`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("test.event", buildJsonObject { put("key", "value") })
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val reader = SessionReader.open(file)

        assertEquals(recorder.sessionId, reader.sessionId)
        assertEquals(CURRENT_SCHEMA_VERSION, reader.schemaVersion)
        assertEquals(SessionSource.RECORDING, reader.source)
        assertNull(reader.replayOf)

        reader.close()
    }

    @Test
    fun `returns Sequence of Event`() {
        val recorder = SessionRecorder.create(tempDir)
        repeat(5) { i ->
            recorder.record("test.event.$i", buildJsonObject { put("index", i) })
        }
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            val events = reader.events()
            assertTrue(events is Sequence<Event>)

            val eventList = events.toList()
            assertEquals(5, eventList.size)

            eventList.forEachIndexed { index, event ->
                assertEquals(index.toLong(), event.seq)
                assertEquals("test.event.$index", event.type)
            }
        }
    }

    @Test
    fun `unknown fields ignored`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING","unknownField":"ignored"}""",
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"test","payload":{},"extraField":123}""",
            """{"recordType":"session_end","eventCount":1,"endedAtMillis":2000}"""
        ))

        SessionReader.open(file).use { reader ->
            val events = reader.events().toList()
            assertEquals(1, events.size)
            assertEquals("test", events[0].type)
        }
    }

    @Test
    fun `future schema version rejected`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":999,"id":"test","startedAtMillis":1000,"source":"RECORDING"}"""
        ))

        val exception = assertThrows<UnsupportedSchemaVersionException> {
            SessionReader.open(file)
        }
        assertEquals(999, exception.version)
    }

    @Test
    fun `missing session_end accepted`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}""",
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"test.event","payload":{}}""",
            """{"recordType":"event","seq":1,"timestampNanos":200,"type":"test.event","payload":{}}"""
            // No session_end - simulates crash
        ))

        SessionReader.open(file).use { reader ->
            val events = reader.events().toList()
            assertEquals(2, events.size)
        }
    }

    @Test
    fun `malformed JSON detected`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}""",
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"test","payload":{}}""",
            """this is not valid json""",
            """{"recordType":"session_end","eventCount":1,"endedAtMillis":2000}"""
        ))

        SessionReader.open(file).use { reader ->
            val exception = assertThrows<MalformedRecordException> {
                reader.events().toList()
            }
            assertEquals(3, exception.lineNumber)
        }
    }

    @Test
    fun `non-increasing seq rejected`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}""",
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"test","payload":{}}""",
            """{"recordType":"event","seq":0,"timestampNanos":200,"type":"test","payload":{}}""",
            """{"recordType":"session_end","eventCount":2,"endedAtMillis":2000}"""
        ))

        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> {
                reader.events().toList()
            }
        }
    }

    @Test
    fun `decreasing seq rejected`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}""",
            """{"recordType":"event","seq":5,"timestampNanos":100,"type":"test","payload":{}}""",
            """{"recordType":"event","seq":3,"timestampNanos":200,"type":"test","payload":{}}""",
            """{"recordType":"session_end","eventCount":2,"endedAtMillis":2000}"""
        ))

        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> {
                reader.events().toList()
            }
        }
    }

    @Test
    fun `negative timestamp rejected`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}""",
            """{"recordType":"event","seq":0,"timestampNanos":-100,"type":"test","payload":{}}""",
            """{"recordType":"session_end","eventCount":1,"endedAtMillis":2000}"""
        ))

        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> {
                reader.events().toList()
            }
        }
    }

    @Test
    fun `equal timestamps accepted`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}""",
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"test","payload":{}}""",
            """{"recordType":"event","seq":1,"timestampNanos":100,"type":"test","payload":{}}""",
            """{"recordType":"event","seq":2,"timestampNanos":200,"type":"test","payload":{}}""",
            """{"recordType":"session_end","eventCount":3,"endedAtMillis":2000}"""
        ))

        SessionReader.open(file).use { reader ->
            val events = reader.events().toList()
            assertEquals(3, events.size)
            assertEquals(100L, events[0].timestampNanos)
            assertEquals(100L, events[1].timestampNanos) // Equal is valid
            assertEquals(200L, events[2].timestampNanos)
        }
    }

    @Test
    fun `decreasing timestamps rejected`() {
        val file = writeSession(listOf(
            """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}""",
            """{"recordType":"event","seq":0,"timestampNanos":200,"type":"test","payload":{}}""",
            """{"recordType":"event","seq":1,"timestampNanos":100,"type":"test","payload":{}}""",
            """{"recordType":"session_end","eventCount":2,"endedAtMillis":2000}"""
        ))

        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> {
                reader.events().toList()
            }
        }
    }

    @Test
    fun `empty file rejected`() {
        val file = tempDir.resolve("empty.trace.jsonl")
        Files.createFile(file)

        assertThrows<IllegalStateException> {
            SessionReader.open(file)
        }
    }

    @Test
    fun `first line not session_start rejected`() {
        val file = writeSession(listOf(
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"test","payload":{}}"""
        ))

        assertThrows<IllegalStateException> {
            SessionReader.open(file)
        }
    }

    @Test
    fun `replay session has correct metadata`() {
        val originalId = "original-session-id"
        val recorder = SessionRecorder.create(tempDir, replayOf = originalId)
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            assertEquals(SessionSource.REPLAY, reader.source)
            assertEquals(originalId, reader.replayOf)
        }
    }

    @Test
    fun `events sequence is lazy`() {
        val recorder = SessionRecorder.create(tempDir)
        repeat(100) { i ->
            recorder.record("test.event", buildJsonObject { put("index", i) })
        }
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        SessionReader.open(file).use { reader ->
            // Taking first 5 should not read all 100 events
            val firstFive = reader.events().take(5).toList()
            assertEquals(5, firstFive.size)
            assertEquals(0L, firstFive[0].seq)
            assertEquals(4L, firstFive[4].seq)
        }
    }
}
