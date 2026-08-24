package com.aatorque.prefs

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.aatorque.stats.R
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MiAveoActivity : AppCompatActivity() {
    private lateinit var pendingList: LinearLayout
    private lateinit var maintenanceList: LinearLayout
    private lateinit var documentsList: LinearLayout
    private lateinit var categoryBreakdown: LinearLayout
    private lateinit var scroll: ScrollView
    private var currentKm = 0

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mi_aveo)
        supportActionBar?.hide()
        pendingList = findViewById(R.id.aveoPendingList)
        maintenanceList = findViewById(R.id.aveoMaintenanceList)
        documentsList = findViewById(R.id.aveoDocumentsList)
        categoryBreakdown = findViewById(R.id.aveoCategoryBreakdown)
        scroll = findViewById(R.id.aveoScroll)
        findViewById<View>(R.id.aveoSectionServices).setOnClickListener { showSection(R.id.aveoServicesSection, R.id.aveoSectionServices) }
        findViewById<View>(R.id.aveoSectionDocuments).setOnClickListener { showSection(R.id.aveoDocumentsSection, R.id.aveoSectionDocuments) }
        findViewById<View>(R.id.aveoSectionReports).setOnClickListener { showSection(R.id.aveoReportsSection, R.id.aveoSectionReports) }
        findViewById<View>(R.id.aveoChooseFile).setOnClickListener {
            fileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        findViewById<View>(R.id.aveoNavHome).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_HOME, true)
                .apply()
            finish()
        }
        findViewById<View>(R.id.aveoNavAveo).setOnClickListener {
            selectNavigation(R.id.aveoNavAveo)
            showSection(R.id.aveoServicesSection, R.id.aveoSectionServices)
            scroll.smoothScrollTo(0, 0)
        }
        findViewById<View>(R.id.aveoNavPending).setOnClickListener { showPending() }
        findViewById<View>(R.id.aveoNavExpenses).setOnClickListener {
            startActivity(Intent(this, ExpenseSummaryActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.aveoNavSettings).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_SETTINGS, true)
                .apply()
            finish()
        }
        if (intent.getStringExtra("section") == "pending") {
            selectNavigation(R.id.aveoNavPending)
        }
    }

    override fun onResume() {
        super.onResume()
        val selected = selectedUri()
        val data = selected?.let(::loadFromUri) ?: loadCached()
        if (data != null) render(data, selected?.let(::displayName)) else showEmpty()
    }

    private fun loadCached(): JSONObject? = runCatching {
        val file = getFileStreamPath(DATA_FILE)
        if (!file.exists()) null else JSONObject(file.readText())
    }.getOrNull()

    private fun selectedUri(): Uri? = PreferenceManager.getDefaultSharedPreferences(this)
        .getString(PREF_DATA_URI, null)
        ?.let(Uri::parse)

    private fun selectFile(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val data = loadFromUri(uri)
        if (data == null) {
            findViewById<TextView>(R.id.aveoFileState).text = getString(R.string.aveo_file_error)
            return
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_DATA_URI, uri.toString())
            .apply()
        render(data, displayName(uri))
    }

    private fun loadFromUri(uri: Uri): JSONObject? = runCatching {
        val data = contentResolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
            ?: return@runCatching null
        openFileOutput(DATA_FILE, MODE_PRIVATE).bufferedWriter().use { it.write(data.toString()) }
        data
    }.getOrNull()

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: DATA_FILE

    private fun showEmpty() {
        findViewById<View>(R.id.aveoDataContainer).visibility = View.GONE
        findViewById<TextView>(R.id.aveoUpdated).text = getString(R.string.aveo_no_file)
        findViewById<TextView>(R.id.aveoFileState).text = getString(R.string.aveo_file_prompt)
    }

    private fun render(root: JSONObject, filename: String?) {
        findViewById<View>(R.id.aveoDataContainer).visibility = View.VISIBLE
        findViewById<TextView>(R.id.aveoFileState).text = getString(
            R.string.aveo_file_selected,
            filename ?: DATA_FILE
        )
        val vehicle = root.getJSONObject("vehicle")
        val summary = root.getJSONObject("summary")
        currentKm = vehicle.getInt("currentKm")
        findViewById<TextView>(R.id.aveoVehicleName).text = vehicle.getString("name")
        findViewById<TextView>(R.id.aveoVehicleDetail).text = getString(
            R.string.aveo_vehicle_detail_format,
            vehicle.getInt("year"),
            vehicle.getString("engine"),
            money(summary.getDouble("registeredCost"))
        )
        findViewById<TextView>(R.id.aveoKm).text = getString(R.string.aveo_km_format, vehicle.getInt("currentKm"))
        findViewById<TextView>(R.id.aveoMaintenanceCount).text = summary.getInt("maintenanceCount").toString()
        findViewById<TextView>(R.id.aveoUpdated).text = getString(R.string.aveo_updated_format, vehicle.getString("updatedAt"))

        pendingList.removeAllViews()
        root.getJSONArray("pending")
            .toObjectList()
            .sortedWith(
                compareBy<JSONObject> {
                    when {
                        !it.has("dueKm") -> 2
                        it.optInt("dueKm") <= currentKm -> 0
                        else -> 1
                    }
                }.thenBy {
                    if (it.has("dueKm")) kotlin.math.abs(it.optInt("dueKm") - currentKm)
                    else Int.MAX_VALUE
                }.thenBy { it.optString("dueDate", "9999-12-31") }
                    .thenBy { it.optString("title") }
            )
            .forEach { pendingList.addView(pendingCard(it)) }
        maintenanceList.removeAllViews()
        documentsList.removeAllViews()
        val maintenance = root.getJSONArray("maintenance")
        maintenance.forEachObject {
            if (isDocument(it)) documentsList.addView(maintenanceCard(it))
            else maintenanceList.addView(maintenanceCard(it))
        }
        renderReports(maintenance)
        if (intent.getStringExtra("section") == "pending") showPending()
    }

    private fun showPending() {
        selectNavigation(R.id.aveoNavPending)
        showSection(R.id.aveoRemindersSection, null)
        val target = findViewById<View>(R.id.aveoPendingTitle)
        val container = findViewById<View>(R.id.aveoDataContainer)
        scroll.post { scroll.smoothScrollTo(0, container.top + target.top) }
    }

    private fun showSection(sectionId: Int, tabId: Int?) {
        findViewById<View>(R.id.aveoTabs).visibility = if (tabId == null) View.GONE else View.VISIBLE
        intArrayOf(R.id.aveoServicesSection, R.id.aveoDocumentsSection, R.id.aveoRemindersSection, R.id.aveoReportsSection)
            .forEach { findViewById<View>(it).visibility = if (it == sectionId) View.VISIBLE else View.GONE }
        intArrayOf(R.id.aveoSectionServices, R.id.aveoSectionDocuments, R.id.aveoSectionReports)
            .forEach { id ->
                findViewById<TextView>(id).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setTextColor(Color.rgb(102, 103, 108))
                    setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                }
            }
        tabId?.let {
            findViewById<TextView>(it).apply {
                setBackgroundResource(R.drawable.viewer_nav_selected)
                setTextColor(Color.rgb(9, 9, 9))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
        }
    }

    private fun selectNavigation(selectedId: Int) {
        val ids = intArrayOf(
            R.id.aveoNavHome,
            R.id.aveoNavAveo,
            R.id.aveoNavPending,
            R.id.aveoNavExpenses,
            R.id.aveoNavSettings
        )
        ids.forEach { id ->
            findViewById<TextView>(id).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.rgb(111, 112, 117))
                setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            }
        }
        findViewById<TextView>(selectedId).apply {
            setBackgroundResource(R.drawable.viewer_nav_selected)
            setTextColor(Color.rgb(9, 9, 9))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    private fun pendingCard(item: JSONObject): MaterialCardView {
        val card = baseCard()
        val body = verticalBody()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label(item.getString("title"), 16f, Color.rgb(9, 9, 9), true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(label(item.getString("category"), 11f, Color.rgb(35, 40, 20), true).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(217, 255, 67))
                cornerRadius = dp(13).toFloat()
            }
            setPadding(dp(9), dp(5), dp(9), dp(5))
        })
        body.addView(top)
        body.addView(label(item.getString("detail"), 14f, Color.rgb(102, 103, 108)).apply {
            setPadding(0, dp(7), 0, 0)
        })
        if (item.has("dueKm") && item.has("lastKm")) {
            val due = item.getInt("dueKm")
            val last = item.getInt("lastKm")
            val progress = (((currentKm - last).toDouble() / (due - last).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
            val remaining = due - currentKm
            val color = when {
                remaining <= 0 -> Color.rgb(220, 53, 69)
                progress >= 80 -> Color.rgb(255, 181, 45)
                else -> Color.rgb(150, 190, 40)
            }
            body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = progress
                progressTintList = ColorStateList.valueOf(color)
                progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(232, 233, 236))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { setMargins(0, dp(12), 0, 0) }
            })
            val status = if (remaining >= 0) getString(R.string.aveo_reminder_progress, progress, remaining)
                else getString(R.string.aveo_reminder_overdue, -remaining)
            body.addView(label(status, 12f, color, true).apply { setPadding(0, dp(5), 0, 0) })
        } else if (item.has("dueDate")) {
            body.addView(label(getString(R.string.aveo_reminder_due_date, item.getString("dueDate")), 12f, Color.rgb(86, 87, 92), true).apply { setPadding(0, dp(8), 0, 0) })
        }
        card.addView(body)
        return card
    }

    private fun renderReports(items: JSONArray) {
        var total = 0.0
        var services = 0.0
        var documents = 0.0
        var known = 0
        val yearly = linkedMapOf<String, Double>()
        val categories = linkedMapOf<String, Double>()
        items.forEachObject { item ->
            if (!item.isNull("cost")) {
                val cost = item.getDouble("cost")
                total += cost
                known++
                if (isDocument(item)) documents += cost else services += cost
                val year = Regex("20\\d{2}").find(item.optString("date"))?.value ?: getString(R.string.aveo_unknown_year)
                yearly[year] = (yearly[year] ?: 0.0) + cost
                val category = categoryFor(item.getString("title"))
                categories[category] = (categories[category] ?: 0.0) + cost
            }
        }
        findViewById<TextView>(R.id.aveoReportTotal).text = money(total)
        val servicePct = if (total == 0.0) 0 else (services * 100 / total).toInt()
        val documentPct = if (total == 0.0) 0 else 100 - servicePct
        val coverage = if (items.length() == 0) 0 else (known * 100 / items.length())
        findViewById<TextView>(R.id.aveoReportPercentages).text = getString(
            R.string.aveo_report_percentages, servicePct, documentPct, coverage, known, items.length()
        )
        findViewById<VehicleReportView>(R.id.aveoReportChart).setData(
            total.toFloat(), services.toFloat(), documents.toFloat(),
            yearly.entries.sortedByDescending { it.key }.map { it.key to it.value.toFloat() }
        )
        categoryBreakdown.removeAllViews()
        categories.entries.sortedByDescending { it.value }.forEach { (name, value) ->
            categoryBreakdown.addView(categoryCard(name, value, total))
        }
    }

    private fun categoryCard(name: String, value: Double, total: Double): MaterialCardView {
        val percent = if (total == 0.0) 0 else (value * 100 / total).toInt()
        val card = baseCard()
        val body = verticalBody()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(name, 15f, Color.rgb(9, 9, 9), true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        top.addView(label("$percent% · ${money(value)}", 13f, Color.rgb(70, 71, 76), true))
        body.addView(top)
        body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = percent
            progressTintList = ColorStateList.valueOf(Color.rgb(217, 255, 67))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(232, 233, 236))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { setMargins(0, dp(10), 0, 0) }
        })
        card.addView(body)
        return card
    }

    private fun isDocument(item: JSONObject) = item.optString("title").contains("matrícula", ignoreCase = true)

    private fun categoryFor(title: String): String {
        val value = title.lowercase(Locale.getDefault())
        return when {
            "matrícula" in value -> getString(R.string.aveo_category_documents)
            listOf("freno", "disco", "pastilla", "tambor").any(value::contains) -> getString(R.string.aveo_category_brakes)
            listOf("embrague", "caja", "transmisión").any(value::contains) -> getString(R.string.aveo_category_transmission)
            listOf("suspensión", "rotación", "balanceo", "alineación", "rodamiento").any(value::contains) -> getString(R.string.aveo_category_suspension)
            listOf("batería", "eléctric").any(value::contains) -> getString(R.string.aveo_category_electrical)
            listOf("aceite", "distribución", "anticongelante", "inyección", "encendido", "cabezote", "termostato", "motor").any(value::contains) -> getString(R.string.aveo_category_engine)
            else -> getString(R.string.aveo_category_other)
        }
    }

    private fun maintenanceCard(item: JSONObject): MaterialCardView {
        val card = baseCard()
        val body = verticalBody()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        top.addView(label(item.getString("title"), 17f, Color.rgb(9, 9, 9), true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!item.isNull("cost")) {
            top.addView(label(money(item.getDouble("cost")), 14f, Color.rgb(9, 9, 9), true).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(217, 255, 67))
                    cornerRadius = dp(13).toFloat()
                }
                setPadding(dp(10), dp(5), dp(10), dp(5))
            })
        }
        body.addView(top)
        val km = if (item.isNull("km")) "" else getString(R.string.aveo_card_km, item.getInt("km"))
        body.addView(label(listOf(item.getString("date"), km).filter { it.isNotBlank() }.joinToString(" · "), 13f, Color.rgb(112, 113, 118)).apply {
            setPadding(0, dp(5), 0, 0)
        })
        val items = item.getJSONArray("items").toStringList().joinToString(" · ")
        body.addView(label(items, 14f, Color.rgb(35, 36, 41)).apply { setPadding(0, dp(10), 0, 0) })
        body.addView(label(item.getString("note"), 13f, Color.rgb(105, 106, 111)).apply { setPadding(0, dp(6), 0, 0) })
        card.addView(body)
        return card
    }

    private fun baseCard() = MaterialCardView(this).apply {
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

    private fun verticalBody() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(15), dp(17), dp(15))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) action(getJSONObject(index))
    }

    private fun JSONArray.toObjectList() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.toStringList() = (0 until length()).map { getString(it) }
    private fun money(value: Double) = String.format(Locale.US, "$%.2f", value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DATA_FILE = "mi_aveo.json"
        private const val PREF_DATA_URI = "miAveoDataUri"
    }
}
