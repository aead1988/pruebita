package com.aatorque.prefs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aatorque.stats.ArchivedTrip
import com.aatorque.stats.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class DrivingAnalysisView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var trips: List<ArchivedTrip> = emptyList()

    fun setData(values: List<ArchivedTrip>) {
        trips = values.sortedByDescending { it.startedAt }.take(10).sortedBy { it.startedAt }
        contentDescription = if (trips.isEmpty()) {
            context.getString(R.string.viewer_analysis_empty)
        } else {
            val average = trips.mapNotNull { it.averageKmPerGallon }.average()
            context.getString(
                R.string.viewer_analysis_average,
                number(if (average.isNaN()) 0.0 else average),
                number(trips.sumOf { it.distanceKm })
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        if (trips.isEmpty()) {
            paint.color = Color.rgb(105, 106, 111)
            paint.textSize = 14f * resources.displayMetrics.scaledDensity
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                context.getString(R.string.viewer_analysis_empty),
                width / 2f,
                height / 2f,
                paint
            )
            return
        }

        val left = 43f * density
        val right = width - 18f * density
        drawTitle(canvas, context.getString(R.string.viewer_analysis_efficiency), 25f * density)
        val efficiencyChart = RectF(left, 50f * density, right, 156f * density)
        drawGrid(canvas, efficiencyChart)
        drawEfficiency(canvas, efficiencyChart, density)

        drawTitle(canvas, context.getString(R.string.viewer_analysis_distance), 213f * density)
        val distanceChart = RectF(left, 235f * density, right, 337f * density)
        drawGrid(canvas, distanceChart)
        drawDistance(canvas, distanceChart, density)

        val efficiencies = trips.mapNotNull { it.averageKmPerGallon }
        val average = if (efficiencies.isEmpty()) 0.0 else efficiencies.average()
        paint.color = Color.rgb(73, 74, 79)
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            context.getString(
                R.string.viewer_analysis_average,
                number(average),
                number(trips.sumOf { it.distanceKm })
            ),
            width / 2f,
            371f * density,
            paint
        )
    }

    private fun drawTitle(canvas: Canvas, title: String, y: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(9, 9, 9)
        paint.textSize = 14f * resources.displayMetrics.scaledDensity
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, 18f * resources.displayMetrics.density, y, paint)
    }

    private fun drawGrid(canvas: Canvas, area: RectF) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.rgb(232, 233, 236)
        repeat(4) { index ->
            val y = area.top + area.height() * index / 3f
            canvas.drawLine(area.left, y, area.right, y, paint)
        }
    }

    private fun drawEfficiency(canvas: Canvas, area: RectF, density: Float) {
        val values = trips.map { it.averageKmPerGallon ?: 0.0 }
        val low = max(0.0, (values.minOrNull() ?: 0.0) - 5.0)
        val high = max(low + 10.0, (values.maxOrNull() ?: 0.0) + 5.0)
        drawScale(canvas, area, high, low)

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = pointX(area, index, values.size)
            val y = area.bottom - ((value - low) / (high - low) * area.height()).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.rgb(133, 170, 20)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        values.forEachIndexed { index, value ->
            val x = pointX(area, index, values.size)
            val y = area.bottom - ((value - low) / (high - low) * area.height()).toFloat()
            paint.color = if (index == values.lastIndex) Color.rgb(9, 9, 9) else Color.rgb(217, 255, 67)
            canvas.drawCircle(x, y, 5f * density, paint)
        }
        drawTripLabels(canvas, area, density, centered = false)
    }

    private fun drawDistance(canvas: Canvas, area: RectF, density: Float) {
        val values = trips.map { it.distanceKm }
        val high = max(1.0, values.maxOrNull() ?: 1.0)
        drawScale(canvas, area, high, 0.0)
        val slot = area.width() / values.size
        val barWidth = min(22f * density, slot * 0.58f)
        values.forEachIndexed { index, value ->
            val center = area.left + slot * (index + 0.5f)
            val top = area.bottom - (value / high * area.height()).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = if (index == values.lastIndex) Color.rgb(217, 255, 67) else Color.rgb(34, 38, 47)
            canvas.drawRoundRect(
                RectF(center - barWidth / 2f, top, center + barWidth / 2f, area.bottom),
                5f * density,
                5f * density,
                paint
            )
        }
        drawTripLabels(canvas, area, density, centered = true)
    }

    private fun drawScale(canvas: Canvas, area: RectF, high: Double, low: Double) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(112, 113, 118)
        paint.textSize = 10f * resources.displayMetrics.scaledDensity
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.US, "%.0f", high), area.left - 7f * resources.displayMetrics.density, area.top + 4f * resources.displayMetrics.density, paint)
        canvas.drawText(String.format(Locale.US, "%.0f", low), area.left - 7f * resources.displayMetrics.density, area.bottom, paint)
    }

    private fun drawTripLabels(canvas: Canvas, area: RectF, density: Float, centered: Boolean) {
        paint.color = Color.rgb(112, 113, 118)
        paint.textSize = 9f * resources.displayMetrics.scaledDensity
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        trips.indices.forEach { index ->
            if (trips.size <= 6 || index == 0 || index == trips.lastIndex || index % 2 == 0) {
                val x = if (centered) area.left + area.width() / trips.size * (index + 0.5f)
                    else pointX(area, index, trips.size)
                canvas.drawText((index + 1).toString(), x, area.bottom + 14f * density, paint)
            }
        }
    }

    private fun pointX(area: RectF, index: Int, size: Int): Float {
        return if (size <= 1) area.centerX() else area.left + area.width() * index / (size - 1f)
    }

    private fun number(value: Double) = String.format(Locale.US, "%.2f", value)
}
