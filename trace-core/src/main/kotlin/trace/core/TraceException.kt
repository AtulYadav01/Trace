package trace.core

/**
 * Base exception for all TRACE-related errors.
 */
sealed class TraceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when attempting to read a session file with a schema version
 * newer than the current library supports.
 */
class UnsupportedSchemaVersionException(
    val version: Int
) : TraceException("Unsupported schema version: $version (current: $CURRENT_SCHEMA_VERSION)")

/**
 * Thrown when encountering an unknown recordType in a session file.
 */
class UnsupportedRecordTypeException(
    val recordType: String
) : TraceException("Unsupported record type: $recordType")

/**
 * Thrown when a line in the session file contains malformed JSON.
 */
class MalformedRecordException(
    val lineNumber: Int,
    override val cause: Throwable
) : TraceException("Malformed record at line $lineNumber: ${cause.message}", cause)

/**
 * Thrown when session data violates integrity constraints.
 * This indicates data corruption or tampering.
 */
class CorruptedSessionException(
    reason: String
) : TraceException("Corrupted session: $reason")
