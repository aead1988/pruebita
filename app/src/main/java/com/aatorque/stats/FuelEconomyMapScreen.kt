package com.aatorque.stats

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat

/** Host-controlled navigation template with the custom map rendered on its background surface. */
class FuelEconomyMapScreen(
    carContext: CarContext,
    private val renderer: FuelEconomyMapRenderer,
    private val resetTrip: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val reset = Action.Builder()
            .setTitle(carContext.getString(R.string.fuel_map_reset))
            .setIcon(icon(R.drawable.ic_map_reset))
            .setOnClickListener(resetTrip)
            .build()

        val mapActions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.fuel_map_zoom_in))
                    .setIcon(icon(R.drawable.ic_map_zoom_in))
                    .setOnClickListener(renderer::zoomIn)
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.fuel_map_zoom_out))
                    .setIcon(icon(R.drawable.ic_map_zoom_out))
                    .setOnClickListener(renderer::zoomOut)
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.fuel_map_recenter))
                    .setIcon(icon(R.drawable.ic_map_recenter))
                    .setOnClickListener(renderer::recenter)
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(reset).build())
            .setMapActionStrip(mapActions)
            .build()
    }

    private fun icon(resourceId: Int): CarIcon = CarIcon.Builder(
        IconCompat.createWithResource(carContext, resourceId)
    ).build()
}
