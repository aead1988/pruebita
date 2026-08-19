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
    val awardId: String,
    val awardTitle: String
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
            awardId = value.optString("awardId", "trip_complete"),
            awardTitle = value.optString("awardTitle", "Viaje completado")
        )
    }
}

class FuelTripHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun add(trip: ArchivedTrip) {
        val values = load().toMutableList().apply { add(trip) }.takeLast(MAX_TRIPS)
        write(values)
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

/** Writes AA Torque reports to the user-selected Android document tree, including Drive providers. */
object FuelDriveArchive {
    private const val PREFS_NAME = "fuel_drive_archive"
    private const val KEY_LAST_SIGNATURE = "last_trip_signature"

    data class ExportResult(val writtenFiles: Int, val awardTitle: String) {
        val success: Boolean get() = writtenFiles > 0
    }

    fun exportJourney(
        context: Context,
        journey: FuelEconomySnapshot,
        journeyStartedAt: Long,
        automatic: Boolean
    ): ExportResult? {
        val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context) ?: return null
        if (journey.distanceKm < 0.05 && journey.elapsedSeconds < 60.0) return null
        val signature = listOf(
            journeyStartedAt / 60_000L,
            (journey.distanceKm * 100).toLong(),
            (journey.fuelLiters * 10_000).toLong(),
            journey.elapsedSeconds.toLong()
        ).joinToString(":")
        val archivePreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (automatic && archivePreferences.getString(KEY_LAST_SIGNATURE, null) == signature) return null

        val price = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("fuelPricePerGallon", "3.00")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 3.0
        val award = awardFor(journey)
        val timestamp = System.currentTimeMillis()
        val trip = ArchivedTrip(
            timestamp = timestamp,
            distanceKm = journey.distanceKm,
            fuelLiters = journey.fuelLiters,
            elapsedSeconds = journey.elapsedSeconds,
            fuelCost = journey.fuelGallons * price,
            awardId = award.first,
            awardTitle = award.second
        )
        FuelTripHistoryStore(context).add(trip)

