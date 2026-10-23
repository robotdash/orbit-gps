package com.robotdash.orbitgps.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.robotdash.orbitgps.Format
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.R
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.search.Categories
import java.util.concurrent.Executors

/**
 * Car search. Empty box: favourites and recent places. Typing (when parked —
 * Android Auto locks the keyboard while driving) searches the offline index.
 */
class CarSearchScreen(car: CarContext, private val initial: String = "") : Screen(car) {

    private data class Item(val name: String, val detail: String, val pt: Pt)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var items: List<Item> = saved()
    private var query = ""

    init { if (initial.isNotBlank()) runSearch(initial) }

    private fun maxRows(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceAtMost(12)

    private fun saved(): List<Item> =
        (NavCore.saved.favourites() + NavCore.saved.recents())
            .distinctBy { it.name + it.pt }
            .map { Item(it.name, it.detail, it.pt) }

    private fun runSearch(text: String) {
        query = text
        if (text.isBlank()) { items = saved(); invalidate(); return }
        val near = NavCore.lastFix?.let { Pt(it.latitude, it.longitude) }
        io.execute {
            val found = NavCore.search.search(text, near, limit = 20).map { Item(it.name, it.subtitle, it.pt) }
            main.post { if (query == text) { items = found; invalidate() } }
        }
    }

    override fun onGetTemplate(): Template {
        val near = NavCore.lastFix?.let { Pt(it.latitude, it.longitude) }
        val list = ItemList.Builder()
        var room = maxRows()
        // Empty box: quick categories first (most useful on the road), then saved places.
        if (query.isBlank()) {
            (if (NavCore.settings.rvMode) Categories.all.filter { it.label in setOf("Sani dumps", "Free camping", "Gas", "Rest areas") }
             else Categories.all.filter { it.label in setOf("Gas", "Food", "Coffee", "Rest areas") }).take((room - 2).coerceAtLeast(2)).forEach { c ->
                list.addItem(Row.Builder().setTitle("${c.emoji} ${c.label}")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarCategoryScreen(carContext, c)) }.build())
                room--
            }
        }
        items.take(room.coerceAtLeast(0)).forEach { it ->
            val row = Row.Builder().setTitle(it.name)
            DirIcons.icon(it.pt)?.let { ic -> row.setImage(ic, Row.IMAGE_TYPE_SMALL) }
            val dist = near?.let { n -> Format.distance(Geo.distance(n, it.pt)) }
            val second = listOfNotNull(dist, it.detail.takeIf { d -> d.isNotBlank() }).joinToString(" · ")
            if (second.isNotBlank()) row.addText(second)
            row.setOnClickListener { CarCategoryScreen.pick(carContext, screenManager, it.pt, it.name, it.detail) }
            list.addItem(row.build())
        }
        list.setNoItemsMessage(carContext.getString(
            if (!NavCore.search.available) R.string.search_missing
            else if (query.isBlank()) R.string.car_search_empty else R.string.no_results
        ))
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = runSearch(searchText)
            override fun onSearchSubmitted(searchText: String) = runSearch(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.search_hint))
            .setShowKeyboardByDefault(false)
            .apply { if (initial.isNotBlank()) setInitialSearchText(initial) }
            .setItemList(list.build())
            .build()
    }
}
