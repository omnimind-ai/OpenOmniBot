package cn.com.omnimind.bot.ui.scheduled

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.dpToPx

class ScheduledTaskReminderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var countDownTimer: CountDownTimer? = null
    private var onCancelClick: (() -> Unit)? = null
    private var onExecuteNowClick: (() -> Unit)? = null
    private var onCountdownFinish: (() -> Unit)? = null

    private val taskNameText: TextView
    private val countdownText: TextView

    private fun t(zh: String, en: String): String {
        return if (AppLocaleManager.isEnglish(context)) en else zh
    }

    init {
        visibility = View.GONE

        val cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRectDrawable(color = 0xFFFFFFFF.toInt(), radiusDp = 16)
            elevation = 6.dpToPx().toFloat()
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10.dpToPx())
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleText = TextView(context).apply {
            text = t("定时任务即将执行", "Scheduled task starting soon")
            textSize = 12f
            setTextColor(0xFF6B6B6B.toInt())
        }
        textColumn.addView(titleText)

        taskNameText = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF303030.toInt())
            setPadding(0, 4.dpToPx(), 0, 0)
            paint.isFakeBoldText = true
            maxLines = 1
        }
        textColumn.addView(taskNameText)

        contentRow.addView(
            textColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )

        countdownText = TextView(context).apply {
            textSize = 24f
            setTextColor(0xFF1565C0.toInt())
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 0, 0, 0)
        }
        contentRow.addView(
            countdownText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        cardContainer.addView(
            contentRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelButton = TextView(context).apply {
            text = t("取消", "Cancel")
            contentDescription = t("取消定时任务", "Cancel scheduled task")
            isClickable = true
            isFocusable = true
            textSize = 12f
            setTextColor(0xFF6B6B6B.toInt())
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            background = roundedRectDrawable(color = 0xFFEEEEEE.toInt(), radiusDp = 16)
            setOnClickListener {
                stopCountdown()
                onCancelClick?.invoke()
            }
        }

        val executeNowButton = TextView(context).apply {
            text = t("立即执行", "Run Now")
            contentDescription = t("立即执行定时任务", "Run scheduled task now")
            isClickable = true
            isFocusable = true
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            background = roundedRectDrawable(color = 0xFF1565C0.toInt(), radiusDp = 16)
            setOnClickListener {
                stopCountdown()
                onExecuteNowClick?.invoke()
            }
        }

        buttonContainer.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = 8.dpToPx()
            },
        )

        buttonContainer.addView(
            executeNowButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        cardContainer.addView(
            buttonContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        addView(
            cardContainer,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = 16.dpToPx()
                rightMargin = 16.dpToPx()
            },
        )
    }

    fun showReminder(taskName: String, countdownSeconds: Int = 5) {
        taskNameText.text = taskName
        visibility = View.VISIBLE
        alpha = 0f
        updateCountdownDisplay(countdownSeconds)
        contentDescription = t(
            "定时任务 $taskName 即将执行，剩余 $countdownSeconds 秒",
            "Scheduled task $taskName starts in $countdownSeconds seconds",
        )

        animate()
            .alpha(1f)
            .setDuration(200)
            .start()

        startCountdown(countdownSeconds)
    }

    fun hideReminder() {
        stopCountdown()
        animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                visibility = View.GONE
            }
            .start()
    }

    fun setOnCancelClickListener(listener: () -> Unit) {
        onCancelClick = listener
    }

    fun setOnExecuteNowClickListener(listener: () -> Unit) {
        onExecuteNowClick = listener
    }

    fun setOnCountdownFinishListener(listener: () -> Unit) {
        onCountdownFinish = listener
    }

    private fun startCountdown(seconds: Int) {
        stopCountdown()
        countDownTimer = object : CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000).toInt() + 1
                updateCountdownDisplay(remaining)
            }

            override fun onFinish() {
                updateCountdownDisplay(0)
                hideReminder()
                onCountdownFinish?.invoke()
            }
        }.start()
    }

    private fun stopCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    private fun updateCountdownDisplay(seconds: Int) {
        countdownText.text = seconds.toString()
        countdownText.contentDescription = t(
            "剩余 $seconds 秒",
            "$seconds seconds remaining",
        )
    }

    private fun roundedRectDrawable(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dpToPx().toFloat()
            setColor(color)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCountdown()
    }
}
