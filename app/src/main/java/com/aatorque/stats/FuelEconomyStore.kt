package com.aatorque.stats

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FuelEconomySnapshot(
    val distanceKm: Double,
    val fuelLiters: Double,
    val elapsedSeconds: Double = 0.0,
    val instantKmPerGallon: Double? = null,
    val connected: Boolean = false,
    val status: String = "Esperando Torque Pro"
) {
    val averageKmPerGallon: Double?
        get() = if (fuelLiters > 0.0001) distanceKm / fuelLiters * US_GALLON_LITERS else null

    val fuelGallons: Double
        get() = fuelLiters / US_GALLON_LITERS

    val averageSpeedKph: Double?
        get() = if (elapsedSeconds > 1.0) distanceKm / (elapsedSeconds / 3600.0) else null

    companion object {
        const val US_GALLON_LITERS = 3.785411784
    }
}

class FuelEconomyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): FuelEconomySnapshot = FuelEconomySnapshot(
        distanceKm = preferences.getString(KEY_DISTANCE_KM, "0")?.toDoubleOrNull() ?: 0.0,
        fuelLiters = preferences.getString(KEY_FUEL_LITERS, "0")?.toDoubleOrNull() ?: 0.0,
        elapsedSeconds = preferences.getString(KEY_ELAPSED_SECONDS, "0")?.toDoubleOrNull() ?: 0.0
    )

    fun save(distanceKm: Double, fuelLiters: Double, elapsedSeconds: Double = 0.0) {
        preferences.edit()
            .putString(KEY_DISTANCE_KM, distanceKm.coerceAtLeast(0.0).toString())
            .putString(KEY_FUEL_LITERS, fuelLiters.coerceAtLeast(0.0).toString())
            .putString(KEY_ELAPSED_SECONDS, elapsedSeconds.coerceAtLeast(0.0).toString())
            .apply()
    }

    fun reset() {
        preferences.edit()
            .putString(KEY_DISTANCE_KM, "0")
            .putString(KEY_FUEL_LITERS, "0")
            .putString(KEY_ELAPSED_SECONDS, "0")
            .putLong(KEY_RESET_GENERATION, resetGeneration() + 1)
            .apply()
    }

    fun resetGeneration(): Long = preferences.getLong(KEY_RESET_GENERATION, 0L)

    companion object {
        const val PREFS_NAME = "fuel_economy_trip"
        private const val KEY_DISTANCE_KM = "distance_km"
        private const val KEY_FUEL_LITERS = "fuel_liters"
        private const val KEY_ELAPSED_SECONDS = "elapsed_seconds"
        private const val KEY_RESET_GENERATION = "reset_generation"
    }
}

data class MonthlyFuelEconomySnapshot(
    val monthKey: String,
    val distanceKm: Double = 0.0,
    val fuelLiters: Double = 0.0,
    val fuelCost: Double = 0.0
) {
    val averageKmPerGallon: Double?
        get() = if (fuelLiters > 0.0001) {
            distanceKm / fuelLiters * FuelEconomySnapshot.US_GALLON_LITERS
        } else null

    val fuelGallons: Double
        get() = fuelLiters / FuelEconomySnapshot.US_GALLON_LITERS
}

/** Stores the current calendar month's totals independently from the resettable trip. */
class MonthlyFuelEconomyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentMonthKey(now: Date = Date()): String =
        SimpleDateFormat(MONTH_KEY_PATTERN, Locale.US).format(now)

    @Synchronized
    fun loadCurrent(): MonthlyFuelEconomySnapshot {
        val currentMonth = currentMonthKey()
        if (preferences.getString(KEY_MONTH, null) != currentMonth) {
            return MonthlyFuelEconomySnapshot(currentMonth).also(::write)
        }
        return MonthlyFuelEconomySnapshot(
            monthKey = currentMonth,
            distanceKm = preferences.getString(KEY_DISTANCE_KM, "0")?.toDoubleOrNull() ?: 0.0,
            fuelLiters = preferences.getString(KEY_FUEL_LITERS, "0")?.toDoubleOrNull() ?: 0.0,
            fuelCost = preferences.getString(KEY_FUEL_COST, "0")?.toDoubleOrNull() ?: 0.0
        )
    }

    @Synchronized
    fun save(snapshot: MonthlyFuelEconomySnapshot): MonthlyFuelEconomySnapshot {
        val currentMonth = currentMonthKey()
        val value = if (snapshot.monthKey == currentMonth) snapshot else MonthlyFuelEconomySnapshot(currentMonth)
        write(value)
        return value
    }

    private fun write(snapshot: MonthlyFuelEconomySnapshot) {
        preferences.edit()
            .putString(KEY_MONTH, snapshot.monthKey)
            .putString(KEY_DISTANCE_KM, snapshot.distanceKm.coerceAtLeast(0.0).toString())
            .putString(KEY_FUEL_LITERS, snapshot.fuelLiters.coerceAtLeast(0.0).toString())
            .putString(KEY_FUEL_COST, snapshot.fuelCost.coerceAtLeast(0.0).toString())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "fuel_economy_month"
        private const val MONTH_KEY_PATTERN = "yyyy-MM"
        private const val KEY_MONTH = "month"
        private const val KEY_DISTANCE_KM = "distance_km"
        private const val KEY_FUEL_LITERS = "fuel_liters"
        private const val KEY_FUEL_COST = "fuel_cost"
    }
}
