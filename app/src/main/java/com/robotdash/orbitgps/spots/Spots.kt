package com.robotdash.orbitgps.spots

import android.content.Context
import com.robotdash.orbitgps.BuildConfig
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.search.Place
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spots reported by Orbit users (free camping, Walmart overnight yes/no, potable water…),
 * via spots.orbitgps.ca. Reports and votes wait on the phone until there's a connection;
 * approved spots are downloaded as one small file and searched alongside the map's places.
 *
 * Only what the person chooses is sent: the spot, its type, a note and tick boxes.
 */
object Spots {
    const val BASE = "https://spots.orbitgps.ca/"
    private val AGENT = "OrbitGPS/${BuildConfig.VERSION_NAME} (Android)"
    /** Fetch the list again after this long (when online). */
    private const val REFRESH_MS = 3600_000L

    enum class Type(val key: String, val emoji: String, val label: String) {
        OVERNIGHT_OK("overnight_ok", "✅", "Overnight parking OK"),
        NO_OVERNIGHT("no_overnight", "🚫", "No overnight parking"),
        FREE_CAMPING("free_camping", "🌲", "Free camping"),
        REST_AREA("rest_area", "🅿️", "Rest area / pull-off"),
        SANI_DUMP("sani_dump", "🚐", "Sani dump"),
        POTABLE_WATER("potable_water", "💧", "Fresh potable water"),
        PROPANE("propane", "🔥", "Propane");
        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
    }

    enum class Flag(val key: String, val label: String) {
        BIG_RIG("bigrig", "Big rigs OK"), TOILETS("toilets", "Toilets"), CELL("cell", "Cell signal"), FEE("fee", "Costs money");
        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
    }

    data class Spot(val id: Long, val type: Type, val pt: Pt, val note: String, val flags: List<Flag>,
                    val up: Int, val down: Int, val confirmed: String?)

