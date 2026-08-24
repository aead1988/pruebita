package com.aatorque.stats

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class FuelSyncRole(val value: String) {
    DISABLED("disabled"),
    PRIMARY("primary"),
    SECONDARY("secondary");

    companion object {
        fun from(value: String?): FuelSyncRole = entries.firstOrNull { it.value == value } ?: DISABLED
    }
}

enum class FuelSyncStatus {
    PUBLISHED,
    UPDATED,
    UP_TO_DATE,
    DISABLED,
    FOLDER_REQUIRED,
    SOURCE_REQUIRED,
    FILE_NOT_FOUND,
    ERROR
}

data class FuelSyncResult(val status: FuelSyncStatus, val timestamp: Long = 0L)

/** One-way Drive-folder synchronization between the car phone and a read-only viewer phone. */
object FuelDeviceSync {
    const val PREF_ROLE = "fuelSyncRole"
    const val PREF_AUTO = "fuelSyncAutomatic"

    private const val PREFS_NAME = "fuel_device_sync"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_SUCCESS = "last_success"
    private const val KEY_LAST_APPLIED_REMOTE = "last_applied_remote"
    private const val KEY_VIEWER_FILE_URI = "viewer_file_uri"
    private const val SYNC_FOLDER = "Sincronizacion"
    private const val SYNC_FILE = "my-huno-sync.json"
    private const val SYNC_MIME = "application/json"
    private const val SCHEMA_VERSION = 3
    private const val NOTIFICATION_CHANNEL = "my_huno_viewer_sync"
    private const val NOTIFICATION_ID = 2075

