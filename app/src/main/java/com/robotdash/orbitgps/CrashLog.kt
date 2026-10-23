package com.robotdash.orbitgps

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few problems (crashes and caught errors) in a small file, so they can
 * be shared from About → "Send problem report" — the app has no internet reporting.
 */
object CrashLog {
    private const val MAX_BYTES = 60_000

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "problems.txt")

    /** Record uncaught crashes too, then let Android handle them as usual. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { record(app, e, "crash on ${thread.name}") }
            previous?.uncaughtException(thread, e)
        }
    }

    fun record(ctx: Context, t: Throwable, where: String) {
        runCatching {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = "=== $stamp  $where  (Orbit GPS ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL})\n$sw\n"
            val f = file(ctx)
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + entry).takeLast(MAX_BYTES))
        }
    }

    fun text(ctx: Context): String? = file(ctx).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear(ctx: Context) { file(ctx).delete() }
}
