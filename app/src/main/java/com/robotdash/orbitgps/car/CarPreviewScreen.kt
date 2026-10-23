package com.robotdash.orbitgps.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.robotdash.orbitgps.Format
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.R
import com.robotdash.orbitgps.nav.Pt

/** Route preview on the car screen: distance, time and a big Go button. */
class CarPreviewScreen(
    car: CarContext,
    private val dest: Pt,
    private val name: String,
    private val detail: String,
) : Screen(car), NavCore.Listener {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                NavCore.addListener(this@CarPreviewScreen)
                NavCore.preview(dest, name, detail)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                NavCore.removeListener(this@CarPreviewScreen)
                // Backed out without starting? Clear the preview.
                if (NavCore.mode == NavCore.Mode.PREVIEW && NavCore.destination == dest) NavCore.cancelPreview()
            }
        })
    }

    override fun onStateChanged() = invalidate()

    override fun onGetTemplate(): Template {
        val error = NavCore.previewError
        if (error != null && NavCore.previewRoute == null) {
            // No route: a plain message with a Back button.
            return MessageTemplate.Builder(error)
                .setTitle(carContext.getString(R.string.car_no_route))
                .setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle(carContext.getString(R.string.back))
                    .setOnClickListener { screenManager.pop() }.build())
                .build()
        }
        val b = RoutePreviewNavigationTemplate.Builder()
            .setTitle(name)
            .setHeaderAction(Action.BACK)
        val route = NavCore.previewRoute
        when {
            route != null -> {
                // Android Auto wants the time and distance as tagged values it can format itself.
                val text = SpannableString("  ·  ")
                text.setSpan(DurationSpan.create(route.durationSec.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                val km = route.length / 1000
                val dist = if (route.length < 1000) Distance.create(route.length, Distance.UNIT_METERS)
                    else Distance.create(km, if (km < 10) Distance.UNIT_KILOMETERS_P1 else Distance.UNIT_KILOMETERS)
                text.setSpan(DistanceSpan.create(dist), 4, 5, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                val row = Row.Builder()
                    .setTitle(CarText.create(text))
                    .apply { if (detail.isNotBlank()) addText(detail) }
                    .build()
                b.setItemList(ItemList.Builder().addItem(row).setOnSelectedListener { }.setSelectedIndex(0).build())
                b.setNavigateAction(
                    Action.Builder().setTitle(carContext.getString(R.string.go))
                        .setOnClickListener {
                            NavCore.start(simulate = false)
                            screenManager.popToRoot()
                        }.build()
                )
            }
            else -> b.setLoading(true)
        }
        return b.build()
    }
}
