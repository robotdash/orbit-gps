package com.robotdash.orbitgps.nav

import android.content.Context
import com.robotdash.orbitgps.OfflineMaps
import com.robotdash.orbitgps.RoadNetwork
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
    private val engines = HashMap<String, Valhalla>()        // router key -> loaded road network (worker thread only)
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

    private fun engine(key: String): Valhalla? {
        engines[key]?.let { return it }
        return try {
            val config = if (key.startsWith(GRAPH))
                ValhallaConfigFactory.usingTilesDir(RoadNetwork.dir(ctx, key.removePrefix(GRAPH)).absolutePath)
            else ValhallaConfigFactory.usingTileExtract(OfflineMaps.file(ctx, key, OfflineMaps.Kind.ROUTING).absolutePath)
            Valhalla(ctx, config).also {
                // Keep at most two road networks loaded (each maps a few hundred MB of tiles).
                if (engines.size >= 2) engines.keys.first().let { k -> engines.remove(k)?.close() }
                engines[key] = it; loadError = null
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /** Thrown when a trip starts in one downloaded region and ends in another. */
    class CrossesRegions(msg: String) : Exception(msg)

    /**
     * A road network that can route a trip: one region's own routing.tar, or the shared network
     * of every region whose routing came from the same joint build ([RoadNetwork]).
     * [areas]: each member region's area (region + 50 km), null when unknown (loaded by hand).
     */
    data class Router(val key: String, val areas: List<Outline?>, val cores: List<Outline?>) {
        val outlined get() = areas.none { it == null }
        fun holds(p: Pt) = areas.any { it != null && p in it }
        fun inCore(p: Pt) = cores.any { it != null && p in it }
    }

    internal fun routers(): List<Router> {
        val regions = OfflineMaps.regions(ctx).filter { OfflineMaps.has(ctx, it, OfflineMaps.Kind.ROUTING) }
        val shapes = regions.associateWith { com.robotdash.orbitgps.RegionDownloads.shapes(ctx, it) }
        val joined = regions.groupBy { RoadNetwork.graphOf(ctx, it) }
        return joined.flatMap { (graph, members) ->
            if (graph == null) members.map { Router(it, listOf(shapes[it]!!.first), listOf(shapes[it]!!.second)) }
            else listOf(Router(GRAPH + graph, members.map { shapes[it]!!.first }, members.map { shapes[it]!!.second }))
        }
    }

    /** The road network to route [points] with (see [pick] below). */
    internal fun pick(points: List<Pt>): String? = pick(points, routers())

    companion object {
        private const val GRAPH = "graph:"

        /**
         * The network that holds the whole trip (every point within one of its regions' areas),
         * preferring the one whose provinces/states hold the most points. A network with a region
         * loaded by hand (no outline) is used when no outlined one fits.
         */
        internal fun pick(points: List<Pt>, routers: List<Router>): String? {
            if (routers.isEmpty()) return null
            if (points.isEmpty()) return routers.first().key
            val fits = routers.filter { r -> r.outlined && points.all { r.holds(it) } }
            if (fits.isNotEmpty()) return fits.maxBy { r -> points.count { r.inCore(it) } }.key
            return routers.firstOrNull { !it.outlined }?.key
                ?: routers.maxBy { r -> points.count { r.holds(it) } }.key   // best try; may fail at the edge
        }

        /** One region per network (tests, and regions with their own routing.tar). */
        @JvmName("pickRegions")
        internal fun pick(points: List<Pt>, regions: List<Pair<String, Pair<Outline?, Outline?>>>): String? =
            pick(points, regions.map { (id, s) -> Router(id, listOf(s.first), listOf(s.second)) })
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
    private fun crossing(points: List<Pt>, key: String): String? {
        val all = routers()
        val mine = all.firstOrNull { it.key == key } ?: return null
        if (!mine.outlined || points.all { mine.holds(it) }) return null
        if (points.any { p -> all.none { it.holds(p) } }) return null   // outside everything: normal error
        return ctx.getString(com.robotdash.orbitgps.R.string.route_crosses_regions)
    }

    fun close() {
        worker.execute { engines.values.forEach { it.close() }; engines.clear() }
        worker.shutdown()
    }
}
