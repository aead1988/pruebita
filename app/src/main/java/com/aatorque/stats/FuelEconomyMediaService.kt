package com.aatorque.stats

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.browse.MediaBrowser.MediaItem
import android.media.browse.MediaBrowser.MediaItem.FLAG_PLAYABLE
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Exposes AA Torque as an Android Auto media source. Android Auto owns the media-card layout,
 * so fuel economy is published through title/artist/album metadata.
 */
class FuelEconomyMediaService : MediaBrowserService() {
    private lateinit var mediaSession: MediaSession
    private lateinit var store: FuelEconomyStore
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var refreshTask: ScheduledFuture<*>? = null
    private var torqueService: ITorqueService? = null
    private var torqueBound = false
    private var speedPid: String? = null
    private var fuelFlowPid: String? = null
    private var speedToKph = 1.0
    private var fuelFlowToLitersPerHour = 1.0
    private var snapshot = FuelEconomySnapshot(0.0, 0.0)
    private var lastSampleNanos = 0L
    private var lastPersistNanos = 0L
    private var tracking = true
    private var observedResetGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        store = FuelEconomyStore(this)
        snapshot = store.load()
        observedResetGeneration = store.resetGeneration()
        mediaSession = MediaSession(this, "AA Torque Fuel Economy").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    tracking = true
                    this@FuelEconomyMediaService.mediaSession.isActive = true
                    publishPlaybackState()
                    connectToTorque()
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

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action == ACTION_RESET_TRIP) {
                        resetTrip()
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
        val description = android.media.MediaDescription.Builder()
            .setMediaId(MEDIA_ITEM_ID)
            .setTitle(getString(R.string.fuel_media_item_title))
            .setSubtitle(getString(R.string.fuel_media_item_subtitle))
            .build()
        result.sendResult(mutableListOf(MediaItem(description, FLAG_PLAYABLE)))
    }

    private val torqueConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torqueService = ITorqueService.Stub.asInterface(binder)
            executor.execute { discoverPidsAndStart() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torqueService = null
            torqueBound = false
            speedPid = null
            fuelFlowPid = null
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
            Timber.e(error, "Unable to bind fuel economy media service to Torque Pro")
            false
        }
        if (!torqueBound) {
            publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_torque_required)))
        }
    }

    private fun discoverPidsAndStart() {
        val service = torqueService ?: return
        try {
            val pids = service.listAllPIDs()
            val information = service.getPIDInformation(pids)
            val candidates = pids.zip(information).mapNotNull { (pid, rawInfo) ->
                val fields = rawInfo.split(',')
                if (fields.size < 3) null else PidCandidate(
                    pid = pid,
                    searchableName = fields.take(2).joinToString(" ").lowercase(Locale.ROOT),
                    unit = fields[2].trim().lowercase(Locale.ROOT)
                )
            }

            speedPid = candidates.firstOrNull {
                (it.pid == "0d,0" || it.searchableName.contains("speed")) && speedScale(it.unit) != null
            }?.also {
                speedToKph = speedScale(it.unit) ?: 1.0
            }?.pid ?: "0d,0"

            fuelFlowPid = candidates.firstOrNull {
                val fuelFlowName = it.searchableName.contains("fuel") &&
                    (it.searchableName.contains("flow") || it.searchableName.contains("rate"))
                fuelFlowName && fuelFlowScale(it.unit) != null
            }?.also {
                fuelFlowToLitersPerHour = fuelFlowScale(it.unit) ?: 1.0
            }?.pid

            if (fuelFlowPid == null) {
                publishMetadata(snapshot.copy(
                    connected = true,
                    status = getString(R.string.fuel_media_no_flow_pid)
                ))
                return
            }
            lastSampleNanos = System.nanoTime()
            stopRefreshTask()
            refreshTask = executor.scheduleWithFixedDelay(
                ::sampleTorque,
                0,
                SAMPLE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: Exception) {
            Timber.e(error, "Unable to discover Torque fuel economy PIDs")
            publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_pid_error)))
        }
    }

    private fun sampleTorque() {
        val service = torqueService ?: return
        val currentSpeedPid = speedPid ?: return
        val currentFuelPid = fuelFlowPid ?: return
        try {
            val currentResetGeneration = store.resetGeneration()
            if (currentResetGeneration != observedResetGeneration) {
                observedResetGeneration = currentResetGeneration
                snapshot = FuelEconomySnapshot(0.0, 0.0, connected = true)
                lastSampleNanos = System.nanoTime()
            }
            val values = service.getPIDValuesAsDouble(arrayOf(currentSpeedPid, currentFuelPid))
            if (values.size < 2) return
            val speedKph = (values[0] * speedToKph).coerceAtLeast(0.0)
            val fuelLitersPerHour = (values[1] * fuelFlowToLitersPerHour).coerceAtLeast(0.0)
            val now = System.nanoTime()
            val elapsedHours = if (lastSampleNanos == 0L) 0.0 else
                (now - lastSampleNanos).coerceAtMost(MAX_SAMPLE_GAP_NANOS) / NANOS_PER_HOUR
            lastSampleNanos = now

            if (tracking && elapsedHours > 0.0) {
                snapshot = snapshot.copy(
                    distanceKm = snapshot.distanceKm + speedKph * elapsedHours,
                    fuelLiters = snapshot.fuelLiters + fuelLitersPerHour * elapsedHours
                )
            }
            val instant = if (fuelLitersPerHour > 0.01 && speedKph > 0.1) {
                speedKph / fuelLitersPerHour * FuelEconomySnapshot.US_GALLON_LITERS
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
            Timber.e(error, "Unable to read fuel economy values from Torque")
        }
    }

    private fun publishMetadata(value: FuelEconomySnapshot) {
        val average = value.averageKmPerGallon?.let(::formatOneDecimal) ?: "--"
        val instant = value.instantKmPerGallon?.let(::formatOneDecimal) ?: "--"
        val title = getString(R.string.fuel_media_title_format, average)
        val artist = getString(R.string.fuel_media_artist_format, instant, value.status)
        val album = getString(
            R.string.fuel_media_album_format,
            formatTwoDecimals(value.distanceKm),
            formatTwoDecimals(value.fuelGallons)
        )
        val artwork = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, MEDIA_ITEM_ID)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, album)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
                .build()
        )
    }

    private fun publishPlaybackState() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (tracking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (tracking) 1f else 0f
            )
            .addCustomAction(
                ACTION_RESET_TRIP,
                getString(R.string.fuel_media_reset),
                R.drawable.ic_distance
            )
        mediaSession.setPlaybackState(builder.build())
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
        store.save(snapshot.distanceKm, snapshot.fuelLiters)
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

    private fun formatOneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun formatTwoDecimals(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun speedScale(unit: String): Double? = when {
        unit.contains("km/h") || unit.contains("kph") -> 1.0
        unit.contains("mph") || unit.contains("mi/h") -> 1.609344
        else -> null
    }

    private fun fuelFlowScale(unit: String): Double? = when {
        unit.contains("l/h") || unit.contains("lph") || unit.contains("liter/hour") ||
            unit.contains("litre/hour") -> 1.0
        unit.contains("l/min") || unit.contains("liter/min") || unit.contains("litre/min") -> 60.0
        unit.contains("ml/min") || unit.contains("cc/min") -> 0.06
        unit.contains("gal/h") || unit.contains("gph") -> FuelEconomySnapshot.US_GALLON_LITERS
        else -> null
    }

    private data class PidCandidate(val pid: String, val searchableName: String, val unit: String)

    companion object {
        const val ACTION_RESET_TRIP = "com.aatorque.stats.action.RESET_FUEL_TRIP"
        private const val MEDIA_ROOT_ID = "aa_torque_fuel_root"
        private const val MEDIA_ITEM_ID = "aa_torque_fuel_economy"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val NANOS_PER_HOUR = 3_600_000_000_000.0
        private const val MAX_SAMPLE_GAP_NANOS = 5_000_000_000L
        private const val PERSIST_INTERVAL_NANOS = 10_000_000_000L
    }
}