        return try {
            val root = treeDocument(context, treeUri)
            val results = directory(context, treeUri, root, "Resultados") ?: root
            val awards = directory(context, treeUri, root, "Premios") ?: root
            val cards = directory(context, treeUri, root, "Tarjetas") ?: root
            val data = directory(context, treeUri, root, "Datos") ?: root
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(timestamp))
            var written = 0
            if (writeText(context, results, "text/csv", "viaje-$stamp.csv", tripCsv(trip))) written++
            if (writeText(context, data, "application/json", "respaldo-aa-torque-$stamp.json", backupJson(context))) written++
            if (writeBytes(context, cards, "image/png", "tarjeta-viaje-$stamp.png", tripCard(trip, false))) written++
            if (writeBytes(context, awards, "image/png", "premio-${trip.awardId}-$stamp.png", tripCard(trip, true))) written++
            if (writeText(context, awards, "text/html", "celebracion-$stamp.html", animatedCelebration(trip))) written++
            if (writeText(context, data, "text/csv", "historial-mensual-$stamp.csv", MonthlyFuelEconomyStore(context).exportCsv())) written++
            if (written > 0) archivePreferences.edit().putString(KEY_LAST_SIGNATURE, signature).apply()
            ExportResult(written, trip.awardTitle)
        } catch (error: Exception) {
            Timber.e(error, "Unable to archive AA Torque journey")
            null
        }
    }

    fun exportCurrentBackup(context: Context): Uri? {
        val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context) ?: return null
        return try {
            val root = treeDocument(context, treeUri)
            val data = directory(context, treeUri, root, "Datos") ?: root
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            createText(context, data, "application/json", "respaldo-aa-torque-$stamp.json", backupJson(context))
        } catch (error: Exception) {
            Timber.e(error, "Unable to export AA Torque backup")
            null
        }
    }

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
            val values = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(MonthlyFuelEconomySnapshot(
                        item.getString("month"),
                        item.optDouble("distanceKm"),
                        item.optDouble("fuelLiters"),
                        item.optDouble("fuelCost")
                    ))
                }
            }
            MonthlyFuelEconomyStore(context).restore(values)
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

    private fun backupJson(context: Context): String {
        val sinceRefuel = FuelEconomyStore(context)
        val trip = sinceRefuel.load()
        val months = MonthlyFuelEconomyStore(context).historyIncludingCurrent()
        val archivedTrips = FuelTripHistoryStore(context).load()
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("sinceRefuel", JSONObject()
                .put("startedAt", sinceRefuel.tankPeriodStartTimestamp())
                .put("distanceKm", trip.distanceKm)
                .put("fuelLiters", trip.fuelLiters)
                .put("elapsedSeconds", trip.elapsedSeconds))
            .put("monthlyHistory", JSONArray().apply {
                months.forEach { value -> put(JSONObject()
                    .put("month", value.monthKey)
                    .put("distanceKm", value.distanceKm)
                    .put("fuelLiters", value.fuelLiters)
                    .put("fuelCost", value.fuelCost)) }
            })
            .put("trips", JSONArray().apply { archivedTrips.forEach { put(it.toJson()) } })
            .toString(2)
    }

    private fun awardFor(value: FuelEconomySnapshot): Pair<String, String> {
        val average = value.averageKmPerGallon ?: 0.0
        return when {
            average >= 55.0 -> "economy_master" to "Maestro del ahorro"
            average >= 48.0 -> "efficient_driver" to "Conducción eficiente"
            value.distanceKm >= 150.0 -> "road_explorer" to "Explorador de carretera"
            value.distanceKm >= 80.0 -> "long_trip" to "Gran viajero"
            else -> "trip_complete" to "Viaje completado"
        }
    }

    private fun tripCsv(value: ArchivedTrip): String = buildString {
        append('\uFEFF')
        append("timestamp,distance_km,gallons_used,average_km_per_gallon,fuel_cost_usd,duration_minutes,award\r\n")
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value.timestamp))).append(',')
        append(number(value.distanceKm)).append(',').append(number(value.gallons)).append(',')
        append(value.averageKmPerGallon?.let(::number) ?: "").append(',')
        append(number(value.fuelCost)).append(',').append(number(value.elapsedSeconds / 60.0)).append(',')
        append('"').append(value.awardTitle.replace("\"", "\"\"")).append('"').append("\r\n")
    }

    private fun tripCard(value: ArchivedTrip, awardOnly: Boolean): ByteArray {
        val width = if (awardOnly) 900 else 1200
        val height = if (awardOnly) 900 else 675
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(10, 12, 16))
        paint.color = Color.rgb(255, 199, 0)
        canvas.drawRoundRect(RectF(34f, 34f, width - 34f, height - 34f), 36f, 36f, paint)
        paint.color = Color.rgb(20, 23, 29)
        canvas.drawRoundRect(RectF(46f, 46f, width - 46f, height - 46f), 30f, 30f, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = if (awardOnly) 56f else 48f
        canvas.drawText("AA TORQUE", width / 2f, 125f, paint)
        paint.color = Color.rgb(255, 199, 0)
        paint.textSize = if (awardOnly) 76f else 62f
        canvas.drawText(value.awardTitle.uppercase(Locale.getDefault()), width / 2f, if (awardOnly) 285f else 220f, paint)
        paint.color = Color.WHITE
        paint.textSize = if (awardOnly) 42f else 38f
        val average = value.averageKmPerGallon?.let(::number) ?: "--"
        if (awardOnly) {
            canvas.drawText("★", width / 2f, 470f, Paint(paint).apply { textSize = 180f; color = Color.rgb(255, 199, 0) })
            canvas.drawText("$average km/gal · ${number(value.distanceKm)} km", width / 2f, 650f, paint)
            canvas.drawText(SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(value.timestamp)), width / 2f, 735f, paint)
        } else {
            val labels = listOf(
                "${number(value.distanceKm)} km" to "DISTANCIA",
                "${number(value.gallons)} gal" to "COMBUSTIBLE",
                "$average km/gal" to "PROMEDIO",
                "\$${number(value.fuelCost)}" to "COSTO"
            )
            labels.forEachIndexed { index, pair ->
                val x = width * (index + 0.5f) / labels.size
                paint.color = Color.WHITE; paint.textSize = 42f; canvas.drawText(pair.first, x, 390f, paint)
                paint.color = Color.LTGRAY; paint.textSize = 22f; canvas.drawText(pair.second, x, 435f, paint)
            }
            paint.color = Color.LTGRAY; paint.textSize = 25f
            canvas.drawText(SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.getDefault()).format(Date(value.timestamp)), width / 2f, 545f, paint)
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 96, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun animatedCelebration(value: ArchivedTrip): String {
        val average = value.averageKmPerGallon?.let(::number) ?: "--"
        return """<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>AA Torque</title>
<style>body{margin:0;background:#0a0c10;color:white;font-family:system-ui;display:grid;place-items:center;min-height:100vh;overflow:hidden}.card{text-align:center;border:4px solid #ffc700;border-radius:32px;padding:42px;background:#14171d;box-shadow:0 0 60px #ffc70055;animation:enter 1s ease-out}.star{font-size:9rem;color:#ffc700;animation:pulse 1.2s infinite}.stats{display:flex;gap:28px;justify-content:center;flex-wrap:wrap}.confetti{position:fixed;top:-10%;font-size:24px;animation:fall 4s linear infinite}@keyframes enter{from{transform:scale(.4) rotate(-8deg);opacity:0}}@keyframes pulse{50%{transform:scale(1.16);filter:drop-shadow(0 0 25px #ffc700)}}@keyframes fall{to{transform:translateY(120vh) rotate(720deg)}}small{color:#aaa}</style>
<body>${(1..22).joinToString("") { "<i class=confetti style=\"left:${it * 4}%;animation-delay:-${it % 4}s\">${if (it % 2 == 0) "★" else "●"}</i>" }}<main class=card><div class=star>★</div><h1>${escapeHtml(value.awardTitle)}</h1><div class=stats><b>${number(value.distanceKm)} km<br><small>Distancia</small></b><b>${number(value.gallons)} gal<br><small>Combustible</small></b><b>$average km/gal<br><small>Promedio</small></b><b>${'$'}${number(value.fuelCost)}<br><small>Costo</small></b></div><p>AA Torque · ${SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(value.timestamp))}</p></main></body></html>"""
    }

    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun number(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun treeDocument(context: Context, treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )

    private fun directory(context: Context, treeUri: Uri, parent: Uri, name: String): Uri? {
        findChild(context, treeUri, parent, name, DocumentsContract.Document.MIME_TYPE_DIR)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    private fun findChild(context: Context, treeUri: Uri, parent: Uri, name: String, mime: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
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

    private fun writeText(context: Context, parent: Uri, mime: String, name: String, value: String): Boolean =
        createText(context, parent, mime, name, value) != null

    private fun createText(context: Context, parent: Uri, mime: String, name: String, value: String): Uri? {
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: return null
        val written = context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(value) } != null
        return if (written) uri else null
    }

    private fun writeBytes(context: Context, parent: Uri, mime: String, name: String, value: ByteArray): Boolean {
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: return false
        return context.contentResolver.openOutputStream(uri, "w")?.use { it.write(value) } != null
    }

    private const val SCHEMA_VERSION = 1
}
