package com.robotdash.orbitgps

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.robotdash.orbitgps.nav.Outline
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Region packs downloaded from maps.orbitgps.ca (Cloudflare R2, free downloads).
 *
 * The catalog (regions.json) lists each region's files with sizes and SHA-256s. Files
 * are fetched by Android's DownloadManager — Wi-Fi only by default, resumes by itself,
 * keeps going with the app closed — into app-specific storage. When all have arrived
 * they are checked, imported with [OfflineMaps.import] (same as picking files by hand)
 * and deleted.
 */
object RegionDownloads {
    const val BASE = "https://maps.orbitgps.ca/"
    /** 3.4+ (several regions): regions-v2.json. Older apps read regions.json, which lists only BC so
     *  they can't replace their BC maps with another region by accident. */
    private const val CATALOG = BASE + "regions-v2.json"
    /** Cloudflare turns away some generic agents, so say who we are. */
    private val AGENT = "OrbitGPS/${BuildConfig.VERSION_NAME} (Android)"

    data class RegionFile(val kind: String, val name: String, val url: String, val size: Long, val sha256: String, val optional: Boolean)

    /**
     * [area]: the region + 50 km (what its files cover); [core]: the province/state itself.
     * Outlines as rings of lon/lat pairs (null in older catalogs); routing uses them to pick a region.
     */
    data class Region(val id: String, val name: String, val description: String, val built: String, val files: List<RegionFile>,
                      val area: Outline? = null, val core: Outline? = null) {
        fun files(withTopo: Boolean) = files.filter { withTopo || !it.optional }
        fun size(withTopo: Boolean) = files(withTopo).sumOf { it.size }
    }

    // ------------------------------------------------------------------ catalog

