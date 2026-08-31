package trace.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraceRecordTest {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "recordType"
    }

    @Test
    fun `SessionStart serialization round-trip`() {
        val original = SessionStart(
            schemaVersion = 1,
            id = "test-session-id",
            startedAtMillis = 1709251200000L,
            source = SessionSource.RECORDING,
            replayOf = null
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<SessionStart>(serialized)

        assertEquals(original, deserialized)
        assertEquals("session_start", deserialized.recordType)
    }

    @Test
    fun `SessionStart with replayOf serialization round-trip`() {
        val original = SessionStart(
            schemaVersion = 1,
            id = "replay-session-id",
            startedAtMillis = 1709251200000L,
            source = SessionSource.REPLAY,
            replayOf = "original-session-id"
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<SessionStart>(serialized)

        assertEquals(original, deserialized)
        assertEquals("original-session-id", deserialized.replayOf)
    }

    @Test
    fun `EventRecord serialization round-trip`() {
        val payload = buildJsonObject {
            put("x", 120)
            put("y", 340)
        }

        val original = EventRecord(
            seq = 0,
            timestampNanos = 15000000L,
            type = "app.ui.tap",
            payload = payload
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<EventRecord>(serialized)

        assertEquals(original, deserialized)
        assertEquals("event", deserialized.recordType)
    }

    @Test
    fun `SessionEnd serialization round-trip`() {
        val original = SessionEnd(
            eventCount = 42,
            endedAtMillis = 1709251200120L
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<SessionEnd>(serialized)

        assertEquals(original, deserialized)
        assertEquals("session_end", deserialized.recordType)
    }

    @Test
    fun `Polymorphic deserialization with recordType discriminator`() {
        val sessionStartJson = """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING"}"""
        val eventJson = """{"recordType":"event","seq":0,"timestampNanos":100,"type":"test.event","payload":{}}"""
        val sessionEndJson = """{"recordType":"session_end","eventCount":1,"endedAtMillis":2000}"""

        val sessionStart = json.decodeFromString<TraceRecord>(sessionStartJson)
        val event = json.decodeFromString<TraceRecord>(eventJson)
        val sessionEnd = json.decodeFromString<TraceRecord>(sessionEndJson)

        assert(sessionStart is SessionStart)
        assert(event is EventRecord)
        assert(sessionEnd is SessionEnd)
    }

    @Test
    fun `Unknown fields ignored during deserialization`() {
        val jsonWithExtra = """{"recordType":"session_start","schemaVersion":1,"id":"test","startedAtMillis":1000,"source":"RECORDING","unknownField":"ignored","anotherUnknown":123}"""

        val deserialized = json.decodeFromString<SessionStart>(jsonWithExtra)

        assertEquals("test", deserialized.id)
        assertEquals(1, deserialized.schemaVersion)
        assertNull(deserialized.replayOf)
    }

    @Test
    fun `SessionSource enum serialization`() {
        val recording = SessionSource.RECORDING
        val replay = SessionSource.REPLAY

        assertEquals("\"RECORDING\"", json.encodeToString(recording))
        assertEquals("\"REPLAY\"", json.encodeToString(replay))
    }

    @Test
    fun `Empty payload in EventRecord`() {
        val original = EventRecord(
            seq = 0,
            timestampNanos = 0,
            type = "empty.payload",
            payload = JsonObject(emptyMap())
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<EventRecord>(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `Complex nested payload in EventRecord`() {
        val payload = buildJsonObject {
            put("string", "value")
            put("number", 42)
            put("boolean", true)
            put("nested", buildJsonObject {
                put("inner", "data")
            })
        }

        val original = EventRecord(
            seq = 5,
            timestampNanos = 1000000L,
            type = "complex.event",
            payload = payload
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<EventRecord>(serialized)

        assertEquals(original, deserialized)
    }
}
