package com.robotdash.orbitgps.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.robotdash.orbitgps.Format
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.R

/** Car screen: every turn still ahead on the route, with how far away it is. */
class CarTurnsScreen(car: CarContext) : Screen(car) {

    override fun onGetTemplate(): Template {
        val b = ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.turns_title))
            .setHeaderAction(Action.BACK)
        val route = NavCore.currentRoute
        if (route == null) {
            return b.setSingleList(ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_no_turns)).build()).build()
        }
        val along = NavCore.progress?.along ?: 0.0
        val steps = route.announced.filter { it.distanceFromStart > along + 3 }
        // The car decides how many rows may be shown (fewer while driving).
        val max = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val list = ItemList.Builder()
        steps.take(max).forEachIndexed { i, s ->
            val gapAfterPrev = if (i == 0) null else s.distanceFromStart - steps[i - 1].distanceFromStart
            val row = Row.Builder()
                .setTitle(s.instruction.trimEnd('.').ifBlank { carContext.getString(R.string.navigating) })
                .addText(buildString {
                    append(carContext.getString(R.string.car_turn_in, Format.distance(s.distanceFromStart - along)))
                    gapAfterPrev?.let { append("  ·  ").append(carContext.getString(R.string.car_turn_after_prev, Format.distance(it))) }
                })
                .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, turnDrawable(s))).build())
            list.addItem(row.build())
        }
        if (steps.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.car_no_turns))
        return b.setSingleList(list.build()).build()
    }
}
