package com.eun0115.petaldrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeColor = ContextCompat.getColor(context, R.color.text_primary)
    private val inactiveColor = ContextCompat.getColor(context, R.color.text_secondary)
    private var level = 0

    fun setLevel(value: Int) {
        val next = value.coerceIn(0, BAR_COUNT)
        if (level != next) {
            level = next
            contentDescription = context.getString(R.string.signal_strength_accessibility, next)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = width * 0.08f
        val barWidth = (width - gap * (BAR_COUNT - 1)) / BAR_COUNT
        val maxHeight = height.toFloat()
        for (index in 0 until BAR_COUNT) {
            val barHeight = maxHeight * (index + 1) / BAR_COUNT
            val left = index * (barWidth + gap)
            paint.color = if (index < level) activeColor else inactiveColor
            paint.alpha = if (index < level) 255 else 100
            canvas.drawRoundRect(
                left,
                maxHeight - barHeight,
                left + max(1f, barWidth),
                maxHeight,
                barWidth * 0.2f,
                barWidth * 0.2f,
                paint
            )
        }
        paint.alpha = 255
    }

    private companion object {
        const val BAR_COUNT = 4
    }
}
