package trace.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.BufferedReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads events from a TRACE session file.
 *
 * Usage:
 * ```
 * SessionReader.open(sessionFile).use { reader ->
 *     reader.events().forEach { event ->
 *         println("${event.type}: ${event.payload}")
 *     }
 *     println(if (reader.isComplete) "session ended cleanly" else "session was truncated")
 * }
 * ```
 *
 * Events are streamed lazily via [Sequence], reading and validating one line at
 * a time, so arbitrarily large session files can be processed without loading
 * them into memory.
 *
 * ### Lifecycle
 * - [events] is **single-use**. It is bound to the underlying file cursor; a
 *   second call throws [IllegalStateException] rather than silently returning
 *   nothing. The sequence is not restartable and is not buffered.
 * - The returned sequence must be consumed before [close]. Closing the reader
 *   ends any in-progress iteration.
 * - [close] is idempotent.
 */
class SessionReader private constructor(
    private val reader: BufferedReader,
    sessionStart: SessionStart
) : AutoCloseable {

    val sessionId: String = sessionStart.id
    val schemaVersion: Int = sessionStart.schemaVersion
    val source: SessionSource = sessionStart.source
    val replayOf: String? = sessionStart.replayOf

    private val eventsConsumed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var complete = false

    /**
     * Whether the session ended with a `session_end` record.
     *
     * - `true`  — a `session_end` record was reached: the session is complete.
     * - `false` — the stream ended at a clean EOF with no `session_end`: the
     *   session is a valid but incomplete/partial recording (e.g. the recording
     *   process crashed).
     *
     * Only meaningful once the sequence returned by [events] has been fully
     * consumed; before then it reflects how far iteration has progressed.
     * A malformed or truncated final line does not reach EOF cleanly — it
     * throws [MalformedRecordException] — so it is never reported as either
     * complete or a clean incomplete session.
     */
    val isComplete: Boolean
        get() = complete

    /**
     * Returns a lazy, single-use sequence of events from the session.
     *
     * Each line is read, parsed and validated on demand as the sequence is
     * consumed. Iteration stops at `session_end` or at a clean EOF.
     *
     * @throws IllegalStateException if called more than once, or after [close].
     * @throws CorruptedSessionException if `seq` is not continuous from 0, a
     *         timestamp is negative or decreasing, a second `session_start`
     *         appears, a `session_end` is duplicated, or any record appears
     *         after `session_end`.
     * @throws MalformedRecordException if a line is not a valid JSON object,
     *         is blank, is missing its `recordType`, or is a truncated final line.
     * @throws UnsupportedRecordTypeException if a line is valid JSON with an
     *         unknown `recordType`.
     */
    fun events(): Sequence<Event> {
        check(!closed.get()) { "SessionReader has been closed" }
        check(eventsConsumed.compareAndSet(false, true)) {
            "events() has already been consumed; the sequence is single-use"
        }

        return sequence {
            var lineNumber = 1 // session_start was physical line 1
            var expectedSeq = 0L
            var lastTimestamp = 0L
            var sawSessionEnd = false

            // Drive the line iterator manually so that a truncated final line
            // that splits a multi-byte UTF-8 character (which makes the decoder
            // throw CharacterCodingException) surfaces as MalformedRecordException
            // rather than a raw IOException.
            val lines = reader.lineSequence().iterator()
            while (true) {
                val hasNext = try {
                    lines.hasNext()
                } catch (e: CharacterCodingException) {
                    throw MalformedRecordException(lineNumber + 1, e)
                }
                if (!hasNext) break
                val line = try {
                    lines.next()
                } catch (e: CharacterCodingException) {
                    throw MalformedRecordException(lineNumber + 1, e)
                }
                lineNumber++

                if (line.isBlank()) {
                    throw MalformedRecordException(lineNumber, "unexpected blank line")
                }

                val obj = parseObject(line, lineNumber)
                when (val recordType = discriminator(obj, lineNumber)) {
                    "event" -> {
                        if (sawSessionEnd) {
                            throw CorruptedSessionException(
                                "event record at line $lineNumber appears after session_end"
                            )
                        }
                        val record = decodeEvent(obj, lineNumber)

                        if (record.seq != expectedSeq) {
                            throw CorruptedSessionException(
                                "expected seq $expectedSeq at line $lineNumber but found ${record.seq} " +
                                    "(seq must start at 0 and increment by exactly 1)"
                            )
                        }
                        if (record.timestampNanos < 0) {
                            throw CorruptedSessionException(
                                "event at line $lineNumber has negative timestamp: ${record.timestampNanos}"
                            )
                        }
                        if (record.timestampNanos < lastTimestamp) {
                            throw CorruptedSessionException(
                                "timestamp ${record.timestampNanos} at line $lineNumber is less than " +
                                    "previous timestamp $lastTimestamp"
                            )
                        }

                        expectedSeq++
                        lastTimestamp = record.timestampNanos

                        yield(
                            Event(
                                seq = record.seq,
                                timestampNanos = record.timestampNanos,
                                type = record.type,
                                payload = record.payload
                            )
                        )
                    }

                    "session_end" -> {
                        if (sawSessionEnd) {
                            throw CorruptedSessionException(
                                "duplicate session_end at line $lineNumber"
                            )
                        }
                        sawSessionEnd = true
                    }

                    "session_start" -> throw CorruptedSessionException(
                        "unexpected second session_start at line $lineNumber"
                    )

                    else -> throw UnsupportedRecordTypeException(recordType)
                }
            }

            complete = sawSessionEnd
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            reader.close()
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "recordType"
        }

        /**
         * Opens a session file for reading. Reads and validates only the
         * `session_start` header; events are read lazily via [events].
         *
         * @param file Path to the `.trace.jsonl` file.
         * @throws MalformedRecordException if the first line is not a valid JSON object.
         * @throws CorruptedSessionException if the file is empty or its first
         *         record is not `session_start`.
         * @throws UnsupportedRecordTypeException if the first line is valid JSON
         *         with an unknown `recordType`.
         * @throws UnsupportedSchemaVersionException if `schemaVersion` is not
         *         [CURRENT_SCHEMA_VERSION].
         */
        fun open(file: Path): SessionReader {
            val reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)

            val start: SessionStart = try {
                val firstLine = reader.readLine()
                    ?: throw CorruptedSessionException("session file is empty")

                val obj = parseObject(firstLine, 1)
                when (val recordType = discriminator(obj, 1)) {
                    "session_start" -> try {
                        json.decodeFromJsonElement<SessionStart>(obj)
                    } catch (e: SerializationException) {
                        throw MalformedRecordException(1, e)
                    }

                    "event", "session_end" -> throw CorruptedSessionException(
                        "first record must be session_start but found '$recordType'"
                    )

                    else -> throw UnsupportedRecordTypeException(recordType)
                }
            } catch (e: CharacterCodingException) {
                reader.close()
                throw MalformedRecordException(1, e)
            } catch (e: Throwable) {
                reader.close()
                throw e
            }

            if (start.schemaVersion < 1 || start.schemaVersion > CURRENT_SCHEMA_VERSION) {
                reader.close()
                throw UnsupportedSchemaVersionException(start.schemaVersion)
            }

            return SessionReader(reader, start)
        }

        private fun parseObject(line: String, lineNumber: Int): JsonObject {
            val element = try {
                json.parseToJsonElement(line)
            } catch (e: SerializationException) {
                throw MalformedRecordException(lineNumber, e)
            } catch (e: IllegalArgumentException) {
                throw MalformedRecordException(lineNumber, e)
            }
            return element as? JsonObject
                ?: throw MalformedRecordException(lineNumber, "record is not a JSON object")
        }

        private fun discriminator(obj: JsonObject, lineNumber: Int): String {
            val primitive = obj["recordType"] as? JsonPrimitive
                ?: throw MalformedRecordException(lineNumber, "missing 'recordType' discriminator")
            if (!primitive.isString) {
                throw MalformedRecordException(lineNumber, "'recordType' is not a string")
            }
            return primitive.content
        }

        private fun decodeEvent(obj: JsonObject, lineNumber: Int): EventRecord = try {
            json.decodeFromJsonElement<EventRecord>(obj)
        } catch (e: SerializationException) {
            throw MalformedRecordException(lineNumber, e)
        } catch (e: IllegalArgumentException) {
            throw MalformedRecordException(lineNumber, e)
        }
    }
}
