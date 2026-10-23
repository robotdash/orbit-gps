package com.robotdash.orbitgps

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** User settings, stored on the phone. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    enum class Theme { AUTO, DAY, NIGHT }

    var voiceOn: Boolean
        get() = p.getBoolean("voiceOn", true)
        set(v) = p.edit().putBoolean("voiceOn", v).apply()

    /** 0–100 */
    var volume: Int
        get() = p.getInt("volume", 100)
        set(v) = p.edit().putInt("volume", v.coerceIn(0, 100)).apply()

    /** Always play Orbit's voice on the phone speaker, even with Bluetooth connected. */
    var phoneSpeaker: Boolean
        get() = p.getBoolean("phoneSpeaker", false)
        set(v) = p.edit().putBoolean("phoneSpeaker", v).apply()

    /** Give turn warnings even earlier (about 30% further out). */
    var earlyWarnings: Boolean
        get() = p.getBoolean("earlyWarnings", false)
        set(v) = p.edit().putBoolean("earlyWarnings", v).apply()

    var avoidHighways: Boolean
        get() = p.getBoolean("avoidHighways", false)
        set(v) = p.edit().putBoolean("avoidHighways", v).apply()

    var avoidFerries: Boolean
        get() = p.getBoolean("avoidFerries", false)
        set(v) = p.edit().putBoolean("avoidFerries", v).apply()

    var avoidUnpaved: Boolean
        get() = p.getBoolean("avoidUnpaved", false)
        set(v) = p.edit().putBoolean("avoidUnpaved", v).apply()

    var theme: Theme
        get() = runCatching { Theme.valueOf(p.getString("theme", "AUTO")!!) }.getOrDefault(Theme.AUTO)
        set(v) = p.edit().putString("theme", v.name).apply()

    /** Show RV & van life place types (sani dumps, free camping, campgrounds, propane). */
    var rvMode: Boolean
        get() = p.getBoolean("rvMode", false)
        set(v) = p.edit().putBoolean("rvMode", v).apply()

    /**
     * The 6 tiles on the main screen's shortcut row: category labels, or [HOME], [WORK], [FAVOURITES].
     * (2.8 kept 3 place types only; those move after Home, Work and Favourites.)
     */
    var shortcuts: List<String>
        get() {
            val stored = p.getString("shortcuts", null)?.split("|")?.filter { it.isNotBlank() } ?: return DEFAULT_SHORTCUTS
            return if (stored.none { it.startsWith("@") } && stored.size <= 3) listOf(HOME, WORK, FAVOURITES) + stored else stored
        }
        set(v) = p.edit().putString("shortcuts", v.take(SHORTCUT_SLOTS).joinToString("|")).apply()

    /** Always exactly 6 slots (topped up from the defaults). */
    fun shortcutSlots(): List<String> = (shortcuts + DEFAULT_SHORTCUTS).distinct().take(SHORTCUT_SLOTS)

    /** Put [label] in [slot]; if it's already in another slot the two swap. */
    fun pinShortcut(slot: Int, label: String) {
        val now = shortcutSlots().toMutableList()
        val was = now.indexOf(label)
        if (was >= 0 && was != slot) now[was] = now[slot]
        now[slot] = label
        shortcuts = now
    }

    /** Garmin-style junction pictures before exits, forks and tricky turns. */
    var junctionPictures: Boolean
        get() = p.getBoolean("junctionPictures", true)
        set(v) = p.edit().putBoolean("junctionPictures", v).apply()

    var mapStyle: OfflineMaps.MapStyle
        get() = runCatching { OfflineMaps.MapStyle.valueOf(p.getString("mapStyle", "STANDARD")!!) }
            .getOrDefault(OfflineMaps.MapStyle.STANDARD)
        set(v) = p.edit().putString("mapStyle", v.name).apply()

    companion object {
        const val SHORTCUT_SLOTS = 6
        const val HOME = "@home"
        const val WORK = "@work"
        const val FAVOURITES = "@fav"
        val DEFAULT_SHORTCUTS = listOf(HOME, WORK, FAVOURITES, "Gas", "Food", "Coffee")
    }
}

/** Sunrise/sunset (NOAA approximation), to switch the map to night colours automatically. */
object Sun {
    /** True if the sun is down (or within ~15 min of setting / after rising) at this place and time. */
    fun isNight(lat: Double, lon: Double, now: Calendar = Calendar.getInstance()): Boolean {
        val (rise, set) = riseSetUtcHours(lat, lon, now) ?: return false
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now.timeInMillis }
        val h = utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0
        // Normalise into rise..rise+24 so overnight wrap-around works.
        val sinceRise = ((h - rise) % 24 + 24) % 24
        val dayLen = ((set - rise) % 24 + 24) % 24
        return sinceRise > dayLen - 0.25 // dark from 15 min before sunset until sunrise
    }

    /** UTC hours of sunrise and sunset, or null in polar day/night. */
    fun riseSetUtcHours(lat: Double, lon: Double, day: Calendar): Pair<Double, Double>? {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val g = 2 * Math.PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val zenith = Math.toRadians(90.833)
        val c = cos(zenith) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (c < -1 || c > 1) return null
        val ha = Math.toDegrees(acos(c))
        val riseMin = 720 - 4 * (lon + ha) - eqTime
        val setMin = 720 - 4 * (lon - ha) - eqTime
        return (riseMin / 60 + 24) % 24 to (setMin / 60 + 24) % 24
    }
}
