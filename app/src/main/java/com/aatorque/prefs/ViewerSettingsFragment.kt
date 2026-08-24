package com.aatorque.prefs

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.aatorque.stats.DailyFuelEconomyStore
import com.aatorque.stats.ArchivedTrip
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
import com.google.android.material.card.MaterialCardView
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
    private lateinit var recentTripCount: TextView
    private lateinit var tripList: LinearLayout

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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
        recentTripCount = view.findViewById(R.id.viewerRecentTripCount)
        tripList = view.findViewById(R.id.viewerTripList)

        view.findViewById<View>(R.id.viewerOpenRecords).setOnClickListener {
            startActivity(Intent(requireContext(), FuelRecordsActivity::class.java))
        }
        view.findViewById<View>(R.id.viewerAveoTab).setOnClickListener {
            startActivity(Intent(requireContext(), MiAveoActivity::class.java))
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
        requestNotificationPermission()
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
        renderRecentTrips(trips.sortedByDescending { it.startedAt })
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun renderRecentTrips(trips: List<ArchivedTrip>) {
        val visible = trips.take(5)
        recentTripCount.text = getString(R.string.viewer_recent_trips_count, visible.size, trips.size)
        tripList.removeAllViews()
        if (visible.isEmpty()) {
            tripList.addView(textView(
                getString(R.string.viewer_recent_trips_empty),
                14f,
                Color.rgb(95, 96, 101)
            ).apply { setPadding(dp(6), dp(14), dp(6), dp(8)) })
            return
        }
        visible.forEachIndexed { index, trip ->
            tripList.addView(recentTripCard(trips.size - index, trip))
        }
    }

    private fun recentTripCard(number: Int, trip: ArchivedTrip): MaterialCardView {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.rgb(225, 226, 229)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
        }
        val top = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        top.addView(textView(
            getString(R.string.fuel_records_trip_number, number),
            17f,
            Color.rgb(9, 9, 9)
        ).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(textView(trip.classification, 12f, classificationColor(trip.classification)).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(classificationBackground(trip.classification))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(9), dp(5), dp(9), dp(5))
        })
        body.addView(top)
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(trip.startedAt))
        val start = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(trip.startedAt))
        val end = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(trip.endedAt))
        body.addView(textView(
            getString(R.string.fuel_records_trip_time, date, start, end, duration(trip.elapsedSeconds)),
            13f,
            Color.rgb(112, 113, 118)
        ).apply { setPadding(0, dp(5), 0, 0) })
        body.addView(textView(
            getString(
                R.string.fuel_records_trip_values,
                number(trip.distanceKm),
                number(trip.gallons),
                trip.averageKmPerGallon?.let(::number) ?: "--",
                number(trip.fuelCost)
            ),
            14f,
            Color.rgb(32, 33, 38)
        ).apply { setPadding(0, dp(9), 0, 0) })
        card.addView(body)
        return card
    }

    private fun textView(value: String, size: Float, color: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun classificationColor(value: String): Int = when (value) {
        "Viaje eficiente" -> Color.rgb(38, 104, 45)
        "Viaje poco eficiente" -> Color.rgb(150, 46, 46)
        else -> Color.rgb(85, 73, 11)
    }

    private fun classificationBackground(value: String): Int = when (value) {
        "Viaje eficiente" -> Color.rgb(218, 255, 190)
        "Viaje poco eficiente" -> Color.rgb(255, 224, 224)
        else -> Color.rgb(217, 255, 67)
    }

    private fun duration(seconds: Double): String {
        val minutes = (seconds / 60.0).toLong().coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d h", minutes / 60L, minutes % 60L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
