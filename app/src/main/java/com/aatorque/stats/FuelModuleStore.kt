package com.aatorque.stats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent, ordered configuration for the modules exposed by Android Auto. */
class FuelModuleStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun modules(): List<FuelModule> {
        val saved = preferences.getString(KEY_MODULES, null) ?: return defaults()
        return try {
            val array = JSONArray(saved)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val metrics = item.optJSONArray("metrics") ?: JSONArray()
                    add(
                        FuelModule(
                            id = item.getString("id"),
                            name = item.optString("name"),
                            builtIn = item.optBoolean("builtIn"),
                            enabled = item.optBoolean("enabled", true),
                            metrics = buildList {
                                for (metricIndex in 0 until metrics.length()) {
                                    runCatching { FuelModuleMetric.valueOf(metrics.getString(metricIndex)) }
                                        .getOrNull()?.let(::add)
                                }
                            }
                        )
                    )
                }
            }.ifEmpty { defaults() }
        } catch (_: Exception) {
            defaults()
        }
    }

    fun activeModules(): List<FuelModule> = modules().filter { it.enabled }.ifEmpty { listOf(defaults().first()) }

    fun add(name: String, metrics: List<FuelModuleMetric>) {
        val updated = modules().toMutableList()
        updated += FuelModule("custom_${UUID.randomUUID()}", name.trim(), false, true, metrics.distinct())
        save(updated)
    }

    fun update(id: String, name: String, metrics: List<FuelModuleMetric>) {
        save(modules().map { module ->
            if (module.id == id && !module.builtIn) {
                module.copy(name = name.trim(), metrics = metrics.distinct())
            } else module
        })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val updated = modules().map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(if (updated.any { it.enabled }) updated else updated.map { if (it.id == ID_FUEL_COST) it.copy(enabled = true) else it })
    }

    fun move(id: String, offset: Int) {
        val updated = modules().toMutableList()
        val from = updated.indexOfFirst { it.id == id }
        val to = (from + offset).coerceIn(0, updated.lastIndex)
        if (from >= 0 && from != to) {
            val item = updated.removeAt(from)
            updated.add(to, item)
            save(updated)
        }
    }

    fun delete(id: String) {
        val target = modules().firstOrNull { it.id == id } ?: return
        if (target.builtIn) setEnabled(id, false) else save(modules().filterNot { it.id == id })
    }

    fun restoreDefaults() = save(defaults())

    private fun save(modules: List<FuelModule>) {
        val array = JSONArray()
        modules.forEach { module ->
            array.put(JSONObject().apply {
                put("id", module.id)
                put("name", module.name)
                put("builtIn", module.builtIn)
                put("enabled", module.enabled)
                put("metrics", JSONArray(module.metrics.map { it.name }))
            })
        }
        preferences.edit().putString(KEY_MODULES, array.toString()).apply()
    }

    private fun defaults(): List<FuelModule> = listOf(
        FuelModule(ID_FUEL_COST, "", true, true),
        FuelModule(ID_DAILY, "", true, true),
        FuelModule(ID_WEEKLY, "", true, true),
        FuelModule(ID_MONTHLY, "", true, true),
        FuelModule(ID_SINCE_REFUEL, "", true, true),
        FuelModule(ID_ANNUAL, "", true, true)
    )

    companion object {
        const val ID_FUEL_COST = "mode_fuel_cost"
        const val ID_DAILY = "mode_daily"
        const val ID_WEEKLY = "mode_weekly"
        const val ID_MONTHLY = "mode_monthly"
        const val ID_SINCE_REFUEL = "mode_since_refuel"
        const val ID_ANNUAL = "mode_annual"
        private const val PREFS_NAME = "fuel_module_configuration"
        private const val KEY_MODULES = "modules"
    }
}

data class FuelModule(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val enabled: Boolean,
    val metrics: List<FuelModuleMetric> = emptyList()
)

enum class FuelModuleMetric {
    FLOW_GPH,
    DISTANCE_KM,
    GALLONS,
    COST,
    AVERAGE_KMPG,
    DURATION,
    SPEED,
    RPM,
    COOLANT,
    VOLTAGE,
    FUEL_LEVEL
}
