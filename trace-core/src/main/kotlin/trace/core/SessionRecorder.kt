package trace.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Records events to a TRACE session file.
 *
 * Usage:
 * ```
 * SessionRecorder.create(outputDir).use { recorder ->
 *     recorder.record("app.ui.tap", buildJsonObject { put("x", 100) })
 *     recorder.record("app.ui.tap", buildJsonObject { put("x", 200) })
 * }
 * ```
 *
 * Thread-safe: multiple threads can call [record] concurrently.
 */
class SessionRecorder private constructor(
    private val writer: BufferedWriter,
    private val startNanos: Long,
    private val startMillis: Long,
    val sessionId: String,
    private val source: SessionSource,
    private val replayOf: String?
) : AutoCloseable {

    private val json = Json {
        encodeDefaults = true
    }

    private val sequenceNumber = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    /**
     * Records an event to the session file.
     *
     * @param type Event type identifier. Must be non-empty.
     *             Recommended convention: {vendor}.{category}.{name}
     * @param payload Arbitrary JSON data associated with the event.
     * @throws IllegalArgumentException if type is empty
     * @throws IllegalStateException if the recorder has been closed
     */
    fun record(type: String, payload: JsonObject) {
        require(type.isNotEmpty()) { "Event type must not be empty" }
        check(!closed.get()) { "SessionRecorder has been closed" }

        val seq = sequenceNumber.getAndIncrement()
        val timestampNanos = System.nanoTime() - startNanos

        val record = EventRecord(
            seq = seq,
            timestampNanos = timestampNanos,
            type = type,
            payload = payload
        )

        writeLine(json.encodeToString(record))
    }

    /**
     * Closes the session, writing the session_end record.
     * Idempotent: multiple calls have no effect after the first.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return // Already closed
        }

        val endRecord = SessionEnd(
            eventCount = sequenceNumber.get(),
            endedAtMillis = System.currentTimeMillis()
        )

        synchronized(writeLock) {
            writer.write(json.encodeToString(endRecord))
            writer.write("\n")
            writer.flush()
            writer.close()
        }
    }

    private fun writeLine(line: String) {
        synchronized(writeLock) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    companion object {
        /**
         * Creates a new session recorder.
         *
         * @param directory Directory where the session file will be created.
         * @param replayOf If this session is a replay, the ID of the original session.
         * @return A new SessionRecorder ready for recording.
         */
        fun create(directory: Path, replayOf: String? = null): SessionRecorder {
            val sessionId = UUID.randomUUID().toString()
            val filePath = directory.resolve("$sessionId.trace.jsonl")
            val source = if (replayOf != null) SessionSource.REPLAY else SessionSource.RECORDING

            Files.createDirectories(directory)

            val writer = Files.newBufferedWriter(
                filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )

            val startMillis = System.currentTimeMillis()
            val startNanos = System.nanoTime()

            val json = Json { encodeDefaults = true }

            val sessionStart = SessionStart(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                id = sessionId,
                startedAtMillis = startMillis,
                source = source,
                replayOf = replayOf
            )

            writer.write(json.encodeToString(sessionStart))
            writer.write("\n")
            writer.flush()

            return SessionRecorder(
                writer = writer,
                startNanos = startNanos,
                startMillis = startMillis,
                sessionId = sessionId,
                source = source,
                replayOf = replayOf
            )
        }
    }
}
