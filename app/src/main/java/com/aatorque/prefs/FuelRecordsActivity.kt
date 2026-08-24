package com.aatorque.prefs

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.aatorque.stats.ArchivedTrip
import com.aatorque.stats.DailyFuelEconomyStore
import com.aatorque.stats.FuelDeviceSync
import com.aatorque.stats.FuelEconomySnapshot
import com.aatorque.stats.FuelEconomyStore
import com.aatorque.stats.FuelSyncRole
import com.aatorque.stats.FuelSyncStatus
import com.aatorque.stats.FuelTripHistoryStore
import com.aatorque.stats.MonthlyFuelEconomyStore
import com.aatorque.stats.R
import com.aatorque.stats.WeeklyFuelEconomyStore
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FuelRecordsActivity : AppCompatActivity() {
    private lateinit var lastSync: TextView
    private lateinit var todayValues: TextView
    private lateinit var weekValues: TextView
    private lateinit var monthValues: TextView
    private lateinit var yearValues: TextView
    private lateinit var tankValues: TextView
    private lateinit var tripCount: TextView
    private lateinit var tripList: LinearLayout
    private lateinit var syncButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fuel_records)
        supportActionBar?.hide()

        lastSync = findViewById(R.id.recordsLastSync)
        todayValues = bindPeriodCard(R.id.recordsTodayCard, R.string.fuel_records_today)
        weekValues = bindPeriodCard(R.id.recordsWeekCard, R.string.fuel_records_week)
        monthValues = bindPeriodCard(R.id.recordsMonthCard, R.string.fuel_records_month)
        yearValues = bindPeriodCard(R.id.recordsYearCard, R.string.fuel_records_year)
        tankValues = bindPeriodCard(R.id.recordsTankCard, R.string.fuel_records_since_tank)
        tripCount = findViewById(R.id.recordsTripCount)
        tripList = findViewById(R.id.recordsTripList)
        syncButton = findViewById(R.id.recordsSyncButton)
        findViewById<View>(R.id.recordsBackButton).setOnClickListener { finish() }
        syncButton.setOnClickListener { synchronize(showToast = true) }
        findViewById<View>(R.id.recordsNavHome).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_HOME, true)
                .apply()
            finish()
        }
        findViewById<View>(R.id.recordsNavAveo).setOnClickListener {
            startActivity(Intent(this, MiAveoActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.recordsNavPending).setOnClickListener {
            startActivity(Intent(this, MiAveoActivity::class.java).putExtra("section", "pending"))
            finish()
        }
        findViewById<View>(R.id.recordsNavExpenses).setOnClickListener {
            startActivity(Intent(this, ExpenseSummaryActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.recordsNavSettings).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_SETTINGS, true)
                .apply()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        render()
        if (FuelDeviceSync.role(this) == FuelSyncRole.SECONDARY && FuelDeviceSync.automatic(this)) {
            synchronize(showToast = false)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun synchronize(showToast: Boolean) {
        syncButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result = FuelDeviceSync.syncNow(applicationContext)
            withContext(Dispatchers.Main) {
                syncButton.isEnabled = true
                render()
                if (showToast || result.status == FuelSyncStatus.UPDATED) {
                    Toast.makeText(
                        this@FuelRecordsActivity,
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

    private fun render() {
        val syncTimestamp = FuelDeviceSync.lastSuccess(this)
        lastSync.text = if (syncTimestamp > 0L) {
            getString(
                R.string.fuel_records_last_sync,
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(syncTimestamp))
            )
        } else getString(R.string.fuel_sync_never)

        val daily = DailyFuelEconomyStore(this).loadCurrent()
        val weekly = WeeklyFuelEconomyStore(this).loadCurrent()
        val monthlyStore = MonthlyFuelEconomyStore(this)
        val monthly = monthlyStore.loadCurrent()
        val currentYear = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val annual = monthlyStore.historyIncludingCurrent().filter { it.monthKey.startsWith(currentYear) }
        val annualDistance = annual.sumOf { it.distanceKm }
        val annualLiters = annual.sumOf { it.fuelLiters }
        val annualCost = annual.sumOf { it.fuelCost }
        val sinceTank = FuelEconomyStore(this).load()
        val price = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("fuelPricePerGallon", "3.24")?.toDoubleOrNull() ?: 3.24

        todayValues.text = periodValues(daily.distanceKm, daily.fuelLiters, daily.fuelCost)
        weekValues.text = periodValues(weekly.distanceKm, weekly.fuelLiters, weekly.fuelCost)
        monthValues.text = periodValues(monthly.distanceKm, monthly.fuelLiters, monthly.fuelCost)
        yearValues.text = periodValues(annualDistance, annualLiters, annualCost)
        tankValues.text = periodValues(
            sinceTank.distanceKm,
            sinceTank.fuelLiters,
            sinceTank.fuelGallons * price
        )

        val trips = FuelTripHistoryStore(this).load().sortedByDescending { it.startedAt }
        tripCount.text = resources.getQuantityString(R.plurals.fuel_records_trip_count, trips.size, trips.size)
        tripList.removeAllViews()
        if (trips.isEmpty()) {
            tripList.addView(textView(getString(R.string.fuel_records_empty), 16f, Color.rgb(95, 96, 101)).apply {
                setPadding(dp(8), dp(24), dp(8), dp(40))
            })
        } else {
            trips.forEachIndexed { index, trip -> tripList.addView(tripCard(trips.size - index, trip)) }
        }
    }

    private fun bindPeriodCard(cardId: Int, titleId: Int): TextView {
        val card = findViewById<View>(cardId)
        card.findViewById<TextView>(R.id.periodTitle).setText(titleId)
        return card.findViewById(R.id.periodValues)
    }

    private fun periodValues(distanceKm: Double, fuelLiters: Double, cost: Double): String {
        val gallons = fuelLiters / FuelEconomySnapshot.US_GALLON_LITERS
        val average = if (fuelLiters > 0.0001) distanceKm / fuelLiters * FuelEconomySnapshot.US_GALLON_LITERS else null
        return getString(
            R.string.fuel_records_period_values,
            number(distanceKm),
            number(gallons),
            average?.let(::number) ?: "--",
            number(cost)
        )
    }

    private fun tripCard(number: Int, trip: ArchivedTrip): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.rgb(225, 226, 229)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        body.addView(textView(getString(R.string.fuel_records_trip_number, number), 18f, Color.rgb(9, 9, 9)).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(trip.startedAt))
        val start = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(trip.startedAt))
        val end = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(trip.endedAt))
        body.addView(textView(
            getString(R.string.fuel_records_trip_time, date, start, end, duration(trip.elapsedSeconds)),
            14f,
            Color.rgb(112, 113, 118)
        ))
        body.addView(textView(
            getString(
                R.string.fuel_records_trip_values,
                number(trip.distanceKm),
                number(trip.gallons),
                trip.averageKmPerGallon?.let(::number) ?: "--",
                number(trip.fuelCost)
            ),
            15f,
            Color.rgb(32, 33, 38)
        ).apply { setPadding(0, dp(10), 0, dp(8)) })
        body.addView(textView(trip.classification, 15f, classificationColor(trip.classification)).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(classificationBackground(trip.classification))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(11), dp(6), dp(11), dp(6))
        })
        card.addView(body)
        return card
    }

    private fun textView(value: String, size: Float, color: Int) = TextView(this).apply {
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

    private fun number(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
