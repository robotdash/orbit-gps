package com.robotdash.orbitgps

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Offline region packs, stored inside the app, one folder per region (maps/r/<region id>/),
 * so several regions can be on the phone at once (3.4+). Each folder can hold:
 *  - map:     region.pmtiles  (what the map looks like)
 *  - routing: routing.tar     (Valhalla road network for turn-by-turn)
 *  - search:  search.db       (towns, streets, addresses, businesses)
 *  - terrain: terrain.pmtiles (elevation tiles for hill shading, Topo style)
 *  - contours: contours.pmtiles (contour lines, Topo style)
 *
 * Files can also be picked by hand (they go in the [HAND] folder). Big files can come in
 * parts (…part1, …part2); parts are joined back together in name order. Each file's
 * type is recognised from its contents, so they can be picked all at once.
 * Up to 3.3 there was one set of files directly in maps/; [migrate] moves it into its region's folder.
 */
object OfflineMaps {

    enum class Kind(val fileName: String, val label: String) {
        MAP("region.pmtiles", "map"),
        ROUTING("routing.tar", "routing"),
        SEARCH("search.db", "search"),
        TERRAIN("terrain.pmtiles", "terrain"),
        CONTOURS("contours.pmtiles", "contours"),
    }

    enum class MapStyle(val label: String) { STANDARD("Standard"), TOPO("Topo"), GREY("Grey") }

    /** Region folder for files picked by hand (not from the download list). */
    const val HAND = "files"

    private fun root(ctx: Context) = File(ctx.filesDir, "maps").apply { mkdirs() }
    private fun regionsDir(ctx: Context) = File(root(ctx), "r").also { migrate(ctx, it) }
    fun dir(ctx: Context, region: String) = File(regionsDir(ctx), region).apply { mkdirs() }
    fun file(ctx: Context, region: String, kind: Kind) = File(dir(ctx, region), kind.fileName)
    fun has(ctx: Context, region: String, kind: Kind) = file(ctx, region, kind).let { it.exists() && it.length() > 1024 } ||
        (kind == Kind.ROUTING && RoadNetwork.has(ctx, region))   // unpacked into the shared road network

    /** Regions with at least one file on the phone. */
    fun regions(ctx: Context): List<String> =
        regionsDir(ctx).listFiles()?.filter { d -> d.isDirectory && Kind.entries.any { has(ctx, d.name, it) } }
            ?.map { it.name }?.sorted().orEmpty()

    /** Every region's file of this kind. */
    fun files(ctx: Context, kind: Kind): List<File> = regions(ctx).filter { has(ctx, it, kind) }.map { file(ctx, it, kind) }
    fun has(ctx: Context, kind: Kind) = files(ctx, kind).isNotEmpty()
    fun hasOfflineMap(ctx: Context) = has(ctx, Kind.MAP)

    /** Remove a region from the phone. */
    fun deleteRegion(ctx: Context, region: String) {
        RoadNetwork.release(ctx, region)
        File(regionsDir(ctx), region).deleteRecursively()
    }

    /**
     * 3.3 and earlier kept one set of files directly in maps/. Move them into the folder of the
     * region they came from (recorded by [RegionDownloads]), or [HAND] if they were picked by hand.
     */
    @Synchronized private fun migrate(ctx: Context, dir: File) {
        val old = Kind.entries.map { it to File(root(ctx), it.fileName) }.filter { it.second.exists() }
        if (old.isNotEmpty()) {
            val (base, topo) = RegionDownloads.legacyRegions(ctx)
            old.forEach { (kind, f) ->
                val region = (if (kind == Kind.TERRAIN || kind == Kind.CONTOURS) topo ?: base else base) ?: HAND
                val to = File(File(dir, region).apply { mkdirs() }, kind.fileName)
                if (to.exists()) f.delete() else if (!f.renameTo(to)) f.copyTo(to, overwrite = true).also { f.delete() }
            }
        }
    }

    /** Topo needs a terrain file; without one, fall back to Standard. */
    fun effectiveStyle(ctx: Context, wanted: MapStyle): MapStyle =
        if (wanted == MapStyle.TOPO && !has(ctx, Kind.TERRAIN)) MapStyle.STANDARD else wanted

    /** Style JSON showing every region's map files together. */
    fun styleJson(ctx: Context, night: Boolean = false, wanted: MapStyle = MapStyle.STANDARD): String {
        val style = effectiveStyle(ctx, wanted)
        val base = when (style) {
            MapStyle.STANDARD -> "offline_style"
            MapStyle.TOPO -> "offline_style_topo"
            MapStyle.GREY -> "offline_style_grey"
        }
        val template = ctx.assets.open(base + (if (night) "_night" else "") + ".json").bufferedReader().use { it.readText() }
        fun urls(kind: Kind) = files(ctx, kind).map { "pmtiles://file://" + it.absolutePath }
        return withFiles(template, mapOf("PMTILES_URL" to urls(Kind.MAP), "DEM_URL" to urls(Kind.TERRAIN),
            "CONTOUR_URL" to urls(Kind.CONTOURS)))
    }

