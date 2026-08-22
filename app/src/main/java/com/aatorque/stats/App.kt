package com.aatorque.stats

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import org.acra.config.mailSender
import org.acra.config.toast
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import timber.log.Timber


class App : Application() {

    val logTree = CacheLogTree()


    override fun onCreate() {
        super.onCreate()
        Timber.plant(logTree)
        applyFuelPrice324Once()
        FuelSyncScheduler.refresh(this, runImmediately = true)
        fixAndroid14Perms()
    }

    private fun applyFuelPrice324Once() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.getBoolean(PREF_FUEL_PRICE_324_APPLIED, false)) return
        preferences.edit()
            .putString(PREF_FUEL_PRICE, "3.24")
            .putBoolean(PREF_FUEL_PRICE_324_APPLIED, true)
            .apply()
    }
    
    fun fixAndroid14Perms() {
        for (file in getDir("car_sdk_impl", Context.MODE_PRIVATE).listFiles() ?: emptyArray()) {
            if (file.isDirectory) {
                for (subfile in file.listFiles() ?: emptyArray()) {
                    Timber.i("Setting read only permission for $subfile")
                    subfile.setReadOnly()
                }
            }
            Timber.i("Setting read only permission for $file")
            file.setReadOnly()
        }
    }

    override fun attachBaseContext(base:Context) {
        super.attachBaseContext(base)

        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.KEY_VALUE_LIST
            toast {
                text = "App crashed. Tap to report."
            }
            mailSender {
                //required
                mailTo = "zgronick+zcrz@gmzil.com".replace("z", "a")
                //defaults to true
                reportAsFile = false
                //defaults to ACRA-report.stacktrace
                reportFileName = "Crash.txt"
            }
        }
    }

    companion object {
        private const val PREF_FUEL_PRICE = "fuelPricePerGallon"
        private const val PREF_FUEL_PRICE_324_APPLIED = "fuelPrice324Applied"
    }
}
