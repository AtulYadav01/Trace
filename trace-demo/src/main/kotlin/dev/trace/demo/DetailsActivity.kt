package dev.trace.demo

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/** Second screen: one action button and a Back button. */
class DetailsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        findViewById<Button>(R.id.details_action).setOnClickListener { /* captured automatically */ }
        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }
    }
}
