package trace.core

import kotlinx.serialization.SerializationException

/**
 * Base exception for all TRACE-related errors.
 *
 * Consumers can catch this single type to handle any failure originating from
 * reading or writing a session file.
 */
sealed class TraceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when attempting to read a session file whose schema version is not
 * supported by this library.
 *
 * Only [CURRENT_SCHEMA_VERSION] is supported: both older (`< 1`) and newer
 * (`> CURRENT_SCHEMA_VERSION`) versions are rejected.
 */
class UnsupportedSchemaVersionException(
    val version: Int
) : TraceException("Unsupported schema version: $version (supported: $CURRENT_SCHEMA_VERSION)")

/**
 * Thrown when a session file contains a syntactically valid JSON record whose
 * `recordType` is not one this library understands.
 *
 * This is distinct from [MalformedRecordException], which indicates the bytes
 * were not valid JSON at all.
 */
class UnsupportedRecordTypeException(
    val recordType: String
) : TraceException("Unsupported record type: $recordType")

/**
 * Thrown when a line in the session file is not a valid JSON record — malformed
 * syntax, a truncated final line, a blank line, or a record that is not a JSON
 * object / is missing its `recordType` discriminator.
 */
class MalformedRecordException : TraceException {
    val lineNumber: Int

    constructor(lineNumber: Int, cause: Throwable) : super(
        "Malformed record at line $lineNumber: ${cause.message}", cause
    ) {
        this.lineNumber = lineNumber
    }

    constructor(lineNumber: Int, reason: String) : super(
        "Malformed record at line $lineNumber: $reason", SerializationException(reason)
    ) {
        this.lineNumber = lineNumber
    }
}

/**
 * Thrown when a session file is structurally intact JSON but violates TRACE
 * integrity constraints: missing/duplicate `session_start`, records after
 * `session_end`, duplicate `session_end`, non-continuous `seq`, or
 * negative / decreasing timestamps.
 */
class CorruptedSessionException(
    reason: String
) : TraceException("Corrupted session: $reason")