    fun parseCatalog(json: String): List<Region> {
        val o = JSONObject(json)
        val arr = o.getJSONArray("regions")
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val fa = r.getJSONArray("files")
            Region(
                r.getString("id"), r.getString("name"), r.optString("description"), r.getString("built"),
                (0 until fa.length()).map { j ->
                    val f = fa.getJSONObject(j)
                    RegionFile(f.getString("kind"), f.getString("name"), f.getString("url"), f.getLong("size"),
                        f.getString("sha256"), f.optBoolean("optional", false))
                },
                r.optJSONObject("area")?.let { Outline.fromGeoJson(it) },
                r.optJSONObject("core")?.let { Outline.fromGeoJson(it) },
            )
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("regions", Context.MODE_PRIVATE)

    /** Download the list of regions (falls back to the last copy when offline). */
    fun fetchCatalog(ctx: Context): Result<List<Region>> = runCatching {
        val c = URL(CATALOG).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("User-Agent", AGENT)
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val regions = parseCatalog(text)
        prefs(ctx).edit().putString("catalog", text).apply()
        regions
    }.recoverCatching { e -> cachedCatalog(ctx) ?: throw e }

    fun cachedCatalog(ctx: Context): List<Region>? =
        prefs(ctx).getString("catalog", null)?.let { runCatching { parseCatalog(it) }.getOrNull() }

    // ------------------------------------------------------------------ installed

    /** Build date of [regionId]'s downloaded map/routing/search (null if not downloaded). */
    fun installed(ctx: Context, regionId: String): String? = prefs(ctx).let { p ->
        p.getString("built:$regionId", null) ?: p.takeIf { it.getString("installedId", null) == regionId }?.getString("installedBuilt", "")
    }

    /** Where 3.3's single set of files came from: (map/routing/search region, Topo region). */
    fun legacyRegions(ctx: Context): Pair<String?, String?> =
        prefs(ctx).getString("installedId", null) to prefs(ctx).getString("topoId", null)

    /** What's actually on the phone for [regionId], from the files themselves. */
    data class Parts(val base: Boolean, val topo: Boolean)
    fun parts(ctx: Context, regionId: String) = Parts(
        base = listOf(OfflineMaps.Kind.MAP, OfflineMaps.Kind.ROUTING, OfflineMaps.Kind.SEARCH).all { OfflineMaps.has(ctx, regionId, it) },
        topo = listOf(OfflineMaps.Kind.TERRAIN, OfflineMaps.Kind.CONTOURS).all { OfflineMaps.has(ctx, regionId, it) },
    )

    /** Take [regionId] off the phone (files and records). */
    fun remove(ctx: Context, regionId: String) {
        OfflineMaps.deleteRegion(ctx, regionId)
        val e = prefs(ctx).edit().remove("urls:$regionId").remove("built:$regionId").remove("topoBuilt:$regionId")
            .remove("area:$regionId").remove("core:$regionId")
        if (prefs(ctx).getString("installedId", null) == regionId) e.remove("installedId").remove("installedBuilt")
        if (prefs(ctx).getString("topoId", null) == regionId) e.remove("topoId").remove("topoBuilt")
        e.apply()
    }

    /** Outlines of an installed region (kept when it was downloaded, so they work offline). */
    fun shapes(ctx: Context, regionId: String): Pair<Outline?, Outline?> {
        fun get(k: String) = prefs(ctx).getString("$k:$regionId", null)?.let { runCatching { Outline.fromGeoJson(JSONObject(it)) }.getOrNull() }
        val a = get("area"); val c = get("core")
        if (a != null) return a to c
        // Downloaded before outlines were kept: take them from the catalog.
        val r = cachedCatalog(ctx)?.firstOrNull { it.id == regionId } ?: return null to null
        return r.area to r.core
    }

    // ------------------------------------------------------------------ per-file updates

    /**
     * The catalog URLs of the files installed for [region]. A new build only replaces the
     * files that changed (they get a new dated folder), so an update downloads just those.
     */
    fun installedUrls(ctx: Context, region: Region): Set<String> =
        storedUrls(ctx, region.id)?.keys ?: legacyUrls(ctx, region).keys

    /** Installed files of [region] that the catalog has newer versions of. */
    fun stale(ctx: Context, region: Region, optional: Boolean): List<RegionFile> {
        val have = installedUrls(ctx, region)
        val mine = region.files.filter { it.optional == optional }
        // Parts are joined on install, so a changed kind always comes down whole.
        val changedKinds = mine.filter { it.url !in have }.map { it.kind }.toSet()
        return mine.filter { it.kind in changedKinds }
    }

    private fun storedUrls(ctx: Context, regionId: String): MutableMap<String, String>? =
        prefs(ctx).getString("urls:$regionId", null)?.let { s ->
            runCatching { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap() } }.getOrNull()
        }

    /** Before 2.5 only the build date was kept: files in that date's folder are the installed ones. */
    private fun legacyUrls(ctx: Context, region: Region): MutableMap<String, String> {
        val base = installed(ctx, region.id)
        val topo = topoBuilt(ctx, region.id)
        return region.files.filter { f ->
            val built = if (f.optional) topo else base
            built != null && f.url.contains("/$built/")
        }.associate { it.url to it.kind }.toMutableMap()
    }

    /** Build date of the Topo files downloaded for [regionId], if any. */
    fun topoBuilt(ctx: Context, regionId: String): String? = prefs(ctx).let { p ->
        p.getString("topoBuilt:$regionId", null) ?: p.takeIf { it.getString("topoId", null) == regionId }?.getString("topoBuilt", null)
    }

    fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean("wifiOnly", true)
    fun setWifiOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wifiOnly", on).apply()

    // ------------------------------------------------------------------ jobs

    data class Job(val regionId: String, val regionName: String, val built: String, val withTopo: Boolean,
                   val files: List<Pair<RegionFile, Long>>) // file → DownloadManager id

    enum class State { RUNNING, WAITING_WIFI, WAITING_NETWORK, FAILED, COMPLETE }
    data class Status(val job: Job, val state: State, val doneBytes: Long, val totalBytes: Long) {
        val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt()
    }

