package com.robotdash.orbitgps.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.R

/** Stops on this trip, on the car screen. Tap one to remove it. */
class CarStopsScreen(car: CarContext) : Screen(car) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        NavCore.stops.forEachIndexed { i, s ->
            list.addItem(Row.Builder().setTitle("${i + 1}. ${s.name}")
                .addText(carContext.getString(R.string.stop_remove))
                .setOnClickListener {
                    val idx = NavCore.stops.indexOf(s)
                    if (idx >= 0) NavCore.removeStop(idx)
                    CarToast.makeText(carContext, R.string.car_stop_removed, CarToast.LENGTH_SHORT).show()
                    screenManager.pop()
                }.build())
        }
        list.setNoItemsMessage(carContext.getString(R.string.stops_title))
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.stops_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
