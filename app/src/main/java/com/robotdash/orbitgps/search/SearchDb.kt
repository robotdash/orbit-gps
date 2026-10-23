package com.robotdash.orbitgps.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.robotdash.orbitgps.OfflineMaps
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.Pt
import java.text.Normalizer

data class Place(
    val name: String,
    val kind: String,      // city/town/…/poi/address/street
    val category: String,  // "supermarket", "fuel"… for businesses
    val detail: String,    // street + town
    val pt: Pt,
    val rank: Int,
) {
    /** Second line shown under the name. */
    val subtitle: String
        get() = listOf(category.replaceFirstChar { it.uppercase() }.takeIf { (kind == "poi" || kind == "address") && it.isNotBlank() },
            detail.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
}

/** A kind of place the user can browse ("Gas", "Rest areas"…) and the categories it covers. */
data class Category(val emoji: String, val label: String, val match: List<String>, val rv: Boolean = false, val words: List<String> = emptyList())

object Categories {
    val all = listOf(
        // RV & van life (shown first when that setting is on)
        Category("🚐", "Sani dumps", listOf("sanitary dump station"), rv = true, words = listOf("sani dump", "sani station", "dump station", "rv dump", "sewer dump", "sani")),
        Category("🌲", "Free camping", listOf("free camping"), rv = true, words = listOf("free camping", "boondocking", "rec site", "overnight")),
        Category("🏕️", "Campgrounds", listOf("camp site", "caravan site"), rv = true, words = listOf("campground", "rv park")),
        Category("🔥", "Propane", listOf("propane"), rv = true, words = listOf("propane", "lpg")),
        Category("✅", "Overnight parking", emptyList(), rv = true, words = listOf("overnight parking", "walmart overnight", "park overnight")),
        Category("💧", "Fresh water", listOf("water fill", "drinking water", "water point"), rv = true, words = listOf("potable water", "fresh water", "drinking water", "water fill")),
        // Everyone
        Category("⛽", "Gas", listOf("fuel"), words = listOf("gas", "fuel", "gas station", "petrol")),
        Category("🍔", "Food", listOf("restaurant", "fast food", "food court", "pub"), words = listOf("food", "restaurant", "restaurants", "eat")),
        Category("☕", "Coffee", listOf("cafe", "coffee"), words = listOf("coffee", "coffee shop", "cafe")),
        Category("🅿️", "Rest areas", listOf("rest area", "service area"), words = listOf("rest area", "rest stop")),
        Category("🛒", "Groceries", listOf("supermarket", "convenience", "greengrocer", "bakery", "butcher"), words = listOf("grocery", "groceries", "supermarket")),
        Category("🏨", "Hotels", listOf("hotel", "motel", "guest house", "hostel", "resort", "chalet"), words = listOf("hotel", "motel")),
        Category("⛺", "Camping", listOf("camp site", "caravan site", "free camping"), words = listOf("camping", "campsite")),
        Category("🏥", "Hospital", listOf("hospital", "clinic", "doctors"), words = listOf("hospital", "clinic")),
        Category("🐾", "Vet", listOf("veterinary"), words = listOf("vet", "veterinarian", "animal hospital", "pet hospital")),
        Category("💊", "Pharmacy", listOf("pharmacy", "chemist"), words = listOf("pharmacy", "drugstore")),
        Category("🏧", "Bank & ATM", listOf("bank", "atm"), words = listOf("bank", "atm")),
        Category("🔧", "Car repair", listOf("car repair", "tyres", "car parts"), words = listOf("mechanic", "car repair", "tires")),
        Category("🔌", "EV charging", listOf("charging station"), words = listOf("ev charging", "charging station", "charger")),
        Category("📷", "Sights", listOf("attraction", "viewpoint", "museum", "monument", "memorial", "artwork",
            "lighthouse", "castle", "ruins", "archaeological site", "gallery", "zoo")),
        Category("🏔️", "Landmarks", listOf("peak", "waterfall", "hot spring", "beach", "glacier", "volcano", "cave entrance", "arch")),
        Category("🌲", "Parks", listOf("park", "nature reserve", "picnic site"), words = listOf("park")),
    )

    /** Tiles to show: the RV & van life ones only when that setting is on. */
    fun visible(rv: Boolean) = all.filter { rv || !it.rv }

    /** "gas station", "coffee near me", "find a vet" → that category (for voice requests). */
    fun match(text: String): Category? {
        val t = SearchDb.fold(text).replace(Regex("""\b(near ?by|near me|nearest|closest|find|a|an|the|me)\b"""), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        return all.firstOrNull { c -> c.words.any { w -> t == w || t == w + "s" } || SearchDb.fold(c.label) == t }
    }
}

/** Offline search over every downloaded region's search.db. */
class SearchDb(private val ctx: Context) {

    private val dbs = LinkedHashMap<String, SQLiteDatabase>()   // file path -> open database

    val available: Boolean get() = OfflineMaps.has(ctx, OfflineMaps.Kind.SEARCH)

    /** The open search files (opening new regions' files, closing removed ones). */
    @Synchronized private fun open(): List<SQLiteDatabase> {
        val paths = OfflineMaps.files(ctx, OfflineMaps.Kind.SEARCH).map { it.absolutePath }
        dbs.keys.filter { it !in paths }.forEach { dbs.remove(it)?.close() }
        for (p in paths) if (p !in dbs) runCatching {
            dbs[p] = SQLiteDatabase.openDatabase(p, null, SQLiteDatabase.OPEN_READONLY)
        }
        return dbs.values.toList()
    }

    /** One of each place: regions overlap by ~50 km at their borders, so the same place can be in two files. */
    private fun distinct(places: List<Place>): List<Place> {
        val seen = HashMap<String, MutableList<Pt>>()
        return places.filter { p ->
            val k = p.kind + "|" + p.name.lowercase()
            val near = seen.getOrPut(k) { mutableListOf() }
            if (near.any { Geo.distance(it, p.pt) < 150 }) false else { near += p.pt; true }
        }
    }

    /**
     * Search as you type. Every word is matched as a prefix ("save on" finds
     * "Save-On-Foods"). Results near [near] come first within each importance level.
     */
    fun search(text: String, near: Pt?, limit: Int = 40): List<Place> {
        val ds = open().ifEmpty { return emptyList() }
        val words = queryWords(text)
        if (words.isEmpty()) return emptyList()
        val number = words.first().takeIf { w -> w.all { it.isDigit() } }
        val found = distinct(ds.flatMap { d -> query(d, words, addresses = false, max = 200) + query(d, words, addresses = true, max = 300) })
        if (found.isEmpty() && number != null && words.size > 1) {
            // That house number isn't on file: offer the nearest numbers on the same street.
            return closestNumbers(ds, number.toInt(), words.drop(1), near)
        }
        val first = words.first()
        return found.sortedWith(compareBy<Place>(
            // "5230 …": the exact house number first.
            { if (number != null && it.kind == "address" && leadingNumber(it.name) == number.toInt()) 0 else 1 },
            // Exact name start beats a match somewhere in the middle.
            { if (fold(it.name).startsWith(first)) 0 else 1 },
            // Nearer and more important first.
            { p -> near?.let { Geo.distance(it, p.pt) / 1000.0 + p.rank * 8 } ?: p.rank.toDouble() },
        )).take(limit)
    }

    private fun query(d: SQLiteDatabase, words: List<String>, addresses: Boolean, max: Int): List<Place> {
        val out = mutableListOf<Place>()
        d.rawQuery(
            "SELECT name, kind, category, detail, lat, lon, rank FROM places WHERE search MATCH ? AND kind " +
                (if (addresses) "= 'address'" else "!= 'address'") + " LIMIT $max",
            arrayOf(words.joinToString(" ") { "$it*" })
        ).use { c ->
            while (c.moveToNext()) out += Place(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                Pt(c.getDouble(4), c.getDouble(5)), c.getInt(6))
        }
        return out
    }

    /** Addresses on the street named by [streetWords], nearest house number to [n] first. */
    private fun closestNumbers(ds: List<SQLiteDatabase>, n: Int, streetWords: List<String>, near: Pt?): List<Place> {
        val onStreet = distinct(ds.flatMap { d -> query(d, streetWords, addresses = true, max = 2000) })
        if (onStreet.isEmpty()) return emptyList()
        // Several towns can have the same street name: keep the town nearest to you.
        val town = near?.let { me -> onStreet.minBy { Geo.distance(me, it.pt) }.detail }
        return onStreet.filter { town == null || it.detail == town }
            .mapNotNull { p -> leadingNumber(p.name)?.let { p to Math.abs(it - n) } }
            .sortedBy { it.second }
            .take(5)
            .map { (p, _) -> p.copy(category = "closest to $n") }
    }

    private fun leadingNumber(name: String): Int? = Regex("^(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Words to search for: lower-case, accents removed, "BC", "Canada" etc. dropped, a postal
     * code dropped ("V1T 4H9"), and "22nd", "1st", "3rd" read as the numbers BC addresses use.
     */
    internal fun queryWords(text: String): List<String> {
        val w = fold(text).replace(POSTAL, " ").replace(ZIP, " ")
            .split(Regex("[^a-z0-9]+"))
            .map { w -> ORDINAL.matchEntire(w)?.groupValues?.get(1) ?: w }
            .filter { it.isNotBlank() && it !in STOP_WORDS }
        return w
    }

    /** A place found by category, with how far it is (and, while navigating, how far off the route). */
    data class Found(val place: Place, val distance: Double, val offRoute: Double?)

    /**
     * Places in [category] near you. With [routeAhead] (the route still to drive) it
     * returns places within 2 km of the route, in the order you'll pass them, with
     * [Found.distance] = distance along the route.
     */
    fun nearby(category: Category, near: Pt, routeAhead: List<Pt>? = null, limit: Int = 60,
               extra: List<Place> = emptyList()): List<Found> {
        val found = mutableListOf<Place>()
        if (category.match.isNotEmpty()) for (d in open()) {
            val marks = category.match.joinToString(",") { "?" }
            d.rawQuery(
                "SELECT name, kind, category, detail, lat, lon, rank FROM places WHERE kind = 'poi' AND category IN ($marks)",
                category.match.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) found += Place(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    Pt(c.getDouble(4), c.getDouble(5)), c.getInt(6))
            }
        }
        val all = distinct(found).toMutableList()
        // Spots reported by Orbit users (spots.orbitgps.ca) sit alongside the map's places.
        all += extra
        if (routeAhead != null && routeAhead.size >= 2) {
            // Route sampled every ~150 m, with distance along it.
            val samples = ArrayList<Pair<Pt, Double>>()
            var along = 0.0
            samples += routeAhead[0] to 0.0
            for (i in 1 until routeAhead.size) {
                along += Geo.distance(routeAhead[i - 1], routeAhead[i])
                if (along - samples.last().second >= 150 || i == routeAhead.size - 1) samples += routeAhead[i] to along
            }
            val onRoute = all.mapNotNull { p ->
                // Quick reject: farther than the whole route + 2 km from the start.
                if (Geo.distance(p.pt, routeAhead[0]) > along + 2000) return@mapNotNull null
                val best = samples.minBy { Geo.distance(it.first, p.pt) }
                val off = Geo.distance(best.first, p.pt)
                if (off <= 2000) Found(p, best.second, off) else null
            }.sortedBy { it.distance }
            if (onRoute.isNotEmpty()) return onRoute.take(limit)
        }
        return all.map { Found(it, Geo.distance(near, it.pt), null) }.sortedBy { it.distance }.take(limit)
    }

    /**
     * The address (or street) nearest [p], e.g. "4741 Lakelse Avenue, Terrace" —
     * used to label "my current location" when saving Home. Null if nothing within 300 m.
     */
    fun nearestAddress(p: Pt): String? {
        val ds = open().ifEmpty { return null }
        val dLat = 0.003; val dLon = 0.005 // ~300 m box
        var best: Pair<String, Double>? = null
        // Let SQLite pick out the ~300 m box: with all of BC (1.4 million rows) reading every
        // row into the app took many seconds (Oct 22: "Report a spot" froze the phone).
        // (Numbers written into the SQL: Android binds arguments as text, which never equals a REAL.)
        fun n(v: Double) = String.format(java.util.Locale.ROOT, "%.6f", v)
        for (d in ds) d.rawQuery(
            "SELECT name, kind, detail, lat, lon FROM places WHERE kind IN ('address','street') " +
                "AND lat BETWEEN ${n(p.lat - dLat)} AND ${n(p.lat + dLat)} AND lon BETWEEN ${n(p.lon - dLon)} AND ${n(p.lon + dLon)}",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val lat = c.getDouble(3); val lon = c.getDouble(4)
                if (Math.abs(lat - p.lat) > dLat || Math.abs(lon - p.lon) > dLon) continue
                // Prefer a house address over a street name.
                val dist = Geo.distance(p, Pt(lat, lon)) + if (c.getString(1) == "street") 60 else 0
                if (best == null || dist < best!!.second) {
                    val town = c.getString(2)
                    best = (c.getString(0) + if (town.isNotBlank()) ", $town" else "") to dist
                }
            }
        }
        return best?.takeIf { it.second < 300 }?.first
    }

    /** What's in the loaded search file, e.g. built 2026-10-19 with 98,027 addresses (null for older files). */
    data class Info(val built: String?, val addresses: Int?)

    /** Info for the search file of [region]. */
    fun info(region: String): Info? {
        val f = OfflineMaps.file(ctx, region, OfflineMaps.Kind.SEARCH)
        if (!OfflineMaps.has(ctx, region, OfflineMaps.Kind.SEARCH)) return null
        val d = open().firstOrNull { it.path == f.absolutePath } ?: return null
        return runCatching {
            val m = HashMap<String, String>()
            d.rawQuery("SELECT k, v FROM meta", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) }
            // Older files have no counts: count the addresses directly.
            val addrs = m["addresses"]?.toIntOrNull() ?: d.rawQuery(
                "SELECT count(*) FROM places WHERE kind = 'address'", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            Info(m["built"], addrs)
        }.getOrNull()
    }

    /** Open the database and touch the index in the background, so the first search is instant. */
    fun warmUp() {
        for (d in open()) runCatching { d.rawQuery("SELECT name FROM places WHERE search MATCH 'a*' LIMIT 1", null).use { it.moveToFirst() } }
    }

    @Synchronized fun close() { dbs.values.forEach { it.close() }; dbs.clear() }

    companion object {
        private val STOP_WORDS = setOf("bc", "british", "columbia", "canada", "ca", "alberta", "usa")
        /** A US ZIP code at the end of an address with a street and number ("… Seattle 98101"). */
        private val ZIP = Regex("""(?<=[a-z].{0,60})\b\d{5}(-\d{4})?\s*$""")
        private val POSTAL = Regex("""\b[a-z]\d[a-z] ?\d[a-z]\d\b""")
        private val ORDINAL = Regex("""(\d+)(st|nd|rd|th)""")

        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}"), "")
                .lowercase()
                .replace("'", "").replace("’", "")
    }
}
