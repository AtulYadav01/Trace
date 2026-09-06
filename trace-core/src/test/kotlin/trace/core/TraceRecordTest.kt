package trace.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the on-disk record model and its serialization.
 *
 * NOTE: [TraceRecord] and its subclasses are `internal`; these tests can see
 * them because the test source set is part of the same Gradle module.
 */
class TraceRecordTest {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "recordType"
    }

    // Mirrors the writer's configuration.
    private val writerJson = Json { encodeDefaults = true }

    @Test
    fun `SessionStart round-trip`() {
        val original = SessionStart(
            schemaVersion = 1,
            id = "test-session-id",
            startedAtMillis = 1709251200000L,
            source = SessionSource.RECORDING,
            replayOf = null
        )
        val decoded = json.decodeFromString<SessionStart>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals("session_start", decoded.recordType)
    }

    @Test
    fun `SessionStart with replayOf round-trip`() {
        val original = SessionStart(
            schemaVersion = 1,
            id = "replay-id",
            startedAtMillis = 1709251200000L,
            source = SessionSource.REPLAY,
            replayOf = "original-id"
        )
        val decoded = json.decodeFromString<SessionStart>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals("original-id", decoded.replayOf)
    }

    @Test
    fun `EventRecord round-trip`() {
        val original = EventRecord(
            seq = 0,
            timestampNanos = 15_000_000L,
            type = "app.ui.tap",
            payload = buildJsonObject { put("x", 120); put("y", 340) }
        )
        val decoded = json.decodeFromString<EventRecord>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals("event", decoded.recordType)
    }

    @Test
    fun `SessionEnd round-trip`() {
        val original = SessionEnd(eventCount = 42, endedAtMillis = 1709251200120L)
        val decoded = json.decodeFromString<SessionEnd>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals("session_end", decoded.recordType)
    }

    @Test
    fun `polymorphic decode via sealed base uses recordType discriminator`() {
        val start = json.decodeFromString<TraceRecord>(
            """{"recordType":"session_start","schemaVersion":1,"id":"t","startedAtMillis":1,"source":"RECORDING"}"""
        )
        val event = json.decodeFromString<TraceRecord>(
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"t","payload":{}}"""
        )
        val end = json.decodeFromString<TraceRecord>(
            """{"recordType":"session_end","eventCount":1,"endedAtMillis":2}"""
        )
        assertTrue(start is SessionStart)
        assertTrue(event is EventRecord)
        assertTrue(end is SessionEnd)
    }

    @Test
    fun `stable recordType values with no collision between discriminator and event type`() {
        // The writer serializes CONCRETE record types (never the polymorphic base),
        // which is what keeps the "recordType" discriminator from colliding with
        // EventRecord's own "type" field. This test locks that behavior in.
        val startLine = writerJson.encodeToString(
            SessionStart(
                schemaVersion = 1, id = "id", startedAtMillis = 1,
                source = SessionSource.RECORDING, replayOf = null
            )
        )
        val eventLine = writerJson.encodeToString(
            EventRecord(seq = 0, timestampNanos = 0, type = "app.ui.tap", payload = JsonObject(emptyMap()))
        )
        val endLine = writerJson.encodeToString(SessionEnd(eventCount = 1, endedAtMillis = 2))

        assertTrue(startLine.contains("\"recordType\":\"session_start\""))
        assertTrue(eventLine.contains("\"recordType\":\"event\""))
        assertTrue(endLine.contains("\"recordType\":\"session_end\""))

        // The event line carries BOTH a recordType discriminator and a domain
        // "type" field; they must remain distinct.
        assertTrue(eventLine.contains("\"recordType\":\"event\""))
        assertTrue(eventLine.contains("\"type\":\"app.ui.tap\""))

        // Decoding through the sealed base still resolves the right subclass and
        // keeps `type` separate from `recordType`.
        val decoded = listOf(startLine, eventLine, endLine).map { json.decodeFromString<TraceRecord>(it) }
        assertTrue(decoded[0] is SessionStart)
        assertTrue(decoded[1] is EventRecord)
        assertEquals("event", (decoded[1] as EventRecord).recordType)
        assertEquals("app.ui.tap", (decoded[1] as EventRecord).type)
        assertTrue(decoded[2] is SessionEnd)
    }

    @Test
    fun `recordType discriminator does not depend on Kotlin class names`() {
        // Explicit @SerialName values, not class-name-derived.
        val eventLine = writerJson.encodeToString(
            EventRecord(seq = 1, timestampNanos = 2, type = "x", payload = JsonObject(emptyMap()))
        )
        assertTrue(eventLine.contains("\"recordType\":\"event\""))
        assertTrue(!eventLine.contains("EventRecord"))
    }

    @Test
    fun `unknown JSON fields are ignored on decode`() {
        val decoded = json.decodeFromString<SessionStart>(
            """{"recordType":"session_start","schemaVersion":1,"id":"t","startedAtMillis":1,"source":"RECORDING","x":"y","n":5}"""
        )
        assertEquals("t", decoded.id)
        assertNull(decoded.replayOf)
    }

    @Test
    fun `SessionSource enum serializes to stable names`() {
        assertEquals("\"RECORDING\"", json.encodeToString(SessionSource.RECORDING))
        assertEquals("\"REPLAY\"", json.encodeToString(SessionSource.REPLAY))
    }

    @Test
    fun `empty and nested payloads round-trip`() {
        val empty = EventRecord(seq = 0, timestampNanos = 0, type = "e", payload = JsonObject(emptyMap()))
        assertEquals(empty, json.decodeFromString<EventRecord>(json.encodeToString(empty)))

        val nested = EventRecord(
            seq = 5, timestampNanos = 1, type = "n",
            payload = buildJsonObject {
                put("s", "v"); put("n", 42); put("b", true)
                put("inner", buildJsonObject { put("deep", "data") })
            }
        )
        assertEquals(nested, json.decodeFromString<EventRecord>(json.encodeToString(nested)))
    }
}
