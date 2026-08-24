package com.aatorque.prefs

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.aatorque.stats.FuelTripHistoryStore
import com.aatorque.stats.R
import org.json.JSONObject
import java.util.Locale

class ExpenseSummaryActivity : AppCompatActivity() {
    private lateinit var totalValue: TextView
    private lateinit var fuelValue: TextView
    private lateinit var maintenanceValue: TextView
    private lateinit var registrationValue: TextView
    private lateinit var sourceState: TextView
    private lateinit var chart: ExpenseSummaryView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense_summary)
        supportActionBar?.hide()

        totalValue = findViewById(R.id.expenseTotal)
        fuelValue = bindCard(R.id.expenseFuelCard, R.string.expense_fuel)
        maintenanceValue = bindCard(R.id.expenseMaintenanceCard, R.string.expense_maintenance)
        registrationValue = bindCard(R.id.expenseRegistrationCard, R.string.expense_registration)
        sourceState = findViewById(R.id.expenseSourceState)
        chart = findViewById(R.id.expenseChart)

        findViewById<View>(R.id.expenseNavHome).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_HOME, true)
                .apply()
            finish()
        }
        findViewById<View>(R.id.expenseNavAveo).setOnClickListener {
            startActivity(Intent(this, MiAveoActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.expenseNavPending).setOnClickListener {
            startActivity(Intent(this, MiAveoActivity::class.java).putExtra("section", "pending"))
            finish()
        }
        findViewById<View>(R.id.expenseNavSettings).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_SETTINGS, true)
                .apply()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    private fun render() {
        val fuel = FuelTripHistoryStore(this).load().sumOf { it.fuelCost }
        var maintenance = 0.0
        var registration = 0.0
        val vehicleLog = loadVehicleLog()
        vehicleLog?.optJSONArray("maintenance")?.let { items ->
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                if (item.isNull("cost")) continue
                val cost = item.optDouble("cost", 0.0)
                if (item.optString("title").contains("matrícula", ignoreCase = true)) {
                    registration += cost
                } else {
                    maintenance += cost
                }
            }
        }
        val total = fuel + maintenance + registration
        totalValue.text = money(total)
        fuelValue.text = money(fuel)
        maintenanceValue.text = money(maintenance)
        registrationValue.text = money(registration)
        chart.setData(fuel, maintenance, registration)
        sourceState.setText(
            if (vehicleLog == null) R.string.expense_source_missing
            else R.string.expense_source_ready
        )
    }

    private fun loadVehicleLog(): JSONObject? = runCatching {
        val file = getFileStreamPath(VEHICLE_LOG_FILE)
        if (!file.exists()) null else JSONObject(file.readText())
    }.getOrNull()

    private fun bindCard(cardId: Int, titleId: Int): TextView {
        val card = findViewById<View>(cardId)
        card.findViewById<TextView>(R.id.expenseItemTitle).setText(titleId)
        return card.findViewById(R.id.expenseItemValue)
    }

    private fun money(value: Double) = String.format(Locale.US, "$%.2f", value)

    companion object {
        private const val VEHICLE_LOG_FILE = "mi_aveo.json"
    }
}
