package dev.trace.android.internal

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Builds TRACE event type strings and payloads for the `android.*` taxonomy.
 *
 * The `android.*` namespace and the payload shapes are owned by trace-android and
 * versioned by [TAXONOMY_VERSION]. trace-core is unaware of any of this.
 *
 * Every method here is deterministic given its inputs. Methods that take only
 * primitives are pure JVM and unit-testable without Android.
 */
internal object EventFactory {

    /** Version of the `android.*` event vocabulary and payload shapes. */
    const val TAXONOMY_VERSION = 1

    /** Reported in `android.app.start` so consumers can pin the adapter version. */
    const val TRACE_ANDROID_VERSION = "0.2.0"

    private const val MAX_STACK_CHARS = 8_000
    private const val MAX_STACK_FRAMES = 100

    fun appStart(application: Application): TraceEvent {
        val pkg = application.packageName
        val info: PackageInfo? = try {
            application.packageManager.getPackageInfo(pkg, 0)
        } catch (t: Throwable) {
            null
        }
        return TraceEvent(
            TYPE_APP_START,
            buildJsonObject {
                put("package", pkg)
                info?.versionName?.let { put("versionName", it) }
                info?.let { put("versionCode", legacyVersionCode(it)) }
                put("osApiLevel", Build.VERSION.SDK_INT)
                put("deviceModel", Build.MODEL ?: "unknown")
                put("deviceManufacturer", Build.MANUFACTURER ?: "unknown")
                put("traceAndroidVersion", TRACE_ANDROID_VERSION)
                put("taxonomyVersion", TAXONOMY_VERSION)
            },
        )
    }

    fun appStop(): TraceEvent = TraceEvent(TYPE_APP_STOP, EMPTY)

    fun activity(phase: ActivityPhase, activityName: String, instanceId: String, restoredState: Boolean? = null): TraceEvent =
        TraceEvent(
            phase.type,
            buildJsonObject {
                put("activity", activityName)
                put("instanceId", instanceId)
                if (phase == ActivityPhase.CREATE && restoredState != null) {
                    put("restoredState", restoredState)
                }
            },
        )

    fun screenEnter(screen: String, activity: String?): TraceEvent =
        TraceEvent(
            TYPE_SCREEN_ENTER,
            buildJsonObject {
                put("screen", screen)
                activity?.let { put("activity", it) }
            },
        )

    /**
     * @param text non-null only when [TraceConfig.captureText] is enabled.
     * @param contentDescription non-null only when [TraceConfig.captureText] is enabled.
     */
    fun click(
        activity: String?,
        viewId: String,
        viewClass: String,
        text: String? = null,
        contentDescription: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): TraceEvent =
        TraceEvent(
            TYPE_CLICK,
            buildJsonObject {
                activity?.let { put("activity", it) }
                put("viewId", viewId)
                put("viewClass", viewClass)
                text?.let { put("text", it) }
                contentDescription?.let { put("contentDescription", it) }
                for ((k, v) in extra) put(k, v)
            },
        )

    /** @param text non-null only when [TraceConfig.captureText] is enabled and the field is not a password. */
    fun textChange(activity: String?, viewId: String, length: Int, text: String? = null): TraceEvent =
        TraceEvent(
            TYPE_TEXT_CHANGE,
            buildJsonObject {
                activity?.let { put("activity", it) }
                put("viewId", viewId)
                put("length", length)
                text?.let { put("text", it) }
            },
        )

    fun scroll(activity: String?, viewId: String, dx: Int, dy: Int): TraceEvent =
        TraceEvent(
            TYPE_SCROLL,
            buildJsonObject {
                activity?.let { put("activity", it) }
                put("viewId", viewId)
                put("dx", dx)
                put("dy", dy)
            },
        )

    fun exception(throwable: Throwable, threadName: String, fatal: Boolean): TraceEvent =
        TraceEvent(
            TYPE_EXCEPTION,
            buildJsonObject {
                put("exceptionType", throwable.javaClass.name)
                throwable.message?.let { put("message", it) }
                put("thread", threadName)
                put("fatal", fatal)
                put("stackTrace", stackTraceString(throwable))
            },
        )

    private fun stackTraceString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val full = sw.toString()
        val byFrames = full.lineSequence().take(MAX_STACK_FRAMES + 1).joinToString("\n")
        return if (byFrames.length > MAX_STACK_CHARS) byFrames.substring(0, MAX_STACK_CHARS) + "\n...(truncated)" else byFrames
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    val EMPTY: JsonObject = JsonObject(emptyMap())

    const val TYPE_APP_START = "android.app.start"
    const val TYPE_APP_STOP = "android.app.stop"
    const val TYPE_SCREEN_ENTER = "android.screen.enter"
    const val TYPE_CLICK = "android.ui.click"
    const val TYPE_TEXT_CHANGE = "android.ui.text_change"
    const val TYPE_SCROLL = "android.ui.scroll"
    const val TYPE_EXCEPTION = "android.exception"
}

internal enum class ActivityPhase(val type: String) {
    CREATE("android.activity.create"),
    START("android.activity.start"),
    RESUME("android.activity.resume"),
    PAUSE("android.activity.pause"),
    STOP("android.activity.stop"),
    DESTROY("android.activity.destroy"),
}
