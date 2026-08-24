package com.aatorque.prefs

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.aatorque.stats.FuelModule
import com.aatorque.stats.FuelModuleMetric
import com.aatorque.stats.FuelModuleStore
import com.aatorque.stats.R

/** Phone-side editor for the ordered modules shown by the Android Auto media service. */
class FuelModuleSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var store: FuelModuleStore

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        store = FuelModuleStore(requireContext())
        rebuild()
    }

    override fun onStart() {
        super.onStart()
        requireActivity().title = getString(R.string.fuel_modules_title)
    }

    private fun rebuild() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        screen.addPreference(Preference(requireContext()).apply {
            title = getString(R.string.fuel_modules_create)
            summary = getString(R.string.fuel_modules_create_summary)
            setOnPreferenceClickListener { showNameDialog(); true }
        })

        val active = PreferenceCategory(requireContext()).apply { title = getString(R.string.fuel_modules_active) }
        screen.addPreference(active)
        val hidden = PreferenceCategory(requireContext()).apply { title = getString(R.string.fuel_modules_hidden) }

        store.modules().forEach { module ->
            val preference = modulePreference(module)
            if (module.enabled) active.addPreference(preference) else hidden.addPreference(preference)
        }
        screen.addPreference(hidden)
        screen.addPreference(Preference(requireContext()).apply {
            title = getString(R.string.fuel_modules_restore)
            summary = getString(R.string.fuel_modules_restore_summary)
            setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.fuel_modules_restore)
                    .setMessage(R.string.fuel_modules_restore_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> store.restoreDefaults(); rebuild() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        })
        preferenceScreen = screen
    }

    private fun modulePreference(module: FuelModule): Preference = Preference(requireContext()).apply {
        title = moduleTitle(module)
        summary = when {
            !module.enabled -> getString(R.string.fuel_modules_hidden_summary)
            module.builtIn -> getString(R.string.fuel_modules_builtin_summary)
            else -> module.metrics.joinToString(" · ") { metricLabel(it) }
        }
        setOnPreferenceClickListener { showModuleActions(module); true }
    }

    private fun showModuleActions(module: FuelModule) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (module.enabled) {
            actions += getString(R.string.fuel_modules_move_up) to { store.move(module.id, -1); rebuild() }
            actions += getString(R.string.fuel_modules_move_down) to { store.move(module.id, 1); rebuild() }
            actions += getString(R.string.fuel_modules_hide) to { store.setEnabled(module.id, false); rebuild() }
        } else {
            actions += getString(R.string.fuel_modules_show) to { store.setEnabled(module.id, true); rebuild() }
        }
        if (!module.builtIn) {
            actions += getString(R.string.fuel_modules_edit) to { showNameDialog(module) }
            actions += getString(R.string.fuel_modules_delete) to { confirmDelete(module) }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(moduleTitle(module))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNameDialog(existing: FuelModule? = null) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.name.orEmpty())
            hint = getString(R.string.fuel_modules_name_hint)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.fuel_modules_create else R.string.fuel_modules_edit)
            .setView(input)
            .setPositiveButton(R.string.fuel_modules_choose_data) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.fuel_modules_name_required, Toast.LENGTH_SHORT).show()
                } else showMetricDialog(name, existing)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMetricDialog(name: String, existing: FuelModule?) {
        val metrics = FuelModuleMetric.entries
        val selected = BooleanArray(metrics.size) { existing?.metrics?.contains(metrics[it]) == true }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fuel_modules_choose_data)
            .setMultiChoiceItems(metrics.map(::metricLabel).toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = metrics.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.fuel_modules_data_required, Toast.LENGTH_SHORT).show()
                } else {
                    if (existing == null) store.add(name, chosen) else store.update(existing.id, name, chosen)
                    rebuild()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(module: FuelModule) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fuel_modules_delete)
            .setMessage(getString(R.string.fuel_modules_delete_confirm, module.name))
            .setPositiveButton(R.string.fuel_modules_delete) { _, _ -> store.delete(module.id); rebuild() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun metricLabel(metric: FuelModuleMetric): String = getString(when (metric) {
        FuelModuleMetric.FLOW_GPH -> R.string.fuel_metric_flow
        FuelModuleMetric.DISTANCE_KM -> R.string.fuel_metric_distance
        FuelModuleMetric.GALLONS -> R.string.fuel_metric_gallons
        FuelModuleMetric.COST -> R.string.fuel_metric_cost
        FuelModuleMetric.AVERAGE_KMPG -> R.string.fuel_metric_average
        FuelModuleMetric.DURATION -> R.string.fuel_metric_duration
        FuelModuleMetric.SPEED -> R.string.fuel_metric_speed
        FuelModuleMetric.RPM -> R.string.fuel_metric_rpm
        FuelModuleMetric.COOLANT -> R.string.fuel_metric_coolant
        FuelModuleMetric.VOLTAGE -> R.string.fuel_metric_voltage
        FuelModuleMetric.FUEL_LEVEL -> R.string.fuel_metric_level
    })
}
