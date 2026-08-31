package trace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads events from a TRACE session file.
 *
 * Usage:
 * ```
 * SessionReader.open(sessionFile).use { reader ->
 *     reader.events().forEach { event ->
 *         println("${event.type}: ${event.payload}")
 *     }
 * }
 * ```
 *
 * Events are streamed lazily via [Sequence], allowing processing of large
 * session files without loading everything into memory.
 */
class SessionReader private constructor(
    private val reader: BufferedReader,
    private val sessionStart: SessionStart
) : AutoCloseable {

    val sessionId: String = sessionStart.id
    val schemaVersion: Int = sessionStart.schemaVersion
    val source: SessionSource = sessionStart.source
    val replayOf: String? = sessionStart.replayOf

    /**
     * Returns a lazy sequence of events from the session.
     *
     * The sequence reads events on-demand from the underlying file.
     * Events are validated as they are read.
     *
     * @throws CorruptedSessionException if events have invalid seq or timestamps
     * @throws MalformedRecordException if a line contains invalid JSON
     * @throws UnsupportedRecordTypeException if an unknown record type is encountered
     */
    fun events(): Sequence<Event> = sequence {
        var lineNumber = 1 // session_start was line 0
        var lastSeq = -1L
        var lastTimestamp = -1L

        reader.lineSequence().forEach { line ->
            lineNumber++

            if (line.isBlank()) return@forEach

            val record = try {
                json.decodeFromString<TraceRecord>(line)
            } catch (e: Exception) {
                throw MalformedRecordException(lineNumber, e)
            }

            when (record) {
                is EventRecord -> {
                    // Validate seq is strictly increasing
                    if (record.seq <= lastSeq) {
                        throw CorruptedSessionException(
                            "Event seq ${record.seq} at line $lineNumber is not greater than previous seq $lastSeq"
                        )
                    }

                    // Validate timestamp is non-negative
                    if (record.timestampNanos < 0) {
                        throw CorruptedSessionException(
                            "Event at line $lineNumber has negative timestamp: ${record.timestampNanos}"
                        )
                    }

                    // Validate timestamp is non-decreasing
                    if (record.timestampNanos < lastTimestamp) {
                        throw CorruptedSessionException(
                            "Event timestamp ${record.timestampNanos} at line $lineNumber is less than previous timestamp $lastTimestamp"
                        )
                    }

                    lastSeq = record.seq
                    lastTimestamp = record.timestampNanos

                    yield(Event(
                        seq = record.seq,
                        timestampNanos = record.timestampNanos,
                        type = record.type,
                        payload = record.payload
                    ))
                }

                is SessionEnd -> {
                    // End of session, stop iterating
                    return@sequence
                }

                is SessionStart -> {
                    throw CorruptedSessionException(
                        "Unexpected session_start at line $lineNumber"
                    )
                }
            }
        }
        // EOF without session_end is valid (crash recovery)
    }

    override fun close() {
        reader.close()
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "recordType"
        }

        /**
         * Opens a session file for reading.
         *
         * @param file Path to the .trace.jsonl file
         * @return A SessionReader for the file
         * @throws UnsupportedSchemaVersionException if schema version is unsupported
         * @throws MalformedRecordException if the first line is not valid JSON
         * @throws IllegalStateException if the first line is not a session_start
         */
        fun open(file: Path): SessionReader {
            val reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)

            val firstLine = reader.readLine()
                ?: throw IllegalStateException("Session file is empty")

            val firstRecord = try {
                json.decodeFromString<TraceRecord>(firstLine)
            } catch (e: Exception) {
                reader.close()
                throw MalformedRecordException(1, e)
            }

            if (firstRecord !is SessionStart) {
                reader.close()
                throw IllegalStateException(
                    "First record must be session_start, got: ${firstRecord.recordType}"
                )
            }

            if (firstRecord.schemaVersion > CURRENT_SCHEMA_VERSION) {
                reader.close()
                throw UnsupportedSchemaVersionException(firstRecord.schemaVersion)
            }

            return SessionReader(reader, firstRecord)
        }
    }
}
