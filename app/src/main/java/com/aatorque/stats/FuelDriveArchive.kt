package com.aatorque.stats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ArchivedTrip(
    val timestamp: Long,
    val distanceKm: Double,
    val fuelLiters: Double,
    val elapsedSeconds: Double,
    val fuelCost: Double,
    val awardId: String = "",
    val awardTitle: String = ""
) {
    val gallons: Double get() = fuelLiters / FuelEconomySnapshot.US_GALLON_LITERS
    val averageKmPerGallon: Double?
        get() = if (fuelLiters > 0.0001) distanceKm / fuelLiters * FuelEconomySnapshot.US_GALLON_LITERS else null

    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("distanceKm", distanceKm)
        .put("fuelLiters", fuelLiters)
        .put("elapsedSeconds", elapsedSeconds)
        .put("fuelCost", fuelCost)
        .put("awardId", awardId)
        .put("awardTitle", awardTitle)

    companion object {
        fun fromJson(value: JSONObject): ArchivedTrip = ArchivedTrip(
            timestamp = value.optLong("timestamp"),
            distanceKm = value.optDouble("distanceKm"),
            fuelLiters = value.optDouble("fuelLiters"),
            elapsedSeconds = value.optDouble("elapsedSeconds"),
            fuelCost = value.optDouble("fuelCost"),
            awardId = value.optString("awardId", ""),
            awardTitle = value.optString("awardTitle", "")
        )
    }
}

class FuelTripHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun add(trip: ArchivedTrip) {
        write(load().toMutableList().apply { add(trip) }.takeLast(MAX_TRIPS))
    }

    @Synchronized
    fun load(): List<ArchivedTrip> = try {
        val array = JSONArray(preferences.getString(KEY_TRIPS, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) add(ArchivedTrip.fromJson(array.getJSONObject(index)))
        }
    } catch (error: Exception) {
        Timber.w(error, "Unable to read archived fuel trips")
        emptyList()
    }

    @Synchronized
    fun replace(values: List<ArchivedTrip>) = write(values.takeLast(MAX_TRIPS))

    private fun write(values: List<ArchivedTrip>) {
        val array = JSONArray().apply { values.forEach { put(it.toJson()) } }
        preferences.edit().putString(KEY_TRIPS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "fuel_trip_history"
        private const val KEY_TRIPS = "trips"
        private const val MAX_TRIPS = 1000
    }
}

/** Saves one consolidated daily card and CSV reports for completed tank periods. */
object FuelDriveArchive {
    private const val PREFS_NAME = "fuel_drive_archive"
    private const val KEY_LAST_SIGNATURE = "last_trip_signature"

    data class DailyCardResult(val uri: Uri, val tripCount: Int)

