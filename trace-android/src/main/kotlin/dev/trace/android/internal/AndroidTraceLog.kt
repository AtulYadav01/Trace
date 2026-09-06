package dev.trace.android.internal

import android.util.Log

/**
 * Minimal internal logging. TRACE must never crash or spam the host app, so all
 * internal failures are logged at WARN and swallowed.
 */
internal object AndroidTraceLog {
    private const val TAG = "TraceAndroid"

    fun w(message: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
    }

    fun d(message: String) {
        Log.d(TAG, message)
    }
}