    /**
     * Point a style template's sources at the files: a source whose url is a placeholder gets one
     * copy per file ("protomaps", "protomaps@1"...) and every layer using it is repeated right after
     * itself for each copy, so the drawing order stays the same. A source with no files is dropped
     * along with its layers. The first copy keeps the original source and layer ids.
     */
    internal fun withFiles(template: String, files: Map<String, List<String>>): String {
        val o = JSONObject(template)
        val sources = o.getJSONObject("sources")
        val copies = HashMap<String, Int>()            // source id -> number of files
        for (id in sources.keys().asSequence().toList()) {
            val src = sources.getJSONObject(id)
            val urls = files[src.optString("url")] ?: continue
            sources.remove(id)
            urls.forEachIndexed { i, u ->
                sources.put(if (i == 0) id else "$id@$i", JSONObject(src.toString()).put("url", u))
            }
            copies[id] = urls.size
        }
        val layers = o.getJSONArray("layers")
        val out = JSONArray()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val n = copies[l.optString("source")]
            if (n == null) { out.put(l); continue }
            for (k in 0 until n) {
                out.put(if (k == 0) l else JSONObject(l.toString()).put("id", l.getString("id") + "@$k")
                    .put("source", l.getString("source") + "@$k"))
            }
        }
        o.put("layers", out)
        return o.toString()
    }

    /** Drop a source and every layer that uses it from a style. */
    internal fun withoutSource(json: String, source: String): String {
        val o = JSONObject(json)
        o.getJSONObject("sources").remove(source)
        val layers = o.getJSONArray("layers")
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.optString("source") != source) kept.put(l)
        }
        o.put("layers", kept)
        return o.toString()
    }

    private fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: ""

    private fun sizeOf(ctx: Context, uri: Uri): Long =
        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L

    /** What kind of file is this, from its first bytes? */
    internal fun sniff(head: ByteArray, n: Int, name: String = ""): Kind? {
        fun str(from: Int, len: Int) = if (n >= from + len) String(head, from, len, Charsets.ISO_8859_1) else ""
        return when {
            str(0, 7) == "PMTiles" -> pmtilesKind(head, n, name)
            str(0, 15) == "SQLite format 3" -> Kind.SEARCH
            str(257, 5) == "ustar" -> Kind.ROUTING
            else -> null
        }
    }

    /**
     * Map, terrain or contours? Terrain tiles are images (PMTiles tile type png/webp);
     * the contours file has "orbit-contours" in its metadata.
     */
    private fun pmtilesKind(head: ByteArray, n: Int, name: String): Kind {
        if (n < 127) return Kind.MAP
        when (head[99].toInt() and 0xFF) {
            2, 4 -> return Kind.TERRAIN
            1 -> {}
            else -> return Kind.MAP
        }
        fun u64(off: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (head[off + i].toLong() and 0xFF)
            return v
        }
        val metaOff = u64(24)
        val metaLen = u64(32)
        val meta = runCatching {
            if (metaOff + metaLen > n || metaLen <= 0) "" else {
                var bytes = head.copyOfRange(metaOff.toInt(), (metaOff + metaLen).toInt())
                if ((head[97].toInt() and 0xFF) == 2) bytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
        return if (meta.contains("\"orbit-contours\"") || meta.contains("\"id\":\"contours\"") ||
            (meta.isEmpty() && name.contains("contour", ignoreCase = true))) Kind.CONTOURS else Kind.MAP
    }

    /** Base name without ".partN", used to group the parts of one file. */
    private fun baseName(name: String) = name.replace(Regex("""\.part\d+.*$"""), "")

    /**
     * Import whatever the user picked. Returns the kinds that were installed.
     * [progress] gets 0..100 across everything.
     */
    fun import(ctx: Context, uris: List<Uri>, progress: (Int) -> Unit, region: String = HAND): Result<List<Kind>> = runCatching {
        require(uris.isNotEmpty()) { "No file chosen." }
        val files = uris.map { Triple(it, displayName(ctx, it), sizeOf(ctx, it)) }
        val groups = files.groupBy { baseName(it.second) }.values.map { g -> g.sortedBy { it.second } }
        val total = files.sumOf { it.third }.coerceAtLeast(1)
        var copied = 0L
        var lastPct = -1
        val installed = mutableListOf<Kind>()
        val buf = ByteArray(1 shl 20)

        for (group in groups) {
            val firstName = group.first().second
            if (group.size == 1 && firstName.contains(".part")) {
                require(!firstName.contains("part1")) {
                    "\"$firstName\" is one part of a bigger file. Select all of its parts together."
                }
            }
            // Identify from the first part's header.
            val head = ByteArray(65536)
            val n = ctx.contentResolver.openInputStream(group.first().first)!!.use { readFully(it, head) }
            val kind = sniff(head, n, firstName) ?: error(
                "\"$firstName\" isn't an Orbit GPS map, routing, search, terrain or contours file." +
                    if (firstName.contains(".part")) " If it comes in parts, make sure part 1 is selected too." else ""
            )
            val dest = file(ctx, region, kind)
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { out ->
                for ((uri, _, _) in group) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            copied += r
                            val pct = (copied * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct.coerceAtMost(100)) }
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            check(tmp.renameTo(dest)) { "Couldn't save the ${kind.label} file." }
            if (kind == Kind.ROUTING) RoadNetwork.absorb(ctx, region)    // join it to the other regions' roads
            installed += kind
        }
        installed
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    fun delete(ctx: Context, region: String, kind: Kind) { file(ctx, region, kind).delete() }
}
