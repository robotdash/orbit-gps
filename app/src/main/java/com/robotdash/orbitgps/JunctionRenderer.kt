package com.robotdash.orbitgps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.robotdash.orbitgps.nav.Geo
import com.robotdash.orbitgps.nav.Junction
import com.robotdash.orbitgps.nav.JunctionKind
import com.robotdash.orbitgps.nav.LaneInfo
import com.robotdash.orbitgps.nav.NavStep
import com.robotdash.orbitgps.nav.Pt
import com.robotdash.orbitgps.nav.Route
import com.robotdash.orbitgps.nav.Sign
import com.robotdash.orbitgps.voice.Side
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Garmin-style junction pictures, drawn on the phone from the route data:
 *  - highway exits and forks: driver's-eye perspective, usable lanes lit, green sign
 *  - lane turns: perspective with lane arrows
 *  - intersections: top-down from route geometry and every crossing's bearings
 *  - roundabouts: top-down circle with numbered exits
 * Sizes are in units of width/300 so the picture scales to any bitmap size.
 */
object JunctionRenderer {

    private const val CYAN = 0xFF2FD0FF.toInt()
    private const val CYAN_DARK = 0xFF0D3A8A.toInt()
    /** The route arrow on exit/fork/lane pictures (standard for all perspective pictures). */
    const val ROUTE_ARROW = 0xFFFF9100.toInt()
    private const val SIGN_GREEN = 0xFF0B6B3A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun render(route: Route, step: NavStep, w: Int, h: Int, night: Boolean): Bitmap? {
        val j = step.junction ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (j.kind) {
            JunctionKind.HIGHWAY_EXIT, JunctionKind.FORK -> Perspective(c, w, h, night).highway(j)
            JunctionKind.LANE_TURN -> Perspective(c, w, h, night).laneTurn(j)
            JunctionKind.INTERSECTION -> TopDown(c, w, h, night).intersection(route, step)
            JunctionKind.ROUNDABOUT -> TopDown(c, w, h, night).roundabout(j)
        }
        return bmp
    }

    /** How far before the junction (metres) to start showing its picture. */
    fun showDistance(j: Junction, speedMps: Double): Double = when (j.kind) {
        JunctionKind.HIGHWAY_EXIT, JunctionKind.FORK -> max(800.0, min(1500.0, speedMps * 30))
        else -> max(250.0, min(600.0, speedMps * 15))
    }

    fun showDistance(j: Junction, speedMps: Float) = showDistance(j, speedMps.toDouble())

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = style
            strokeWidth = width
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

