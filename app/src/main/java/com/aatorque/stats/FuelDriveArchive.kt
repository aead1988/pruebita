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
    val startedAt: Long,
    val endedAt: Long,
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
    val averageSpeedKph: Double?
        get() = if (elapsedSeconds > 1.0) distanceKm / (elapsedSeconds / 3_600.0) else null
    val classification: String
        get() {
            val average = averageKmPerGallon ?: return "Viaje sin puntuación"
            return when {
                average >= 48.0 -> "Viaje eficiente"
                average >= 42.0 -> "Viaje normal"
                else -> "Viaje poco eficiente"
            }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", endedAt) // Kept for compatibility with versions 2.0.53-2.0.55.
        .put("startedAt", startedAt)
        .put("endedAt", endedAt)
        .put("distanceKm", distanceKm)
        .put("fuelLiters", fuelLiters)
        .put("elapsedSeconds", elapsedSeconds)
        .put("fuelCost", fuelCost)
        .put("awardId", awardId)
        .put("awardTitle", awardTitle)

    companion object {
        fun fromJson(value: JSONObject): ArchivedTrip {
            val elapsedSeconds = value.optDouble("elapsedSeconds")
            val legacyTimestamp = value.optLong("timestamp", System.currentTimeMillis())
            val endedAt = value.optLong("endedAt", legacyTimestamp)
            val inferredStart = endedAt - (elapsedSeconds * 1_000.0).toLong().coerceAtLeast(0L)
            return ArchivedTrip(
                startedAt = value.optLong("startedAt", inferredStart),
                endedAt = endedAt,
                distanceKm = value.optDouble("distanceKm"),
                fuelLiters = value.optDouble("fuelLiters"),
                elapsedSeconds = elapsedSeconds,
                fuelCost = value.optDouble("fuelCost"),
                awardId = value.optString("awardId", ""),
                awardTitle = value.optString("awardTitle", "")
            )
        }
    }
}

class FuelTripHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun add(trip: ArchivedTrip) {
        write(load().toMutableList().apply { add(trip) }.takeLast(MAX_TRIPS))
    }

    @Synchronized
    fun upsert(trip: ArchivedTrip) {
        val values = load().toMutableList()
        val existingIndex = values.indexOfLast { it.startedAt == trip.startedAt }
        if (existingIndex >= 0) values[existingIndex] = trip else values.add(trip)
        write(values.takeLast(MAX_TRIPS))
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

    fun resumableJourney(context: Context, now: Long = System.currentTimeMillis()): ArchivedTrip? {
        val pauseMinutes = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_TRIP_PAUSE_MINUTES, DEFAULT_TRIP_PAUSE_MINUTES.toString())
            ?.toLongOrNull()
            ?.coerceIn(0L, MAX_TRIP_PAUSE_MINUTES)
            ?: DEFAULT_TRIP_PAUSE_MINUTES
        if (pauseMinutes == 0L) return null
        val latest = FuelTripHistoryStore(context).load().maxByOrNull { it.endedAt } ?: return null
        val gap = now - latest.endedAt
        if (gap !in 0..pauseMinutes * 60_000L) return null
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return latest.takeIf { dayFormat.format(Date(it.endedAt)) == dayFormat.format(Date(now)) }
    }

    fun archiveJourneyAndUpdateDailyCard(
        context: Context,
        journey: FuelEconomySnapshot,
        journeyStartedAt: Long
    ): DailyCardResult? {
        if (journey.distanceKm < 0.05 && journey.elapsedSeconds < 60.0) return null
        val endedAt = System.currentTimeMillis()
        val startedAt = journeyStartedAt.coerceAtMost(endedAt)
        val signature = listOf(
            startedAt / 60_000L,
            (journey.distanceKm * 100).toLong(),
            (journey.fuelLiters * 10_000).toLong(),
            journey.elapsedSeconds.toLong()
        ).joinToString(":")
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_LAST_SIGNATURE, null) != signature) {
            val price = fuelPrice(context)
            FuelTripHistoryStore(context).upsert(
                ArchivedTrip(
                    startedAt = startedAt,
                    endedAt = endedAt,
                    distanceKm = journey.distanceKm,
                    fuelLiters = journey.fuelLiters,
                    elapsedSeconds = journey.elapsedSeconds,
                    fuelCost = journey.fuelGallons * price
                )
            )
            preferences.edit().putString(KEY_LAST_SIGNATURE, signature).apply()
        }
        val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context) ?: return null
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
                .filter { it.endedAt in periodStartedAt..endedAt }
                .toMutableList()
            if (currentJourney.distanceKm >= 0.05 || currentJourney.elapsedSeconds >= 60.0) {
                val currentTrip = ArchivedTrip(
                    startedAt = currentJourneyStartedAt.coerceAtMost(endedAt),
                    endedAt = endedAt,
                    distanceKm = currentJourney.distanceKm,
                    fuelLiters = currentJourney.fuelLiters,
                    elapsedSeconds = currentJourney.elapsedSeconds,
                    fuelCost = currentJourney.fuelGallons * fuelPrice(context)
                )
                val existingIndex = history.indexOfLast { it.startedAt == currentTrip.startedAt }
                if (existingIndex >= 0) history[existingIndex] = currentTrip else history.add(currentTrip)
                FuelTripHistoryStore(context).upsert(currentTrip)
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
            Timber.e(error, "Unable to restore Huno backup")
            false
        }
    }

    private fun writeDailyCard(context: Context, treeUri: Uri): DailyCardResult? {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val trips = FuelTripHistoryStore(context).load().filter {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.endedAt)) == dayKey
        }.sortedBy { it.startedAt }
        if (trips.isEmpty()) return null
        return try {
            val root = treeDocument(treeUri)
            val cards = directory(context, treeUri, root, "Resumenes diarios") ?: root
            val name = "resumen-diario-$dayKey.png"
            val existing = findChild(context, treeUri, cards, name, "image/png")
            val uri = existing ?: DocumentsContract.createDocument(context.contentResolver, cards, "image/png", name)
                ?: return null
            val card = dailySummaryCard(trips)
            val written = openReplacingOutput(context, uri)?.use { it.write(card) } != null
            if (written) DailyCardResult(uri, trips.size) else null
        } catch (error: Exception) {
            Timber.e(error, "Unable to update daily summary card")
            null
        }
    }

    private fun dailySummaryCard(trips: List<ArchivedTrip>): ByteArray {
        val width = 2200
        val height = maxOf(900, 450 + trips.size * 96)
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
        canvas.drawText("HUNO · RESUMEN DE HOY", 85f, 115f, paint)
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
        paint.textSize = 30f
        canvas.drawText(
            "${number(totalDistance)} km    ${number(totalGallons)} gal    ${numberOrDash(totalAverage)} km/gal    \$${number(totalCost)}    ${duration(totalSeconds)}",
            85f,
            220f,
            paint
        )

        paint.color = Color.LTGRAY
        paint.textSize = 22f
        canvas.drawText("VIAJE", 85f, 282f, paint)
        canvas.drawText("INICIO", 220f, 282f, paint)
        canvas.drawText("FIN", 345f, 282f, paint)
        canvas.drawText("DISTANCIA", 470f, 282f, paint)
        canvas.drawText("GALONES", 680f, 282f, paint)
        canvas.drawText("RENDIMIENTO", 870f, 282f, paint)
        canvas.drawText("VEL. PROM.", 1140f, 282f, paint)
        canvas.drawText("COSTO", 1360f, 282f, paint)
        canvas.drawText("DURACIÓN", 1510f, 282f, paint)
        canvas.drawText("PUNTUACIÓN", 1700f, 282f, paint)

        trips.forEachIndexed { index, trip ->
            val y = 340f + index * 96f
            paint.color = if (index % 2 == 0) Color.rgb(35, 39, 48) else Color.rgb(28, 32, 40)
            canvas.drawRoundRect(RectF(72f, y - 43f, width - 72f, y + 28f), 14f, 14f, paint)
            paint.color = Color.WHITE
            paint.textSize = 26f
            canvas.drawText("Viaje ${index + 1}", 85f, y, paint)
            canvas.drawText(time(trip.startedAt), 220f, y, paint)
            canvas.drawText(time(trip.endedAt), 345f, y, paint)
            canvas.drawText("${number(trip.distanceKm)} km", 470f, y, paint)
            canvas.drawText("${number(trip.gallons)} gal", 680f, y, paint)
            canvas.drawText("${numberOrDash(trip.averageKmPerGallon)} km/gal", 870f, y, paint)
            canvas.drawText("${numberOrDash(trip.averageSpeedKph)} km/h", 1140f, y, paint)
            canvas.drawText("\$${number(trip.fuelCost)}", 1360f, y, paint)
            canvas.drawText(duration(trip.elapsedSeconds), 1510f, y, paint)
            paint.color = classificationColor(trip.classification)
            canvas.drawText(trip.classification, 1700f, y, paint)
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
        append("tipo,numero,inicio,fin,dias,distance_km,galones_usados,promedio_km_por_galon,velocidad_promedio_kmh,costo_usd,duracion_minutos,clasificacion\r\n")
        append("RESUMEN_TANQUEADA,,")
        append(dateTime(startedAt)).append(',').append(dateTime(endedAt)).append(',').append(days).append(',')
        append(number(totals.distanceKm)).append(',').append(number(totals.fuelGallons)).append(',')
        append(numberOrDash(totals.averageKmPerGallon)).append(',').append(numberOrDash(totals.averageSpeedKph)).append(',')
        append(number(totals.fuelGallons * fuelPrice(context))).append(',')
        append(number(totals.elapsedSeconds / 60.0)).append(',').append("\r\n")
        trips.forEachIndexed { index, trip ->
            append("VIAJE,").append(index + 1).append(',')
                .append(dateTime(trip.startedAt)).append(',').append(dateTime(trip.endedAt)).append(",,")
            append(number(trip.distanceKm)).append(',').append(number(trip.gallons)).append(',')
            append(numberOrDash(trip.averageKmPerGallon)).append(',').append(numberOrDash(trip.averageSpeedKph)).append(',')
                .append(number(trip.fuelCost)).append(',')
            append(number(trip.elapsedSeconds / 60.0)).append(',').append(trip.classification).append("\r\n")
        }
    }

    private fun fuelPrice(context: Context): Double = PreferenceManager.getDefaultSharedPreferences(context)
        .getString("fuelPricePerGallon", "3.24")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 3.24

    private fun dateTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value))
    private fun time(value: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(value))
    private fun classificationColor(value: String): Int = when (value) {
        "Viaje eficiente" -> Color.rgb(76, 217, 100)
        "Viaje normal" -> Color.rgb(255, 199, 0)
        "Viaje poco eficiente" -> Color.rgb(255, 107, 107)
        else -> Color.LTGRAY
    }
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

    private fun openReplacingOutput(context: Context, uri: Uri) = try {
        context.contentResolver.openOutputStream(uri, "rwt")
    } catch (_: Exception) {
        context.contentResolver.openOutputStream(uri, "w")
    }

    private const val PREF_TRIP_PAUSE_MINUTES = "tripPauseMergeMinutes"
    private const val DEFAULT_TRIP_PAUSE_MINUTES = 60L
    private const val MAX_TRIP_PAUSE_MINUTES = 360L
    private const val SCHEMA_VERSION = 1
}
