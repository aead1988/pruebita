package com.aatorque.stats

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser.MediaItem
import android.media.browse.MediaBrowser.MediaItem.FLAG_PLAYABLE
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Android Auto media source with selectable fuel, engine, trip and diagnostic modes. */
class FuelEconomyMediaService : MediaBrowserService() {
    private lateinit var mediaSession: MediaSession
    private lateinit var store: FuelEconomyStore
    private lateinit var monthlyStore: MonthlyFuelEconomyStore
    private lateinit var dailyStore: DailyFuelEconomyStore
    private lateinit var weeklyStore: WeeklyFuelEconomyStore
    private lateinit var moduleStore: FuelModuleStore
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var refreshTask: ScheduledFuture<*>? = null
    private var torqueService: ITorqueService? = null
    private var torqueBound = false
    private var snapshot = FuelEconomySnapshot(0.0, 0.0)
    private var journeySnapshot = FuelEconomySnapshot(0.0, 0.0)
    private var monthlySnapshot = MonthlyFuelEconomySnapshot("")
    private var dailySnapshot = DailyFuelEconomySnapshot("")
    private var weeklySnapshot = WeeklyFuelEconomySnapshot("")
    private var telemetry = VehicleTelemetry()
    private var telemetryPids = emptyList<TelemetryPid>()
    private var lastSampleNanos = 0L
    private var lastPersistNanos = 0L
    private var tracking = true
    private var observedResetGeneration = 0L
    private var selectedModuleId = FuelModuleStore.ID_FUEL_COST
    private var journeyStartedAt = System.currentTimeMillis()
    private var tankEntryStage = TankEntryStage.NONE
    private var pendingFuelPrice = 0.0
    private var pendingRefuelGallons = 0.0
    private var spotifyLaunchRequested = false
    private var spotifyAutoPlayPending = true
    private var spotifySessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        store = FuelEconomyStore(this)
        monthlyStore = MonthlyFuelEconomyStore(this)
        dailyStore = DailyFuelEconomyStore(this)
        weeklyStore = WeeklyFuelEconomyStore(this)
        moduleStore = FuelModuleStore(this)
        snapshot = store.load()
        journeySnapshot = FuelEconomySnapshot(0.0, 0.0, status = getString(R.string.fuel_media_waiting))
        monthlySnapshot = monthlyStore.loadCurrent()
        dailySnapshot = dailyStore.loadCurrent()
        weeklySnapshot = weeklyStore.loadCurrent()
        observedResetGeneration = store.resetGeneration()
        selectedModuleId = loadModuleId()
        mediaSession = MediaSession(this, "Huno selectable telemetry").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (tankEntryStage != TankEntryStage.NONE) {
                        advanceTankEntry()
                        return
                    }
                    tracking = true
                    this@FuelEconomyMediaService.mediaSession.isActive = true
                    publishPlaybackState()
                    connectToTorque()
                }

                override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
                    selectModule(mediaId)
                    onPlay()
                }

                override fun onSkipToNext() {
                    selectNextModule()
                }

                override fun onSkipToPrevious() {
                    forwardSpotifyMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }

                @Suppress("DEPRECATION")
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?: return false
                    if (keyEvent.action != KeyEvent.ACTION_DOWN) return true
                    return when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> forwardSpotifyMediaCommand(keyEvent.keyCode)
                        else -> false
                    }
                }

                override fun onPause() {
                    if (tankEntryStage != TankEntryStage.NONE) return
                    tracking = false
                    persistTrip()
                    publishPlaybackState()
                }

                override fun onStop() {
                    if (tankEntryStage != TankEntryStage.NONE) {
                        cancelTankEntry()
                        return
                    }
                    tracking = false
                    persistTrip()
                    publishPlaybackState()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        ACTION_RESET_TRIP -> resetTrip()
                        ACTION_TANK_FILLED -> beginTankPriceEntry()
                        ACTION_NEXT_MODE -> selectNextModule()
                        ACTION_EXPORT_MONTHLY_CSV -> exportMonthlyCsv()
                        ACTION_PRICE_MINUS_TEN -> adjustPendingFuelPrice(-0.10)
                        ACTION_PRICE_MINUS_ONE -> adjustPendingFuelPrice(-0.01)
                        ACTION_PRICE_PLUS_ONE -> adjustPendingFuelPrice(0.01)
                        ACTION_PRICE_PLUS_TEN -> adjustPendingFuelPrice(0.10)
                        ACTION_GALLONS_MINUS_ONE -> adjustPendingRefuelGallons(-1.00)
                        ACTION_GALLONS_MINUS_CENT -> adjustPendingRefuelGallons(-0.01)
                        ACTION_GALLONS_PLUS_CENT -> adjustPendingRefuelGallons(0.01)
                        ACTION_GALLONS_PLUS_ONE -> adjustPendingRefuelGallons(1.00)
                    }
                }
            })
            setExtras(Bundle().apply {
                putBoolean(SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREVIOUS, true)
            })
            isActive = true
        }
        setSessionToken(mediaSession.sessionToken)
        publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_waiting)))
        publishPlaybackState()
        connectToTorque()
        registerSpotifySessionListener()
        scheduleSpotifyAutoPlay()
        scheduleDailyCardRecovery()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != MEDIA_ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val useSpotifyArtwork = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_SPOTIFY_ARTWORK, false)
        val artwork = (if (useSpotifyArtwork) spotifyArtwork()?.bitmap else null)
            ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        val items = moduleStore.activeModules().map { module ->
            val description = MediaDescription.Builder()
                .setMediaId(module.id)
                .setTitle(moduleTitle(module))
                .setSubtitle(moduleSummary(module))
                .setIconBitmap(artwork)
                .build()
            MediaItem(description, FLAG_PLAYABLE)
        }.toMutableList()
        result.sendResult(items)
    }

    private val torqueConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torqueService = ITorqueService.Stub.asInterface(binder)
            val connectedAt = System.currentTimeMillis()
            val resumedJourney = FuelDriveArchive.resumableJourney(this@FuelEconomyMediaService, connectedAt)
            journeyStartedAt = resumedJourney?.startedAt ?: connectedAt
            journeySnapshot = FuelEconomySnapshot(
                distanceKm = resumedJourney?.distanceKm ?: 0.0,
                fuelLiters = resumedJourney?.fuelLiters ?: 0.0,
                elapsedSeconds = resumedJourney?.elapsedSeconds ?: 0.0,
                connected = true,
                status = getString(R.string.fuel_media_waiting)
            )
            if (resumedJourney != null) {
                Timber.i("Resumed journey after a short stop of %d seconds", (connectedAt - resumedJourney.endedAt) / 1_000L)
            }
            lastSampleNanos = System.nanoTime()
            executor.execute(::discoverPidsAndStart)
            scheduleSpotifyAutoPlay()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            persistTrip()
            archiveCompletedJourney()
            torqueService = null
            torqueBound = false
            telemetryPids = emptyList()
            stopRefreshTask()
            journeySnapshot = journeySnapshot.copy(
                connected = false,
                status = getString(R.string.fuel_media_disconnected)
            )
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
        if (!torqueBound) {
            journeySnapshot = journeySnapshot.copy(status = getString(R.string.fuel_media_torque_required))
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
                journeySnapshot = journeySnapshot.copy(
                    connected = true,
                    status = getString(R.string.fuel_media_no_flow_pid)
                )
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
            journeySnapshot = journeySnapshot.copy(status = getString(R.string.fuel_media_pid_error))
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
                val distanceDelta = telemetry.speedKph * elapsedSeconds / 3600.0
                val fuelLitersDelta = telemetry.fuelLitersPerHour * elapsedSeconds / 3600.0
                val price = fuelPricePerGallon()
                snapshot = snapshot.copy(
                    distanceKm = snapshot.distanceKm + distanceDelta,
                    fuelLiters = snapshot.fuelLiters + fuelLitersDelta,
                    elapsedSeconds = snapshot.elapsedSeconds + elapsedSeconds
                )
                journeySnapshot = journeySnapshot.copy(
                    distanceKm = journeySnapshot.distanceKm + distanceDelta,
                    fuelLiters = journeySnapshot.fuelLiters + fuelLitersDelta,
                    elapsedSeconds = journeySnapshot.elapsedSeconds + elapsedSeconds
                )
                if (monthlySnapshot.monthKey != monthlyStore.currentMonthKey()) {
                    monthlySnapshot = monthlyStore.save(monthlySnapshot)
                }
                monthlySnapshot = monthlySnapshot.copy(
                    distanceKm = monthlySnapshot.distanceKm + distanceDelta,
                    fuelLiters = monthlySnapshot.fuelLiters + fuelLitersDelta,
                    fuelCost = monthlySnapshot.fuelCost +
                        fuelLitersDelta / FuelEconomySnapshot.US_GALLON_LITERS * price
                )
                if (dailySnapshot.dayKey != dailyStore.currentDayKey()) {
                    dailySnapshot = dailyStore.loadCurrent()
                }
                dailySnapshot = dailySnapshot.copy(
                    distanceKm = dailySnapshot.distanceKm + distanceDelta,
                    fuelLiters = dailySnapshot.fuelLiters + fuelLitersDelta,
                    fuelCost = dailySnapshot.fuelCost +
                        fuelLitersDelta / FuelEconomySnapshot.US_GALLON_LITERS * price
                )
                if (weeklySnapshot.weekKey != weeklyStore.currentWeekKey()) {
                    weeklySnapshot = weeklyStore.loadCurrent()
                }
                weeklySnapshot = weeklySnapshot.copy(
                    distanceKm = weeklySnapshot.distanceKm + distanceDelta,
                    fuelLiters = weeklySnapshot.fuelLiters + fuelLitersDelta,
                    fuelCost = weeklySnapshot.fuelCost +
                        fuelLitersDelta / FuelEconomySnapshot.US_GALLON_LITERS * price
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
            journeySnapshot = journeySnapshot.copy(
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
        if (tankEntryStage != TankEntryStage.NONE) {
            val artwork = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            val isPrice = tankEntryStage == TankEntryStage.PRICE
            val title = if (isPrice) {
                getString(R.string.tank_price_entry_title, String.format(Locale.US, "$%.2f", pendingFuelPrice))
            } else {
                getString(R.string.tank_gallons_entry_title, String.format(Locale.US, "%.2f", pendingRefuelGallons))
            }
            val instruction = if (isPrice) {
                getString(R.string.tank_price_entry_instruction)
            } else {
                getString(
                    R.string.tank_gallons_entry_instruction,
                    String.format(Locale.US, "$%.2f", pendingFuelPrice * pendingRefuelGallons)
                )
            }
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, MEDIA_ID_TANK_ENTRY)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, instruction)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, instruction)
                    .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                    .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
                    .build()
            )
            return
        }
        val module = moduleStore.activeModules().firstOrNull { it.id == selectedModuleId }
            ?: moduleStore.activeModules().first().also { selectedModuleId = it.id }
        val text = when (module.id) {
            FuelModuleStore.ID_FUEL_COST -> fuelCostText(journeySnapshot)
            FuelModuleStore.ID_DAILY -> dailyText()
            FuelModuleStore.ID_WEEKLY -> weeklyText()
            FuelModuleStore.ID_MONTHLY -> monthlyText()
            FuelModuleStore.ID_SINCE_REFUEL -> sinceRefuelText(value)
            FuelModuleStore.ID_ANNUAL -> annualText()
            else -> customModuleText(module)
        }
        val spotifyArtwork = spotifyArtwork()
        val useSpotifyArtwork = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_SPOTIFY_ARTWORK, false)
        val artwork = (if (useSpotifyArtwork) spotifyArtwork?.bitmap else null)
            ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        val mediaId = module.id + ":" + (spotifyArtwork?.key ?: "default")
        val vehicleLine = listOf(text.title, text.subtitle).joinToString(" · ")
        val musicLine = spotifyArtwork?.nowPlayingLine() ?: getString(R.string.fuel_media_no_song)
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, vehicleLine)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, vehicleLine)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, musicLine)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, musicLine)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
                .build()
        )
    }

    private fun spotifyArtwork(): SpotifyMediaArtwork? {
        if (!NotiService.isNotificationAccessEnabled(this)) return null
        return try {
            val manager = getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(this, NotiService::class.java)
            val metadata = manager.getActiveSessions(listener)
                .firstOrNull { it.packageName == SPOTIFY_PACKAGE }
                ?.metadata
            val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (artwork != null) {
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                val key = listOf(
                    metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    title,
                    metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ).joinToString(":") + ":" + artwork.generationId
                SpotifyMediaArtwork(fitArtworkForMediaSession(artwork), key, title, artist)
            } else {
                NotiService.currentSpotifyArtwork()?.let {
                    SpotifyMediaArtwork(fitArtworkForMediaSession(it.bitmap), it.key, it.title, it.artist)
                }
            }
        } catch (error: SecurityException) {
            Timber.w(error, "Notification access is required for Spotify artwork")
            null
        } catch (error: Exception) {
            Timber.w(error, "Unable to read Spotify artwork")
            null
        }
    }

    private fun fitArtworkForMediaSession(artwork: Bitmap): Bitmap {
        val longestSide = maxOf(artwork.width, artwork.height)
        if (longestSide <= MAX_ARTWORK_EDGE_PX) return artwork
        val scale = MAX_ARTWORK_EDGE_PX.toDouble() / longestSide
        return Bitmap.createScaledBitmap(
            artwork,
            (artwork.width * scale).toInt().coerceAtLeast(1),
            (artwork.height * scale).toInt().coerceAtLeast(1),
            true
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
        val price = preferences.getString(PREF_FUEL_PRICE, "3.24")?.toDoubleOrNull() ?: 3.24
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
        val price = fuelPricePerGallon()
        val gallonsPerHour = telemetry.fuelLitersPerHour / FuelEconomySnapshot.US_GALLON_LITERS
        return DisplayText(
            getString(R.string.mode_fuel_cost_title_format, two(gallonsPerHour), two(value.distanceKm)),
            getString(R.string.mode_fuel_cost_subtitle_format, two(value.fuelGallons), money(value.fuelGallons * price)),
            value.status
        )
    }

    private fun customModuleText(module: FuelModule): DisplayText {
        val values = module.metrics.map { metric ->
            when (metric) {
                FuelModuleMetric.FLOW_GPH -> "${two(telemetry.fuelLitersPerHour / FuelEconomySnapshot.US_GALLON_LITERS)} gal/h"
                FuelModuleMetric.DISTANCE_KM -> "${two(journeySnapshot.distanceKm)} km"
                FuelModuleMetric.GALLONS -> "${two(journeySnapshot.fuelGallons)} gal"
                FuelModuleMetric.COST -> money(journeySnapshot.fuelGallons * fuelPricePerGallon())
                FuelModuleMetric.AVERAGE_KMPG -> "${one(journeySnapshot.averageKmPerGallon)} km/gal"
                FuelModuleMetric.DURATION -> "${duration(journeySnapshot.elapsedSeconds)} h"
                FuelModuleMetric.SPEED -> "${one(telemetry.speedKph)} km/h"
                FuelModuleMetric.RPM -> "${whole(telemetry.rpm)} rpm"
                FuelModuleMetric.COOLANT -> "${one(telemetry.coolantCelsius)} °C"
                FuelModuleMetric.VOLTAGE -> "${one(telemetry.voltage)} V"
                FuelModuleMetric.FUEL_LEVEL -> "${one(telemetry.fuelLevelPercent)}%"
            }
        }
        return DisplayText(module.name, values.joinToString(" · "), snapshot.status)
    }

    private fun monthlyText(): DisplayText {
        val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        return DisplayText(
            getString(R.string.mode_monthly_title_format, month, two(monthlySnapshot.distanceKm)),
            getString(
                R.string.mode_monthly_subtitle_format,
                one(monthlySnapshot.averageKmPerGallon),
                two(monthlySnapshot.fuelGallons),
                money(monthlySnapshot.fuelCost)
            ),
            snapshot.status
        )
    }

    private fun dailyText(): DisplayText {
        return DisplayText(
            getString(R.string.mode_daily_today),
            getString(
                R.string.mode_daily_subtitle_format,
                one(dailySnapshot.averageKmPerGallon),
                two(dailySnapshot.fuelGallons),
                money(dailySnapshot.fuelCost)
            ),
            snapshot.status
        )
    }

    private fun weeklyText(): DisplayText = DisplayText(
        getString(
            R.string.mode_weekly_title_format,
            two(weeklySnapshot.distanceKm)
        ),
        getString(
            R.string.mode_weekly_subtitle_format,
            one(weeklySnapshot.averageKmPerGallon),
            two(weeklySnapshot.fuelGallons),
            money(weeklySnapshot.fuelCost)
        ),
        snapshot.status
    )

    private fun sinceRefuelText(value: FuelEconomySnapshot): DisplayText = DisplayText(
        getString(
            R.string.mode_since_refuel_title_format,
            tankPeriodDaysText(),
            two(value.distanceKm)
        ),
        getString(
            R.string.mode_since_refuel_subtitle_format,
            one(value.averageKmPerGallon),
            two(value.fuelGallons),
            money(value.fuelGallons * fuelPricePerGallon())
        ),
        value.status
    )

    private fun tankPeriodDays(now: Long = System.currentTimeMillis()): Long {
        val elapsed = (now - store.tankPeriodStartTimestamp(now)).coerceAtLeast(0L)
        return elapsed / MILLIS_PER_DAY + 1L
    }

    private fun tankPeriodDaysText(): String {
        val days = tankPeriodDays().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return resources.getQuantityString(R.plurals.mode_since_refuel_days, days, days)
    }

    private fun annualText(): DisplayText {
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val reports = monthlyStore.historyIncludingCurrent().filter { it.monthKey.startsWith("$year-") }
        val annual = MonthlyFuelEconomySnapshot(
            monthKey = year,
            distanceKm = reports.sumOf { it.distanceKm },
            fuelLiters = reports.sumOf { it.fuelLiters },
            fuelCost = reports.sumOf { it.fuelCost }
        )
        return DisplayText(
            getString(R.string.mode_annual_title_format, year, two(annual.distanceKm)),
            getString(
                R.string.mode_annual_subtitle_format,
                one(annual.averageKmPerGallon),
                two(annual.fuelGallons),
                money(annual.fuelCost)
            ),
            snapshot.status
        )
    }

    private fun fuelPricePerGallon(): Double = PreferenceManager.getDefaultSharedPreferences(this)
        .getString(PREF_FUEL_PRICE, "3.24")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 3.24

    private fun forwardSpotifyMediaCommand(keyCode: Int): Boolean {
        if (!NotiService.isNotificationAccessEnabled(this)) return false
        return try {
            val manager = getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(this, NotiService::class.java)
            val controls = manager.getActiveSessions(listener)
                .firstOrNull { it.packageName == SPOTIFY_PACKAGE }
                ?.transportControls ?: return false
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                else -> return false
            }
            true
        } catch (error: Exception) {
            Timber.w(error, "Unable to forward physical media button to Spotify")
            false
        }
    }

    /**
     * Resumes Spotify after Android Auto and its media sessions have finished starting.
     * Several attempts are intentional: Spotify is often registered a few seconds after
     * Huno's MediaBrowserService is created.
     */
    private fun scheduleSpotifyAutoPlay() {
        spotifyAutoPlayPending = true
        SPOTIFY_AUTO_PLAY_DELAYS_SECONDS.forEach { delaySeconds ->
            try {
                executor.schedule({ resumeSpotifyPlayback() }, delaySeconds, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // The service is already shutting down.
            }
        }
    }

    private fun resumeSpotifyPlayback(): Boolean {
        if (!spotifyAutoPlayPending) return true
        if (!NotiService.isNotificationAccessEnabled(this)) {
            requestSpotifyStartup()
            return false
        }
        registerSpotifySessionListener()
        return try {
            val manager = getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(this, NotiService::class.java)
            val spotify = manager.getActiveSessions(listener)
                .firstOrNull { it.packageName == SPOTIFY_PACKAGE }
            if (spotify == null) {
                requestSpotifyStartup()
                return false
            }
            if (spotify.playbackState?.state != PlaybackState.STATE_PLAYING) {
                spotify.transportControls.play()
                Timber.i("Requested Spotify playback after Android Auto start")
            }
            spotifyAutoPlayPending = false
            true
        } catch (error: Exception) {
            Timber.w(error, "Unable to resume Spotify automatically")
            false
        }
    }

    private fun registerSpotifySessionListener() {
        if (!NotiService.isNotificationAccessEnabled(this) || spotifySessionListener != null) return
        try {
            val manager = getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(this, NotiService::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                if (!spotifyAutoPlayPending) return@OnActiveSessionsChangedListener
                val spotify = controllers?.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
                    ?: return@OnActiveSessionsChangedListener
                if (spotify.playbackState?.state != PlaybackState.STATE_PLAYING) {
                    spotify.transportControls.play()
                    Timber.i("Requested Spotify playback as soon as its media session appeared")
                }
                spotifyAutoPlayPending = false
            }
            manager.addOnActiveSessionsChangedListener(listener, component)
            spotifySessionListener = listener
        } catch (error: Exception) {
            Timber.w(error, "Unable to observe Spotify media sessions")
        }
    }

    private fun requestSpotifyStartup(): Boolean {
        if (spotifyLaunchRequested) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        return try {
            val pendingIntent = PendingIntent.getActivity(
                this,
                SPOTIFY_LAUNCH_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            pendingIntent.send(this, 0, null, null, null, null, options)
            spotifyLaunchRequested = true
            Timber.i("Requested Spotify startup before automatic playback")
            true
        } catch (error: Exception) {
            Timber.w(error, "Unable to start Spotify automatically")
            false
        }
    }

    /** Rewrites a pending daily card when Drive was temporarily unavailable. */
    private fun scheduleDailyCardRecovery() {
        DAILY_CARD_RECOVERY_DELAYS_SECONDS.forEach { delaySeconds ->
            try {
                executor.schedule(
                    { FuelDriveArchive.exportDailySummary(this) },
                    delaySeconds,
                    TimeUnit.SECONDS
                )
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // The service is already shutting down.
            }
        }
    }

    private fun publishPlaybackState() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
            (if (tankEntryStage != TankEntryStage.NONE) 0L else PlaybackState.ACTION_SKIP_TO_NEXT)
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (tankEntryStage != TankEntryStage.NONE) PlaybackState.STATE_PAUSED
                else if (tracking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (tracking && tankEntryStage == TankEntryStage.NONE) 1f else 0f
            )
        if (tankEntryStage != TankEntryStage.NONE) {
            if (tankEntryStage == TankEntryStage.PRICE) {
                builder
                    .addCustomAction(ACTION_PRICE_MINUS_TEN, getString(R.string.tank_price_minus_ten), R.drawable.arrow_back)
                    .addCustomAction(ACTION_PRICE_MINUS_ONE, getString(R.string.tank_price_minus_one), R.drawable.arrow_back)
                    .addCustomAction(ACTION_PRICE_PLUS_ONE, getString(R.string.tank_price_plus_one), R.drawable.arrow_forward)
                    .addCustomAction(ACTION_PRICE_PLUS_TEN, getString(R.string.tank_price_plus_ten), R.drawable.arrow_forward)
            } else {
                builder
                    .addCustomAction(ACTION_GALLONS_MINUS_ONE, getString(R.string.tank_gallons_minus_one), R.drawable.arrow_back)
                    .addCustomAction(ACTION_GALLONS_MINUS_CENT, getString(R.string.tank_gallons_minus_cent), R.drawable.arrow_back)
                    .addCustomAction(ACTION_GALLONS_PLUS_CENT, getString(R.string.tank_gallons_plus_cent), R.drawable.arrow_forward)
                    .addCustomAction(ACTION_GALLONS_PLUS_ONE, getString(R.string.tank_gallons_plus_one), R.drawable.arrow_forward)
            }
            mediaSession.setPlaybackState(builder.build())
            return
        }
        builder
            .addCustomAction(ACTION_TANK_FILLED, getString(R.string.fuel_media_tank_filled), R.drawable.ic_fuel)
            .addCustomAction(
                ACTION_EXPORT_MONTHLY_CSV,
                getString(R.string.fuel_media_export_csv),
                R.drawable.baseline_file_download_24
            )
        mediaSession.setPlaybackState(builder.build())
    }

    private fun beginTankPriceEntry() {
        pendingFuelPrice = fuelPricePerGallon().coerceAtLeast(MIN_FUEL_PRICE)
        pendingRefuelGallons = FuelRefuelHistoryStore(this).load().lastOrNull()?.gallonsPurchased
            ?.coerceIn(MIN_REFUEL_GALLONS, MAX_REFUEL_GALLONS)
            ?: DEFAULT_REFUEL_GALLONS
        tankEntryStage = TankEntryStage.PRICE
        publishMetadata(snapshot)
        publishPlaybackState()
    }

    private fun adjustPendingFuelPrice(delta: Double) {
        if (tankEntryStage != TankEntryStage.PRICE) return
        pendingFuelPrice = ((pendingFuelPrice + delta).coerceAtLeast(MIN_FUEL_PRICE) * 100.0)
            .roundToInt() / 100.0
        publishMetadata(snapshot)
    }

    private fun adjustPendingRefuelGallons(delta: Double) {
        if (tankEntryStage != TankEntryStage.GALLONS) return
        pendingRefuelGallons = ((pendingRefuelGallons + delta)
            .coerceIn(MIN_REFUEL_GALLONS, MAX_REFUEL_GALLONS) * 100.0).roundToInt() / 100.0
        publishMetadata(snapshot)
    }

    private fun advanceTankEntry() {
        when (tankEntryStage) {
            TankEntryStage.PRICE -> {
                tankEntryStage = TankEntryStage.GALLONS
                publishMetadata(snapshot)
                publishPlaybackState()
            }
            TankEntryStage.GALLONS -> confirmTankFilled()
            TankEntryStage.NONE -> Unit
        }
    }

    private fun confirmTankFilled() {
        if (tankEntryStage != TankEntryStage.GALLONS) return
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_FUEL_PRICE, String.format(Locale.US, "%.2f", pendingFuelPrice))
            .apply()
        val price = pendingFuelPrice
        val gallons = pendingRefuelGallons
        tankEntryStage = TankEntryStage.NONE
        tankFilled(price, gallons)
        publishMetadata(snapshot)
        publishPlaybackState()
    }

    private fun cancelTankEntry() {
        if (tankEntryStage == TankEntryStage.NONE) return
        tankEntryStage = TankEntryStage.NONE
        publishMetadata(snapshot)
        publishPlaybackState()
    }

    private fun selectModule(moduleId: String) {
        val module = moduleStore.activeModules().firstOrNull { it.id == moduleId } ?: return
        selectedModuleId = module.id
        getSharedPreferences(MODE_PREFS, MODE_PRIVATE).edit().putString(MODE_KEY, module.id).apply()
        publishMetadata(snapshot)
        publishPlaybackState()
    }

    private fun selectNextModule() {
        val active = moduleStore.activeModules()
        val currentIndex = active.indexOfFirst { it.id == selectedModuleId }
        selectModule(active[(currentIndex + 1).mod(active.size)].id)
    }

    private fun loadModuleId(): String {
        val saved = getSharedPreferences(MODE_PREFS, MODE_PRIVATE).getString(MODE_KEY, null)
        return moduleStore.activeModules().firstOrNull {
            it.id == saved || DisplayMode.entries.firstOrNull { mode -> mode.name == saved }?.mediaId == it.id
        }?.id ?: moduleStore.activeModules().first().id
    }

    private fun moduleTitle(module: FuelModule): String = if (!module.builtIn) module.name else when (module.id) {
        FuelModuleStore.ID_FUEL_COST -> getString(R.string.mode_fuel_cost)
        FuelModuleStore.ID_DAILY -> getString(R.string.mode_daily)
        FuelModuleStore.ID_WEEKLY -> getString(R.string.mode_weekly)
        FuelModuleStore.ID_MONTHLY -> getString(R.string.mode_monthly)
        FuelModuleStore.ID_SINCE_REFUEL -> getString(R.string.mode_since_refuel)
        FuelModuleStore.ID_ANNUAL -> getString(R.string.mode_annual)
        else -> module.id
    }

    private fun moduleSummary(module: FuelModule): String = if (!module.builtIn) {
        getString(R.string.fuel_modules_custom_summary)
    } else when (module.id) {
        FuelModuleStore.ID_FUEL_COST -> getString(R.string.mode_fuel_cost_summary)
        FuelModuleStore.ID_DAILY -> getString(R.string.mode_daily_summary)
        FuelModuleStore.ID_WEEKLY -> getString(R.string.mode_weekly_summary)
        FuelModuleStore.ID_MONTHLY -> getString(R.string.mode_monthly_summary)
        FuelModuleStore.ID_SINCE_REFUEL -> getString(R.string.mode_since_refuel_summary)
        FuelModuleStore.ID_ANNUAL -> getString(R.string.mode_annual_summary)
        else -> ""
    }

    private fun resetTrip() {
        store.reset()
        observedResetGeneration = store.resetGeneration()
        snapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        journeySnapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        lastSampleNanos = System.nanoTime()
        publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_trip_reset)))
    }

    private fun tankFilled(pricePerGallon: Double, gallonsPurchased: Double) {
        persistTrip()
        val report = FuelDriveArchive.exportTankPeriod(
            this,
            snapshot,
            store.tankPeriodStartTimestamp(),
            journeySnapshot,
            journeyStartedAt,
            pricePerGallon,
            gallonsPurchased
        )
        if (report == null) {
            val message = if (MonthlyFuelCsvExporter.configuredDirectory(this) == null) {
                R.string.monthly_history_location_required
            } else {
                R.string.tank_report_export_failed
            }
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        FuelRefuelHistoryStore(this).add(
            FuelRefuelRecord(
                timestamp = System.currentTimeMillis(),
                periodStartedAt = store.tankPeriodStartTimestamp(),
                pricePerGallon = pricePerGallon,
                gallonsPurchased = gallonsPurchased,
                distanceKm = snapshot.distanceKm,
                consumedGallons = snapshot.fuelGallons,
                elapsedSeconds = snapshot.elapsedSeconds
            )
        )
        store.markTankFilled()
        observedResetGeneration = store.resetGeneration()
        snapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        lastSampleNanos = System.nanoTime()
        FuelDeviceSync.publishIfEnabled(this)
        publishMetadata(snapshot.copy(status = getString(R.string.fuel_media_tank_filled)))
        publishPlaybackState()
        android.widget.Toast.makeText(applicationContext, R.string.tank_report_exported, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun exportMonthlyCsv() {
        val result = FuelDriveArchive.exportDailySummary(this)
        val message = if (result != null) {
            R.string.daily_summary_exported
        } else if (MonthlyFuelCsvExporter.configuredDirectory(this) == null) {
            R.string.monthly_history_location_required
        } else {
            R.string.daily_summary_export_failed
        }
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun archiveCompletedJourney() {
        val result = FuelDriveArchive.archiveJourneyAndUpdateDailyCard(this, journeySnapshot, journeyStartedAt)
        if (result != null) {
            Timber.i("Updated daily card with %d trips", result.tripCount)
        } else if (MonthlyFuelCsvExporter.configuredDirectory(this) != null) {
            scheduleDailyCardRecovery()
        }
    }

    private fun persistTrip() {
        val currentResetGeneration = store.resetGeneration()
        if (currentResetGeneration != observedResetGeneration) {
            observedResetGeneration = currentResetGeneration
            snapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        }
        store.save(snapshot.distanceKm, snapshot.fuelLiters, snapshot.elapsedSeconds)
        monthlySnapshot = monthlyStore.save(monthlySnapshot)
        dailySnapshot = dailyStore.save(dailySnapshot)
        weeklySnapshot = weeklyStore.save(weeklySnapshot)
    }

    private fun stopRefreshTask() {
        refreshTask?.cancel(true)
        refreshTask = null
    }

    override fun onDestroy() {
        persistTrip()
        archiveCompletedJourney()
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
        spotifySessionListener?.let { listener ->
            try {
                getSystemService(MediaSessionManager::class.java)
                    .removeOnActiveSessionsChangedListener(listener)
            } catch (_: Exception) {
                // The listener was already removed with the notification service.
            }
        }
        spotifySessionListener = null
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
    private data class SpotifyMediaArtwork(
        val bitmap: Bitmap,
        val key: String,
        val title: String?,
        val artist: String?
    ) {
        fun nowPlayingLine(): String? {
            val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                cleanArtist != null && cleanTitle != null -> "$cleanTitle — $cleanArtist"
                cleanTitle != null -> cleanTitle
                cleanArtist != null -> cleanArtist
                else -> null
            }
        }
    }
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
        FUEL_COST("mode_fuel_cost", R.string.mode_fuel_cost, R.string.mode_fuel_cost_summary),
        DAILY("mode_daily", R.string.mode_daily, R.string.mode_daily_summary),
        WEEKLY("mode_weekly", R.string.mode_weekly, R.string.mode_weekly_summary),
        MONTHLY("mode_monthly", R.string.mode_monthly, R.string.mode_monthly_summary),
        SINCE_REFUEL("mode_since_refuel", R.string.mode_since_refuel, R.string.mode_since_refuel_summary),
        ANNUAL("mode_annual", R.string.mode_annual, R.string.mode_annual_summary);

        fun next(): DisplayMode = entries[(ordinal + 1) % entries.size]

        companion object {
            fun fromMediaId(mediaId: String?): DisplayMode? = entries.firstOrNull { it.mediaId == mediaId }
        }
    }

    companion object {
        const val ACTION_RESET_TRIP = "com.aatorque.stats.action.RESET_FUEL_TRIP"
        const val ACTION_NEXT_MODE = "com.aatorque.stats.action.NEXT_FUEL_MODE"
        const val ACTION_TANK_FILLED = "com.aatorque.stats.action.TANK_FILLED"
        const val ACTION_EXPORT_MONTHLY_CSV = "com.aatorque.stats.action.EXPORT_MONTHLY_CSV"
        private const val ACTION_PRICE_MINUS_TEN = "com.aatorque.stats.action.PRICE_MINUS_TEN"
        private const val ACTION_PRICE_MINUS_ONE = "com.aatorque.stats.action.PRICE_MINUS_ONE"
        private const val ACTION_PRICE_PLUS_ONE = "com.aatorque.stats.action.PRICE_PLUS_ONE"
        private const val ACTION_PRICE_PLUS_TEN = "com.aatorque.stats.action.PRICE_PLUS_TEN"
        private const val ACTION_GALLONS_MINUS_ONE = "com.aatorque.stats.action.GALLONS_MINUS_ONE"
        private const val ACTION_GALLONS_MINUS_CENT = "com.aatorque.stats.action.GALLONS_MINUS_CENT"
        private const val ACTION_GALLONS_PLUS_CENT = "com.aatorque.stats.action.GALLONS_PLUS_CENT"
        private const val ACTION_GALLONS_PLUS_ONE = "com.aatorque.stats.action.GALLONS_PLUS_ONE"
        private const val MEDIA_ROOT_ID = "aa_torque_modes_root"
        private const val MEDIA_ID_TANK_ENTRY = "huno_tank_entry"
        private const val SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREVIOUS =
            "android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MAX_SAMPLE_GAP_NANOS = 5_000_000_000L
        private const val PERSIST_INTERVAL_NANOS = 10_000_000_000L
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val MODE_PREFS = "fuel_display_mode"
        private const val MODE_KEY = "selected_mode"
        private const val PREF_FUEL_PRICE = "fuelPricePerGallon"
        private const val PREF_TANK_GALLONS = "fuelTankGallons"
        private const val PREF_SPOTIFY_ARTWORK = "spotifyArtworkEnabled"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val SPOTIFY_LAUNCH_REQUEST_CODE = 6202
        private const val MIN_FUEL_PRICE = 0.01
        private const val MIN_REFUEL_GALLONS = 0.01
        private const val MAX_REFUEL_GALLONS = 99.99
        private const val DEFAULT_REFUEL_GALLONS = 10.00
        private const val MAX_ARTWORK_EDGE_PX = 384
        private val SPOTIFY_AUTO_PLAY_DELAYS_SECONDS = longArrayOf(1L, 4L, 10L, 20L, 35L)
        private val DAILY_CARD_RECOVERY_DELAYS_SECONDS = longArrayOf(5L, 30L)
    }
}

private enum class TankEntryStage { NONE, PRICE, GALLONS }
