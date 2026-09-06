package trace.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private var counter = 0

    /** Writes an exact set of lines (LF-joined, trailing LF) and returns the path. */
    private fun session(vararg lines: String): Path {
        val file = tempDir.resolve("s${counter++}.trace.jsonl")
        Files.write(file, (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        return file
    }

    private val START = """{"recordType":"session_start","schemaVersion":1,"id":"t","startedAtMillis":1000,"source":"RECORDING"}"""
    private fun event(seq: Long, ts: Long = seq * 10) =
        """{"recordType":"event","seq":$seq,"timestampNanos":$ts,"type":"e","payload":{}}"""
    private val END = """{"recordType":"session_end","eventCount":0,"endedAtMillis":2000}"""

    // ---------- header / metadata ----------

    @Test
    fun `opens valid session and exposes metadata`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("t", buildJsonObject { put("k", "v") })
        recorder.close()

        SessionReader.open(tempDir.resolve("${recorder.sessionId}.trace.jsonl")).use { reader ->
            assertEquals(recorder.sessionId, reader.sessionId)
            assertEquals(CURRENT_SCHEMA_VERSION, reader.schemaVersion)
            assertEquals(SessionSource.RECORDING, reader.source)
            assertNull(reader.replayOf)
        }
    }

    @Test
    fun `replay metadata surfaced`() {
        val recorder = SessionRecorder.create(tempDir, replayOf = "orig")
        recorder.close()
        SessionReader.open(tempDir.resolve("${recorder.sessionId}.trace.jsonl")).use { reader ->
            assertEquals(SessionSource.REPLAY, reader.source)
            assertEquals("orig", reader.replayOf)
        }
    }

    @Test
    fun `unknown JSON fields ignored`() {
        val file = session(
            """{"recordType":"session_start","schemaVersion":1,"id":"t","startedAtMillis":1000,"source":"RECORDING","extra":"x"}""",
            """{"recordType":"event","seq":0,"timestampNanos":100,"type":"e","payload":{},"more":123}""",
            END
        )
        SessionReader.open(file).use { reader ->
            assertEquals(1, reader.events().toList().size)
        }
    }

    // ---------- VALID sessions ----------

    @Test
    fun `empty session - start then end, zero events`() {
        val file = session(START, END)
        SessionReader.open(file).use { reader ->
            assertEquals(0, reader.events().toList().size)
            assertTrue(reader.isComplete)
        }
    }

    @Test
    fun `one event`() {
        SessionReader.open(session(START, event(0), END)).use { reader ->
            val events = reader.events().toList()
            assertEquals(1, events.size)
            assertEquals(0L, events[0].seq)
            assertTrue(reader.isComplete)
        }
    }

    @Test
    fun `multiple events`() {
        SessionReader.open(session(START, event(0), event(1), event(2), END)).use { reader ->
            assertEquals(listOf(0L, 1L, 2L), reader.events().toList().map { it.seq })
        }
    }

    @Test
    fun `equal timestamps accepted`() {
        val file = session(
            START,
            event(0, ts = 100),
            event(1, ts = 100),
            event(2, ts = 200),
            END
        )
        SessionReader.open(file).use { reader ->
            assertEquals(listOf(100L, 100L, 200L), reader.events().toList().map { it.timestampNanos })
        }
    }

    // ---------- INVALID: sequence ----------

    @Test
    fun `sequence starting at 1 rejected`() {
        val file = session(START, event(1), event(2), END)
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    @Test
    fun `sequence gap rejected`() {
        val file = session(START, event(0), event(1), event(3), END)
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    @Test
    fun `duplicate sequence rejected`() {
        val file = session(START, event(0), event(0), END)
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    @Test
    fun `decreasing sequence rejected`() {
        val file = session(START, event(0), event(1), event(0), END)
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    // ---------- INVALID: timestamps ----------

    @Test
    fun `negative timestamp rejected`() {
        val file = session(
            START,
            """{"recordType":"event","seq":0,"timestampNanos":-1,"type":"e","payload":{}}""",
            END
        )
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    @Test
    fun `decreasing timestamp rejected`() {
        val file = session(START, event(0, ts = 200), event(1, ts = 100), END)
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    // ---------- INVALID: schema ----------

    @Test
    fun `schema version 0 rejected`() {
        val file = session("""{"recordType":"session_start","schemaVersion":0,"id":"t","startedAtMillis":1,"source":"RECORDING"}""")
        val ex = assertThrows<UnsupportedSchemaVersionException> { SessionReader.open(file) }
        assertEquals(0, ex.version)
    }

    @Test
    fun `negative schema version rejected`() {
        val file = session("""{"recordType":"session_start","schemaVersion":-1,"id":"t","startedAtMillis":1,"source":"RECORDING"}""")
        assertThrows<UnsupportedSchemaVersionException> { SessionReader.open(file) }
    }

    @Test
    fun `future schema version rejected`() {
        val file = session("""{"recordType":"session_start","schemaVersion":999,"id":"t","startedAtMillis":1,"source":"RECORDING"}""")
        val ex = assertThrows<UnsupportedSchemaVersionException> { SessionReader.open(file) }
        assertEquals(999, ex.version)
    }

    // ---------- INVALID: session structure ----------

    @Test
    fun `empty file rejected`() {
        val file = tempDir.resolve("empty.trace.jsonl")
        Files.createFile(file)
        assertThrows<CorruptedSessionException> { SessionReader.open(file) }
    }

    @Test
    fun `missing session_start - first line is event`() {
        val file = session(event(0), END)
        assertThrows<CorruptedSessionException> { SessionReader.open(file) }
    }

    @Test
    fun `event before session_start rejected`() {
        // Same physical shape as "missing session_start": the first record is an event.
        val file = session(event(0), START, END)
        assertThrows<CorruptedSessionException> { SessionReader.open(file) }
    }

    @Test
    fun `second session_start rejected`() {
        val file = session(START, event(0), START, END)
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    @Test
    fun `duplicate session_end rejected`() {
        val file = session(START, event(0), END, END)
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    @Test
    fun `event after session_end rejected`() {
        val file = session(START, event(0), END, event(1))
        SessionReader.open(file).use { reader ->
            assertThrows<CorruptedSessionException> { reader.events().toList() }
        }
    }

    // ---------- INVALID: record type / JSON ----------

    @Test
    fun `unknown recordType throws UnsupportedRecordTypeException not MalformedRecordException`() {
        val file = session(START, """{"recordType":"heartbeat","x":1}""", END)
        SessionReader.open(file).use { reader ->
            val ex = assertThrows<UnsupportedRecordTypeException> { reader.events().toList() }
            assertEquals("heartbeat", ex.recordType)
        }
    }

    @Test
    fun `unknown recordType on first line throws UnsupportedRecordTypeException`() {
        val file = session("""{"recordType":"heartbeat","x":1}""")
        assertThrows<UnsupportedRecordTypeException> { SessionReader.open(file) }
    }

    @Test
    fun `malformed JSON in the middle throws MalformedRecordException with line number`() {
        val file = session(START, event(0), "this is not json", END)
        SessionReader.open(file).use { reader ->
            val ex = assertThrows<MalformedRecordException> { reader.events().toList() }
            assertEquals(3, ex.lineNumber)
        }
    }

    @Test
    fun `malformed JSON on first line throws MalformedRecordException`() {
        val file = session("not json at all")
        assertThrows<MalformedRecordException> { SessionReader.open(file) }
    }

    @Test
    fun `record that is a JSON array not object is malformed`() {
        val file = session(START, """["not","an","object"]""", END)
        SessionReader.open(file).use { reader ->
            assertThrows<MalformedRecordException> { reader.events().toList() }
        }
    }

    @Test
    fun `record missing recordType is malformed`() {
        val file = session(START, """{"seq":0,"timestampNanos":0,"type":"e","payload":{}}""", END)
        SessionReader.open(file).use { reader ->
            assertThrows<MalformedRecordException> { reader.events().toList() }
        }
    }

    @Test
    fun `blank line is rejected as malformed`() {
        val file = session(START, event(0), "", END)
        SessionReader.open(file).use { reader ->
            val ex = assertThrows<MalformedRecordException> { reader.events().toList() }
            assertEquals(3, ex.lineNumber)
        }
    }

    // ---------- RECOVERY / truncation ----------

    @Test
    fun `missing session_end is a valid incomplete session`() {
        val file = session(START, event(0), event(1))
        SessionReader.open(file).use { reader ->
            assertEquals(2, reader.events().toList().size)
            assertEquals(false, reader.isComplete)
        }
    }

    @Test
    fun `truncated final record throws MalformedRecordException not silent EOF`() {
        val file = tempDir.resolve("trunc.trace.jsonl")
        Files.write(
            file,
            (START + "\n" + event(0) + "\n" + """{"recordType":"event","seq":1,"timestampNanos":20,"type":"e","pay""")
                .toByteArray(Charsets.UTF_8)
        )
        SessionReader.open(file).use { reader ->
            assertThrows<MalformedRecordException> { reader.events().toList() }
        }
    }

    @Test
    fun `final line truncated in the middle of a multi-byte UTF-8 char throws MalformedRecordException`() {
        val recorder = SessionRecorder.create(tempDir)
        repeat(10) { recorder.record("e", buildJsonObject { put("s", "value-$it-é中🚀") }) }
        recorder.close()
        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val full = Files.readAllBytes(file)

        // Walk back from EOF to find an offset that lands inside a multi-byte sequence.
        var cut = full.size - 1
        while (cut > 1 && (full[cut].toInt() and 0xC0) != 0x80) cut--
        val truncated = tempDir.resolve("mb.trace.jsonl")
        Files.write(truncated, full.copyOfRange(0, cut))

        SessionReader.open(truncated).use { reader ->
            assertThrows<MalformedRecordException> { reader.events().toList() }
        }
    }

    @Test
    fun `truncated session_end throws MalformedRecordException`() {
        val file = tempDir.resolve("trunc2.trace.jsonl")
        Files.write(
            file,
            (START + "\n" + event(0) + "\n" + """{"recordType":"session_end","eventCoun""")
                .toByteArray(Charsets.UTF_8)
        )
        SessionReader.open(file).use { reader ->
            assertThrows<MalformedRecordException> { reader.events().toList() }
        }
    }

    @Test
    fun `complete vs incomplete are distinguishable via isComplete`() {
        val complete = session(START, event(0), END)
        val incomplete = session(START, event(0))

        SessionReader.open(complete).use { r ->
            r.events().toList()
            assertTrue(r.isComplete)
        }
        SessionReader.open(incomplete).use { r ->
            r.events().toList()
            assertTrue(!r.isComplete)
        }
    }

    // ---------- lifecycle ----------

    @Test
    fun `second events call throws`() {
        SessionReader.open(session(START, event(0), event(1), END)).use { reader ->
            assertEquals(2, reader.events().toList().size)
            assertThrows<IllegalStateException> { reader.events().toList() }
        }
    }

    @Test
    fun `events after close throws`() {
        val reader = SessionReader.open(session(START, event(0), END))
        reader.close()
        assertThrows<IllegalStateException> { reader.events().toList() }
    }

    @Test
    fun `close is idempotent`() {
        val reader = SessionReader.open(session(START, event(0), END))
        reader.close()
        reader.close()
        reader.close()
    }

    @Test
    fun `partially consumed sequence - take does not advance isComplete and file handle still closes`() {
        val lines = buildList {
            add(START)
            repeat(20) { add(event(it.toLong())) }
            add("""{"recordType":"session_end","eventCount":20,"endedAtMillis":2}""")
        }
        val file = session(*lines.toTypedArray())

        SessionReader.open(file).use { reader ->
            val firstFive = reader.events().take(5).toList()
            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), firstFive.map { it.seq })
            // Not fully consumed => completeness not yet established.
            assertTrue(!reader.isComplete)
            // Sequence is single-use even when only partially drained.
            assertThrows<IllegalStateException> { reader.events().toList() }
        }
    }

    @Test
    fun `open does not leak the file handle on a bad header`() {
        // If open() leaked the descriptor, re-writing the file would fail on Windows.
        val file = session("not json")
        assertThrows<MalformedRecordException> { SessionReader.open(file) }
        Files.write(file, "overwritten\n".toByteArray(Charsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING)
        assertEquals("overwritten", Files.readAllLines(file).single())
    }
}
