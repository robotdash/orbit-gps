package com.robotdash.orbitgps

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Car-friendly menus: a dark panel of large tiles that are easy to read and hit at a
 * glance (replaces Android's small list dialogs for every choice the driver makes).
 */
object BigMenu {

    data class Choice(
        val label: String,
        val icon: String = "",
        /** Smaller second line under the label. */
        val detail: String = "",
        /** Shown with a highlighted edge and a check (current setting). */
        val selected: Boolean = false,
        /** Red tile (stop, remove). */
        val danger: Boolean = false,
    )

    private const val PANEL = 0xFF0C1430.toInt()
    private const val TILE = 0xFF1B2A4A.toInt()
    private const val TILE_DANGER = 0xFF7A1F27.toInt()
    private const val ACCENT = 0xFF2FD0FF.toInt()
    private const val SUBTEXT = 0xFFB8C4D6.toInt()

    /**
     * Show [choices] under [title]. [onPick] gets the index of the tile tapped.
     * A "Cancel" tile is added at the bottom unless [cancelLabel] is null.
     */
    fun show(
        a: Activity,
        title: String,
        choices: List<Choice>,
        message: String? = null,
        cancelLabel: String? = a.getString(android.R.string.cancel),
        onCancel: () -> Unit = {},
        onPick: (Int) -> Unit,
    ): AlertDialog {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(14))
        }
        col.addView(TextView(a).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(if (message == null) 14 else 6))
        })
        if (message != null) col.addView(TextView(a).apply {
            text = message
            setTextColor(SUBTEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(4), 0, dp(4), dp(14))
        })

        var dialog: AlertDialog? = null
        var picked = false
        fun tile(c: Choice, outline: Boolean, onTap: () -> Unit) = TextView(a).apply {
            text = tileText(c)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setLineSpacing(dp(3).toFloat(), 1f)
            gravity = Gravity.CENTER_VERTICAL or (if (outline) Gravity.CENTER_HORIZONTAL else Gravity.START)
            minHeight = dp(76)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            val shape = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                if (outline) { setColor(Color.TRANSPARENT); setStroke(dp(2), SUBTEXT) }
                else setColor(if (c.danger) TILE_DANGER else TILE)
                if (c.selected) setStroke(dp(3), ACCENT)
            }
            background = RippleDrawable(ColorStateList.valueOf(0x552FD0FF), shape, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { picked = true; dialog?.dismiss(); onTap() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        choices.forEachIndexed { i, c -> col.addView(tile(c, false) { onPick(i) }.apply { tag = TAG + i }) }
        if (cancelLabel != null) col.addView(tile(Choice(cancelLabel), true) { onCancel() })

        dialog = AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(col) })
            .setOnCancelListener { if (!picked) onCancel() }
            .create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(PANEL); cornerRadius = dp(26).toFloat()
        })
        dialog.show()
        // Nearly full width, so the tiles are as big as the phone allows.
        dialog.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    private const val TAG = "bigmenu-tile-"

    private fun tileText(c: Choice): CharSequence {
        val label = buildString {
            if (c.icon.isNotEmpty()) append(c.icon).append("   ")
            append(c.label)
            if (c.selected) append("   ✓")
        }
        return android.text.SpannableStringBuilder(label).apply {
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            if (c.detail.isNotEmpty()) {
                val from = length
                append("\n").append(c.detail)
                setSpan(android.text.style.RelativeSizeSpan(0.76f), from, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(SUBTEXT), from, length, 0)
            }
        }
    }

    /** Change tile [index] of a menu that's showing (e.g. live download progress). */
    fun update(d: AlertDialog, index: Int, c: Choice) {
        if (!d.isShowing) return
        (d.window?.decorView?.findViewWithTag<TextView>(TAG + index))?.text = tileText(c)
    }

    data class Check(val label: String, val detail: String = "", val checked: Boolean = false, val icon: String = "")

    /**
     * Big tick-box tiles plus one action button, e.g. pick what to download then "Download 1.1 GB".
     * [actionLabel] is asked again every time a box changes; null greys the button out.
     */
    fun checklist(
        a: Activity,
        title: String,
        items: List<Check>,
        message: String? = null,
        actionLabel: (BooleanArray) -> String?,
        /** Optional text box shown above the tick boxes (e.g. a note). */
        input: android.widget.EditText? = null,
        onAction: (BooleanArray) -> Unit,
    ): AlertDialog {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val state = BooleanArray(items.size) { items[it].checked }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(14)) }
        col.addView(TextView(a).apply {
            text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), 0, dp(4), dp(if (message == null) 14 else 6))
        })
        if (message != null) col.addView(TextView(a).apply {
            text = message; setTextColor(SUBTEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPadding(dp(4), 0, dp(4), dp(14))
        })
        var dialog: AlertDialog? = null
        lateinit var action: TextView
        fun box(i: Int) = TextView(a).apply {
            fun paint() {
                val c = items[i]
                text = android.text.SpannableStringBuilder((if (state[i]) "☑   " else "☐   ") + (if (c.icon.isNotEmpty()) c.icon + "  " else "") + c.label).apply {
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                    setSpan(android.text.style.RelativeSizeSpan(1.35f), 0, 1, 0) // bigger tick box
                    if (state[i]) setSpan(android.text.style.ForegroundColorSpan(ACCENT), 0, 1, 0)
                    if (c.detail.isNotEmpty()) {
                        val from = length
                        append("\n").append(c.detail)
                        setSpan(android.text.style.RelativeSizeSpan(0.76f), from, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(SUBTEXT), from, length, 0)
                    }
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat(); setColor(TILE)
                    if (state[i]) setStroke(dp(3), ACCENT)
                }
                contentDescription = c.label + if (state[i]) ", ticked" else ", not ticked"
            }
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setLineSpacing(dp(3).toFloat(), 1f)
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(76); setPadding(dp(20), dp(14), dp(20), dp(14))
            isClickable = true; isFocusable = true
            paint()
            setOnClickListener { state[i] = !state[i]; paint(); refresh(action, actionLabel(state), d) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        if (input != null) {
            input.setTextColor(Color.WHITE); input.setHintTextColor(SUBTEXT)
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            input.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF0A1128.toInt()); setStroke(dp(2), SUBTEXT) }
            input.setPadding(dp(16), dp(14), dp(16), dp(14))
            input.minHeight = dp(76)
            col.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) })
        }
        items.indices.forEach { col.addView(box(it)) }
        action = TextView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; minHeight = dp(76); setPadding(dp(20), dp(14), dp(20), dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener { if (actionLabel(state) != null) { dialog?.dismiss(); onAction(state.copyOf()) } }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6); bottomMargin = dp(10) }
        }
        refresh(action, actionLabel(state), d)
        col.addView(action)
        col.addView(TextView(a).apply {
            text = a.getString(android.R.string.cancel); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; minHeight = dp(76)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(2), SUBTEXT) }
            isClickable = true; setOnClickListener { dialog?.dismiss() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        dialog = AlertDialog.Builder(a).setView(ScrollView(a).apply { addView(col) }).create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(PANEL); cornerRadius = dp(26).toFloat() })
        dialog.show()
        dialog.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    private const val ACTION = 0xFF0A84FF.toInt()

    private fun refresh(v: TextView, label: String?, density: Float) {
        v.text = label ?: v.context.getString(R.string.dl_pick_something)
        v.setTextColor(if (label != null) Color.WHITE else SUBTEXT)
        v.alpha = if (label != null) 1f else 0.6f
        v.background = GradientDrawable().apply {
            cornerRadius = 18 * density; setColor(if (label != null) ACTION else TILE)
        }
    }
}