    private fun text(color: Int, size: Float, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ===================================================================== perspective

    private class Perspective(val c: Canvas, w: Int, h: Int, val night: Boolean) {
        val W = w.toFloat()
        val H = h.toFloat()
        val u = W / 300f
        val horizon = H * 0.46f
        val cx = W / 2f
        val asphalt = if (night) 0xFF3B4048.toInt() else 0xFF6C727A.toInt()
        val asphaltFar = if (night) 0xFF4A5059.toInt() else 0xFF8D939A.toInt()
        val line = if (night) 0xFFD8DDE3.toInt() else WHITE

        /** Sky, mountains and either town buildings or trees. */
        fun scenery(urban: Boolean) {
            val sky = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, horizon,
                    if (night) 0xFF0B1533.toInt() else 0xFF5AA7E0.toInt(),
                    if (night) 0xFF2A3A66.toInt() else 0xFFCFE6F5.toInt(),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(0f, 0f, W, horizon + 2, sky)
            if (night) {
                val star = paint(0x99FFFFFF.toInt())
                for ((x, y) in listOf(0.08f to 0.12f, 0.22f to 0.3f, 0.41f to 0.1f, 0.63f to 0.22f, 0.8f to 0.08f, 0.92f to 0.27f)) {
                    c.drawCircle(W * x, horizon * y, u * 1.2f, star)
                }
            }
            val ridge = floatArrayOf(0.1f, 0.62f, 0.18f, 0.82f, 0.3f, 0.55f, 0.42f, 0.78f, 0.52f, 0.6f, 0.64f, 0.84f, 0.74f, 0.58f, 0.86f, 0.72f, 0.95f, 0.66f, 1f, 0.8f)
            val mountains = Path().apply {
                moveTo(0f, horizon * 0.9f)
                for (i in ridge.indices step 2) lineTo(W * ridge[i], horizon * ridge[i + 1])
                lineTo(W, horizon + 2)
                lineTo(0f, horizon + 2)
                close()
            }
            c.drawPath(mountains, paint(if (night) 0xFF22304F.toInt() else 0xFF7F97B0.toInt()))

            if (urban) {
                c.drawRect(0f, horizon, W, H, paint(if (night) 0xFF353B47.toInt() else 0xFFCFCABD.toInt()))
                val b1 = paint(if (night) 0xFF2A2F3A.toInt() else 0xFFB9A58C.toInt())
                val b2 = paint(if (night) 0xFF303644.toInt() else 0xFFA9B3BD.toInt())
                c.drawRect(W * 0.02f, horizon - 30 * u, W * 0.2f, horizon + 4, b1)
                c.drawRect(W * 0.21f, horizon - 20 * u, W * 0.33f, horizon + 4, b2)
                c.drawRect(W * 0.7f, horizon - 26 * u, W * 0.84f, horizon + 4, b2)
                c.drawRect(W * 0.85f, horizon - 34 * u, W, horizon + 4, b1)
                if (night) {
                    val window = paint(0xCCF6C445.toInt())
                    for (x in listOf(0.05f, 0.11f, 0.24f, 0.74f, 0.88f, 0.94f)) {
                        c.drawRect(W * x, horizon - 16 * u, W * x + 4 * u, horizon - 12 * u, window)
                    }
                }
            } else {
                c.drawRect(0f, horizon, W, H, paint(if (night) 0xFF1F3527.toInt() else 0xFF6F9A5F.toInt()))
                val tree = paint(if (night) 0xFF14261B.toInt() else 0xFF2F5A36.toInt())
                for ((fx, s) in listOf(0.05f to 1f, 0.11f to 0.8f, 0.17f to 0.6f, 0.86f to 1f, 0.93f to 0.85f)) {
                    val x = W * fx
                    val th = 24 * u * s
                    val p = Path().apply {
                        moveTo(x, horizon + 6 * u - th)
                        lineTo(x + th * 0.36f, horizon + 6 * u)
                        lineTo(x - th * 0.36f, horizon + 6 * u)
                        close()
                    }
                    c.drawPath(p, tree)
                }
            }
        }

        fun roadGradient(top: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, H, asphaltFar, asphalt, Shader.TileMode.CLAMP)
        }

        /** A lane edge from the bottom of the picture up to the horizon. */
        class Edge(val pts: List<PointF>)

        fun straightEdge(xBottom: Float, vx: Float, topY: Float, k: Float) = Edge((0..24).map { i ->
            val t = i / 24f
            PointF(xBottom + ((xBottom - cx) * k + vx - xBottom) * t, H + (topY - H) * t)
        })

        fun curvedEdge(xBottom: Float, xFar: Float, topY: Float, dir: Int): Edge {
            val p0 = PointF(xBottom, H)
            val p1 = PointF(xBottom, H - (H - topY) * 0.42f)
            val p2 = PointF(xFar - dir * W * 0.13f, topY + (H - topY) * 0.24f)
            val p3 = PointF(xFar, topY)
            return Edge((0..32).map { i ->
                val t = i / 32f
                val m = 1 - t
                val a = m * m * m; val b = 3 * m * m * t; val cc = 3 * m * t * t; val d = t * t * t
                PointF(a * p0.x + b * p1.x + cc * p2.x + d * p3.x, a * p0.y + b * p1.y + cc * p2.y + d * p3.y)
            })
        }

        fun lanePoly(a: Edge, b: Edge) = Path().apply {
            moveTo(a.pts[0].x, a.pts[0].y)
            for (p in a.pts) lineTo(p.x, p.y)
            for (p in b.pts.asReversed()) lineTo(p.x, p.y)
            close()
        }

        fun edgePath(e: Edge, upTo: Float = 1f) = Path().apply {
            val last = ((e.pts.size - 1) * upTo).toInt()
            moveTo(e.pts[0].x, e.pts[0].y)
            for (i in 1..last) lineTo(e.pts[i].x, e.pts[i].y)
        }

        /** x of lane edge [i] (0..n) at the bottom of the picture. */
        fun bottomX(i: Int, n: Int): Float {
            val span = W * min(0.96f, 0.5f + 0.12f * n)
            return cx - span / 2 + span * i / n
        }

        /** Highway exit (lanes peel off) or fork (road splits in two). */
        fun highway(j: Junction) {
            scenery(urban = false)
            val fork = j.kind == JunctionKind.FORK
            val dir = if (j.side == Side.LEFT) -1 else 1
            val hasLanes = j.lanes != null
            val slight = if (dir > 0) "slight right" else "slight left"
            // No lane data: one road lane plus one exit lane, no arrows.
            val lanes = j.lanes ?: if (dir > 0) listOf(LaneInfo(listOf("straight"), false), LaneInfo(listOf(slight), true))
            else listOf(LaneInfo(listOf(slight), true), LaneInfo(listOf("straight"), false))
            val n = lanes.size
            val nBranch = if (n <= 1) 1 else branchLanes(lanes, dir, fork).coerceIn(1, n - 1)
            val nMain = n - nBranch
            val topY = horizon + 3 * u
            val branchLaneIdx = if (dir > 0) nMain until n else 0 until nBranch
            val split = if (dir > 0) nMain else nBranch
            val mainFar = if (fork) cx - dir * W * 0.2f else cx - dir * W * 0.04f
            val branchFar = if (fork) cx + dir * W * 0.26f else cx + dir * W * 0.42f

            val mainRange = if (dir > 0) 0..nMain else nBranch..n
            val branchRange = if (dir > 0) nMain..n else 0..nBranch
            val main = HashMap<Int, Edge>()
            val branch = HashMap<Int, Edge>()
            for (i in mainRange) {
                main[i] = if (fork) curvedEdge(bottomX(i, n), mainFar + (bottomX(i, n) - cx) * 0.05f, topY, -dir)
                else straightEdge(bottomX(i, n), mainFar, topY, 0.05f)
            }
            for (i in branchRange) {
                branch[i] = curvedEdge(bottomX(i, n), branchFar + (bottomX(i, n) - bottomX(split, n)) * 0.07f, topY + 2 * u, dir)
            }

            val road = roadGradient(horizon)
            c.drawPath(lanePoly(main[mainRange.first]!!, main[mainRange.last]!!), road)
            c.drawPath(lanePoly(branch[branchRange.first]!!, branch[branchRange.last]!!), road)

            // Grass in the gore between the two roads.
            c.drawPath(lanePoly(main[split]!!, branch[split]!!), paint(if (night) 0xFF5E6B57.toInt() else 0xFF86A86F.toInt()))

            // Light up the lanes our route can use.
            val lit = paint(0x552FD0FF)
            for (i in 0 until n) {
                if (!lanes[i].valid) continue
                val (a, b) = if (i in branchLaneIdx) branch[i]!! to branch[i + 1]!! else main[i]!! to main[i + 1]!!
                c.drawPath(lanePoly(a, b), lit)
            }

            val solid = paint(line, Paint.Style.STROKE, u * 2.6f)
            val dashed = paint(line, Paint.Style.STROKE, u * 2.2f).apply {
                pathEffect = DashPathEffect(floatArrayOf(14 * u, 12 * u), 0f)
            }
            for (e in listOf(main[mainRange.first]!!, main[mainRange.last]!!, branch[branchRange.first]!!, branch[branchRange.last]!!)) {
                c.drawPath(edgePath(e), solid)
            }
            for (i in mainRange.first + 1 until mainRange.last) c.drawPath(edgePath(main[i]!!), dashed)
            for (i in branchRange.first + 1 until branchRange.last) c.drawPath(edgePath(branch[i]!!), dashed)

            // Yellow chevrons across the gore.
            val chevron = paint(if (night) 0xFFC9B24A.toInt() else 0xFFE8C84A.toInt(), Paint.Style.STROKE, u * 2.2f)
            val e3 = main[split]!!
            val e4 = branch[split]!!
            for (f in listOf(0.38f, 0.5f, 0.62f)) {
                val k = (f * (e3.pts.size - 1)).toInt()
                val p = e3.pts[k]
                val q = e4.pts[min(k, e4.pts.size - 1)]
                if (abs(p.x - q.x) > 6 * u) c.drawLine(p.x, p.y + 6 * u, q.x, q.y - 4 * u, chevron)
            }

            // Route arrow up the lane(s) to take, like the top-down pictures.
            val take = branchLaneIdx.filter { lanes[it].valid }.ifEmpty { branchLaneIdx.toList() }
            routeArrow(branch[take.first()]!!, branch[take.last() + 1]!!, from = if (hasLanes) 0.42f else 0f)

            if (hasLanes) {
                for (i in 0 until n) {
                    laneArrow((bottomX(i, n) + bottomX(i + 1, n)) / 2, H - 10 * u, lanes[i], bottomX(1, n) - bottomX(0, n))
                }
            }
            j.sign?.let { drawSign(it, dir) }
        }

        /**
         * Cyan arrow running up between lane edges [a] and [b]: shaft over the middle
         * third, then a head; perspective comes for free from the edges converging.
         * [from] (0..1 up the edges): 0 starts flush with the bottom of the picture;
         * more leaves room for lane arrows painted there.
         */
        fun routeArrow(a: Edge, b: Edge, from: Float = 0f) {
            // A slim arrow down the middle of the lane(s): a narrow shaft that thins with
            // distance and a pointed head, outlined so it never reads as another road.
            val n = min(a.pts.size, b.pts.size) - 1
            val start = (n * from).toInt()
            val tip = (n * 0.78f).toInt()
            val mid = (start..tip).map { k ->
                val p = a.pts[k]; val q = b.pts[k]
                PointF((p.x + q.x) / 2, (p.y + q.y) / 2)
            }
            if (mid.size < 4) return
            val headLen = (mid.size * 0.28f).toInt().coerceAtLeast(2)
            val baseIdx = mid.size - 1 - headLen
            // Half-widths: shaft 2.6u near the driver, 1.4u at the head; head 2.8× the shaft.
            fun half(i: Int) = u * (2.6f - 1.2f * i / mid.lastIndex)
            fun normal(i: Int): PointF {
                val p = mid[(i - 1).coerceAtLeast(0)]; val q = mid[(i + 1).coerceAtMost(mid.lastIndex)]
                val dx = q.x - p.x; val dy = q.y - p.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                return PointF(-dy / len, dx / len)
            }
            fun side(i: Int, w: Float, sign: Int): PointF {
                val nn = normal(i)
                return PointF(mid[i].x + nn.x * w * sign, mid[i].y + nn.y * w * sign)
            }
            val path = Path()
            side(0, half(0), 1).let { path.moveTo(it.x, it.y) }
            for (i in 0..baseIdx) side(i, half(i), 1).let { path.lineTo(it.x, it.y) }
            val hw = half(baseIdx) * 2.8f
            side(baseIdx, hw, 1).let { path.lineTo(it.x, it.y) }
            mid.last().let { path.lineTo(it.x, it.y) }
            side(baseIdx, hw, -1).let { path.lineTo(it.x, it.y) }
            for (i in baseIdx downTo 0) side(i, half(i), -1).let { path.lineTo(it.x, it.y) }
            path.close()
            // Orange stands out against every lane, grass and road colour, day and night.
            c.drawPath(path, paint(0xFF2A1300.toInt(), Paint.Style.STROKE, 1.6f * u))
            c.drawPath(path, paint(ROUTE_ARROW))
        }

        /** How many lanes (counted from the branch side) go the branch way. */
        fun branchLanes(lanes: List<LaneInfo>, dir: Int, fork: Boolean): Int {
            val word = if (dir > 0) "right" else "left"
            val goes = lanes.map { l ->
                if (fork) l.valid
                else l.indications.any { it.contains(word) } || (l.valid && l.indications.isEmpty())
            }
            val fromSide = if (dir > 0) goes.asReversed() else goes
            val n = fromSide.takeWhile { it }.size
            if (n != 0) return n
            return lanes.count { it.valid }.coerceAtLeast(1)
        }

        /** Green overhead sign: route shield, destinations, exit tab and arrow. */
        fun drawSign(s: Sign, dir: Int) {
            val nameP = text(WHITE, 12 * u)
            val textW = (s.names.map { nameP.measureText(it) } + listOf(60 * u)).max()
            val signW = (textW + 46 * u).coerceIn(112 * u, 170 * u)
            val signH = max(40f, ((if (s.refs.isNotEmpty()) 28 else 6) + s.names.size * 15 + 14).toFloat()) * u
            val left = (if (dir > 0) W - signW - 14 * u else 14 * u).coerceIn(6 * u, W - signW - 6 * u)
            val top = 16 * u
            val right = left + signW
            val post = paint(if (night) 0xFF6B7480.toInt() else 0xFF9AA3AD.toInt())
            c.drawRect(left + 10 * u, top + signH, left + 13 * u, horizon + 6 * u, post)
            c.drawRect(right - 13 * u, top + signH, right - 10 * u, horizon + 6 * u, post)
            val r = RectF(left, top, right, top + signH)
            c.drawRoundRect(r, 6 * u, 6 * u, paint(SIGN_GREEN))
            c.drawRoundRect(r, 6 * u, 6 * u, paint(WHITE, Paint.Style.STROKE, u * 2))

            s.exit?.let { exit ->
                val tp = text(WHITE, 10 * u)
                val str = "EXIT $exit"
                val ex = right - (tp.measureText(str) + 12 * u)
                val r2 = RectF(ex - 4 * u, top - 13 * u, right - 4 * u, top + 3 * u)
                c.drawRoundRect(r2, 3 * u, 3 * u, paint(SIGN_GREEN))
                c.drawRoundRect(r2, 3 * u, 3 * u, paint(WHITE, Paint.Style.STROKE, u * 1.6f))
                c.drawText(str, r2.left + 6 * u, r2.bottom - 4 * u, tp)
            }

            var y = top + 8 * u
            val x = left + (if (dir < 0) 26 else 8) * u
            val ref = s.refs.firstOrNull()
            if (ref != null) {
                val num = Regex("""\d+[A-Z]?""").find(ref)?.value ?: ref.take(3)
                val rest = Regex("""^[A-Z]{1,4}\s""").replace(ref.replace(num, ""), "").trim()
                val shield = RectF(x, y, x + 22 * u, y + 22 * u)
                c.drawRoundRect(shield, 4 * u, 4 * u, paint(WHITE))
                val np = text(SIGN_GREEN, (if (num.length > 2) 10 else 13) * u)
                c.drawText(num, shield.centerX() - np.measureText(num) / 2, shield.centerY() + np.textSize * 0.36f, np)
                if (rest.isNotEmpty()) {
                    c.drawText(fit(rest, 11 * u, signW - 40 * u), x + 28 * u, y + 15 * u, text(WHITE, 11 * u))
                }
                y += 28 * u
            } else {
                y += 4 * u
            }
            for (name in s.names) {
                c.drawText(fit(name, nameP.textSize, signW - 34 * u), x, y + 10 * u, nameP)
                y += 15 * u
            }

            // Arrow pointing up and toward the exit side.
            val ap = paint(WHITE, Paint.Style.STROKE, u * 2.4f)
            val ax = if (dir > 0) right - 14 * u else left + 14 * u
            val ay = top + 22 * u
            c.drawLine(ax - dir * 8 * u, ay + 2 * u, ax + dir * 2 * u, ay - 8 * u, ap)
            c.drawLine(ax + dir * 2 * u, ay - 8 * u, ax - dir * 4 * u, ay - 8 * u, ap)
            c.drawLine(ax + dir * 2 * u, ay - 8 * u, ax + dir * 2 * u, ay - 2 * u, ap)
        }

        fun fit(s: String, size: Float, maxW: Float): String {
            val p = text(WHITE, size)
            if (p.measureText(s) <= maxW) return s
            var t = s
            while (t.length > 3 && p.measureText("$t…") > maxW) t = t.dropLast(1)
            return "$t…"
        }

        /** Town street with lanes approaching a cross street, each lane with its arrows. */
        fun laneTurn(j: Junction) {
            scenery(urban = true)
            val lanes = j.lanes ?: return
            val n = lanes.size
            val farY = horizon + (H - horizon) * 0.2f
            c.drawRect(0f, horizon + 3 * u, W, farY, paint(asphaltFar))
            val centre = paint(0xFFE8C84A.toInt(), Paint.Style.STROKE, u * 1.6f).apply {
                pathEffect = DashPathEffect(floatArrayOf(10 * u, 8 * u), 0f)
            }
            c.drawLine(0f, (horizon + farY) / 2, W, (horizon + farY) / 2, centre)

            val edges = (0..n).map { straightEdge(bottomX(it, n), cx, farY, 0.36f) }
            c.drawPath(lanePoly(edges[0], edges[n]), roadGradient(farY))
            val lit = paint(0x552FD0FF)
            for (i in 0 until n) if (lanes[i].valid) c.drawPath(lanePoly(edges[i], edges[i + 1]), lit)

            val solid = paint(line, Paint.Style.STROKE, u * 2.6f)
            val dashed = paint(line, Paint.Style.STROKE, u * 2.1f).apply {
                pathEffect = DashPathEffect(floatArrayOf(12 * u, 10 * u), 0f)
            }
            c.drawPath(edgePath(edges[0]), solid)
            c.drawPath(edgePath(edges[n]), solid)
            // Solid between lanes that go different ways, dashed between ones that match.
            for (i in 1 until n) {
                val same = lanes[i - 1].indications.toSet() == lanes[i].indications.toSet()
                c.drawPath(edgePath(edges[i]), if (same) dashed else solid)
            }
            c.drawLine(edges[0].pts.last().x, farY + 2 * u, edges[n].pts.last().x, farY + 2 * u, paint(line, Paint.Style.STROKE, u * 4))
            for (i in 0 until n) {
                laneArrow((bottomX(i, n) + bottomX(i + 1, n)) / 2, H - 10 * u, lanes[i], bottomX(1, n) - bottomX(0, n))
            }

            if (j.street.isBlank()) return
            val tp = text(WHITE, 12 * u)
            val arrow = when (j.side) { Side.RIGHT -> " →"; Side.LEFT -> "← "; else -> "" }
            val name = fit(j.street, tp.textSize, W * 0.6f)
            val label = if (j.side == Side.LEFT) arrow + name else name + arrow
            val lw = tp.measureText(label) + 20 * u
            val r = RectF(cx - lw / 2, horizon - 26 * u, cx + lw / 2, horizon - 6 * u)
            c.drawRoundRect(r, 4 * u, 4 * u, paint(SIGN_GREEN))
            c.drawRoundRect(r, 4 * u, 4 * u, paint(WHITE, Paint.Style.STROKE, u * 1.4f))
            c.drawText(label, r.left + 10 * u, r.bottom - 6 * u, tp)
        }

        /** Arrow(s) painted on a lane; dimmed if our route can't use the lane. */
        fun laneArrow(x: Float, baseY: Float, lane: LaneInfo, laneWidth: Float) {
            val s = min(0.42f * laneWidth, 34 * u)
            val col = if (lane.valid) WHITE else 0x88FFFFFF.toInt()
            val sw = s * 0.2f
            val stroke = paint(col, Paint.Style.STROKE, sw)
            val fill = paint(col)
            val inds = lane.indications.ifEmpty { listOf("straight") }
            val top = baseY - 0.9f * s
            c.drawLine(x, baseY, x, top, stroke)
            for (ind in inds) {
                val deg = when (ind) {
                    "slight right" -> 40f
                    "right" -> 90f
                    "sharp right" -> 130f
                    "slight left" -> -40f
                    "left" -> -90f
                    "sharp left" -> -130f
                    "uturn" -> -180f
                    else -> 0f
                }
                when {
                    deg == 0f -> {
                        arrowHead(x, top - sw, 0f, s, fill)
                        c.drawLine(x, top, x, top - 0.05f * s, stroke)
                    }
                    ind == "uturn" -> {
                        val p = Path().apply {
                            moveTo(x, top)
                            quadTo(x, top - 0.5f * s, x - 0.35f * s, top - 0.5f * s)
                            quadTo(x - 0.7f * s, top - 0.5f * s, x - 0.7f * s, top + 0.1f * s)
                        }
                        c.drawPath(p, stroke)
                        arrowHead(x - 0.7f * s, top + 0.3f * s, 180f, s, fill)
                    }
                    else -> {
                        val r = Math.toRadians(deg.toDouble())
                        val ex = x + (sin(r) * s * 0.55).toFloat()
                        val ey = top - (cos(r) * s * 0.55).toFloat() * 0.8f - s * 0.12f
                        val p = Path().apply {
                            moveTo(x, top + 0.15f * s)
                            quadTo(x, ey, ex, ey)
                        }
                        c.drawPath(p, stroke)
                        arrowHead(ex + (sin(r) * s * 0.12).toFloat(), ey - (cos(r) * s * 0.12).toFloat(), deg, s, fill)
                    }
                }
            }
        }

        fun arrowHead(x: Float, y: Float, deg: Float, s: Float, p: Paint) {
            c.save()
            c.rotate(deg, x, y)
            val path = Path().apply {
                moveTo(x, y - 0.28f * s)
                lineTo(x + 0.26f * s, y + 0.06f * s)
                lineTo(x - 0.26f * s, y + 0.06f * s)
                close()
            }
            c.drawPath(path, p)
            c.restore()
        }
    }

