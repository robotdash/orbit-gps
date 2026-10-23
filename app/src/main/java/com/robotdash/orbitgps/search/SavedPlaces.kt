package com.robotdash.orbitgps.search

import android.content.Context
import com.robotdash.orbitgps.nav.Pt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Home, Work, favourites and recent destinations, kept on the phone.
 */
class SavedPlaces(ctx: Context) {

    enum class Slot { HOME, WORK }

    data class Saved(val name: String, val detail: String, val pt: Pt) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("detail", detail).put("lat", pt.lat).put("lon", pt.lon)
        companion object {
            fun fromJson(o: JSONObject) = Saved(o.optString("name"), o.optString("detail"), Pt(o.getDouble("lat"), o.getDouble("lon")))
        }
    }

    private val prefs = ctx.getSharedPreferences("saved_places", Context.MODE_PRIVATE)

    fun get(slot: Slot): Saved? = prefs.getString(slot.name, null)?.let { runCatching { Saved.fromJson(JSONObject(it)) }.getOrNull() }
    fun set(slot: Slot, s: Saved) = prefs.edit().putString(slot.name, s.toJson().toString()).apply()
    fun clear(slot: Slot) = prefs.edit().remove(slot.name).apply()

    fun favourites(): List<Saved> = list("favourites")
    fun addFavourite(s: Saved) = putList("favourites", listOf(s) + favourites().filterNot { same(it, s) }, 30)
    fun removeFavourite(s: Saved) = putList("favourites", favourites().filterNot { same(it, s) }, 30)

    fun renameFavourite(s: Saved, newName: String) =
        putList("favourites", favourites().map { if (same(it, s)) it.copy(name = newName) else it }, 30)

    fun recents(): List<Saved> = list("recents")
    fun addRecent(s: Saved) = putList("recents", listOf(s) + recents().filterNot { same(it, s) }, 12)

    private fun same(a: Saved, b: Saved) = a.name == b.name &&
        Math.abs(a.pt.lat - b.pt.lat) < 1e-4 && Math.abs(a.pt.lon - b.pt.lon) < 1e-4

    private fun list(key: String): List<Saved> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { Saved.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun putList(key: String, items: List<Saved>, max: Int) {
        val arr = JSONArray(); items.take(max).forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
