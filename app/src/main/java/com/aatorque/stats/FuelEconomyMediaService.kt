package com.aatorque.stats

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser.MediaItem
import android.media.browse.MediaBrowser.MediaItem.FLAG_PLAYABLE
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService
import androidx.preference.PreferenceManager
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Android Auto media source with selectable fuel, engine, trip and diagnostic modes. */
class FuelEconomyMediaService : MediaBrowserService() {
    private lateinit var mediaSession: MediaSession
    private lateinit var store: FuelEconomyStore
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var refreshTask: ScheduledFuture<*>? = null
    private var torqueService: ITorqueService? = null
    private var torqueBound = false
    private var snapshot = FuelEconomySnapshot(0.0, 0.0)
    private var telemetry = VehicleTelemetry()
    private var telemetryPids = emptyList<TelemetryPid>()
    private var lastSampleNanos = 0L
    private var lastPersistNanos = 0L
    private var tracking = true
    private var observedResetGeneration = 0L
    private var selectedMode = DisplayMode.CONSUMPTION

    override fun onCreate() {
        super.onCreate()
        store = FuelEconomyStore(this)
        snapshot = store.load()
        observedResetGeneration = store.resetGeneration()
        selectedMode = loadMode()
        mediaSession = MediaSession(this, "AA Torque selectable telemetry").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    tracking = true
                    this@FuelEconomyMediaService.mediaSession.isActive = true
                    publishPlaybackState()
                    connectToTorque()
                }