    // ===================================================================== top-down

    private class TopDown(val c: Canvas, w: Int, h: Int, val night: Boolean) {
        val W = w.toFloat()
        val H = h.toFloat()
        val u = W / 300f
        val bg = if (night) 0xFF141C3D.toInt() else 0xFF1B2550.toInt()
        val casing = 0xFF3A4680.toInt()
        val road = 0xFF59649C.toInt()

        init { c.drawColor(bg) }

        /** The route through the junction, heading up, with every side road at each crossing. */
        fun intersection(route: Route, step: NavStep) {
            val pts = route.points
            val cum = route.cumulative
            val i0 = step.pointIndex.coerceIn(0, pts.size - 1)
            val here = pts[i0]
            val d0 = cum[i0]
            val ann = route.announced
            val next = ann.getOrNull(ann.indexOf(step) + 1)
            val gap = next?.let { it.distanceFromStart - step.distanceFromStart }
            // Show the next turn too if it's close.
            val ahead = if (gap == null || gap >= 150) 80.0 else gap + 45
            val back = indexBefore(cum, d0 - 15).coerceAtMost(i0 - 1)
            val heading = when {
                back >= 0 -> Geo.bearing(pts[back], here)
                i0 + 1 < pts.size -> Geo.bearing(here, pts[i0 + 1])
                else -> 0.0
            }
            val radiusM = max(75.0, 0.75 * ahead + 20)
            val scale = (min(W, H) / 2f / radiusM.toFloat()) * 0.92f
            val centre = PointF(W / 2f, H * 0.6f)

            fun proj(p: Pt): PointF {
                val dx = Math.toRadians(p.lon - here.lon) * cos(Math.toRadians(here.lat)) * 6_371_000.0
                val dy = Math.toRadians(p.lat - here.lat) * 6_371_000.0
                val h = Math.toRadians(heading)
                return PointF(
                    centre.x + ((cos(h) * dx - sin(h) * dy) * scale).toFloat(),
                    centre.y - ((sin(h) * dx + cos(h) * dy) * scale).toFloat(),
                )
            }

            val from = indexBefore(cum, d0 - 70).coerceAtLeast(0)
            val to = indexAt(cum, d0 + ahead)
            val line = (from..to).map { proj(pts[it]) }
            val roadW = max(scale.coerceAtLeast(0.5f) * 11f, 12 * u)
            val casingP = paint(casing, Paint.Style.STROKE, roadW + 6 * u)
            val roadP = paint(road, Paint.Style.STROKE, roadW)

            val spokes = ArrayList<Pair<PointF, PointF>>()
            for (x in route.crossings) {
                if (x.pointIndex < from - 2 || x.pointIndex > to + 2) continue
                val p = proj(pts[x.pointIndex])
                x.bearings.forEachIndexed { k, b ->
                    if (k == x.inIdx || k == x.outIdx) return@forEachIndexed
                    val r = Math.toRadians(b - heading)
                    val len = (radiusM * 1.2).toFloat() * scale
                    spokes += p to PointF(p.x + (sin(r) * len).toFloat(), p.y - (cos(r) * len).toFloat())
                }
            }
            fun linePath() = Path().apply { line.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            val path = linePath()
            for ((a, b) in spokes) c.drawLine(a.x, a.y, b.x, b.y, casingP)
            c.drawPath(path, casingP)
            for ((a, b) in spokes) c.drawLine(a.x, a.y, b.x, b.y, roadP)
            c.drawPath(path, roadP)
            val roadFill = paint(road)
            for (x in route.crossings) {
                if (x.pointIndex !in from..to) continue
                val p = proj(pts[x.pointIndex])
                c.drawCircle(p.x, p.y, roadW / 2, roadFill)
            }

            val routeW = 0.7f * roadW
            val rp = linePath()
            c.drawPath(rp, paint(CYAN_DARK, Paint.Style.STROKE, routeW + 4 * u))
            c.drawPath(rp, paint(CYAN, Paint.Style.STROKE, routeW))
            if (line.size >= 2) {
                val tip = line.last()
                val b = line[max(0, line.size - 4)]
                val deg = Math.toDegrees(atan2((tip.x - b.x).toDouble(), (b.y - tip.y).toDouble())).toFloat()
                c.save()
                c.rotate(deg, tip.x, tip.y)
                val a = roadW * 1.4f
                val head = Path().apply {
                    moveTo(tip.x, tip.y - a)
                    lineTo(tip.x + 0.8f * a, tip.y + 0.2f * a)
                    lineTo(tip.x - 0.8f * a, tip.y + 0.2f * a)
                    close()
                }
                c.drawPath(head, paint(CYAN))
                c.restore()
            }
            if (step.streetName.isNotBlank() && line.size > 4) {
                val p = line[(line.size * 0.8).toInt().coerceAtMost(line.size - 1)]
                label(step.streetName, p.x, p.y - roadW)
            }
            val start = line.first()
            c.drawCircle(start.x, min(start.y, H - 10 * u), 5 * u, paint(WHITE))
        }

        /**
         * Road-name tag centred near (x, y), kept inside the picture. If it would cover
         * [avoid] (an exit-number badge), it moves beside the badge, or below it.
         */
        fun label(s: String, x: Float, y: Float, avoid: PointF? = null) {
            val tp = text(WHITE, 12 * u)
            var t = s
            while (t.length > 4 && tp.measureText(t) > W * 0.55f) t = t.dropLast(1)
            if (t != s) t += "…"
            val tw = tp.measureText(t)
            var lx = (x - tw / 2).coerceIn(6 * u, W - tw - 6 * u)
            var ly = y.coerceIn(18 * u, H - 8 * u)
            fun rect() = RectF(lx - 6 * u, ly - 13 * u, lx + tw + 6 * u, ly + 4 * u)
            if (avoid != null) {
                val clear = 13 * u
                fun hits() = RectF(avoid.x - clear, avoid.y - clear, avoid.x + clear, avoid.y + clear).let { RectF.intersects(it, rect()) }
                if (hits()) {
                    // Beside the badge, on whichever side has room; same height as the badge.
                    ly = (avoid.y + 4.5f * u).coerceIn(18 * u, H - 8 * u)
                    val right = avoid.x + clear + 6 * u
                    val left = avoid.x - clear - 6 * u - tw
                    lx = if (right + tw + 6 * u <= W) right else left
                    if (lx < 6 * u || hits()) {
                        lx = (avoid.x - tw / 2).coerceIn(6 * u, W - tw - 6 * u)
                        ly = (avoid.y + clear + 15 * u).coerceIn(18 * u, H - 8 * u)
                    }
                }
            }
            val r = rect()
            c.drawRoundRect(r, 5 * u, 5 * u, paint(0xCC0A1230.toInt()))
            c.drawText(t, lx, ly, tp)
        }

        /** Last index whose distance is ≤ [d], or -1 if [d] is before the start. */
        fun indexBefore(cum: DoubleArray, d: Double): Int {
            if (d < 0) return -1
            var lo = 0
            var hi = cum.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (cum[mid] <= d) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** First index whose distance is ≥ [d]. */
        fun indexAt(cum: DoubleArray, d: Double): Int {
            if (d <= 0) return 0
            var lo = 0
            var hi = cum.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (cum[mid] < d) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun roundabout(j: Junction) {
            val ri = j.roundabout ?: return
            val cx = W / 2f
            val cy = 0.52f * H
            val r = min(W, H) * 0.22f
            val roadW = 18 * u
            val far = max(W, H)
            fun at(deg: Double, rad: Float) = PointF(
                cx + (sin(Math.toRadians(deg)) * rad).toFloat(),
                cy - (cos(Math.toRadians(deg)) * rad).toFloat(),
            )
            val casingP = paint(casing, Paint.Style.STROKE, roadW + 6 * u)
            val roadP = paint(road, Paint.Style.STROKE, roadW)
            val angles = listOf(180.0) + ri.exits.map { it.angle }
            for (a in angles) { val p = at(a, r); val q = at(a, far); c.drawLine(p.x, p.y, q.x, q.y, casingP) }
            c.drawCircle(cx, cy, r, paint(casing, Paint.Style.STROKE, roadW + 10 * u))
            for (a in angles) { val p = at(a, r); val q = at(a, far); c.drawLine(p.x, p.y, q.x, q.y, roadP) }
            c.drawCircle(cx, cy, r, paint(road, Paint.Style.STROKE, roadW + 4 * u))
            c.drawCircle(cx, cy, r - roadW / 2 - 2 * u, paint(if (night) 0xFF1E3D2A.toInt() else 0xFF2C5A3A.toInt()))

            // Our path: in from the bottom, round the circle the right way, out our exit.
            val take = ri.exits[ri.takeIndex].angle
            var end = take
            if (ri.leftHand) { while (end <= 180) end += 360 } else { while (end >= 180) end -= 360 }
            val path = Path()
            val s0 = at(180.0, far)
            path.moveTo(s0.x, s0.y)
            for (k in 0..40) { val p = at(180 + (end - 180) * k / 40, r); path.lineTo(p.x, p.y) }
            val out = at(take, far * 0.9f)
            path.lineTo(out.x, out.y)
            c.drawPath(path, paint(CYAN_DARK, Paint.Style.STROKE, 14 * u))
            c.drawPath(path, paint(CYAN, Paint.Style.STROKE, 9 * u))

            // Number the exits. If our count disagrees with the router's, only number ours.
            val before = ri.exits.take(ri.takeIndex).count { it.counted }
            val agree = ri.exitNumber == null || before == ri.exitNumber - 1
            var num = 0
            ri.exits.forEachIndexed { i, e ->
                if (!e.counted || !(agree || i == ri.takeIndex)) return@forEachIndexed
                num++
                val ours = i == ri.takeIndex
                val shown = if (ours && ri.exitNumber != null) ri.exitNumber else num
                val p = at(e.angle, r + 36 * u)
                c.drawCircle(p.x, p.y, (if (ours) 11 else 10) * u, paint(if (ours) CYAN else 0x44FFFFFF))
                val tp = text(if (ours) 0xFF04122B.toInt() else WHITE, 12 * u)
                val str = shown.toString()
                c.drawText(str, p.x - tp.measureText(str) / 2, p.y + u * 4.3f, tp)
            }
            if (j.street.isNotBlank()) {
                val p = at(take, r + 70 * u)
                label(j.street, p.x, p.y, avoid = at(take, r + 36 * u))
            }
        }
    }
}
