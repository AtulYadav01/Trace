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

    private fun fileFor(recorder: SessionRecorder): Path =
        tempDir.resolve("${recorder.sessionId}.trace.jsonl")

    @Test
    fun `create writes session_start as first record`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.close()

        val lines = Files.readAllLines(fileFor(recorder))
        val first = json.decodeFromString<TraceRecord>(lines[0])
        assertTrue(first is SessionStart)
        assertEquals(recorder.sessionId, (first as SessionStart).id)
        assertEquals(CURRENT_SCHEMA_VERSION, first.schemaVersion)
        assertEquals(SessionSource.RECORDING, first.source)
    }

    @Test
    fun `file name is uuid dot trace dot jsonl`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.close()
        val file = fileFor(recorder)
        assertTrue(Files.exists(file))
        assertTrue(file.fileName.toString() == "${recorder.sessionId}.trace.jsonl")
        assertNotNull(java.util.UUID.fromString(recorder.sessionId))
    }

    @Test
    fun `events get sequential seq starting at 0`() {
        val recorder = SessionRecorder.create(tempDir)
        repeat(4) { recorder.record("t", buildJsonObject { put("i", it) }) }
        recorder.close()

        val lines = Files.readAllLines(fileFor(recorder))
        assertEquals(6, lines.size) // start + 4 events + end
        val seqs = lines.subList(1, 5).map { json.decodeFromString<EventRecord>(it).seq }
        assertEquals(listOf(0L, 1L, 2L, 3L), seqs)
    }

    @Test
    fun `timestamps are non-decreasing`() {
        val recorder = SessionRecorder.create(tempDir)
        repeat(50) { recorder.record("t", JsonObject(emptyMap())) }
        recorder.close()

        val lines = Files.readAllLines(fileFor(recorder))
        var last = -1L
        lines.subList(1, lines.size - 1).forEach {
            val ts = json.decodeFromString<EventRecord>(it).timestampNanos
            assertTrue(ts >= 0 && ts >= last, "timestamp $ts < previous $last")
            last = ts
        }
    }

    @Test
    fun `close writes session_end with event count`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("t", JsonObject(emptyMap()))
        recorder.record("t", JsonObject(emptyMap()))
        recorder.close()

        val lines = Files.readAllLines(fileFor(recorder))
        val last = json.decodeFromString<TraceRecord>(lines.last())
        assertTrue(last is SessionEnd)
        assertEquals(2L, (last as SessionEnd).eventCount)
    }

    @Test
    fun `close is idempotent across many calls`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("t", JsonObject(emptyMap()))
        repeat(5) { recorder.close() }

        val lines = Files.readAllLines(fileFor(recorder))
        assertEquals(1, lines.count { it.contains("\"recordType\":\"session_end\"") })
    }

    @Test
    fun `record after close throws IllegalStateException`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.close()
        assertThrows<IllegalStateException> {
            recorder.record("t", JsonObject(emptyMap()))
        }
    }

    @Test
    fun `empty type is rejected`() {
        val recorder = SessionRecorder.create(tempDir)
        assertThrows<IllegalArgumentException> { recorder.record("", JsonObject(emptyMap())) }
        recorder.close()
    }

    @Test
    fun `replay session records source and replayOf`() {
        val recorder = SessionRecorder.create(tempDir, replayOf = "orig-123")
        recorder.close()
        val start = json.decodeFromString<SessionStart>(Files.readAllLines(fileFor(recorder))[0])
        assertEquals(SessionSource.REPLAY, start.source)
        assertEquals("orig-123", start.replayOf)
    }

    @Test
    fun `recording session has RECORDING source and null replayOf`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.close()
        val start = json.decodeFromString<SessionStart>(Files.readAllLines(fileFor(recorder))[0])
        assertEquals(SessionSource.RECORDING, start.source)
        assertEquals(null, start.replayOf)
    }

    @Test
    fun `lines are LF terminated not CRLF`() {
        val recorder = SessionRecorder.create(tempDir)
        recorder.record("t", buildJsonObject { put("x", 1) })
        recorder.close()

        val raw = Files.readAllBytes(fileFor(recorder)).toString(Charsets.UTF_8)
        assertTrue(raw.endsWith("\n"))
        assertTrue(!raw.contains("\r"), "file must not contain CR")
        assertEquals(3, raw.split("\n").filter { it.isNotEmpty() }.size)
    }

    @Test
    fun `concurrent record then reader round-trip yields exact sequence 0 to N-1`() {
        // Regression test for the seq/timestamp-outside-the-lock bug.
        // ONE recorder, many threads, then read the SAME file with SessionReader.
        // MUST NOT sort. This fails on the old implementation.
        val threadCount = 8
        val perThread = 2000
        val total = threadCount * perThread

        val recorder = SessionRecorder.create(tempDir)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val threads = (0 until threadCount).map { t ->
            Thread {
                start.await()
                repeat(perThread) { e ->
                    recorder.record("thread.$t", buildJsonObject { put("e", e) })
                }
                done.countDown()
            }.also { it.start() }
        }
        start.countDown()
        done.await()
        threads.forEach { it.join() }
        recorder.close()

        SessionReader.open(fileFor(recorder)).use { reader ->
            var expected = 0L
            reader.events().forEach { event ->
                assertEquals(expected, event.seq, "seq out of order in file")
                expected++
            }
            assertEquals(total.toLong(), expected)
            assertTrue(reader.isComplete)
        }
    }

    @Test
    fun `record racing close never throws raw IOException and leaves a valid file`() {
        repeat(60) { iteration ->
            val dir = tempDir.resolve("rc$iteration")
            Files.createDirectories(dir)
            val recorder = SessionRecorder.create(dir)

            var sawWrongException: Throwable? = null
            val writer = Thread {
                repeat(200) { e ->
                    try {
                        recorder.record("t", buildJsonObject { put("e", e) })
                    } catch (ok: IllegalStateException) {
                        // expected once close() has won the race
                    } catch (bad: Throwable) {
                        sawWrongException = bad
                    }
                }
            }
            writer.start()
            recorder.close()
            writer.join()

            assertTrue(
                sawWrongException == null,
                "record() leaked ${sawWrongException?.let { it::class.simpleName }}: ${sawWrongException?.message}"
            )

            // Whatever interleaving happened, the file must be readable and continuous.
            val file = dir.resolve("${recorder.sessionId}.trace.jsonl")
            SessionReader.open(file).use { reader ->
                var expected = 0L
                reader.events().forEach { assertEquals(expected++, it.seq) }
                assertTrue(reader.isComplete)
            }
        }
    }

    @Test
    fun `100k events write then stream read`() {
        val n = 100_000
        val recorder = SessionRecorder.create(tempDir)
        repeat(n) { i -> recorder.record("load", buildJsonObject { put("i", i) }) }
        recorder.close()

        SessionReader.open(fileFor(recorder)).use { reader ->
            var count = 0L
            reader.events().forEach { assertEquals(count++, it.seq) }
            assertEquals(n.toLong(), count)
            assertTrue(reader.isComplete)
        }
    }
}