    fun archiveJourneyAndUpdateDailyCard(
        context: Context,
        journey: FuelEconomySnapshot,
        journeyStartedAt: Long
    ): DailyCardResult? {
        if (journey.distanceKm < 0.05 && journey.elapsedSeconds < 60.0) return null
        val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context) ?: return null
        val signature = listOf(
            journeyStartedAt / 60_000L,
            (journey.distanceKm * 100).toLong(),
            (journey.fuelLiters * 10_000).toLong(),
            journey.elapsedSeconds.toLong()
        ).joinToString(":")
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_LAST_SIGNATURE, null) != signature) {
            val price = fuelPrice(context)
            FuelTripHistoryStore(context).add(
                ArchivedTrip(
                    timestamp = System.currentTimeMillis(),
                    distanceKm = journey.distanceKm,
                    fuelLiters = journey.fuelLiters,
                    elapsedSeconds = journey.elapsedSeconds,
                    fuelCost = journey.fuelGallons * price
                )
            )
            preferences.edit().putString(KEY_LAST_SIGNATURE, signature).apply()
        }
        return writeDailyCard(context, treeUri)
    }

    fun exportDailySummary(context: Context): DailyCardResult? {
        val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context) ?: return null
        return writeDailyCard(context, treeUri)
    }

    fun exportTankPeriod(
        context: Context,
        sinceRefuel: FuelEconomySnapshot,
        periodStartedAt: Long,
        currentJourney: FuelEconomySnapshot,
        currentJourneyStartedAt: Long
    ): Uri? {
        val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context) ?: return null
        return try {
            val endedAt = System.currentTimeMillis()
            val history = FuelTripHistoryStore(context).load()
                .filter { it.timestamp in periodStartedAt..endedAt }
                .toMutableList()
            val currentAlreadyArchived = history.any { it.timestamp >= currentJourneyStartedAt }
            if (!currentAlreadyArchived && (currentJourney.distanceKm >= 0.05 || currentJourney.elapsedSeconds >= 60.0)) {
                history.add(
                    ArchivedTrip(
                        timestamp = endedAt,
                        distanceKm = currentJourney.distanceKm,
                        fuelLiters = currentJourney.fuelLiters,
                        elapsedSeconds = currentJourney.elapsedSeconds,
                        fuelCost = currentJourney.fuelGallons * fuelPrice(context)
                    )
                )
            }
            val root = treeDocument(treeUri)
            val folder = directory(context, treeUri, root, "Tanqueadas") ?: root
            val start = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(periodStartedAt))
            val end = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(endedAt))
            createText(
                context,
                folder,
                "text/csv",
                "tanqueada-${start}_a_$end.csv",
                tankPeriodCsv(context, sinceRefuel, periodStartedAt, endedAt, history)
            )
        } catch (error: Exception) {
            Timber.e(error, "Unable to export tank period")
            null
        }
    }

    /** Keeps compatibility with JSON backups produced by version 2.0.53. */
    fun restoreBackup(context: Context, uri: Uri): Boolean {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return false
            val root = JSONObject(raw)
            if (root.optInt("schemaVersion") !in 1..SCHEMA_VERSION) return false
            root.optJSONObject("sinceRefuel")?.let { value ->
                FuelEconomyStore(context).restore(
                    FuelEconomySnapshot(
                        value.optDouble("distanceKm"),
                        value.optDouble("fuelLiters"),
                        value.optDouble("elapsedSeconds")
                    ),
                    value.optLong("startedAt", System.currentTimeMillis())
                )
            }
            root.optJSONArray("monthlyHistory")?.let { array ->
                MonthlyFuelEconomyStore(context).restore(buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(MonthlyFuelEconomySnapshot(
                            item.getString("month"),
                            item.optDouble("distanceKm"),
                            item.optDouble("fuelLiters"),
                            item.optDouble("fuelCost")
                        ))
                    }
                })
            }
            root.optJSONArray("trips")?.let { array ->
                FuelTripHistoryStore(context).replace(buildList {
                    for (index in 0 until array.length()) add(ArchivedTrip.fromJson(array.getJSONObject(index)))
                })
            }
            true
        } catch (error: Exception) {
            Timber.e(error, "Unable to restore AA Torque backup")
            false
        }
    }

    private fun writeDailyCard(context: Context, treeUri: Uri): DailyCardResult? {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val trips = FuelTripHistoryStore(context).load().filter {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.timestamp)) == dayKey
        }
        if (trips.isEmpty()) return null
        return try {
            val root = treeDocument(treeUri)
            val cards = directory(context, treeUri, root, "Resumenes diarios") ?: root
            val name = "resumen-diario-$dayKey.png"
            val existing = findChild(context, treeUri, cards, name, "image/png")
            val uri = existing ?: DocumentsContract.createDocument(context.contentResolver, cards, "image/png", name)
                ?: return null
            val written = context.contentResolver.openOutputStream(uri, "w")?.use {
                it.write(dailySummaryCard(trips))
            } != null
            if (written) DailyCardResult(uri, trips.size) else null
        } catch (error: Exception) {
            Timber.e(error, "Unable to update daily summary card")
            null
        }
    }

    private fun dailySummaryCard(trips: List<ArchivedTrip>): ByteArray {
        val width = 1400
        val height = maxOf(900, 430 + trips.size * 86)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(10, 12, 16))
        paint.color = Color.rgb(255, 199, 0)
        canvas.drawRoundRect(RectF(30f, 30f, width - 30f, height - 30f), 34f, 34f, paint)
        paint.color = Color.rgb(20, 23, 29)
        canvas.drawRoundRect(RectF(42f, 42f, width - 42f, height - 42f), 28f, 28f, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.WHITE
        paint.textSize = 46f
        canvas.drawText("AA TORQUE · RESUMEN DE HOY", 85f, 115f, paint)
        paint.color = Color.rgb(255, 199, 0)
        paint.textSize = 29f
        canvas.drawText(SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault()), 85f, 162f, paint)

        val totalDistance = trips.sumOf { it.distanceKm }
        val totalLiters = trips.sumOf { it.fuelLiters }
        val totalGallons = totalLiters / FuelEconomySnapshot.US_GALLON_LITERS
        val totalAverage = if (totalLiters > 0.0001) totalDistance / totalLiters * FuelEconomySnapshot.US_GALLON_LITERS else null
        val totalCost = trips.sumOf { it.fuelCost }
        val totalSeconds = trips.sumOf { it.elapsedSeconds }
        paint.color = Color.WHITE
        paint.textSize = 31f
        canvas.drawText(
            "${number(totalDistance)} km    ${number(totalGallons)} gal    ${numberOrDash(totalAverage)} km/gal    \$${number(totalCost)}    ${duration(totalSeconds)}",
            85f,
            220f,
            paint
        )

        paint.color = Color.LTGRAY
        paint.textSize = 22f
        canvas.drawText("VIAJE", 85f, 282f, paint)
        canvas.drawText("HORA", 235f, 282f, paint)
        canvas.drawText("DISTANCIA", 420f, 282f, paint)
        canvas.drawText("GALONES", 670f, 282f, paint)
        canvas.drawText("PROMEDIO", 880f, 282f, paint)
        canvas.drawText("COSTO", 1110f, 282f, paint)
        canvas.drawText("TIEMPO", 1260f, 282f, paint)

        trips.forEachIndexed { index, trip ->
            val y = 340f + index * 86f
            paint.color = if (index % 2 == 0) Color.rgb(35, 39, 48) else Color.rgb(28, 32, 40)
            canvas.drawRoundRect(RectF(72f, y - 43f, width - 72f, y + 28f), 14f, 14f, paint)
            paint.color = Color.WHITE
            paint.textSize = 26f
            canvas.drawText("Viaje ${index + 1}", 85f, y, paint)
            canvas.drawText(SimpleDateFormat("HH:mm", Locale.US).format(Date(trip.timestamp)), 235f, y, paint)
            canvas.drawText("${number(trip.distanceKm)} km", 420f, y, paint)
            canvas.drawText("${number(trip.gallons)} gal", 670f, y, paint)
            canvas.drawText("${numberOrDash(trip.averageKmPerGallon)} km/gal", 880f, y, paint)
            canvas.drawText("\$${number(trip.fuelCost)}", 1110f, y, paint)
            canvas.drawText(duration(trip.elapsedSeconds), 1260f, y, paint)
        }
        paint.color = Color.LTGRAY
        paint.textSize = 22f
        canvas.drawText("${trips.size} viajes registrados · Archivo único actualizado al finalizar cada viaje", 85f, height - 80f, paint)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 96, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun tankPeriodCsv(
        context: Context,
        totals: FuelEconomySnapshot,
        startedAt: Long,
        endedAt: Long,
        trips: List<ArchivedTrip>
    ): String = buildString {
        val days = ((endedAt - startedAt).coerceAtLeast(0L) / 86_400_000L) + 1L
        append('\uFEFF')
        append("tipo,numero,inicio,fin,dias,distance_km,galones_usados,promedio_km_por_galon,costo_usd,duracion_minutos\r\n")
        append("RESUMEN_TANQUEADA,,")
        append(dateTime(startedAt)).append(',').append(dateTime(endedAt)).append(',').append(days).append(',')
        append(number(totals.distanceKm)).append(',').append(number(totals.fuelGallons)).append(',')
        append(numberOrDash(totals.averageKmPerGallon)).append(',')
        append(number(totals.fuelGallons * fuelPrice(context))).append(',')
        append(number(totals.elapsedSeconds / 60.0)).append("\r\n")
        trips.forEachIndexed { index, trip ->
            append("VIAJE,").append(index + 1).append(',').append(dateTime(trip.timestamp)).append(",,,")
            append(number(trip.distanceKm)).append(',').append(number(trip.gallons)).append(',')
            append(numberOrDash(trip.averageKmPerGallon)).append(',').append(number(trip.fuelCost)).append(',')
            append(number(trip.elapsedSeconds / 60.0)).append("\r\n")
        }
    }

    private fun fuelPrice(context: Context): Double = PreferenceManager.getDefaultSharedPreferences(context)
        .getString("fuelPricePerGallon", "3.00")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 3.0

    private fun dateTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value))
    private fun duration(seconds: Double): String {
        val minutes = (seconds / 60.0).toLong().coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d h", minutes / 60L, minutes % 60L)
    }
    private fun number(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun numberOrDash(value: Double?): String = value?.takeIf { it.isFinite() }?.let(::number) ?: "--"

    private fun treeDocument(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )

    private fun directory(context: Context, treeUri: Uri, parent: Uri, name: String): Uri? {
        findChild(context, treeUri, parent, name, DocumentsContract.Document.MIME_TYPE_DIR)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    private fun findChild(context: Context, treeUri: Uri, parent: Uri, name: String, mime: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name && cursor.getString(mimeIndex) == mime) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                }
            }
        }
        return null
    }

    private fun createText(context: Context, parent: Uri, mime: String, name: String, value: String): Uri? {
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: return null
        val written = context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(value) } != null
        return if (written) uri else null
    }

    private const val SCHEMA_VERSION = 1
}
