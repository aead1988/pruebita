package com.aatorque.stats

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a privacy-preserving offline map. GPS coordinates never leave the phone: the renderer
 * projects them locally over a geographic grid and a bundled Ecuador intercity-road schematic.
 */
class FuelEconomyMapRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle
) : DefaultLifecycleObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var location: Location? = null
    private var snapshot = FuelEconomySnapshot(0.0, 0.0)
    private var status = carContext.getString(R.string.fuel_map_waiting)
    private var zoom = DEFAULT_ZOOM

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            synchronized(this@FuelEconomyMapRenderer) {
                surface?.release()
                surface = surfaceContainer.surface
            }
            render()
        }

        override fun onVisibleAreaChanged(newVisibleArea: Rect) {
            visibleArea = Rect(newVisibleArea)
            render()
        }

        override fun onStableAreaChanged(newStableArea: Rect) {
            stableArea = Rect(newStableArea)
            render()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            synchronized(this@FuelEconomyMapRenderer) {
                surface?.release()
                surface = null
            }
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            when {
                scaleFactor > 1.08f -> zoomIn()
                scaleFactor < 0.92f -> zoomOut()
            }
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        synchronized(this) {
            surface?.release()
            surface = null
        }
    }

    fun updateLocation(value: Location) {
        location = Location(value)
        render()
    }

    fun updateFuelEconomy(value: FuelEconomySnapshot, newStatus: String) {
        snapshot = value
        status = newStatus
        render()
    }

    fun updateStatus(newStatus: String) {
        status = newStatus
        render()
    }

    fun zoomIn() {
        zoom = (zoom + 1).coerceAtMost(MAX_ZOOM)
        render()
    }

    fun zoomOut() {
        zoom = (zoom - 1).coerceAtLeast(MIN_ZOOM)
        render()
    }

    fun recenter() = render()

    private fun render() {
        mainHandler.removeCallbacks(renderRunnable)
        mainHandler.post(renderRunnable)
    }

    private val renderRunnable = Runnable(::drawFrame)

    private fun drawFrame() {
        val currentSurface = synchronized(this) { surface } ?: return
        if (!currentSurface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = currentSurface.lockCanvas(null)
            drawOfflineMap(canvas)
            location?.let { drawVehicle(canvas, it) }
            drawFuelCard(canvas)
            drawPrivacyLabel(canvas)
        } catch (error: Exception) {
            Timber.w(error, "Unable to draw AA Torque offline map")
        } finally {
            if (canvas != null) {
                try {
                    currentSurface.unlockCanvasAndPost(canvas)
                } catch (error: Exception) {
                    Timber.w(error, "Unable to post AA Torque map frame")
                }
            }
        }
    }

    private fun drawOfflineMap(canvas: Canvas) {
        val dark = carContext.isDarkMode
        canvas.drawColor(if (dark) Color.rgb(26, 31, 36) else Color.rgb(232, 235, 226))
        val current = location
        val centerLat = current?.latitude ?: DEFAULT_LATITUDE
        val centerLon = current?.longitude ?: DEFAULT_LONGITUDE
        val metersPerPixel = metersPerPixel(centerLat)

        drawGeographicGrid(canvas, centerLat, centerLon, metersPerPixel, dark)
        ROAD_CORRIDORS.forEach { corridor ->
            drawRoad(canvas, corridor, centerLat, centerLon, metersPerPixel, dark)
        }
        CITIES.forEach { city ->
            drawCity(canvas, city, centerLat, centerLon, metersPerPixel, dark)
        }
        drawNorthIndicator(canvas, dark)
    }

    private fun drawGeographicGrid(
        canvas: Canvas,
        centerLat: Double,
        centerLon: Double,
        metersPerPixel: Double,
        dark: Boolean
    ) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.rgb(52, 61, 67) else Color.rgb(202, 207, 196)
            strokeWidth = 1.5f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.rgb(135, 145, 150) else Color.rgb(105, 112, 103)
            textSize = max(12f, canvas.height * 0.021f)
        }
        val degreeStep = when {
            zoom >= 14 -> 0.01
            zoom >= 12 -> 0.025
            zoom >= 10 -> 0.05
            else -> 0.1
        }
        val latSpan = canvas.height * metersPerPixel / METERS_PER_LAT_DEGREE / 2
        val lonMeters = metersPerLongitudeDegree(centerLat)
        val lonSpan = canvas.width * metersPerPixel / lonMeters / 2
        var latitude = kotlin.math.floor((centerLat - latSpan) / degreeStep) * degreeStep
        while (latitude <= centerLat + latSpan) {
            val point = project(latitude, centerLon, centerLat, centerLon, metersPerPixel, canvas)
            canvas.drawLine(0f, point.second, canvas.width.toFloat(), point.second, gridPaint)
            canvas.drawText(String.format(Locale.US, "%.3f°", latitude), 8f, point.second - 4f, labelPaint)
            latitude += degreeStep
        }
        var longitude = kotlin.math.floor((centerLon - lonSpan) / degreeStep) * degreeStep
        while (longitude <= centerLon + lonSpan) {
            val point = project(centerLat, longitude, centerLat, centerLon, metersPerPixel, canvas)
            canvas.drawLine(point.first, 0f, point.first, canvas.height.toFloat(), gridPaint)
            canvas.drawText(String.format(Locale.US, "%.3f°", longitude), point.first + 5f, 20f, labelPaint)
            longitude += degreeStep
        }
    }

    private fun drawRoad(
        canvas: Canvas,
        points: List<GeoPoint>,
        centerLat: Double,
        centerLon: Double,
        metersPerPixel: Double,
        dark: Boolean
    ) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val projected = project(point.latitude, point.longitude, centerLat, centerLon, metersPerPixel, canvas)
            if (index == 0) path.moveTo(projected.first, projected.second)
            else path.lineTo(projected.first, projected.second)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.rgb(15, 18, 21) else Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = max(8f, canvas.height * 0.018f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.rgb(219, 153, 48) else Color.rgb(230, 145, 24)
            style = Paint.Style.STROKE
            strokeWidth = max(4f, canvas.height * 0.009f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })
    }

    private fun drawCity(
        canvas: Canvas,
        city: City,
        centerLat: Double,
        centerLon: Double,
        metersPerPixel: Double,
        dark: Boolean
    ) {
        val point = project(city.latitude, city.longitude, centerLat, centerLon, metersPerPixel, canvas)
        if (point.first !in -100f..canvas.width + 100f || point.second !in -100f..canvas.height + 100f) return
        canvas.drawCircle(point.first, point.second, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 110, 210) })
        canvas.drawText(city.name, point.first + 11f, point.second - 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.rgb(35, 42, 45)
            textSize = max(14f, canvas.height * 0.025f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 1f, if (dark) Color.BLACK else Color.WHITE)
        })
    }

    private fun drawNorthIndicator(canvas: Canvas, dark: Boolean) {
        val safe = visibleArea?.takeUnless { it.isEmpty } ?: Rect(0, 0, canvas.width, canvas.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.rgb(30, 36, 40)
            textSize = max(18f, canvas.height * 0.034f)
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("N", safe.centerX().toFloat(), safe.top + paint.textSize + 8f, paint)
    }

    private fun drawVehicle(canvas: Canvas, currentLocation: Location) {
        val area = stableArea?.takeUnless { it.isEmpty } ?: visibleArea?.takeUnless { it.isEmpty }
            ?: Rect(0, 0, canvas.width, canvas.height)
        val x = area.centerX().toFloat()
        val y = area.centerY().toFloat()
        val radius = max(12f, canvas.height * 0.025f)
        val angle = Math.toRadians((if (currentLocation.hasBearing()) currentLocation.bearing else 0f).toDouble())
        val path = Path().apply {
            moveTo((x + sin(angle) * radius * 1.6).toFloat(), (y - cos(angle) * radius * 1.6).toFloat())
            lineTo((x + sin(angle + 2.45) * radius).toFloat(), (y - cos(angle + 2.45) * radius).toFloat())
            lineTo((x + sin(angle - 2.45) * radius).toFloat(), (y - cos(angle - 2.45) * radius).toFloat())
            close()
        }
        canvas.drawCircle(x, y, radius * 1.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 120, 255) })
    }

    private fun drawFuelCard(canvas: Canvas) {
        val safe = stableArea?.takeUnless { it.isEmpty } ?: visibleArea?.takeUnless { it.isEmpty }
            ?: Rect(0, 0, canvas.width, canvas.height)
        val margin = max(12f, canvas.height * 0.025f)
        val width = min(safe.width() * 0.62f, canvas.width * 0.58f)
        val height = min(safe.height() * 0.38f, canvas.height * 0.34f)
        val card = RectF(safe.left + margin, safe.bottom - height - margin, safe.left + margin + width, safe.bottom - margin)
        canvas.drawRoundRect(card, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(222, 15, 18, 22) })

        val average = snapshot.averageKmPerGallon?.let(::oneDecimal) ?: "--"
        val instant = snapshot.instantKmPerGallon?.let(::oneDecimal) ?: "--"
        val titleSize = max(20f, canvas.height * 0.045f)
        val detailSize = max(16f, canvas.height * 0.032f)
        val statusSize = max(13f, canvas.height * 0.025f)
        val x = card.left + margin
        var y = card.top + margin + titleSize
        drawText(canvas, carContext.getString(R.string.fuel_map_average, average), x, y, titleSize, Color.rgb(255, 205, 0), true)
        y += detailSize * 1.45f
        drawText(canvas, carContext.getString(R.string.fuel_map_instant, instant), x, y, detailSize, Color.WHITE, false)
        y += detailSize * 1.35f
        drawText(
            canvas,
            carContext.getString(R.string.fuel_map_trip, twoDecimals(snapshot.distanceKm), twoDecimals(snapshot.fuelGallons)),
            x,
            y,
            detailSize,
            Color.WHITE,
            false
        )
        y += statusSize * 1.45f
        drawText(canvas, status, x, min(y, card.bottom - margin / 2), statusSize, Color.LTGRAY, false)
    }

    private fun drawPrivacyLabel(canvas: Canvas) {
        val safe = visibleArea?.takeUnless { it.isEmpty } ?: Rect(0, 0, canvas.width, canvas.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = max(11f, canvas.height * 0.019f)
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        canvas.drawText(carContext.getString(R.string.fuel_map_attribution), safe.right - 8f, safe.bottom - 7f, paint)
    }

    private fun project(
        latitude: Double,
        longitude: Double,
        centerLat: Double,
        centerLon: Double,
        metersPerPixel: Double,
        canvas: Canvas
    ): Pair<Float, Float> {
        val xMeters = (longitude - centerLon) * metersPerLongitudeDegree(centerLat)
        val yMeters = (latitude - centerLat) * METERS_PER_LAT_DEGREE
        return Pair(
            (canvas.width / 2.0 + xMeters / metersPerPixel).toFloat(),
            (canvas.height / 2.0 - yMeters / metersPerPixel).toFloat()
        )
    }

    private fun metersPerPixel(latitude: Double): Double =
        156543.03392 * cos(Math.toRadians(latitude)) / (1 shl zoom)

    private fun metersPerLongitudeDegree(latitude: Double): Double =
        111320.0 * cos(Math.toRadians(latitude))

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) {
        canvas.drawText(text, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        })
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun twoDecimals(value: Double): String = String.format(Locale.US, "%.2f", value)

    private data class GeoPoint(val latitude: Double, val longitude: Double)
    private data class City(val name: String, val latitude: Double, val longitude: Double)

    companion object {
        private const val DEFAULT_ZOOM = 11
        private const val MIN_ZOOM = 8
        private const val MAX_ZOOM = 15
        private const val DEFAULT_LATITUDE = -0.9352
        private const val DEFAULT_LONGITUDE = -78.6155
        private const val METERS_PER_LAT_DEGREE = 110540.0

        private val ROAD_CORRIDORS = listOf(
            listOf(
                GeoPoint(0.3517, -78.1223), // Ibarra
                GeoPoint(0.1500, -78.3000),
                GeoPoint(-0.1807, -78.4678), // Quito
                GeoPoint(-0.5101, -78.5671), // Machachi
                GeoPoint(-0.7500, -78.5900),
                GeoPoint(-0.9352, -78.6155), // Latacunga
                GeoPoint(-1.2543, -78.6229), // Ambato
                GeoPoint(-1.6636, -78.6546)  // Riobamba
            )
        )
        private val CITIES = listOf(
            City("Ibarra", 0.3517, -78.1223),
            City("Quito", -0.1807, -78.4678),
            City("Machachi", -0.5101, -78.5671),
            City("Latacunga", -0.9352, -78.6155),
            City("Ambato", -1.2543, -78.6229),
            City("Riobamba", -1.6636, -78.6546)
        )
    }
}
