package com.aatorque.prefs

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.aatorque.stats.FuelDeviceSync
import com.aatorque.stats.FuelSyncRole
import com.aatorque.stats.FuelSyncScheduler
import com.aatorque.stats.FuelSyncStatus
import com.aatorque.stats.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewerSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var recordsPref: Preference
    private lateinit var filePref: Preference
    private lateinit var automaticPref: CheckBoxPreference
    private lateinit var syncNowPref: Preference
    private lateinit var statusPref: Preference
    private lateinit var changeRolePref: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.viewer_settings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordsPref = findPreference("viewerRecords")!!
        filePref = findPreference("viewerSyncFile")!!
        automaticPref = findPreference(FuelDeviceSync.PREF_AUTO)!!
        syncNowPref = findPreference("viewerSyncNow")!!
        statusPref = findPreference("viewerSyncStatus")!!
        changeRolePref = findPreference("viewerChangeRole")!!

        recordsPref.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), FuelRecordsActivity::class.java))
            true
        }
        filePref.setOnPreferenceClickListener {
            (requireActivity() as SettingsActivity).selectFuelSyncViewerFile()
            true
        }
        automaticPref.setOnPreferenceChangeListener { _, value ->
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putBoolean(FuelDeviceSync.PREF_AUTO, value as Boolean)
                .commit()
            FuelSyncScheduler.refresh(requireContext(), runImmediately = value)
            true
        }
        syncNowPref.setOnPreferenceClickListener {
            synchronize(showToast = true)
            true
        }
        changeRolePref.setOnPreferenceClickListener {
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
            true
        }
    }

    override fun onStart() {
        super.onStart()
        (requireActivity() as SettingsActivity).supportActionBar?.subtitle = null
        updateSummaries()
        FuelSyncScheduler.refresh(requireContext(), runImmediately = false)
        if (FuelDeviceSync.automatic(requireContext())) synchronize(showToast = false)
    }

    private fun synchronize(showToast: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = FuelDeviceSync.syncNow(requireContext().applicationContext)
            withContext(Dispatchers.Main) {
                updateSummaries()
                if (showToast || result.status == FuelSyncStatus.UPDATED) {
                    Toast.makeText(
                        requireContext(),
                        when (result.status) {
                            FuelSyncStatus.PUBLISHED -> R.string.fuel_sync_published
                            FuelSyncStatus.UPDATED -> R.string.fuel_sync_updated
                            FuelSyncStatus.UP_TO_DATE -> R.string.fuel_sync_up_to_date
                            FuelSyncStatus.DISABLED -> R.string.fuel_sync_disabled_message
                            FuelSyncStatus.FOLDER_REQUIRED -> R.string.monthly_history_location_required
                            FuelSyncStatus.SOURCE_REQUIRED -> R.string.fuel_sync_source_required
                            FuelSyncStatus.FILE_NOT_FOUND -> R.string.fuel_sync_file_not_found
                            FuelSyncStatus.ERROR -> R.string.fuel_sync_failed
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateSummaries() {
        filePref.summary = if (FuelDeviceSync.viewerFile(requireContext()) != null) {
            getString(R.string.fuel_sync_viewer_file_selected)
        } else getString(R.string.fuel_sync_viewer_file_summary)
        val timestamp = FuelDeviceSync.lastSuccess(requireContext())
        statusPref.summary = if (timestamp > 0L) {
            getString(
                R.string.fuel_sync_last_format,
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            )
        } else getString(R.string.fuel_sync_never)
    }
}