    private fun dir(ctx: Context, regionId: String) = File(ctx.getExternalFilesDir("regions"), regionId)

    fun job(ctx: Context): Job? {
        val s = prefs(ctx).getString("job", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            val fa = o.getJSONArray("files")
            Job(o.getString("id"), o.getString("name"), o.getString("built"), o.getBoolean("topo"),
                (0 until fa.length()).map { i ->
                    val f = fa.getJSONObject(i)
                    RegionFile(f.getString("kind"), f.getString("name"), f.getString("url"), f.getLong("size"),
                        f.getString("sha256"), f.optBoolean("optional")) to f.getLong("dm")
                })
        }.getOrNull()
    }

    private fun saveJob(ctx: Context, j: Job?) {
        if (j == null) { prefs(ctx).edit().remove("job").apply(); return }
        val fa = JSONArray()
        j.files.forEach { (f, id) ->
            fa.put(JSONObject().put("kind", f.kind).put("name", f.name).put("url", f.url).put("size", f.size)
                .put("sha256", f.sha256).put("optional", f.optional).put("dm", id))
        }
        prefs(ctx).edit().putString("job", JSONObject().put("id", j.regionId).put("name", j.regionName)
            .put("built", j.built).put("topo", j.withTopo).put("files", fa).toString()).apply()
    }

    /** Start downloading [region] with or without Topo. Replaces any download already going. */
    fun start(ctx: Context, region: Region, withTopo: Boolean) = start(ctx, region, region.files(withTopo))

    /** Start downloading just [files] of [region] (e.g. only the Topo files). */
    fun start(ctx: Context, region: Region, files: List<RegionFile>) {
        require(files.isNotEmpty()) { "Nothing to download." }
        cancel(ctx)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val d = dir(ctx, region.id).apply { deleteRecursively(); mkdirs() }
        val wifi = wifiOnly(ctx)
        val ids = files.map { f -> f to enqueue(ctx, dm, region.name, d, f, !wifi) }
        saveJob(ctx, Job(region.id, region.name, region.built, files.any { it.optional }, ids))
    }

