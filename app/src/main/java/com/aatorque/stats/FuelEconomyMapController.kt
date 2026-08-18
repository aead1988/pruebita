package com.aatorque.stats

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Connects Torque Pro and GPS to the map renderer and persists one continuous fuel trip. */
class FuelEconomyMapController(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
    private val renderer: FuelEconomyMapRenderer
) : DefaultLifecycleObserver, LocationListener {
    private val store = FuelEconomyStore(carContext)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val locationManager = carContext.getSystemService(LocationManager::class.java)
    private var refreshTask: ScheduledFuture<*>? = null
    private var torqueService: ITorqueService? = null
    private var torqueBound = false
    private var speedPid: String? = null
    private var fuelFlowPid: String? = null
    private var speedToKph = 1.0
    private var fuelFlowToLitersPerHour = 1.0
    private var snapshot = store.load()
    private var lastSampleNanos = 0L
    private var lastPersistNanos = 0L
    private var observedResetGeneration = store.resetGeneration()

    private val torqueConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torqueService = ITorqueService.Stub.asInterface(binder)
            executor.execute(::discoverPidsAndStart)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torqueService = null
            torqueBound = false
            stopRefreshTask()
            renderer.updateFuelEconomy(snapshot.copy(connected = false), carContext.getString(R.string.fuel_media_disconnected))
        }
    }

    init {
        lifecycle.addObserver(this)
        renderer.updateFuelEconomy(snapshot, carContext.getString(R.string.fuel_map_waiting))
    }

    override fun onCreate(owner: LifecycleOwner) {
        startLocationUpdates()
        connectToTorque()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        persistTrip()
        stopRefreshTask()
        executor.shutdownNow()
        locationManager.removeUpdates(this)
        if (torqueBound) {
            try {
                carContext.unbindService(torqueConnection)
            } catch (_: IllegalArgumentException) {
                // Already disconnected by the host.
            }
        }
        torqueBound = false
    }

    override fun onLocationChanged(location: Location) {
        renderer.updateLocation(location)
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    fun resetTrip() {
        store.reset()
        observedResetGeneration = store.resetGeneration()
        snapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        lastSampleNanos = System.nanoTime()
        renderer.updateFuelEconomy(snapshot, carContext.getString(R.string.fuel_media_trip_reset))
    }

    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            renderer.updateStatus(carContext.getString(R.string.fuel_map_location_required))
            return
        }
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            providers.filter(locationManager::isProviderEnabled).forEach { provider ->
                locationManager.requestLocationUpdates(provider, 1_000L, 2f, this, Looper.getMainLooper())
                locationManager.getLastKnownLocation(provider)?.let(renderer::updateLocation)
            }
        } catch (error: SecurityException) {
            Timber.w(error, "Location permission unavailable for AA Torque map")
            renderer.updateStatus(carContext.getString(R.string.fuel_map_location_required))
        }
    }

    private fun connectToTorque() {
        if (torqueBound) return
        val intent = Intent().apply {
            setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
        }
        torqueBound = try {
            carContext.bindService(intent, torqueConnection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            Timber.e(error, "Unable to bind AA Torque map to Torque Pro")
            false
        }
        if (!torqueBound) {
            renderer.updateFuelEconomy(snapshot, carContext.getString(R.string.fuel_media_torque_required))
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
            speedPid = candidates.firstOrNull {
                (it.pid == "0d,0" || it.name.contains("speed")) && speedScale(it.unit) != null
            }?.also { speedToKph = speedScale(it.unit) ?: 1.0 }?.pid ?: "0d,0"
            fuelFlowPid = candidates.firstOrNull {
                it.name.contains("fuel") && (it.name.contains("flow") || it.name.contains("rate")) &&
                    fuelFlowScale(it.unit) != null
            }?.also { fuelFlowToLitersPerHour = fuelFlowScale(it.unit) ?: 1.0 }?.pid

            if (fuelFlowPid == null) {
                renderer.updateFuelEconomy(snapshot, carContext.getString(R.string.fuel_media_no_flow_pid))
                return
            }
            lastSampleNanos = System.nanoTime()
            stopRefreshTask()
            refreshTask = executor.scheduleWithFixedDelay(::sampleTorque, 0, 1_000L, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Timber.e(error, "Unable to discover Torque PIDs for map")
            renderer.updateFuelEconomy(snapshot, carContext.getString(R.string.fuel_media_pid_error))
        }
    }

    private fun sampleTorque() {
        val service = torqueService ?: return
        val currentSpeedPid = speedPid ?: return
        val currentFuelPid = fuelFlowPid ?: return
        try {
            val resetGeneration = store.resetGeneration()
            if (resetGeneration != observedResetGeneration) {
                observedResetGeneration = resetGeneration
                snapshot = FuelEconomySnapshot(0.0, 0.0, connected = true)
                lastSampleNanos = System.nanoTime()
            }
            val values = service.getPIDValuesAsDouble(arrayOf(currentSpeedPid, currentFuelPid))
            if (values.size < 2) return
            val speedKph = (values[0] * speedToKph).coerceAtLeast(0.0)
            val litersPerHour = (values[1] * fuelFlowToLitersPerHour).coerceAtLeast(0.0)
            val now = System.nanoTime()
            val elapsedHours = if (lastSampleNanos == 0L) 0.0 else
                (now - lastSampleNanos).coerceAtMost(MAX_SAMPLE_GAP_NANOS) / NANOS_PER_HOUR
            lastSampleNanos = now
            if (elapsedHours > 0.0) {
                snapshot = snapshot.copy(
                    distanceKm = snapshot.distanceKm + speedKph * elapsedHours,
                    fuelLiters = snapshot.fuelLiters + litersPerHour * elapsedHours
                )
            }
            val instant = if (litersPerHour > 0.01 && speedKph > 0.1) {
                speedKph / litersPerHour * FuelEconomySnapshot.US_GALLON_LITERS
            } else null
            snapshot = snapshot.copy(instantKmPerGallon = instant, connected = true)
            renderer.updateFuelEconomy(snapshot, carContext.getString(R.string.fuel_media_recording))
            if (now - lastPersistNanos >= PERSIST_INTERVAL_NANOS) {
                persistTrip()
                lastPersistNanos = now
            }
        } catch (error: Exception) {
            Timber.e(error, "Unable to read Torque values for map")
        }
    }

    private fun persistTrip() {
        val resetGeneration = store.resetGeneration()
        if (resetGeneration != observedResetGeneration) {
            observedResetGeneration = resetGeneration
            snapshot = FuelEconomySnapshot(0.0, 0.0, connected = torqueService != null)
        }
        store.save(snapshot.distanceKm, snapshot.fuelLiters)
    }

    private fun stopRefreshTask() {
        refreshTask?.cancel(true)
        refreshTask = null
    }

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

    private data class PidCandidate(val pid: String, val name: String, val unit: String)

    companion object {
        private const val NANOS_PER_HOUR = 3_600_000_000_000.0
        private const val MAX_SAMPLE_GAP_NANOS = 5_000_000_000L
        private const val PERSIST_INTERVAL_NANOS = 10_000_000_000L
    }
}
