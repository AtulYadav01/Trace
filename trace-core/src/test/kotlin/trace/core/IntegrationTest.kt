package trace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `full round-trip - record, close, open, read, verify`() {
        val recorder = SessionRecorder.create(tempDir)
        val id = recorder.sessionId
        val events = listOf(
            "app.lifecycle.start" to buildJsonObject { put("version", "1.0.0") },
            "app.ui.tap" to buildJsonObject { put("x", 120); put("y", 340) },
            "app.network.request" to buildJsonObject { put("method", "POST"); put("url", "/api") },
            "app.lifecycle.pause" to buildJsonObject { }
        )
        events.forEach { (t, p) -> recorder.record(t, p) }
        recorder.close()

        SessionReader.open(tempDir.resolve("$id.trace.jsonl")).use { reader ->
            assertEquals(id, reader.sessionId)
            assertEquals(CURRENT_SCHEMA_VERSION, reader.schemaVersion)
            assertEquals(SessionSource.RECORDING, reader.source)

            val read = reader.events().toList()
            assertEquals(events.size, read.size)
            read.forEachIndexed { i, e ->
                assertEquals(i.toLong(), e.seq)
                assertEquals(events[i].first, e.type)
                assertEquals(events[i].second, e.payload)
                assertTrue(e.timestampNanos >= 0)
            }
            assertTrue(reader.isComplete)
        }
    }

    @Test
    fun `replay session links to original which stays intact`() {
        val original = SessionRecorder.create(tempDir)
        original.record("e", buildJsonObject { put("d", "original") })
        original.close()

        val replay = SessionRecorder.create(tempDir, replayOf = original.sessionId)
        replay.record("e", buildJsonObject { put("d", "replay") })
        replay.close()

        SessionReader.open(tempDir.resolve("${replay.sessionId}.trace.jsonl")).use { r ->
            assertEquals(SessionSource.REPLAY, r.source)
            assertEquals(original.sessionId, r.replayOf)
        }
        SessionReader.open(tempDir.resolve("${original.sessionId}.trace.jsonl")).use { r ->
            assertEquals(SessionSource.RECORDING, r.source)
            assertEquals(null, r.replayOf)
            assertEquals(1, r.events().toList().size)
        }
    }

    @Test
    fun `multiple independent sessions in one directory`() {
        val ids = (0 until 5).map { i ->
            val rec = SessionRecorder.create(tempDir)
            repeat(10) { j -> rec.record("s$i", buildJsonObject { put("j", j) }) }
            rec.close()
            rec.sessionId
        }
        ids.forEachIndexed { i, id ->
            SessionReader.open(tempDir.resolve("$id.trace.jsonl")).use { r ->
                val e = r.events().toList()
                assertEquals(10, e.size)
                assertTrue(e.all { it.type == "s$i" })
            }
        }
    }

    @Test
    fun `payload with UTF-8 emoji and CJK round-trips exactly`() {
        val payload = buildJsonObject {
            put("emoji", "🚀🔥😀")
            put("cjk", "日本語テスト 中文测试 한국어")
            put("mixed", "café — naïve — Ω≈ç√∫")
        }
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("i18n", payload)
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val raw = Files.readAllBytes(file).toString(Charsets.UTF_8)
        assertTrue(raw.contains("🚀🔥😀"))
        assertTrue(raw.contains("日本語テスト"))

        SessionReader.open(file).use { reader ->
            assertEquals(payload, reader.events().first().payload)
        }
    }

    @Test
    fun `payload containing newlines and control characters stays single-line JSONL`() {
        val payload = buildJsonObject {
            put("multiline", "line1\nline2\r\nline3")
            put("tab", "a\tb")
            put("control", "bell sep end")
            put("quote", "he said \"hi\" and \\ escaped")
        }
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("weird", payload)
        recorder.record("after", buildJsonObject { put("ok", true) })
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")
        val physicalLines = Files.readAllBytes(file).toString(Charsets.UTF_8)
            .split("\n").filter { it.isNotEmpty() }
        // start + 2 events + end == 4 physical lines; embedded newlines must be escaped.
        assertEquals(4, physicalLines.size)
        physicalLines.forEach { Json.parseToJsonElement(it) } // each line is valid JSON

        SessionReader.open(file).use { reader ->
            val read = reader.events().toList()
            assertEquals(2, read.size)
            assertEquals(payload, read[0].payload)
        }
    }

    @Test
    fun `physical file format matches the frozen v0_1 shape`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("app.ui.tap", buildJsonObject { put("x", 120); put("y", 340) })
        recorder.close()

        val lines = Files.readAllLines(tempDir.resolve("${recorder.sessionId}.trace.jsonl"))
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("""{"recordType":"session_start","schemaVersion":1,"""))
        assertTrue(lines[0].contains(""""source":"RECORDING""""))
        assertTrue(lines[0].contains(""""replayOf":null"""))
        assertTrue(lines[1].startsWith("""{"recordType":"event","seq":0,"""))
        assertTrue(lines[1].contains(""""type":"app.ui.tap","payload":{"x":120,"y":340}}"""))
        assertTrue(lines[2].startsWith("""{"recordType":"session_end","eventCount":1,"""))
        lines.forEach { Json.parseToJsonElement(it) }
    }
}
