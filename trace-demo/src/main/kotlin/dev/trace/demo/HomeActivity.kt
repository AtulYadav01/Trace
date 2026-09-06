package dev.trace.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import dev.trace.android.TraceAndroid

/**
 * Home screen: two plain buttons, a text field, a password field (never
 * captured), navigation to [DetailsActivity], a button to finish the TRACE
 * session and show the file path, and a developer-only crash button.
 *
 * No TRACE calls are needed here for lifecycle or clicks: `trace-android`
 * captures those automatically once [TraceAndroid.start] has run.
 */
class HomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.button_a).setOnClickListener { /* captured automatically */ }
        findViewById<Button>(R.id.button_b).setOnClickListener { /* captured automatically */ }

        findViewById<Button>(R.id.go_to_details).setOnClickListener {
            startActivity(Intent(this, DetailsActivity::class.java))
        }

        findViewById<Button>(R.id.finish_session).setOnClickListener {
            val file = TraceAndroid.currentSessionFile
            TraceAndroid.stop()
            findViewById<TextView>(R.id.session_path).text =
                if (file != null) "Trace written to:\n$file" else "No active session"
        }

        findViewById<Button>(R.id.crash_button).setOnClickListener {
            throw IllegalStateException("Demo crash from HomeActivity")
        }
    }
}
