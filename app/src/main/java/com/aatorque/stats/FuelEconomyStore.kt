package com.aatorque.stats

import android.content.Context

data class FuelEconomySnapshot(
    val distanceKm: Double,
    val fuelLiters: Double,
    val instantKmPerGallon: Double? = null,
    val connected: Boolean = false,
    val status: String = "Esperando Torque Pro"
) {
    val averageKmPerGallon: Double?
        get() = if (fuelLiters > 0.0001) distanceKm / fuelLiters * US_GALLON_LITERS else null

    val fuelGallons: Double
        get() = fuelLiters / US_GALLON_LITERS

    companion object {
        const val US_GALLON_LITERS = 3.785411784
    }
}

class FuelEconomyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): FuelEconomySnapshot = FuelEconomySnapshot(
        distanceKm = preferences.getString(KEY_DISTANCE_KM, "0")?.toDoubleOrNull() ?: 0.0,
        fuelLiters = preferences.getString(KEY_FUEL_LITERS, "0")?.toDoubleOrNull() ?: 0.0
    )

    fun save(distanceKm: Double, fuelLiters: Double) {
        preferences.edit()
            .putString(KEY_DISTANCE_KM, distanceKm.coerceAtLeast(0.0).toString())
            .putString(KEY_FUEL_LITERS, fuelLiters.coerceAtLeast(0.0).toString())
            .apply()
    }

    fun reset() {
        preferences.edit()
            .putString(KEY_DISTANCE_KM, "0")
            .putString(KEY_FUEL_LITERS, "0")
            .putLong(KEY_RESET_GENERATION, resetGeneration() + 1)
            .apply()
    }

    fun resetGeneration(): Long = preferences.getLong(KEY_RESET_GENERATION, 0L)

    companion object {
        const val PREFS_NAME = "fuel_economy_trip"
        private const val KEY_DISTANCE_KM = "distance_km"
        private const val KEY_FUEL_LITERS = "fuel_liters"
        private const val KEY_RESET_GENERATION = "reset_generation"
    }
}