    fun role(context: Context): FuelSyncRole = FuelSyncRole.from(
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_ROLE, FuelSyncRole.DISABLED.value)
    )

    fun automatic(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO, true)

    fun lastSuccess(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_SUCCESS, 0L)

    fun setViewerFile(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VIEWER_FILE_URI, uri.toString()).commit()
    }

    fun viewerFile(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VIEWER_FILE_URI, null)?.let(Uri::parse)

    fun syncNow(context: Context): FuelSyncResult = when (role(context)) {
        FuelSyncRole.PRIMARY -> publish(context)
        FuelSyncRole.SECONDARY -> pull(context)
        FuelSyncRole.DISABLED -> FuelSyncResult(FuelSyncStatus.DISABLED)
    }

    fun publishIfEnabled(context: Context): FuelSyncResult =
        if (role(context) == FuelSyncRole.PRIMARY && automatic(context)) publish(context)
        else FuelSyncResult(FuelSyncStatus.DISABLED)

    @Synchronized
    fun publish(context: Context): FuelSyncResult {
        if (role(context) != FuelSyncRole.PRIMARY) return FuelSyncResult(FuelSyncStatus.DISABLED)
        val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context)
            ?: return FuelSyncResult(FuelSyncStatus.FOLDER_REQUIRED)
        return try {
            val timestamp = System.currentTimeMillis()
            val root = treeDocument(treeUri)
            val folder = directory(context, treeUri, root, SYNC_FOLDER) ?: root
            val existing = findChild(context, treeUri, folder, SYNC_FILE)
            val uri = existing ?: DocumentsContract.createDocument(
                context.contentResolver,
                folder,
                SYNC_MIME,
                SYNC_FILE
            ) ?: return FuelSyncResult(FuelSyncStatus.ERROR)
            val written = openReplacingOutput(context, uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(snapshot(context, timestamp).toString())
            } != null
            if (!written) return FuelSyncResult(FuelSyncStatus.ERROR)
            recordSuccess(context, timestamp)
            FuelSyncResult(FuelSyncStatus.PUBLISHED, timestamp)
        } catch (error: Exception) {
            Timber.e(error, "Unable to publish My Huno device sync")
            FuelSyncResult(FuelSyncStatus.ERROR)
        }
    }

    @Synchronized
    fun pull(context: Context): FuelSyncResult {
        if (role(context) != FuelSyncRole.SECONDARY) return FuelSyncResult(FuelSyncStatus.DISABLED)
        return try {
            val file = viewerFile(context) ?: run {
                val treeUri = MonthlyFuelCsvExporter.configuredDirectory(context)
                    ?: return FuelSyncResult(FuelSyncStatus.SOURCE_REQUIRED)
                val root = treeDocument(treeUri)
                val folder = findChild(context, treeUri, root, SYNC_FOLDER)
                    ?: return FuelSyncResult(FuelSyncStatus.FILE_NOT_FOUND)
                findChild(context, treeUri, folder, SYNC_FILE)
                    ?: return FuelSyncResult(FuelSyncStatus.FILE_NOT_FOUND)
            }
            val raw = context.contentResolver.openInputStream(file)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return FuelSyncResult(FuelSyncStatus.ERROR)
            val payload = JSONObject(raw)
            if (payload.optInt("schemaVersion") !in 1..SCHEMA_VERSION) {
                return FuelSyncResult(FuelSyncStatus.ERROR)
            }
            val remoteTimestamp = payload.optLong("updatedAt", 0L)
            if (remoteTimestamp <= 0L) return FuelSyncResult(FuelSyncStatus.ERROR)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (remoteTimestamp <= prefs.getLong(KEY_LAST_APPLIED_REMOTE, 0L)) {
                recordSuccess(context, System.currentTimeMillis())
                return FuelSyncResult(FuelSyncStatus.UP_TO_DATE, remoteTimestamp)
            }
            restore(context, payload)
            prefs.edit()
                .putLong(KEY_LAST_APPLIED_REMOTE, remoteTimestamp)
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .commit()
            notifyViewerUpdated(context)
            FuelSyncResult(FuelSyncStatus.UPDATED, remoteTimestamp)
        } catch (error: Exception) {
            Timber.e(error, "Unable to pull My Huno device sync")
            FuelSyncResult(FuelSyncStatus.ERROR)
        }
    }

    private fun snapshot(context: Context, timestamp: Long): JSONObject {
        val sinceRefuelStore = FuelEconomyStore(context)
        val sinceRefuel = sinceRefuelStore.load()
        val daily = DailyFuelEconomyStore(context).loadCurrent()
        val weekly = WeeklyFuelEconomyStore(context).loadCurrent()
        val monthly = MonthlyFuelEconomyStore(context).historyIncludingCurrent()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("updatedAt", timestamp)
            .put("sourceDeviceId", deviceId(context))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("sinceRefuel", JSONObject()
                .put("startedAt", sinceRefuelStore.tankPeriodStartTimestamp())
                .put("distanceKm", sinceRefuel.distanceKm)
                .put("fuelLiters", sinceRefuel.fuelLiters)
                .put("elapsedSeconds", sinceRefuel.elapsedSeconds))
            .put("daily", JSONObject()
                .put("day", daily.dayKey)
                .put("distanceKm", daily.distanceKm)
                .put("fuelLiters", daily.fuelLiters)
                .put("fuelCost", daily.fuelCost))
            .put("weekly", JSONObject()
                .put("week", weekly.weekKey)
                .put("distanceKm", weekly.distanceKm)
                .put("fuelLiters", weekly.fuelLiters)
                .put("fuelCost", weekly.fuelCost))
            .put("monthlyHistory", JSONArray().apply {
                monthly.forEach { value ->
                    put(JSONObject()
                        .put("month", value.monthKey)
                        .put("distanceKm", value.distanceKm)
                        .put("fuelLiters", value.fuelLiters)
                        .put("fuelCost", value.fuelCost))
                }
            })
            .put("trips", JSONArray().apply {
                FuelTripHistoryStore(context).load().forEach { put(it.toJson()) }
            })
            .put("refuels", JSONArray().apply {
                FuelRefuelHistoryStore(context).load().forEach { put(it.toJson()) }
            })
            .put("modules", FuelModuleStore(context).exportJson())
            .put("preferences", JSONObject()
                .put("fuelPricePerGallon", preferences.getString("fuelPricePerGallon", "3.24"))
                .put("fuelTankGallons", preferences.getString("fuelTankGallons", "11.90"))
                .put("tripPauseMergeMinutes", preferences.getString("tripPauseMergeMinutes", "60")))
    }

    private fun restore(context: Context, root: JSONObject) {
        root.optJSONObject("sinceRefuel")?.let { value ->
            FuelEconomyStore(context).restore(
                FuelEconomySnapshot(
                    distanceKm = value.optDouble("distanceKm"),
                    fuelLiters = value.optDouble("fuelLiters"),
                    elapsedSeconds = value.optDouble("elapsedSeconds")
                ),
                value.optLong("startedAt", System.currentTimeMillis())
            )
        }
        root.optJSONObject("daily")?.let { value ->
            DailyFuelEconomyStore(context).restore(DailyFuelEconomySnapshot(
                dayKey = value.optString("day"),
                distanceKm = value.optDouble("distanceKm"),
                fuelLiters = value.optDouble("fuelLiters"),
                fuelCost = value.optDouble("fuelCost")
            ))
        }
        root.optJSONObject("weekly")?.let { value ->
            WeeklyFuelEconomyStore(context).restore(WeeklyFuelEconomySnapshot(
                weekKey = value.optString("week"),
                distanceKm = value.optDouble("distanceKm"),
                fuelLiters = value.optDouble("fuelLiters"),
                fuelCost = value.optDouble("fuelCost")
            ))
        }
        root.optJSONArray("monthlyHistory")?.let { array ->
            MonthlyFuelEconomyStore(context).restore(buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(MonthlyFuelEconomySnapshot(
                        monthKey = item.optString("month"),
                        distanceKm = item.optDouble("distanceKm"),
                        fuelLiters = item.optDouble("fuelLiters"),
                        fuelCost = item.optDouble("fuelCost")
                    ))
                }
            })
        }
        root.optJSONArray("trips")?.let { array ->
            FuelTripHistoryStore(context).replace(buildList {
                for (index in 0 until array.length()) add(ArchivedTrip.fromJson(array.getJSONObject(index)))
            })
        }
        root.optJSONArray("refuels")?.let { array ->
            FuelRefuelHistoryStore(context).replace(buildList {
                for (index in 0 until array.length()) {
                    add(FuelRefuelRecord.fromJson(array.getJSONObject(index)))
                }
            })
        }
        root.optJSONArray("modules")?.let { FuelModuleStore(context).restore(it) }
        root.optJSONObject("preferences")?.let { value ->
            val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
            listOf("fuelPricePerGallon", "fuelTankGallons", "tripPauseMergeMinutes").forEach { key ->
                if (value.has(key)) editor.putString(key, value.optString(key))
            }
            editor.commit()
        }
    }

    private fun deviceId(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).commit()
        }
    }

    private fun recordSuccess(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SUCCESS, timestamp).apply()
    }

    private fun notifyViewerUpdated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL,
            context.getString(R.string.viewer_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ))
        val daily = DailyFuelEconomyStore(context).loadCurrent()
        val trips = FuelTripHistoryStore(context).load()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.viewer_notification_title))
            .setContentText(context.getString(
                R.string.viewer_notification_text,
                trips.size,
                String.format(java.util.Locale.US, "%.2f", daily.distanceKm),
                String.format(java.util.Locale.US, "%.2f", daily.fuelCost)
            ))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun treeDocument(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )

    private fun directory(context: Context, treeUri: Uri, parent: Uri, name: String): Uri? {
        findChild(context, treeUri, parent, name)?.let { return it }
        return DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        )
    }

    private fun findChild(context: Context, treeUri: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                }
            }
        }
        return null
    }

    private fun openReplacingOutput(context: Context, uri: Uri) = try {
        context.contentResolver.openOutputStream(uri, "rwt")
    } catch (_: Exception) {
        context.contentResolver.openOutputStream(uri, "w")
    }
}

object FuelSyncScheduler {
    private const val UNIQUE_PERIODIC = "my_huno_periodic_device_sync"
    private const val UNIQUE_IMMEDIATE = "my_huno_immediate_device_sync"

    fun refresh(context: Context, runImmediately: Boolean = true) {
        val manager = WorkManager.getInstance(context)
        if (FuelDeviceSync.role(context) == FuelSyncRole.DISABLED || !FuelDeviceSync.automatic(context)) {
            manager.cancelUniqueWork(UNIQUE_PERIODIC)
            manager.cancelUniqueWork(UNIQUE_IMMEDIATE)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<FuelSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        if (runImmediately) {
            manager.enqueueUniqueWork(
                UNIQUE_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FuelSyncWorker>().setConstraints(constraints).build()
            )
        }
    }
}

class FuelSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (FuelDeviceSync.syncNow(applicationContext).status) {
        FuelSyncStatus.ERROR -> Result.retry()
        else -> Result.success()
    }
}
