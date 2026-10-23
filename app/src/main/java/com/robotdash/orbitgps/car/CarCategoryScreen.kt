package com.robotdash.orbitgps.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.robotdash.orbitgps.Format
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.R
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.search.Category
import com.robotdash.orbitgps.search.SearchDb
import java.util.concurrent.Executors

/** Places in one category (Gas, Food…) — along the route while navigating, else nearest. */
class CarCategoryScreen(car: CarContext, private val cat: Category) : Screen(car) {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var found: List<SearchDb.Found>? = null

    init {
        val near = NavCore.lastFix?.let { Pt(it.latitude, it.longitude) }
        val ahead = if (NavCore.mode == NavCore.Mode.NAVIGATING) {
            val r = NavCore.navigator?.route
            val along = NavCore.progress?.along ?: 0.0
            r?.points?.filterIndexed { i, _ -> r.cumulative[i] >= along }
        } else null
        if (near != null) io.execute {
            val f = NavCore.search.nearby(cat, near, ahead, limit = 20, extra = com.robotdash.orbitgps.spots.Spots.places(carContext, cat.label))
            main.post { found = f; invalidate() }
        } else found = emptyList()
    }

    override fun onGetTemplate(): Template {
        val b = ListTemplate.Builder().setTitle("${cat.emoji} ${cat.label}").setHeaderAction(Action.BACK)
        val list = found ?: return b.setLoading(true).build()
        val max = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val items = ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.cat_none))
        list.take(max).forEach { f ->
            val dist = if (f.offRoute != null) carContext.getString(R.string.km_ahead, Format.distance(f.distance))
                else Format.distance(f.distance)
            items.addItem(Row.Builder().setTitle(f.place.name)
                .apply { DirIcons.icon(f.place.pt)?.let { setImage(it, Row.IMAGE_TYPE_SMALL) } }
                .addText(listOfNotNull(dist, f.place.detail.takeIf { it.isNotBlank() }).joinToString(" · "))
                .setOnClickListener { pick(carContext, screenManager, f.place.pt, f.place.name, f.place.detail) }
                .build())
        }
        return b.setSingleList(items.build()).build()
    }

    companion object {
        /** Picked a place in the car: add it as a stop while navigating, else preview a route. */
        fun pick(car: CarContext, sm: androidx.car.app.ScreenManager, pt: Pt, name: String, detail: String) {
            if (NavCore.mode == NavCore.Mode.NAVIGATING) {
                NavCore.addStop(pt, name) { ok ->
                    CarToast.makeText(car,
                        if (ok) car.getString(R.string.stop_added, name) else car.getString(R.string.stop_failed, NavCore.stopError ?: ""),
                        CarToast.LENGTH_LONG).show()
                }
                sm.popToRoot()
            } else sm.push(CarPreviewScreen(car, pt, name, detail))
        }
    }
}
