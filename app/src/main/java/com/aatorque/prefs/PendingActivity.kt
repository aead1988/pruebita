package com.aatorque.prefs

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.aatorque.stats.R
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject

class PendingActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var emptyState: TextView
    private var currentKm = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)
        supportActionBar?.hide()

        list = findViewById(R.id.pendingList)
        emptyState = findViewById(R.id.pendingEmptyState)

        findViewById<View>(R.id.pendingNavHome).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_HOME, true)
                .apply()
            finish()
        }
        findViewById<View>(R.id.pendingNavAveo).setOnClickListener {
            startActivity(Intent(this, MiAveoActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.pendingNavPending).setOnClickListener {
            findViewById<ScrollView>(R.id.pendingScroll).smoothScrollTo(0, 0)
        }
        findViewById<View>(R.id.pendingNavExpenses).setOnClickListener {
            startActivity(Intent(this, ExpenseSummaryActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.pendingNavSettings).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ViewerSettingsFragment.PREF_OPEN_SETTINGS, true)
                .apply()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        loadCached()?.let(::render) ?: showNoData()
    }

    private fun loadCached(): JSONObject? = runCatching {
        val file = getFileStreamPath(DATA_FILE)
        if (!file.exists()) null else JSONObject(file.readText())
    }.getOrNull()

    private fun render(root: JSONObject) {
        val vehicle = root.optJSONObject("vehicle")
        currentKm = vehicle?.optInt("currentKm", 0) ?: 0
        val items = root.optJSONArray("pending")
            ?.toObjectList()
            ?.sortedWith(
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
            .orEmpty()

        findViewById<TextView>(R.id.pendingCount).text = getString(R.string.pending_count_format, items.size)
        findViewById<TextView>(R.id.pendingMileage).text = getString(R.string.pending_current_km, currentKm)
        list.removeAllViews()
        items.forEach { list.addView(pendingCard(it)) }
        emptyState.setText(R.string.pending_empty)
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showNoData() {
        currentKm = 0
        list.removeAllViews()
        findViewById<TextView>(R.id.pendingCount).text = getString(R.string.pending_count_format, 0)
        findViewById<TextView>(R.id.pendingMileage).text = getString(R.string.pending_no_data)
        emptyState.setText(R.string.pending_no_data)
        emptyState.visibility = View.VISIBLE
    }

    private fun pendingCard(item: JSONObject): MaterialCardView {
        val card = MaterialCardView(this).apply {
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
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label(item.optString("title"), 16f, Color.rgb(9, 9, 9), true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        item.optString("category").takeIf { it.isNotBlank() }?.let { category ->
            top.addView(label(category, 11f, Color.rgb(35, 40, 20), true).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(217, 255, 67))
                    cornerRadius = dp(13).toFloat()
                }
                setPadding(dp(9), dp(5), dp(9), dp(5))
            })
        }
        body.addView(top)
        item.optString("detail").takeIf { it.isNotBlank() }?.let { detail ->
            body.addView(label(detail, 14f, Color.rgb(102, 103, 108)).apply {
                setPadding(0, dp(7), 0, 0)
            })
        }
        if (item.has("dueKm") && item.has("lastKm")) {
            val due = item.optInt("dueKm")
            val last = item.optInt("lastKm")
            val progress = (((currentKm - last).toDouble() / (due - last).coerceAtLeast(1)) * 100)
                .toInt()
                .coerceIn(0, 100)
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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply {
                    setMargins(0, dp(12), 0, 0)
                }
            })
            val status = if (remaining >= 0) {
                getString(R.string.aveo_reminder_progress, progress, remaining)
            } else {
                getString(R.string.aveo_reminder_overdue, -remaining)
            }
            body.addView(label(status, 12f, color, true).apply { setPadding(0, dp(5), 0, 0) })
        } else if (item.has("dueDate")) {
            body.addView(
                label(
                    getString(R.string.aveo_reminder_due_date, item.optString("dueDate")),
                    12f,
                    Color.rgb(86, 87, 92),
                    true
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
        }
        card.addView(body)
        return card
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun JSONArray.toObjectList() = (0 until length()).map { getJSONObject(it) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DATA_FILE = "mi_aveo.json"
    }
}
