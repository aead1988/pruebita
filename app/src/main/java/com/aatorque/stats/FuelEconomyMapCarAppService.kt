package com.aatorque.stats

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/** Entry point advertised to Android Auto as a navigation/map application. */
class FuelEconomyMapCarAppService : CarAppService() {
    override fun onCreateSession(sessionInfo: SessionInfo): Session = FuelEconomyMapSession()

    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }
}

private class FuelEconomyMapSession : Session() {
    private var controller: FuelEconomyMapController? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val renderer = FuelEconomyMapRenderer(carContext, lifecycle)
        val mapController = FuelEconomyMapController(carContext, lifecycle, renderer)
        controller = mapController
        return FuelEconomyMapScreen(carContext, renderer, mapController::resetTrip)
    }
}
