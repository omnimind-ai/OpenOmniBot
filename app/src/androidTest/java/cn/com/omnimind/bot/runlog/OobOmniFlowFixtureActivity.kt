package cn.com.omnimind.bot.runlog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class OobOmniFlowFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderStartPage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        renderStartPage()
    }

    private fun renderStartPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 180, 64, 64)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(
            TextView(this).apply {
                text = "OOB OmniFlow fixture"
                textSize = 22f
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        )

        root.addView(
            EditText(this).apply {
                hint = "Message input"
                contentDescription = "Message input"
                setSingleLine(true)
                id = View.generateViewId()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    160,
                ).apply {
                    topMargin = 48
                }
            }
        )

        root.addView(
            Button(this).apply {
                text = "Network"
                contentDescription = "Network settings"
                id = View.generateViewId()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { renderNetworkPage() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    180,
                ).apply {
                    topMargin = 64
                }
            }
        )

        root.addView(
            Button(this).apply {
                text = "Delete account"
                contentDescription = "Delete account"
                id = View.generateViewId()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    180,
                ).apply {
                    topMargin = 32
                }
            }
        )

        setContentView(root)
    }

    private fun renderNetworkPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 220, 64, 64)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(
            TextView(this).apply {
                text = "Internet"
                contentDescription = "Internet"
                textSize = 28f
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    180,
                )
            }
        )

        root.addView(
            Button(this).apply {
                text = "Back to network list"
                contentDescription = "Back to network list"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { renderStartPage() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    160,
                ).apply {
                    topMargin = 48
                }
            }
        )

        setContentView(root)
    }
}
