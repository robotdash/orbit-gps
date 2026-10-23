package com.robotdash.orbitgps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Regions' road networks joined into one, so a trip can run from one downloaded region into another.
 *
 * Since Nov 2026 every region's routing.tar is a piece of ONE road network built for all of Canada
 * and the US (tools/build_graph.sh); the piece starts with a "graph.json" naming that build. Pieces
 * of the same build fit together exactly, so on install their tiles are unpacked into one shared
 * folder (maps/graph/<build>/), which Valhalla reads as a single network. Each region keeps a list
 * of its tiles (routing.graph) so removing it deletes only tiles no other region needs.
 *
 * Older routing.tar files (no graph.json) are kept as they are and used on their own.
 */
object RoadNetwork {
    const val LIST = "routing.graph"

    private fun graphsDir(ctx: Context) = File(File(ctx.filesDir, "maps"), "graph")
    fun dir(ctx: Context, graph: String) = File(graphsDir(ctx), graph)
    private fun listFile(ctx: Context, region: String) = File(OfflineMaps.dir(ctx, region), LIST)

    /** The build a region's routing belongs to (null: its own routing.tar, or none). */
    fun graphOf(ctx: Context, region: String): String? =
        runCatching { JSONObject(listFile(ctx, region).readText()).getString("graph") }.getOrNull()

    fun has(ctx: Context, region: String) = graphOf(ctx, region) != null

    private fun tilesOf(ctx: Context, region: String): Set<String> = runCatching {
        val a = JSONObject(listFile(ctx, region).readText()).getJSONArray("tiles")
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    /** Regions on the phone whose routing is part of [graph]. */
    fun members(ctx: Context, graph: String) = OfflineMaps.regions(ctx).filter { graphOf(ctx, it) == graph }

    /** Bytes of tiles [region] holds in the shared folder. */
    fun size(ctx: Context, region: String): Long {
        val g = graphOf(ctx, region) ?: return 0
        val d = dir(ctx, g)
        return tilesOf(ctx, region).sumOf { File(d, it).length() }
    }

    /**
     * Just installed [region]'s routing.tar: if it's a piece of a joint build, unpack it into the
     * shared folder and keep the list instead of the tar. [progress] 0..100.
     */
    @Synchronized fun absorb(ctx: Context, region: String, progress: (Int) -> Unit = {}) {
        val tar = OfflineMaps.file(ctx, region, OfflineMaps.Kind.ROUTING)
        if (!tar.exists()) return
        val graph = tar.inputStream().buffered().use { readGraphId(it) } ?: return   // an older, self-contained file
        release(ctx, region)                                 // the region's previous tiles, if any
        val d = dir(ctx, graph).apply { mkdirs() }
        val tiles = JSONArray()
        val total = tar.length().coerceAtLeast(1)
        var done = 0L
        tar.inputStream().buffered(1 shl 16).use { inp ->
            entries(inp) { name, size, body ->
                done += size + 512
                if (!name.endsWith(".gph")) { body.skipFully(size); return@entries }
                val f = File(d, name)
                if (f.exists() && f.length() == size) body.skipFully(size)    // same tile from another region
                else {
                    f.parentFile!!.mkdirs()
                    val tmp = File(f.path + ".tmp")
                    tmp.outputStream().use { out -> body.copyExactly(out, size) }
                    check(tmp.renameTo(f)) { "Couldn't save road tile $name" }
                }
                tiles.put(name)
                progress((done * 100 / total).toInt())
            }
        }
        listFile(ctx, region).writeText(JSONObject().put("graph", graph).put("tiles", tiles).toString())
        tar.delete()
    }

    /** Taking [region] off the phone: delete its tiles that no other region uses. */
    @Synchronized fun release(ctx: Context, region: String) {
        val graph = graphOf(ctx, region) ?: return
        val mine = tilesOf(ctx, region)
        val others = members(ctx, graph).filter { it != region }.flatMap { tilesOf(ctx, it) }.toSet()
        val d = dir(ctx, graph)
        (mine - others).forEach { File(d, it).delete() }
        listFile(ctx, region).delete()
        if (others.isEmpty()) d.deleteRecursively()
        // Builds no region uses any more (after updates) go too.
        graphsDir(ctx).listFiles()?.filter { g -> members(ctx, g.name).isEmpty() }?.forEach { it.deleteRecursively() }
    }

    // ------------------------------------------------------------------ tar reading

    /** The "graph" of a piece: its first entry is graph.json. */
    internal fun readGraphId(inp: InputStream): String? {
        var id: String? = null
        runCatching {
            entries(inp) { name, size, body ->
                if (name == "graph.json") {
                    val b = ByteArray(size.toInt()); body.readExactly(b)
                    id = JSONObject(String(b)).optString("graph").ifBlank { null }
                }
                throw StopReading()
            }
        }
        return id
    }

    private class StopReading : RuntimeException()

    /** Walk a (ustar) tar: [each] must read or skip exactly `size` bytes of `body`. */
    private fun entries(inp: InputStream, each: (name: String, size: Long, body: InputStream) -> Unit) {
        val h = ByteArray(512)
        while (true) {
            if (!inp.readExactlyOrEnd(h)) return
            if (h.all { it.toInt() == 0 }) return                       // end of archive
            val name = str(h, 345, 155).let { p -> if (p.isEmpty()) "" else "$p/" } + str(h, 0, 100)
            val size = str(h, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = h[156].toInt().toChar()
            try {
                if (type == '0' || type == '\u0000') each(name.removePrefix("./"), size, inp) else inp.skipFully(size)
            } catch (e: StopReading) { return }
            inp.skipFully((512 - size % 512) % 512)                     // padding
        }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.US_ASCII)
    }

    private fun InputStream.readExactlyOrEnd(b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) { val r = read(b, n, b.size - n); if (r < 0) return n == 0 && false; n += r }
        return true
    }
    private fun InputStream.readExactly(b: ByteArray) { check(readExactlyOrEnd(b)) { "Road file ends early." } }
    private fun InputStream.skipFully(n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) { val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt()); check(r >= 0) { "Road file ends early." }; left -= r }
    }
    private fun InputStream.copyExactly(out: java.io.OutputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 16)
        while (left > 0) { val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt()); check(r >= 0) { "Road file ends early." }; out.write(buf, 0, r); left -= r }
    }
}