    private fun enqueue(ctx: Context, dm: DownloadManager, regionName: String, d: File, f: RegionFile, metered: Boolean): Long {
        File(d, f.name).delete()
        val req = DownloadManager.Request(Uri.parse(BASE + f.url))
            .setTitle(ctx.getString(R.string.dl_title, regionName))
            .setDescription(f.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(metered)
            .setAllowedOverRoaming(false)
            .addRequestHeader("User-Agent", AGENT)
            .setDestinationUri(Uri.fromFile(File(d, f.name)))
        return dm.enqueue(req)
    }

    /**
     * Start again every file that hasn't finished (after a failure, or to switch to
     * mobile data while waiting for Wi-Fi). Finished files are kept.
     */
    fun restartUnfinished(ctx: Context, allowMobileData: Boolean) {
        val j = job(ctx) ?: return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val done = HashSet<Long>()
        dm.query(DownloadManager.Query().setFilterById(*j.files.map { it.second }.toLongArray()))?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL)
                    done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            }
        }
        val d = dir(ctx, j.regionId).apply { mkdirs() }
        val files = j.files.map { (f, id) ->
            if (id in done) f to id
            else {
                runCatching { dm.remove(id) }
                f to enqueue(ctx, dm, j.regionName, d, f, allowMobileData)
            }
        }
        saveJob(ctx, j.copy(files = files))
    }

    /** Throw away what's downloaded and fetch the same files again. */
    fun startOver(ctx: Context) {
        val j = job(ctx) ?: return
        start(ctx, Region(j.regionId, j.regionName, "", j.built, j.files.map { it.first }), withTopo = true)
    }

    fun cancel(ctx: Context) {
        val j = job(ctx) ?: return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.remove(*j.files.map { it.second }.toLongArray()) }
        dir(ctx, j.regionId).deleteRecursively()
        saveJob(ctx, null)
    }

    /** Where the current download is up to (null if none). */
    fun status(ctx: Context): Status? {
        val j = job(ctx) ?: return null
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val total = j.files.sumOf { it.first.size }
        var done = 0L
        var failed = false; var wifiWait = false; var netWait = false; var allDone = true
        dm.query(DownloadManager.Query().setFilterById(*j.files.map { it.second }.toLongArray()))?.use { c ->
            var seen = 0
            while (c.moveToNext()) {
                seen++
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                done += got.coerceAtLeast(0)
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {}
                    DownloadManager.STATUS_FAILED -> { failed = true; allDone = false }
                    DownloadManager.STATUS_PAUSED -> {
                        allDone = false
                        if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) wifiWait = true
                        if (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) netWait = true
                    }
                    else -> allDone = false
                }
            }
            if (seen < j.files.size) failed = true // a download was removed (e.g. from the notification)
        }
        val state = when {
            failed -> State.FAILED
            allDone -> State.COMPLETE
            wifiWait -> State.WAITING_WIFI
            netWait -> State.WAITING_NETWORK
            else -> State.RUNNING
        }
        return Status(j, state, done.coerceAtMost(total), total)
    }

    /**
     * All files are here: check them, install them, tidy up. [progress] 0..100.
     * Returns the kinds installed.
     */
    fun finish(ctx: Context, progress: (Int) -> Unit): Result<List<OfflineMaps.Kind>> = runCatching {
        val j = job(ctx) ?: error("No download to install.")
        val d = dir(ctx, j.regionId)
        val files = j.files.map { (f, _) -> f to File(d, f.name) }
        files.forEachIndexed { i, (f, file) ->
            check(file.exists() && file.length() == f.size) { ctx.getString(R.string.dl_incomplete, f.name) }
            check(sha256(file) == f.sha256) { ctx.getString(R.string.dl_damaged, f.name) }
            progress(i * 30 / files.size)
        }
        val kinds = OfflineMaps.import(ctx, files.map { Uri.fromFile(it.second) }, { pct -> progress(30 + pct * 70 / 100) },
            region = j.regionId).getOrThrow()
        val e = prefs(ctx).edit()
        // Record exactly which files are now installed (replacing those of the same kinds).
        val urls = storedUrls(ctx, j.regionId)
            ?: cachedCatalog(ctx)?.firstOrNull { it.id == j.regionId }?.let { legacyUrls(ctx, it) }
            ?: mutableMapOf()
        val replaced = j.files.map { it.first.kind }.toSet()
        urls.entries.removeAll { it.value in replaced }
        j.files.forEach { (f, _) -> urls[f.url] = f.kind }
        e.putString("urls:${j.regionId}", JSONObject(urls as Map<*, *>).toString())
        if (j.files.any { !it.first.optional }) e.putString("built:${j.regionId}", j.built)
        if (j.files.any { it.first.optional }) e.putString("topoBuilt:${j.regionId}", j.built)
        cachedCatalog(ctx)?.firstOrNull { it.id == j.regionId }?.let { r ->
            r.area?.let { e.putString("area:${j.regionId}", it.toGeoJson().toString()) }
            r.core?.let { e.putString("core:${j.regionId}", it.toGeoJson().toString()) }
        }
        e.apply()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.remove(*j.files.map { it.second }.toLongArray()) } // clear the Downloads list too
        d.deleteRecursively()
        saveJob(ctx, null)
        kinds
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 20)
            while (true) { val r = inp.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** "1.1 GB", "540 MB". */
    fun sizeText(bytes: Long): String =
        if (bytes >= 1_000_000_000) String.format(java.util.Locale.CANADA, "%.1f GB", bytes / 1e9)
        else "${(bytes / 1_000_000).coerceAtLeast(1)} MB"
}
