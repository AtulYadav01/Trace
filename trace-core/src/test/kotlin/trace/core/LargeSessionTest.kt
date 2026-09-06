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
    fun `100k events stream read without materializing the whole file`() {
        val n = 100_000
        val recorder = SessionRecorder.create(tempDir)
        repeat(n) { i -> recorder.record("load", buildJsonObject { put("i", i); put("d", "row_$i") }) }
        recorder.close()

        val file = tempDir.resolve("${recorder.sessionId}.trace.jsonl")

        // Consume lazily; hold no references to prior events.
        SessionReader.open(file).use { reader ->
            var count = 0L
            var last = -1L
            reader.events().forEach { e ->
                assertEquals(count, e.seq)
                assertTrue(e.seq > last)
                last = e.seq
                count++
            }
            assertEquals(n.toLong(), count)
            assertTrue(reader.isComplete)
        }
    }

    @Test
    fun `take 5 stops before a later malformed record is parsed`() {
        // Build a file whose line 8 is broken; taking the first 5 events must succeed,
        // proving the reader does not scan ahead.
        val file = tempDir.resolve("lazy.trace.jsonl")
        val lines = buildList {
            add("""{"recordType":"session_start","schemaVersion":1,"id":"t","startedAtMillis":1,"source":"RECORDING"}""")
            repeat(6) { add("""{"recordType":"event","seq":$it,"timestampNanos":$it,"type":"e","payload":{}}""") }
            add("""}}} totally broken line {{{""")
            add("""{"recordType":"session_end","eventCount":6,"endedAtMillis":9}""")
        }
        Files.write(file, (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))

        SessionReader.open(file).use { reader ->
            val firstFive = reader.events().take(5).toList()
            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), firstFive.map { it.seq })
        }

        // And consuming past the broken line does raise it.
        SessionReader.open(file).use { reader ->
            org.junit.jupiter.api.assertThrows<MalformedRecordException> {
                reader.events().toList()
            }
        }
    }

    @Test
    fun `file size grows linearly with event count`() {
        val n = 10_000
        val recorder = SessionRecorder.create(tempDir)
        repeat(n) { i -> recorder.record("size", buildJsonObject { put("i", i); put("v", "test") }) }
        recorder.close()

        val bytes = Files.size(tempDir.resolve("${recorder.sessionId}.trace.jsonl"))
        assertTrue(bytes in (n * 50L)..(n * 150L + 1000), "unexpected file size: $bytes")
    }

    @Test
    fun `many concurrent recorders on separate sessions all read back cleanly`() {
        val threads = (0 until 5).map { t ->
            Thread {
                val rec = SessionRecorder.create(tempDir.resolve("t$t").also { Files.createDirectories(it) })
                repeat(1000) { i -> rec.record("p$t", buildJsonObject { put("i", i) }) }
                rec.close()
                SessionReader.open(
                    tempDir.resolve("t$t").resolve("${rec.sessionId}.trace.jsonl")
                ).use { reader ->
                    var expected = 0L
                    reader.events().forEach { assertEquals(expected++, it.seq) }
                    assertEquals(1000L, expected)
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
    }
}
