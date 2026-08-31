package trace.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Sealed hierarchy representing all record types in a TRACE session file.
 * Each line in a .trace.jsonl file deserializes to one of these types.
 */
@Serializable
sealed class TraceRecord {
    abstract val recordType: String
}

/**
 * First record in every session file. Contains session metadata.
 */
@Serializable
@SerialName("session_start")
data class SessionStart(
    override val recordType: String = "session_start",
    val schemaVersion: Int,
    val id: String,
    val startedAtMillis: Long,
    val source: SessionSource,
    val replayOf: String? = null
) : TraceRecord()

/**
 * Represents a single event recorded during the session.
 */
@Serializable
@SerialName("event")
data class EventRecord(
    override val recordType: String = "event",
    val seq: Long,
    val timestampNanos: Long,
    val type: String,
    val payload: JsonObject
) : TraceRecord()

/**
 * Final record in a completed session. Missing if session crashed.
 */
@Serializable
@SerialName("session_end")
data class SessionEnd(
    override val recordType: String = "session_end",
    val eventCount: Long,
    val endedAtMillis: Long
) : TraceRecord()

/**
 * Indicates whether a session is an original recording or a replay.
 */
@Serializable
enum class SessionSource {
    @SerialName("RECORDING")
    RECORDING,

    @SerialName("REPLAY")
    REPLAY
}

/**
 * Current schema version for TRACE session files.
 */
const val CURRENT_SCHEMA_VERSION = 1
