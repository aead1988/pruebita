package com.aatorque.stats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class FuelRefuelRecord(
    val timestamp: Long,
    val periodStartedAt: Long,
    val pricePerGallon: Double,
    val gallonsPurchased: Double,
    val distanceKm: Double,
    val consumedGallons: Double,
    val elapsedSeconds: Double
) {
    val totalCost: Double get() = pricePerGallon * gallonsPurchased
    val purchasedLiters: Double get() = gallonsPurchased * FuelEconomySnapshot.US_GALLON_LITERS
    val fullTankKmPerGallon: Double?
        get() = if (gallonsPurchased > 0.0) distanceKm / gallonsPurchased else null
    val fullTankKmPerLiter: Double?
        get() = if (purchasedLiters > 0.0) distanceKm / purchasedLiters else null
    val fullTankLitersPer100Km: Double?
        get() = if (distanceKm > 0.0) purchasedLiters / distanceKm * 100.0 else null

    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("periodStartedAt", periodStartedAt)
        .put("pricePerGallon", pricePerGallon)
        .put("gallonsPurchased", gallonsPurchased)
        .put("totalCost", totalCost)
        .put("distanceKm", distanceKm)
        .put("consumedGallons", consumedGallons)
        .put("elapsedSeconds", elapsedSeconds)

    companion object {
        fun fromJson(value: JSONObject) = FuelRefuelRecord(
            timestamp = value.optLong("timestamp", System.currentTimeMillis()),
            periodStartedAt = value.optLong("periodStartedAt", value.optLong("timestamp")),
            pricePerGallon = value.optDouble("pricePerGallon"),
            gallonsPurchased = value.optDouble("gallonsPurchased"),
            distanceKm = value.optDouble("distanceKm"),
            consumedGallons = value.optDouble("consumedGallons"),
            elapsedSeconds = value.optDouble("elapsedSeconds")
        )
    }
}

class FuelRefuelHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun add(record: FuelRefuelRecord) {
        replace(load().toMutableList().apply { add(record) })
    }

    @Synchronized
    fun load(): List<FuelRefuelRecord> = try {
        val array = JSONArray(preferences.getString(KEY_REFUELS, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                add(FuelRefuelRecord.fromJson(array.getJSONObject(index)))
            }
        }
    } catch (error: Exception) {
        Timber.w(error, "Unable to read refuel history")
        emptyList()
    }

    @Synchronized
    fun replace(values: List<FuelRefuelRecord>) {
        val array = JSONArray().apply {
            values.takeLast(MAX_REFUELS).forEach { put(it.toJson()) }
        }
        preferences.edit().putString(KEY_REFUELS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "fuel_refuel_history"
        private const val KEY_REFUELS = "refuels"
        private const val MAX_REFUELS = 500
    }
}