    /** Which reported types show under which place category (by its label). */
    fun typesFor(categoryLabel: String): Set<Type> = when (categoryLabel) {
        "Free camping" -> setOf(Type.FREE_CAMPING, Type.OVERNIGHT_OK, Type.NO_OVERNIGHT)
        "Camping" -> setOf(Type.FREE_CAMPING, Type.OVERNIGHT_OK)
        "Overnight parking" -> setOf(Type.OVERNIGHT_OK, Type.NO_OVERNIGHT)
        "Rest areas" -> setOf(Type.REST_AREA)
        "Sani dumps" -> setOf(Type.SANI_DUMP)
        "Propane" -> setOf(Type.PROPANE)
        "Fresh water" -> setOf(Type.POTABLE_WATER)
        else -> emptySet()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("spots", Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, "spots.json")

    // ------------------------------------------------------------------ reporting

    /** Queue a report (sent now if there's a connection, otherwise later). */
    fun report(ctx: Context, type: Type, pt: Pt, note: String, flags: Set<Flag>) {
        val o = JSONObject().put("type", type.key).put("lat", pt.lat).put("lon", pt.lon)
            .put("note", note.trim().take(300)).put("flags", JSONArray(flags.map { it.key }))
        enqueue(ctx, JSONObject().put("path", "report").put("body", o))
    }

    /** "Still good" / "gone or changed" for an approved spot. */
    fun vote(ctx: Context, id: Long, good: Boolean) {
        enqueue(ctx, JSONObject().put("path", "vote").put("body", JSONObject().put("id", id).put("good", good)))
        val v = prefs(ctx).getString("voted", "") ?: ""
        prefs(ctx).edit().putString("voted", "$v,$id").apply()
    }

    fun votedOn(ctx: Context, id: Long) = (prefs(ctx).getString("voted", "") ?: "").split(",").contains(id.toString())

    @Synchronized private fun enqueue(ctx: Context, item: JSONObject) {
        val q = queue(ctx).put(item)
        prefs(ctx).edit().putString("queue", q.toString()).apply()
    }

    private fun queue(ctx: Context) = runCatching { JSONArray(prefs(ctx).getString("queue", "[]")) }.getOrDefault(JSONArray())

    fun waiting(ctx: Context): Int = (0 until queue(ctx).length()).count { queue(ctx).getJSONObject(it).optString("path") == "report" }

    /** Send what's waiting. Stops at the first network failure (no signal); returns reports sent. */
    @Synchronized fun flush(ctx: Context, post: (String, String) -> Int = ::post): Int {
        val q = queue(ctx)
        val keep = JSONArray()
        var sent = 0
        var offline = false
        for (i in 0 until q.length()) {
            val item = q.getJSONObject(i)
            if (offline) { keep.put(item); continue }
            val code = runCatching { post(item.getString("path"), item.getJSONObject("body").toString()) }.getOrDefault(-1)
            when {
                code in 200..299 -> if (item.getString("path") == "report") sent++
                code == 429 || code == -1 || code >= 500 -> { keep.put(item); offline = true }   // try again later
                else -> {}                                                                  // refused (bad data): drop it
            }
        }
        prefs(ctx).edit().putString("queue", keep.toString()).apply()
        return sent
    }

    private fun post(path: String, body: String): Int {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", AGENT)
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode.also { runCatching { (if (it < 400) c.inputStream else c.errorStream)?.close() } }
    }

    // ------------------------------------------------------------------ approved spots

    /** Download the approved spots if it's been a while (call off the main thread). */
    fun refresh(ctx: Context, force: Boolean = false, maxAgeMs: Long = REFRESH_MS): Boolean {
        val last = prefs(ctx).getLong("fetched", 0)
        if (!force && System.currentTimeMillis() - last < maxAgeMs && file(ctx).exists()) return false
        return runCatching {
            val c = URL(BASE + "spots.json").openConnection() as HttpURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 10_000   // with no signal, give up quickly
            c.setRequestProperty("User-Agent", AGENT)
            val text = c.inputStream.bufferedReader().use { it.readText() }
            parse(text)                                   // check it before keeping it
            val tmp = File(file(ctx).path + ".tmp"); tmp.writeText(text); tmp.renameTo(file(ctx))
            prefs(ctx).edit().putLong("fetched", System.currentTimeMillis()).apply()
            cache = null
            true
        }.getOrDefault(false)
    }

    @Volatile private var cache: Pair<Long, List<Spot>>? = null

    fun all(ctx: Context): List<Spot> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        cache?.let { if (it.first == f.lastModified()) return it.second }
        val list = runCatching { parse(f.readText()) }.getOrDefault(emptyList())
        cache = f.lastModified() to list
        return list
    }

    fun parse(text: String): List<Spot> {
        val arr = JSONObject(text).getJSONArray("spots")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val type = Type.of(o.optString("t")) ?: return@mapNotNull null
            Spot(o.getLong("id"), type, Pt(o.getDouble("lat"), o.getDouble("lon")), o.optString("n"),
                o.optString("f").split(",").mapNotNull { Flag.of(it) }, o.optInt("up"), o.optInt("down"),
                o.optString("c").takeIf { it.isNotBlank() && it != "null" })
        }
    }

    /** Reported spots for a place category, as search places (category "spot:<id>"). */
    fun places(ctx: Context, categoryLabel: String, nowMs: Long = System.currentTimeMillis()): List<Place> {
        val types = typesFor(categoryLabel)
        if (types.isEmpty()) return emptyList()
        return all(ctx).filter { it.type in types }.map { s ->
            val bits = listOfNotNull(
                s.note.ifBlank { null },
                s.flags.joinToString(", ") { it.label }.ifBlank { null },
                confirmedText(s.confirmed, nowMs),
                if (s.up > 0) "👍 ${s.up}" else null,
            )
            Place("${s.type.emoji} ${s.type.label}", "poi", "spot:${s.id}", bits.joinToString(" · "), s.pt, 5)
        }
    }

    fun idOf(p: Place): Long? = p.category.removePrefix("spot:").takeIf { p.category.startsWith("spot:") }?.toLongOrNull()

    /** "confirmed 3 weeks ago" (reported spots age; this tells people how fresh it is). */
    fun confirmedText(iso: String?, nowMs: Long): String? {
        val t = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        val days = ((nowMs - t) / 86_400_000L).coerceAtLeast(0)
        return "confirmed " + when {
            days < 1 -> "today"
            days < 2 -> "yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            days < 730 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }
}
