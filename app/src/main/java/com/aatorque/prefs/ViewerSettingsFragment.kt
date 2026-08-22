package com.aatorque.prefs

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.aatorque.stats.DailyFuelEconomyStore
import com.aatorque.stats.FuelDeviceSync
import com.aatorque.stats.FuelEconomySnapshot
import com.aatorque.stats.FuelEconomyStore
import com.aatorque.stats.FuelSyncRole
import com.aatorque.stats.FuelSyncScheduler
import com.aatorque.stats.FuelSyncStatus
import com.aatorque.stats.FuelTripHistoryStore
import com.aatorque.stats.MonthlyFuelEconomyStore
import com.aatorque.stats.R
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewerSettingsFragment : Fragment(R.layout.fragment_viewer_dashboard) {
    private lateinit var lastSync: TextView
    private lateinit var syncState: TextView
    private lateinit var fileState: TextView
    private lateinit var todayValue: TextView
    private lateinit var monthValue: TextView
    private lateinit var tripsValue: TextView
    private lateinit var tankValue: TextView
    private lateinit var automaticSwitch: MaterialSwitch
    private lateinit var scroll: ScrollView
    private lateinit var syncCard: View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scroll = view.findViewById(R.id.viewerScroll)
        lastSync = view.findViewById(R.id.viewerLastSync)
        syncState = view.findViewById(R.id.viewerSyncState)
        fileState = view.findViewById(R.id.viewerFileState)
        todayValue = view.findViewById(R.id.viewerTodayValue)
        monthValue = view.findViewById(R.id.viewerMonthValue)
        tripsValue = view.findViewById(R.id.viewerTripsValue)
        tankValue = view.findViewById(R.id.viewerTankValue)
        automaticSwitch = view.findViewById(R.id.viewerAutomaticSwitch)
        syncCard = view.findViewById(R.id.viewerSyncCard)

        view.findViewById<View>(R.id.viewerOpenRecords).setOnClickListener {
            startActivity(Intent(requireContext(), FuelRecordsActivity::class.java))
        }
        view.findViewById<View>(R.id.viewerChooseDrive).setOnClickListener {
            (requireActivity() as SettingsActivity).selectFuelSyncViewerFile()
        }
        view.findViewById<View>(R.id.viewerSyncNow).setOnClickListener { synchronize(showToast = true) }
        view.findViewById<View>(R.id.viewerSettingsButton).setOnClickListener {
            scroll.smoothScrollTo(0, syncCard.top)
        }
        view.findViewById<View>(R.id.viewerChangeRole).setOnClickListener { confirmPrimaryRole() }

        automaticSwitch.isChecked = FuelDeviceSync.automatic(requireContext())
        automaticSwitch.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putBoolean(FuelDeviceSync.PREF_AUTO, checked)
                .commit()
            FuelSyncScheduler.refresh(requireContext(), runImmediately = checked)
        }
    }

    override fun onStart() {
        super.onStart()
        (requireActivity() as SettingsActivity).supportActionBar?.hide()
        render()
        FuelSyncScheduler.refresh(requireContext(), runImmediately = false)
        if (FuelDeviceSync.automatic(requireContext())) synchronize(showToast = false)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun confirmPrimaryRole() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.viewer_change_role_title)
            .setMessage(R.string.viewer_change_role_confirm)
            .setPositiveButton(R.string.fuel_sync_role_primary) { _, _ ->
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putString(FuelDeviceSync.PREF_ROLE, FuelSyncRole.PRIMARY.value)
                    .commit()
                FuelSyncScheduler.refresh(requireContext(), runImmediately = true)
                requireActivity().recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun synchronize(showToast: Boolean) {
        syncState.setText(R.string.viewer_syncing)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = FuelDeviceSync.syncNow(requireContext().applicationContext)
            withContext(Dispatchers.Main) {
                render()
                if (showToast || result.status == FuelSyncStatus.UPDATED) {
                    Toast.makeText(requireContext(), statusMessage(result.status), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun statusMessage(status: FuelSyncStatus): Int = when (status) {
        FuelSyncStatus.PUBLISHED -> R.string.fuel_sync_published
        FuelSyncStatus.UPDATED -> R.string.fuel_sync_updated
        FuelSyncStatus.UP_TO_DATE -> R.string.fuel_sync_up_to_date
        FuelSyncStatus.DISABLED -> R.string.fuel_sync_disabled_message
        FuelSyncStatus.FOLDER_REQUIRED -> R.string.monthly_history_location_required
        FuelSyncStatus.SOURCE_REQUIRED -> R.string.fuel_sync_source_required
        FuelSyncStatus.FILE_NOT_FOUND -> R.string.fuel_sync_file_not_found
        FuelSyncStatus.ERROR -> R.string.fuel_sync_failed
    }

    private fun render() {
        val context = requireContext()
        val daily = DailyFuelEconomyStore(context).loadCurrent()
        val monthly = MonthlyFuelEconomyStore(context).loadCurrent()
        val trips = FuelTripHistoryStore(context).load()
        val tank = FuelEconomyStore(context).load()
        val price = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("fuelPricePerGallon", "3.24")?.toDoubleOrNull() ?: 3.24

        todayValue.text = getString(R.string.viewer_km_value, number(daily.distanceKm))
        monthValue.text = getString(R.string.viewer_cost_value, number(monthly.fuelCost))
        tripsValue.text = trips.size.toString()
        val tankAverage = if (tank.fuelLiters > 0.0001) {
            tank.distanceKm / tank.fuelLiters * FuelEconomySnapshot.US_GALLON_LITERS
        } else null
        tankValue.text = tankAverage?.let {
            getString(R.string.viewer_efficiency_value, number(it), number(tank.fuelGallons * price))
        } ?: getString(R.string.viewer_no_data)

        fileState.setText(
            if (FuelDeviceSync.viewerFile(context) != null) R.string.viewer_drive_connected
            else R.string.viewer_drive_not_connected
        )
        val timestamp = FuelDeviceSync.lastSuccess(context)
        if (timestamp > 0L) {
            val formatted = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))
            lastSync.text = getString(R.string.viewer_updated_format, formatted)
            syncState.setText(R.string.viewer_sync_ready)
        } else {
            lastSync.setText(R.string.fuel_sync_never)
            syncState.setText(R.string.viewer_sync_waiting)
        }
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.2f", value)
}
