package dev.trace.demo

import android.app.Application
import android.util.Log
import dev.trace.android.TraceAndroid
import dev.trace.android.TraceConfig
import java.io.File

/**
 * Starts a TRACE session for the whole app from [onCreate], so the full
 * activity lifecycle is captured.
 *
 * The trace file is written to the app's external files directory so it can be
 * pulled without root:
 *
 * ```
 * adb shell run-as dev.trace.demo ls files/traces
 * adb pull /sdcard/Android/data/dev.trace.demo/files/traces/<uuid>.trace.jsonl
 * ```
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val dir = File(getExternalFilesDir(null) ?: filesDir, "traces")
        TraceAndroid.start(
            this,
            TraceConfig(
                outputDirectory = dir,
                captureLifecycle = true,
                captureInteractions = true,
                captureText = false, // privacy-safe default; the demo never records typed text
                captureExceptions = true,
            ),
        )
        Log.i("DemoApp", "TRACE session started: ${TraceAndroid.currentSessionFile}")
    }
}
