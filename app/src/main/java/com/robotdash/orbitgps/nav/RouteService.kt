package com.robotdash.orbitgps.nav

import android.content.Context
import com.robotdash.orbitgps.OfflineMaps
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Offline routing on the phone (Valhalla over a downloaded region's routing.tar). With several
 * regions on the phone, each trip uses the region whose area holds the whole trip ([pick]).
 * Loading the road network takes a moment, so it happens once on a background
 * thread; route requests also run off the main thread.
 */
class RouteService(private val ctx: Context) {

    private val worker = Executors.newSingleThreadExecutor()
    private val engines = HashMap<String, Valhalla>()        // region -> loaded road network (worker thread only)
    @Volatile var loadError: String? = null
        private set
    /** Where the last route was planned from (warm-up loads that region's roads first). */
    @Volatile var lastPoint: Pt? = null

    val available: Boolean get() = OfflineMaps.has(ctx, OfflineMaps.Kind.ROUTING)

    /** Start loading the road network in the background. */
    fun warmUp(near: Pt? = lastPoint) {
        if (!available) return
        worker.execute { runCatching { engine(pick(listOfNotNull(near)) ?: return@execute) } }
    }

    private fun engine(region: String): Valhalla? {
        engines[region]?.let { return it }
        return try {
            val tar = OfflineMaps.file(ctx, region, OfflineMaps.Kind.ROUTING).absolutePath
            val config = ValhallaConfigFactory.usingTileExtract(tar)
            Valhalla(ctx, config).also {
                // Keep at most two road networks loaded (each maps a few hundred MB of tiles).
                if (engines.size >= 2) engines.keys.first().let { k -> engines.remove(k)?.close() }
                engines[region] = it; loadError = null
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /** Thrown when a trip starts in one downloaded region and ends in another. */
    class CrossesRegions(msg: String) : Exception(msg)

    /**
     * The region to route [points] with: one whose area (region + 50 km) holds them all,
     * preferring the one whose province/state holds the most of them. Regions loaded by hand
     * have no outline and are used when no outlined region fits.
     */
    internal fun pick(points: List<Pt>): String? = pick(points, OfflineMaps.regions(ctx)
        .filter { OfflineMaps.has(ctx, it, OfflineMaps.Kind.ROUTING) }
        .map { it to com.robotdash.orbitgps.RegionDownloads.shapes(ctx, it) })

    companion object {
        internal fun pick(points: List<Pt>, regions: List<Pair<String, Pair<Outline?, Outline?>>>): String? {
            if (regions.isEmpty()) return null
            if (points.isEmpty()) return regions.first().first
            val outlined = regions.filter { it.second.first != null }
            val fits = outlined.filter { (_, s) -> points.all { it in s.first!! } }
            if (fits.isNotEmpty()) return fits.maxBy { (_, s) -> points.count { p -> s.second?.contains(p) == true } }.first
            return regions.firstOrNull { it.second.first == null }?.first
                ?: regions.maxBy { (_, s) -> points.count { it in s.first!! } }.first   // best try; may fail at the edge
        }
    }

    /** A route starting further than this from the car didn't start where the car is. */
    private val NEAR_START_M = 60.0

    /** Route preferences (from Settings). */
    data class Options(val avoidHighways: Boolean = false, val avoidFerries: Boolean = false, val avoidUnpaved: Boolean = false)
    @Volatile var options = Options()

    /**
     * Route from [from] (with your current [heading], so it doesn't start you with a
     * U-turn) to [to]. Result is delivered on a background thread.
     */
    fun route(from: Pt, heading: Double?, to: Pt, done: (Result<Route>) -> Unit) = route(from, heading, emptyList(), to, done)

    /** Route through [via] stops, in order, then on to [to]. */
    fun route(from: Pt, heading: Double?, via: List<Pt>, to: Pt, done: (Result<Route>) -> Unit) {
        worker.execute {
            var result = runCatching { request(from, heading, via, to) }
            // The heading only helps pick the right side of a road. If it made the router
            // start somewhere else (stale heading in a parking lot, or no road nearby going
            // that way), plan again from the nearest road and keep whichever starts closer.
            if (heading != null) {
                val gap = result.getOrNull()?.let { startGap(it, from) }
                if (gap == null || gap > NEAR_START_M) {
                    val plain = runCatching { request(from, null, via, to) }
                    val plainGap = plain.getOrNull()?.let { startGap(it, from) }
                    if (plainGap != null && (gap == null || plainGap < gap)) result = plain
                }
            }
            done(result)
        }
    }

    private fun startGap(r: Route, from: Pt) = Geo.distance(r.points.first(), from)

    private fun request(from: Pt, heading: Double?, via: List<Pt>, to: Pt): Route {
        lastPoint = from
        val points = listOf(from) + via + to
        val region = pick(points) ?: error("Routing data isn't loaded.")
        crossing(points, region)?.let { throw CrossesRegions(it) }
        val v = engine(region) ?: error(loadError ?: "Routing data isn't loaded.")
        val start = JSONObject().put("lat", from.lat).put("lon", from.lon)
        if (heading != null) start.put("heading", heading.toInt()).put("heading_tolerance", 60)
        val req = JSONObject()
            .put("locations", JSONArray().apply {
                put(start)
                via.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("type", "break")) }
                put(JSONObject().put("lat", to.lat).put("lon", to.lon))
            })
            .put("costing", "auto")
            .put("costing_options", JSONObject().put("auto", JSONObject().apply {
                val o = options
                if (o.avoidHighways) put("use_highways", 0.0)
                if (o.avoidFerries) put("use_ferry", 0.0)
                if (o.avoidUnpaved) put("exclude_unpaved", true)
            }))
            .put("format", "osrm")
            .put("directions_options", JSONObject().put("units", "kilometers"))
        val raw = try {
            v.routeRaw(req.toString())
        } catch (e: Exception) {
            error(friendly(e.message ?: e.javaClass.simpleName))
        }
        val err = JSONObject(raw)
        if (err.has("message") && !err.has("routes")) error(friendly(err.optString("message")))
        return Route.fromOsrmJson(raw)
    }

    private fun friendly(msg: String) = when {
        msg.contains("No path", true) || msg.contains("No suitable edges", true) ->
            "Couldn't find a road route there. It may be outside the downloaded area or not reachable by road."
        msg.contains("exceeds", true) -> "That's too far for the downloaded area."
        else -> "Couldn't make a route: $msg"
    }

    /**
     * If [points] go outside [region]'s area but each lies in some downloaded region, the trip
     * crosses between regions, which needs road networks built together (not yet): say so.
     */
    private fun crossing(points: List<Pt>, region: String): String? {
        val area = com.robotdash.orbitgps.RegionDownloads.shapes(ctx, region).first ?: return null
        if (points.all { it in area }) return null
        val others = OfflineMaps.regions(ctx).filter { it != region }
            .mapNotNull { com.robotdash.orbitgps.RegionDownloads.shapes(ctx, it).first }
        if (points.any { p -> p !in area && others.none { p in it } }) return null   // outside everything: normal error
        return ctx.getString(com.robotdash.orbitgps.R.string.route_crosses_regions)
    }

    fun close() {
        worker.execute { engines.values.forEach { it.close() }; engines.clear() }
        worker.shutdown()
    }
}
