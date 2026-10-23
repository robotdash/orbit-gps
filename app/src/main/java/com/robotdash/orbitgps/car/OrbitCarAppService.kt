package com.robotdash.orbitgps.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.robotdash.orbitgps.NavCore

/** Entry point for Android Auto. */
class OrbitCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Test builds: also allow the Desktop Head Unit and other test hosts.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = OrbitCarSession()
}

/** One connection to a car screen. */
class OrbitCarSession : Session() {

    private lateinit var renderer: CarMapRenderer

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                NavCore.init(carContext)
                renderer = CarMapRenderer(carContext)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
                NavCore.addListener(renderer)
                NavCore.holdGps("car")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                NavCore.removeListener(renderer)
                NavCore.releaseGps("car")
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val home = NavScreen(carContext)
        // Started by "Hey Google, navigate to …": open the search for it on top of the map.
        voiceTarget(intent)?.let { target ->
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(home)
            return target
        }
        return home
    }

    override fun onNewIntent(intent: Intent) {
        voiceTarget(intent)?.let { carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(it) }
    }

    /**
     * Navigation and search intents from the Assistant: geo:lat,lon?q=place.
     * A place name opens the offline search with it filled in; "gas", "coffee"… open
     * that category; bare coordinates go straight to the route preview.
     */
    private fun voiceTarget(intent: Intent): Screen? {
        val a = intent.action ?: return null
        if (a != CarContext.ACTION_NAVIGATE && a != Intent.ACTION_VIEW) return null
        val g = GeoQuery.parse(intent.data?.toString() ?: return null) ?: return null
        if (g.query.isBlank()) {
            val pt = g.pt ?: return null
            return CarPreviewScreen(carContext, pt, carContext.getString(com.robotdash.orbitgps.R.string.dropped_pin), "")
        }
        com.robotdash.orbitgps.search.Categories.match(g.query)?.let { return CarCategoryScreen(carContext, it) }
        return CarSearchScreen(carContext, g.query)
    }
}

/** A geo: URI as sent by the Assistant or another app. */
data class GeoQuery(val pt: com.robotdash.orbitgps.nav.Pt?, val query: String) {
    companion object {
        fun parse(uri: String): GeoQuery? {
            if (!uri.startsWith("geo:") && !uri.startsWith("geo.offline:")) return null
            val body = uri.substringAfter(':')
            val coords = body.substringBefore('?')
            val params = body.substringAfter('?', "").split('&').associate {
                it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val ll = coords.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            var pt = if (ll.size >= 2 && (ll[0] != 0.0 || ll[1] != 0.0)) com.robotdash.orbitgps.nav.Pt(ll[0], ll[1]) else null
            var q = params["q"].orEmpty().trim()
            // "q=49.1,-123.2(Label)" form
            Regex("""^(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?$""").find(q)?.let { m ->
                pt = com.robotdash.orbitgps.nav.Pt(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
                q = ""
            }
            return GeoQuery(pt, q)
        }
    }
}
