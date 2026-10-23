package com.robotdash.orbitgps.car

import android.location.Location
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.robotdash.orbitgps.CrashLog
import com.robotdash.orbitgps.Format
import com.robotdash.orbitgps.JunctionRenderer
import com.robotdash.orbitgps.NavCore
import com.robotdash.orbitgps.R
import com.robotdash.orbitgps.nav.NavStep
import com.robotdash.orbitgps.nav.TurnIcon
import com.robotdash.orbitgps.search.SavedPlaces
import com.robotdash.orbitgps.voice.ManeuverType
import com.robotdash.orbitgps.voice.Side
import java.util.TimeZone

/**
 * The main car screen: the map, plus the turn card and arrival time while
 * navigating. Buttons: Search, Home, Work (idle) / Stop, Mute (navigating).
 */
class NavScreen(car: CarContext) : Screen(car), NavCore.Listener {

    private val navManager = car.getCarService(NavigationManager::class.java)
    private var navStartedWithHost = false
    private var lastInvalidate = 0L
    private var lastStepKey = ""

    init {
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = NavCore.stop()
            /** Google's review "auto drive": run the demo drive to Home (or the previewed place). */
            override fun onAutoDriveEnabled() {
                if (NavCore.mode == NavCore.Mode.PREVIEW && NavCore.previewRoute != null) NavCore.start(simulate = true)
            }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { NavCore.addListener(this@NavScreen); syncHost() }
            override fun onDestroy(owner: LifecycleOwner) {
                NavCore.removeListener(this@NavScreen)
                if (navStartedWithHost) navManager.navigationEnded()
                navManager.clearNavigationManagerCallback()
            }
        })
    }

    // ------------------------------------------------------------ updates

    override fun onStateChanged() { syncHost(); invalidate() }

    override fun onFix(raw: Location, shown: Location) {
        if (NavCore.mode != NavCore.Mode.NAVIGATING) return
        // Refresh the turn card about once a second, or immediately when the turn changes.
        val key = "${NavCore.progress?.nextIndex}:${NavCore.progress?.offRoute}"
        val now = SystemClock.elapsedRealtime()
        if (key != lastStepKey || now - lastInvalidate > 1000) {
            lastStepKey = key; lastInvalidate = now
            invalidate()
            updateTrip()
        }
    }

    /** Tell the car (and its instrument cluster) whether we're navigating. */
    private fun syncHost() {
        val navigating = NavCore.mode == NavCore.Mode.NAVIGATING
        if (navigating && !navStartedWithHost) { navManager.navigationStarted(); navStartedWithHost = true }
        if (!navigating && navStartedWithHost) { navManager.navigationEnded(); navStartedWithHost = false }
    }

    private fun updateTrip() {
        if (!navStartedWithHost) return
        val step = currentStep() ?: return
        val est = travelEstimate() ?: return
        val trip = Trip.Builder()
            .addStep(step.first, est)
            .addDestination(Destination.Builder().setName(NavCore.destinationName).build(), est)
            .apply { NavCore.currentRoute?.announced?.getOrNull(NavCore.progress?.nextIndex ?: -1)?.streetName
                ?.takeIf { it.isNotBlank() }?.let { setCurrentRoad(it) } }
            .build()
        runCatching { navManager.updateTrip(trip) }
    }

    // ------------------------------------------------------------ template

    override fun onGetTemplate(): Template = try {
        buildTemplate()
    } catch (t: Throwable) {
        // A problem building the turn card must not crash the car app: show the plain map.
        android.util.Log.w("OrbitCar", "template failed", t)
        CrashLog.record(carContext, t, "car template")
        NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
            .build()
    }

    private fun buildTemplate(): Template {
        val b = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.createCustom(0xFF1565C0.toInt(), 0xFF1565C0.toInt()))
        when (NavCore.mode) {
            NavCore.Mode.NAVIGATING -> {
                val nav = NavCore.navigator
                when {
                    nav?.arrived == true -> b.setNavigationInfo(
                        MessageInfo.Builder(carContext.getString(R.string.arrived_toast))
                            .setText(NavCore.destinationName)
                            .setImage(icon(R.drawable.turn_arrive)).build()
                    )
                    NavCore.progress?.offRoute == true -> b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                    else -> currentStep()?.let { (step, dist) ->
                        val ri = RoutingInfo.Builder().setCurrentStep(step, dist)
                        junctionIcon()?.let { ri.setJunctionImage(it) }
                        nextStep()?.let { ri.setNextStep(it) }
                        b.setNavigationInfo(ri.build())
                    } ?: b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                }
                travelEstimate()?.let { b.setDestinationTravelEstimate(it) }
                b.setActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_search))
                            .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }.build())
                        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_turn_list))
                            .setOnClickListener { screenManager.push(CarTurnsScreen(carContext)) }.build())
                        .addAction(Action.Builder().setTitle(carContext.getString(R.string.stop))
                            .setOnClickListener { NavCore.stop() }.build())
                        .apply {
                            // Just drove past a stop: offer to go back. Otherwise, manage stops.
                            if (NavCore.passedStop != null) addAction(Action.Builder().setTitle(carContext.getString(R.string.car_go_back))
                                .setOnClickListener { NavCore.goBackToPassedStop() }.build())
                            else if (NavCore.stops.isNotEmpty()) addAction(Action.Builder().setTitle(carContext.getString(R.string.car_stops))
                                .setOnClickListener { screenManager.push(CarStopsScreen(carContext)) }.build())
                        }
                        .build()
                )
                // At most 4 buttons fit in the top strip, so Mute sits on the map's side strip.
                b.setMapActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.Builder()
                            .setIcon(icon(if (NavCore.settings.voiceOn) R.drawable.ic_voice else R.drawable.ic_voice_off))
                            .setOnClickListener {
                                NavCore.settings.voiceOn = !NavCore.settings.voiceOn
                                NavCore.applySettings()
                                CarToast.makeText(carContext,
                                    if (NavCore.settings.voiceOn) R.string.car_voice_on else R.string.car_voice_off,
                                    CarToast.LENGTH_SHORT).show()
                                invalidate()
                            }.build())
                        .build()
                )
            }
            else -> {
                if (NavCore.lastFix == null) {
                    b.setNavigationInfo(MessageInfo.Builder(carContext.getString(R.string.waiting_gps)).build())
                }
                b.setActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_search))
                            .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }.build())
                        .addAction(Action.Builder().setTitle(carContext.getString(R.string.home_plain))
                            .setOnClickListener { goSlot(SavedPlaces.Slot.HOME) }.build())
                        .addAction(Action.Builder().setTitle(carContext.getString(R.string.work_plain))
                            .setOnClickListener { goSlot(SavedPlaces.Slot.WORK) }.build())
                        .build()
                )
            }
        }
        return b.build()
    }

    private fun goSlot(slot: SavedPlaces.Slot) {
        val s = NavCore.saved.get(slot)
        val label = carContext.getString(if (slot == SavedPlaces.Slot.HOME) R.string.home_plain else R.string.work_plain)
        if (s == null) {
            CarToast.makeText(carContext, carContext.getString(R.string.car_slot_not_set, label), CarToast.LENGTH_LONG).show()
            return
        }
        screenManager.push(CarPreviewScreen(carContext, s.pt, label, s.detail))
    }

    // ------------------------------------------------------------ steps

    private fun currentStep(): Pair<Step, Distance>? {
        val nav = NavCore.navigator ?: return null
        val prog = NavCore.progress ?: return null
        val s = nav.route.announced.getOrNull(prog.nextIndex) ?: return null
        return toStep(s) to distance(prog.distanceToNext)
    }

    private var junctionCache: Pair<String, CarIcon>? = null

    /** Junction picture for the next turn once it's close enough, cached per step. */
    private fun junctionIcon(): CarIcon? {
        if (!NavCore.settings.junctionPictures) return null
        val nav = NavCore.navigator ?: return null
        val prog = NavCore.progress ?: return null
        val s = nav.route.announced.getOrNull(prog.nextIndex) ?: return null
        val j = s.junction ?: return null
        if (prog.distanceToNext > JunctionRenderer.showDistance(j, NavCore.lastFix?.speed ?: 0f)) return null
        val dark = carContext.isDarkMode
        val key = "${System.identityHashCode(nav.route)}:${s.pointIndex}:$dark"
        junctionCache?.let { if (it.first == key) return it.second }
        val bmp = runCatching { JunctionRenderer.render(nav.route, s, 720, 480, dark) }.getOrNull() ?: return null
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
        junctionCache = key to icon
        return icon
    }

    /**
     * The turn after the coming one, with how far it is after it ("Then 2.3 km · Turn
     * right onto Martin St"), so the driver knows what follows. Android Auto draws this
     * line itself (its size can't be changed by the app).
     */
    private fun nextStep(): Step? {
        val nav = NavCore.navigator ?: return null
        val prog = NavCore.progress ?: return null
        val cur = nav.route.announced.getOrNull(prog.nextIndex) ?: return null
        val nxt = nav.route.announced.getOrNull(prog.nextIndex + 1) ?: return null
        val gap = nxt.distanceFromStart - cur.distanceFromStart
        val what = nxt.instruction.trimEnd('.').ifBlank { return null }
        return toStep(nxt, carContext.getString(R.string.car_then_after, Format.distance(gap), what))
    }

    private fun toStep(s: NavStep, cue: String? = null): Step {
        val b = Step.Builder(cue ?: s.instruction.trimEnd('.').ifBlank { carContext.getString(R.string.navigating) })
            .setManeuver(Maneuver.Builder(carManeuverType(s)).setIcon(icon(turnDrawable(s)))
                .apply { s.maneuver?.exitNumber?.let { if (s.icon == TurnIcon.ROUNDABOUT) setRoundaboutExitNumber(it) } }
                .build())
        if (s.streetName.isNotBlank()) b.setRoad(s.streetName)
        return b.build()
    }

    private fun travelEstimate(): TravelEstimate? {
        val nav = NavCore.navigator ?: return null
        val remaining = NavCore.progress?.remaining ?: nav.route.length
        val secs = NavCore.secondsLeft()
        val arrival = DateTimeWithZone.create(System.currentTimeMillis() + (secs * 1000).toLong(), TimeZone.getDefault())
        return TravelEstimate.Builder(distance(remaining), arrival)
            .setRemainingTimeSeconds(secs.toLong().coerceAtLeast(0))
            .build()
    }

    private fun distance(m: Double): Distance = when {
        m < 1000 -> Distance.create(((m / 10).toInt() * 10).toDouble().coerceAtLeast(0.0), Distance.UNIT_METERS)
        m < 10_000 -> Distance.create(m / 1000, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(m / 1000, Distance.UNIT_KILOMETERS)
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    /** Our manoeuvre → Android Auto's manoeuvre type (used by car clusters / heads-up displays). */
    private fun carManeuverType(s: NavStep): Int {
        val m = s.maneuver ?: return Maneuver.TYPE_STRAIGHT
        val left = m.side == Side.LEFT
        return when (m.type) {
            ManeuverType.TURN, ManeuverType.END_OF_ROAD -> if (left) Maneuver.TYPE_TURN_NORMAL_LEFT else Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverType.BEAR -> if (left) Maneuver.TYPE_TURN_SLIGHT_LEFT else Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ManeuverType.SHARP -> if (left) Maneuver.TYPE_TURN_SHARP_LEFT else Maneuver.TYPE_TURN_SHARP_RIGHT
            ManeuverType.KEEP -> if (left) Maneuver.TYPE_FORK_LEFT else Maneuver.TYPE_FORK_RIGHT
            ManeuverType.STAY -> if (left) Maneuver.TYPE_KEEP_LEFT else Maneuver.TYPE_KEEP_RIGHT
            ManeuverType.EXIT -> if (left) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            ManeuverType.RAMP -> if (left) Maneuver.TYPE_ON_RAMP_NORMAL_LEFT else Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            ManeuverType.MERGE -> if (left) Maneuver.TYPE_MERGE_LEFT else Maneuver.TYPE_MERGE_RIGHT
            ManeuverType.MERGE_HIGHWAY -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            ManeuverType.ROUNDABOUT -> if (m.exitNumber != null) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            ManeuverType.UTURN -> Maneuver.TYPE_U_TURN_LEFT
            ManeuverType.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverType.FERRY -> Maneuver.TYPE_FERRY_BOAT
            ManeuverType.ARRIVE -> when (m.side) {
                Side.LEFT -> Maneuver.TYPE_DESTINATION_LEFT; Side.RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
                null -> Maneuver.TYPE_DESTINATION
            }
        }
    }
}

/** Turn arrow picture for a step (car screen). */
internal fun turnDrawable(s: NavStep): Int {
    val left = s.maneuver?.side == Side.LEFT
    return when (s.icon) {
        TurnIcon.STRAIGHT -> R.drawable.turn_straight
        TurnIcon.TURN -> if (left) R.drawable.turn_right_left else R.drawable.turn_right
        TurnIcon.SLIGHT -> if (left) R.drawable.turn_slight_left else R.drawable.turn_slight
        TurnIcon.SHARP -> if (left) R.drawable.turn_sharp_left else R.drawable.turn_sharp
        TurnIcon.UTURN -> R.drawable.turn_uturn
        TurnIcon.FORK -> if (left) R.drawable.turn_fork_left else R.drawable.turn_fork
        TurnIcon.ROUNDABOUT -> R.drawable.turn_roundabout
        TurnIcon.MERGE -> if (left) R.drawable.turn_merge_left else R.drawable.turn_merge
        TurnIcon.EXIT -> if (left) R.drawable.turn_exit_left else R.drawable.turn_exit
        TurnIcon.ARRIVE -> R.drawable.turn_arrive
        TurnIcon.FERRY -> R.drawable.turn_ferry
    }
}
