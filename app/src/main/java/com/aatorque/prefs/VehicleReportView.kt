package com.aatorque.prefs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max

class VehicleReportView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var total = 0f
    private var services = 0f
    private var documents = 0f
    private var yearly: List<Pair<String, Float>> = emptyList()

    fun setData(total: Float, services: Float, documents: Float, yearly: List<Pair<String, Float>>) {
        this.total = total
        this.services = services
        this.documents = documents
        this.yearly = yearly
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val cx = width * 0.28f
        val cy = 88f * density
        val radius = 58f * density
        val ring = 17f * density
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.rgb(232, 233, 236)
        canvas.drawArc(oval, -90f, 360f, false, paint)
        if (total > 0f) {
            val serviceSweep = 360f * services / total
            paint.color = Color.rgb(217, 255, 67)
            canvas.drawArc(oval, -90f, serviceSweep, false, paint)
            paint.color = Color.rgb(34, 38, 47)
            canvas.drawArc(oval, -90f + serviceSweep, 360f - serviceSweep, false, paint)
        }

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 17f * resources.displayMetrics.scaledDensity
        paint.color = Color.rgb(9, 9, 9)
        canvas.drawText(String.format(Locale.US, "$%.0f", total), cx, cy + 6f * density, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 14f * resources.displayMetrics.scaledDensity
        paint.color = Color.rgb(9, 9, 9)
        val legendX = width * 0.57f
        drawLegend(canvas, legendX, cy - 20f * density, Color.rgb(217, 255, 67), "Servicios", services, density)
        drawLegend(canvas, legendX, cy + 24f * density, Color.rgb(34, 38, 47), "Documentos", documents, density)

        val chartTop = 176f * density
        val chartLeft = 48f * density
        val chartRight = width - 12f * density
        val maxValue = max(1f, yearly.maxOfOrNull { it.second } ?: 1f)
        val rowHeight = 38f * density
        yearly.take(3).forEachIndexed { index, (year, value) ->
            val y = chartTop + index * rowHeight
            paint.textAlign = Paint.Align.RIGHT
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 12f * resources.displayMetrics.scaledDensity
            paint.color = Color.rgb(87, 88, 93)
            canvas.drawText(year, chartLeft - 9f * density, y + 13f * density, paint)
            val bar = RectF(chartLeft, y, chartLeft + (chartRight - chartLeft) * value / maxValue, y + 18f * density)
            paint.color = if (index == 0) Color.rgb(217, 255, 67) else Color.rgb(34, 38, 47)
            canvas.drawRoundRect(bar, 9f * density, 9f * density, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.rgb(80, 81, 86)
            paint.typeface = android.graphics.Typeface.DEFAULT
            canvas.drawText(String.format(Locale.US, "$%.0f", value), chartLeft, y + 33f * density, paint)
        }
    }

    private fun drawLegend(canvas: Canvas, x: Float, y: Float, color: Int, title: String, value: Float, density: Float) {
        paint.color = color
        canvas.drawCircle(x, y, 6f * density, paint)
        paint.color = Color.rgb(80, 81, 86)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        canvas.drawText(title, x + 13f * density, y + 4f * density, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.color = Color.rgb(9, 9, 9)
        canvas.drawText(String.format(Locale.US, "$%.0f", value), x + 13f * density, y + 20f * density, paint)
    }
}
