package com.aatorque.prefs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aatorque.stats.R
import java.util.Locale
import kotlin.math.max

class ExpenseSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fuel = 0.0
    private var maintenance = 0.0
    private var registration = 0.0

    fun setData(fuel: Double, maintenance: Double, registration: Double) {
        this.fuel = fuel
        this.maintenance = maintenance
        this.registration = registration
        contentDescription = context.getString(
            R.string.expense_distribution_accessibility,
            money(fuel),
            money(maintenance),
            money(registration)
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val total = fuel + maintenance + registration
        val cx = width * 0.28f
        val cy = 92f * density
        val radius = 58f * density
        val ringWidth = 17f * density
        val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val values = listOf(fuel, maintenance, registration)
        val colors = listOf(
            Color.rgb(217, 255, 67),
            Color.rgb(34, 38, 47),
            Color.rgb(114, 116, 124)
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.rgb(232, 233, 236)
        canvas.drawArc(ring, -90f, 360f, false, paint)
        if (total > 0.0) {
            var start = -90f
            values.forEachIndexed { index, value ->
                val sweep = (360.0 * value / total).toFloat()
                paint.color = colors[index]
                canvas.drawArc(ring, start, sweep, false, paint)
                start += sweep
            }
        }

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 16f * resources.displayMetrics.scaledDensity
        paint.color = Color.rgb(9, 9, 9)
        canvas.drawText(money(total), cx, cy + 6f * density, paint)

        val legendX = width * 0.57f
        val labels = listOf(
            context.getString(R.string.expense_fuel),
            context.getString(R.string.expense_maintenance),
            context.getString(R.string.expense_registration)
        )
        labels.forEachIndexed { index, label ->
            drawLegend(canvas, legendX, (55f + index * 42f) * density, colors[index], label, values[index], density)
        }

        val maxValue = max(1.0, values.maxOrNull() ?: 1.0)
        val chartLeft = 105f * density
        val chartRight = width - 18f * density
        values.forEachIndexed { index, value ->
            val y = (196f + index * 36f) * density
            paint.textAlign = Paint.Align.RIGHT
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 11f * resources.displayMetrics.scaledDensity
            paint.color = Color.rgb(82, 83, 88)
            canvas.drawText(labels[index], chartLeft - 9f * density, y + 14f * density, paint)
            paint.color = Color.rgb(232, 233, 236)
            canvas.drawRoundRect(RectF(chartLeft, y, chartRight, y + 17f * density), 8f * density, 8f * density, paint)
            paint.color = colors[index]
            canvas.drawRoundRect(
                RectF(chartLeft, y, chartLeft + ((chartRight - chartLeft) * value / maxValue).toFloat(), y + 17f * density),
                8f * density,
                8f * density,
                paint
            )
        }
    }

    private fun drawLegend(canvas: Canvas, x: Float, y: Float, color: Int, label: String, value: Double, density: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(x, y, 6f * density, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        paint.color = Color.rgb(82, 83, 88)
        canvas.drawText(label, x + 13f * density, y - 2f * density, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.color = Color.rgb(9, 9, 9)
        canvas.drawText(money(value), x + 13f * density, y + 14f * density, paint)
    }

    private fun money(value: Double) = String.format(Locale.US, "$%.2f", value)
}