                override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
                    DisplayMode.fromMediaId(mediaId)?.let(::selectMode)
                    onPlay()
                }

                override fun onSkipToNext() {
                    selectMode(selectedMode.next())
                }

                override fun onPause() {
                    tracking = false
                    persistTrip()
                    publishPlaybackState()
                }

                override fun onStop() {
                    tracking = false
                    persistTrip()
                    publishPlaybackState()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        ACTION_RESET_TRIP -> resetTrip()
                        ACTION_NEXT_MODE -> selectMode(selectedMode.next())
                    }
                }
            })
            isActive = true
        }
        setSessionToken(mediaSession.sessionToken)
        publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_waiting)))
        publishPlaybackState()
        connectToTorque()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != MEDIA_ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val artwork = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        val items = DisplayMode.entries.map { mode ->
            val description = MediaDescription.Builder()
                .setMediaId(mode.mediaId)
                .setTitle(getString(mode.titleResource))
                .setSubtitle(getString(mode.subtitleResource))
                .setIconBitmap(artwork)
                .build()
            MediaItem(description, FLAG_PLAYABLE)
        }.toMutableList()
        result.sendResult(items)
    }

    private val torqueConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torqueService = ITorqueService.Stub.asInterface(binder)
            executor.execute(::discoverPidsAndStart)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torqueService = null
            torqueBound = false
            telemetryPids = emptyList()
            stopRefreshTask()
            publishMetadata(snapshot.copy(connected = false, status = getString(R.string.fuel_media_disconnected)))
        }
    }

    private fun connectToTorque() {
        if (torqueBound) return
        val intent = Intent().apply {
            setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
        }
        torqueBound = try {
            bindService(intent, torqueConnection, BIND_AUTO_CREATE)
        } catch (error: Exception) {
            Timber.e(error, "Unable to bind selectable media service to Torque Pro")
            false
        }
        if (!torqueBound) publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_torque_required)))
    }

    private fun discoverPidsAndStart() {
        val service = torqueService ?: return
        try {
            val pids = service.listAllPIDs()
            val information = service.getPIDInformation(pids)
            val candidates = pids.zip(information).mapNotNull { (pid, rawInfo) ->
                val fields = rawInfo.split(',')
                if (fields.size < 3) null else PidCandidate(
                    pid,
                    fields.take(2).joinToString(" ").lowercase(Locale.ROOT),
                    fields[2].trim().lowercase(Locale.ROOT)
                )
            }

            val speed = findCandidate(candidates, "0d,0") { it.name.contains("speed") && speedScale(it.unit) != null }
                ?: PidCandidate("0d,0", "speed", "km/h")
            val fuelFlow = candidates.firstOrNull {
                it.name.contains("fuel") && (it.name.contains("flow") || it.name.contains("rate")) &&
                    fuelFlowScale(it.unit) != null
            }
            if (fuelFlow == null) {
                publishMetadata(snapshot.copy(connected = true, status = getString(R.string.fuel_media_no_flow_pid)))
                return
            }

            val optional = listOfNotNull(
                findCandidate(candidates, "0c,0") { it.name.contains("rpm") || it.name.contains("revolution") }
                    ?: PidCandidate("0c,0", "rpm", "rpm"),
                findCandidate(candidates, "05,0") { it.name.contains("coolant") && it.name.contains("temp") }
                    ?: PidCandidate("05,0", "coolant temperature", "°c"),
                findCandidate(candidates, "04,0") { it.name.contains("engine") && it.name.contains("load") }
                    ?: PidCandidate("04,0", "engine load", "%"),
                findCandidate(candidates, "2f,0") { it.name.contains("fuel") && it.name.contains("level") }
                    ?: PidCandidate("2f,0", "fuel level", "%"),
                candidates.firstOrNull {
                    it.name.contains("voltage") && !it.name.contains("oxygen") && voltageScale(it.unit) != null
                }
            )

            telemetryPids = buildList {
                add(TelemetryPid(Metric.SPEED, speed.pid, speedScale(speed.unit) ?: 1.0))
                add(TelemetryPid(Metric.FUEL_FLOW, fuelFlow.pid, fuelFlowScale(fuelFlow.unit) ?: 1.0))
                optional.forEach { candidate ->
                    val metric = when {
                        candidate.pid == "0c,0" || candidate.name.contains("rpm") -> Metric.RPM
                        candidate.pid == "05,0" || candidate.name.contains("coolant") -> Metric.COOLANT
                        candidate.pid == "04,0" || candidate.name.contains("engine load") -> Metric.ENGINE_LOAD
                        candidate.pid == "2f,0" || candidate.name.contains("fuel level") -> Metric.FUEL_LEVEL
                        else -> Metric.VOLTAGE
                    }
                    val scale = when (metric) {
                        Metric.COOLANT -> temperatureScale(candidate.unit)
                        Metric.VOLTAGE -> voltageScale(candidate.unit) ?: 1.0
                        else -> 1.0
                    }
                    add(TelemetryPid(metric, candidate.pid, scale, candidate.unit.contains("f")))
                }
            }.distinctBy { it.metric }

            lastSampleNanos = System.nanoTime()
            stopRefreshTask()
            refreshTask = executor.scheduleWithFixedDelay(::sampleTorque, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Timber.e(error, "Unable to discover selectable telemetry PIDs")
            publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_pid_error)))
        }
    }

    private fun sampleTorque() {
        val service = torqueService ?: return
        if (telemetryPids.size < 2) return
        try {
            val currentResetGeneration = store.resetGeneration()
            if (currentResetGeneration != observedResetGeneration) {
                observedResetGeneration = currentResetGeneration
                snapshot = FuelEconomySnapshot(0.0, 0.0, connected = true)
                lastSampleNanos = System.nanoTime()
            }
            val values = service.getPIDValuesAsDouble(telemetryPids.map { it.pid }.toTypedArray())
            if (values.size < 2) return
            telemetryPids.forEachIndexed { index, pid ->
                if (index >= values.size) return@forEachIndexed
                var value = values[index] * pid.scale
                if (pid.metric == Metric.COOLANT && pid.fahrenheit) value = (values[index] - 32.0) * 5.0 / 9.0
                telemetry = telemetry.with(pid.metric, value)
            }

            val now = System.nanoTime()
            val elapsedSeconds = if (lastSampleNanos == 0L) 0.0 else
                (now - lastSampleNanos).coerceAtMost(MAX_SAMPLE_GAP_NANOS) / NANOS_PER_SECOND
            lastSampleNanos = now
            if (tracking && elapsedSeconds > 0.0) {
                snapshot = snapshot.copy(
                    distanceKm = snapshot.distanceKm + telemetry.speedKph * elapsedSeconds / 3600.0,
                    fuelLiters = snapshot.fuelLiters + telemetry.fuelLitersPerHour * elapsedSeconds / 3600.0,
                    elapsedSeconds = snapshot.elapsedSeconds + elapsedSeconds
                )
            }
            val instant = if (telemetry.fuelLitersPerHour > 0.01 && telemetry.speedKph > 0.1) {
                telemetry.speedKph / telemetry.fuelLitersPerHour * FuelEconomySnapshot.US_GALLON_LITERS
            } else null
            snapshot = snapshot.copy(
                instantKmPerGallon = instant,
                connected = true,
                status = if (tracking) getString(R.string.fuel_media_recording) else getString(R.string.fuel_media_paused)
            )
            publishMetadata(snapshot)
            if (now - lastPersistNanos >= PERSIST_INTERVAL_NANOS) {
                persistTrip()
                lastPersistNanos = now
            }
        } catch (error: Exception) {
            Timber.e(error, "Unable to read selectable telemetry values")
        }
    }

    private fun publishMetadata(value: FuelEconomySnapshot) {
        val text = when (selectedMode) {
            DisplayMode.CONSUMPTION -> consumptionText(value)
            DisplayMode.ENGINE -> engineText(value)
            DisplayMode.TRIP -> tripText(value)
            DisplayMode.DIAGNOSTICS -> diagnosticsText(value)
            DisplayMode.FUEL_COST -> fuelCostText(value)
        }
        val artwork = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, selectedMode.mediaId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, text.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, text.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text.subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, text.subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, text.description)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, text.description)
                .putString(MediaMetadata.METADATA_KEY_GENRE, getString(selectedMode.titleResource))
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
                .build()
        )
    }

    private fun consumptionText(value: FuelEconomySnapshot): DisplayText = DisplayText(
        getString(R.string.mode_consumption_title_format, one(value.averageKmPerGallon)),
        getString(R.string.mode_consumption_subtitle_format, one(value.instantKmPerGallon), one(telemetry.speedKph)),
        getString(R.string.mode_consumption_description_format, two(value.distanceKm), two(value.fuelGallons), value.status)
    )

    private fun engineText(value: FuelEconomySnapshot): DisplayText = DisplayText(
        getString(R.string.mode_engine_title_format, whole(telemetry.rpm)),
        getString(R.string.mode_engine_subtitle_format, one(telemetry.coolantCelsius), one(telemetry.engineLoadPercent)),
        getString(R.string.mode_engine_description_format, one(telemetry.voltage), one(telemetry.speedKph), value.status)
    )

    private fun tripText(value: FuelEconomySnapshot): DisplayText {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val price = preferences.getString(PREF_FUEL_PRICE, "3.00")?.toDoubleOrNull() ?: 3.0
        val tankGallons = preferences.getString(PREF_TANK_GALLONS, "11.90")?.toDoubleOrNull() ?: 11.9
        val remainingGallons = telemetry.fuelLevelPercent?.let { tankGallons * it.coerceIn(0.0, 100.0) / 100.0 }
        val averageKmPerGallon = value.averageKmPerGallon
        val range = if (remainingGallons != null && averageKmPerGallon != null) remainingGallons * averageKmPerGallon else null
        return DisplayText(
            getString(R.string.mode_trip_title_format, two(value.distanceKm), duration(value.elapsedSeconds)),
            getString(R.string.mode_trip_subtitle_format, one(value.averageSpeedKph), money(value.fuelGallons * price)),
            getString(R.string.mode_trip_description_format, whole(range), one(telemetry.fuelLevelPercent), value.status)
        )
    }

    private fun diagnosticsText(value: FuelEconomySnapshot): DisplayText = DisplayText(
        getString(R.string.mode_diagnostics_title_format, one(telemetry.coolantCelsius), one(telemetry.voltage)),
        getString(R.string.mode_diagnostics_subtitle_format, one(telemetry.fuelLitersPerHour), one(telemetry.engineLoadPercent)),
        getString(R.string.mode_diagnostics_description_format, whole(telemetry.rpm), one(telemetry.fuelLevelPercent), value.status)
    )

    private fun fuelCostText(value: FuelEconomySnapshot): DisplayText {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val price = preferences.getString(PREF_FUEL_PRICE, "3.00")?.toDoubleOrNull() ?: 3.0
        val gallonsPerHour = telemetry.fuelLitersPerHour / FuelEconomySnapshot.US_GALLON_LITERS
        return DisplayText(
            getString(R.string.mode_fuel_cost_title_format, two(gallonsPerHour), two(value.distanceKm)),
            getString(R.string.mode_fuel_cost_subtitle_format, two(value.fuelGallons), money(value.fuelGallons * price)),
            value.status
        )
    }

    private fun publishPlaybackState() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (tracking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (tracking) 1f else 0f
            )
            .addCustomAction(ACTION_NEXT_MODE, getString(R.string.fuel_media_next_mode), R.drawable.arrow_forward)
            .addCustomAction(ACTION_RESET_TRIP, getString(R.string.fuel_media_reset), R.drawable.ic_distance)
        mediaSession.setPlaybackState(builder.build())
    }

    private fun selectMode(mode: DisplayMode) {
        selectedMode = mode
        getSharedPreferences(MODE_PREFS, MODE_PRIVATE).edit().putString(MODE_KEY, mode.name).apply()
        publishMetadata(snapshot)
        publishPlaybackState()
    }

    private fun loadMode(): DisplayMode {
        val name = getSharedPreferences(MODE_PREFS, MODE_PRIVATE).getString(MODE_KEY, null)
        return DisplayMode.entries.firstOrNull { it.name == name } ?: DisplayMode.CONSUMPTION
    }

    private fun resetTrip() {
        store.reset()
        observedResetGeneration = store.resetGeneration()
        snapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        lastSampleNanos = System.nanoTime()
        publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_trip_reset)))
    }

    private fun persistTrip() {
        val currentResetGeneration = store.resetGeneration()
        if (currentResetGeneration != observedResetGeneration) {
            observedResetGeneration = currentResetGeneration
            snapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        }
        store.save(snapshot.distanceKm, snapshot.fuelLiters, snapshot.elapsedSeconds)
    }

    private fun stopRefreshTask() {
        refreshTask?.cancel(true)
        refreshTask = null
    }

    override fun onDestroy() {
        persistTrip()
        stopRefreshTask()
        executor.shutdownNow()
        if (torqueBound) {
            try {
                unbindService(torqueConnection)
            } catch (_: IllegalArgumentException) {
                // Already disconnected.
            }
        }
        torqueBound = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun findCandidate(candidates: List<PidCandidate>, standardPid: String, matcher: (PidCandidate) -> Boolean): PidCandidate? =
        candidates.firstOrNull { it.pid == standardPid } ?: candidates.firstOrNull(matcher)

    private fun speedScale(unit: String): Double? = when {
        unit.contains("km/h") || unit.contains("kph") -> 1.0
        unit.contains("mph") || unit.contains("mi/h") -> 1.609344
        else -> null
    }

    private fun fuelFlowScale(unit: String): Double? = when {
        unit.contains("l/h") || unit.contains("lph") || unit.contains("liter/hour") || unit.contains("litre/hour") -> 1.0
        unit.contains("l/min") || unit.contains("liter/min") || unit.contains("litre/min") -> 60.0
        unit.contains("ml/min") || unit.contains("cc/min") -> 0.06
        unit.contains("gal/h") || unit.contains("gph") -> FuelEconomySnapshot.US_GALLON_LITERS
        else -> null
    }

    private fun voltageScale(unit: String): Double? = when {
        unit == "v" || unit.contains("volt") -> 1.0
        unit == "mv" || unit.contains("millivolt") -> 0.001
        else -> null
    }

    private fun temperatureScale(unit: String): Double = if (unit.contains("f")) 5.0 / 9.0 else 1.0
    private fun one(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
    private fun two(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
    private fun whole(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
    private fun money(value: Double): String = String.format(Locale.US, "$%.2f", value)
    private fun duration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).toLong().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", totalMinutes / 60, totalMinutes % 60)
    }

    private data class DisplayText(val title: String, val subtitle: String, val description: String)
    private data class PidCandidate(val pid: String, val name: String, val unit: String)
    private data class TelemetryPid(
        val metric: Metric,
        val pid: String,
        val scale: Double = 1.0,
        val fahrenheit: Boolean = false
    )

    private enum class Metric { SPEED, FUEL_FLOW, RPM, COOLANT, ENGINE_LOAD, VOLTAGE, FUEL_LEVEL }

    private data class VehicleTelemetry(
        val speedKph: Double = 0.0,
        val fuelLitersPerHour: Double = 0.0,
        val rpm: Double? = null,
        val coolantCelsius: Double? = null,
        val engineLoadPercent: Double? = null,
        val voltage: Double? = null,
        val fuelLevelPercent: Double? = null
    ) {
        fun with(metric: Metric, value: Double): VehicleTelemetry = when (metric) {
            Metric.SPEED -> copy(speedKph = value.coerceAtLeast(0.0))
            Metric.FUEL_FLOW -> copy(fuelLitersPerHour = value.coerceAtLeast(0.0))
            Metric.RPM -> copy(rpm = value.coerceAtLeast(0.0))
            Metric.COOLANT -> copy(coolantCelsius = value)
            Metric.ENGINE_LOAD -> copy(engineLoadPercent = value.coerceIn(0.0, 100.0))
            Metric.VOLTAGE -> copy(voltage = value.coerceAtLeast(0.0))
            Metric.FUEL_LEVEL -> copy(fuelLevelPercent = value.coerceIn(0.0, 100.0))
        }
    }

    private enum class DisplayMode(
        val mediaId: String,
        val titleResource: Int,
        val subtitleResource: Int
    ) {
        CONSUMPTION("mode_consumption", R.string.mode_consumption, R.string.mode_consumption_summary),
        ENGINE("mode_engine", R.string.mode_engine, R.string.mode_engine_summary),
        TRIP("mode_trip", R.string.mode_trip, R.string.mode_trip_summary),
        DIAGNOSTICS("mode_diagnostics", R.string.mode_diagnostics, R.string.mode_diagnostics_summary),
        FUEL_COST("mode_fuel_cost", R.string.mode_fuel_cost, R.string.mode_fuel_cost_summary);

        fun next(): DisplayMode = entries[(ordinal + 1) % entries.size]

        companion object {
            fun fromMediaId(mediaId: String?): DisplayMode? = entries.firstOrNull { it.mediaId == mediaId }
        }
    }

    companion object {
        const val ACTION_RESET_TRIP = "com.aatorque.stats.action.RESET_FUEL_TRIP"
        const val ACTION_NEXT_MODE = "com.aatorque.stats.action.NEXT_FUEL_MODE"
        private const val MEDIA_ROOT_ID = "aa_torque_modes_root"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MAX_SAMPLE_GAP_NANOS = 5_000_000_000L
        private const val PERSIST_INTERVAL_NANOS = 10_000_000_000L
        private const val MODE_PREFS = "fuel_display_mode"
        private const val MODE_KEY = "selected_mode"
        private const val PREF_FUEL_PRICE = "fuelPricePerGallon"
        private const val PREF_TANK_GALLONS = "fuelTankGallons"
    }
}
