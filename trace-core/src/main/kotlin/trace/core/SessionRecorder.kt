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
 * Thread-safe: multiple threads may call [record] concurrently. For every event
 * the closed-state check, sequence assignment, timestamp calculation,
 * serialization and the write of the line (payload + newline + flush) happen
 * atomically under a single lock, so the resulting file always contains events
 * in physical order `0, 1, 2, ... N` with monotonically non-decreasing
 * timestamps, regardless of how many threads are recording.
 */
class SessionRecorder private constructor(
    private val writer: BufferedWriter,
    private val startNanos: Long,
    val sessionId: String
) : AutoCloseable {

    private val writeLock = Any()

    /** Number of events recorded so far. Guarded by [writeLock]. */
    private var sequenceNumber = 0L

    /** Whether [close] has completed. Guarded by [writeLock]. */
    private var closed = false

    /**
     * Records an event to the session file.
     *
     * @param type Event type identifier. Must be non-empty.
     *             Recommended convention: {vendor}.{category}.{name}
     * @param payload Arbitrary JSON data associated with the event.
     * @throws IllegalArgumentException if [type] is empty.
     * @throws IllegalStateException if the recorder has been closed. A [record]
     *         call that loses a race with [close] fails with this exception
     *         and never with a raw `IOException` from writing to a closed stream.
     */
    fun record(type: String, payload: JsonObject) {
        require(type.isNotEmpty()) { "Event type must not be empty" }

        synchronized(writeLock) {
            check(!closed) { "SessionRecorder has been closed" }

            val seq = sequenceNumber
            val timestampNanos = System.nanoTime() - startNanos

            val line = json.encodeToString(
                EventRecord(
                    seq = seq,
                    timestampNanos = timestampNanos,
                    type = type,
                    payload = payload
                )
            )

            writer.write(line)
            writer.write("\n")
            writer.flush()

            sequenceNumber = seq + 1
        }
    }

    /**
     * Closes the session, writing the `session_end` record, flushing and closing
     * the underlying writer. Idempotent: calls after the first are no-ops.
     */
    override fun close() {
        synchronized(writeLock) {
            if (closed) return
            closed = true

            val endRecord = SessionEnd(
                eventCount = sequenceNumber,
                endedAtMillis = System.currentTimeMillis()
            )

            writer.write(json.encodeToString(endRecord))
            writer.write("\n")
            writer.flush()
            writer.close()
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        /**
         * Creates a new session recorder.
         *
         * @param directory Directory where the session file will be created.
         *                   Created if it does not exist.
         * @param replayOf If this session is a replay, the ID of the original
         *                  session; the session `source` becomes `REPLAY`.
         *                  When `null`, `source` is `RECORDING`.
         * @return A new SessionRecorder ready for recording.
         */
        fun create(directory: Path, replayOf: String? = null): SessionRecorder {
            val sessionId = UUID.randomUUID().toString()
            val source = if (replayOf != null) SessionSource.REPLAY else SessionSource.RECORDING

            Files.createDirectories(directory)
            val filePath = directory.resolve("$sessionId.trace.jsonl")

            val writer = Files.newBufferedWriter(
                filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )

            val startMillis = System.currentTimeMillis()
            val startNanos = System.nanoTime()

            val sessionStart = SessionStart(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                id = sessionId,
                startedAtMillis = startMillis,
                source = source,
                replayOf = replayOf
            )

            try {
                writer.write(json.encodeToString(sessionStart))
                writer.write("\n")
                writer.flush()
            } catch (e: Throwable) {
                writer.close()
                throw e
            }

            return SessionRecorder(
                writer = writer,
                startNanos = startNanos,
                sessionId = sessionId
            )
        }
    }
}
