package com.personal.heyclaude

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small view factory for the dark assistant look shared by the app's screens. */
object Ui {
    const val BG = 0xFF0B0C14.toInt()
    const val CARD = 0xFF161827.toInt()
    const val FIELD = 0xFF20233A.toInt()
    const val TEXT = 0xFFF2F3FA.toInt()
    const val MUTED = 0xFF9AA0B8.toInt()
    const val OK = 0xFF3DDC97.toInt()
    const val WARN = 0xFFFFB454.toInt()
    const val ACCENT_1 = 0xFF7B5CFF.toInt()
    const val ACCENT_2 = 0xFF36C5F0.toInt()
    const val ACCENT_3 = 0xFF2A1B6E.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    fun card(ctx: Context, title: String, subtitle: String? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 18)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(ctx, 22).toFloat() }
        addView(text(ctx, title, 17f, bold = true))
        if (subtitle != null) addView(text(ctx, subtitle, 13.5f, MUTED).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 6)) })
    }

    fun gradientButton(ctx: Context, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT_1, ACCENT_2))
            .apply { cornerRadius = dp(ctx, 30).toFloat() }
        minHeight = dp(ctx, 54)
        setOnClickListener { onClick() }
    }

    fun pill(ctx: Context, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 13.5f
        setTextColor(TEXT)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        background = GradientDrawable().apply {
            setColor(FIELD); cornerRadius = dp(ctx, 20).toFloat(); setStroke(dp(ctx, 1), ACCENT_1)
        }
        setOnClickListener { onClick() }
    }

    fun field(ctx: Context, hint: String) = EditText(ctx).apply {
        this.hint = hint
        isSingleLine = true
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 14.5f
        val p = dp(ctx, 14)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply { setColor(FIELD); cornerRadius = dp(ctx, 14).toFloat() }
    }

    /** A setup row: status dot, label and detail, and an action pill that hides once done. */
    class Row(ctx: Context, label: String, action: String, onClick: () -> Unit) {
        private val dot = View(ctx)
        private val detail = text(ctx, "", 12.5f, MUTED)
        private val button = pill(ctx, action, onClick)
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            addView(dot, LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { marginEnd = dp(ctx, 12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(ctx, label, 15f))
                addView(detail)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button)
        }

        fun set(done: Boolean, doneText: String, todoText: String, required: Boolean = true) {
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (done) OK else if (required) WARN else MUTED)
            }
            detail.text = if (done) doneText else todoText
            button.visibility = if (done) View.GONE else View.VISIBLE
        }
    }

    fun spacer(ctx: Context, h: Int) = View(ctx).apply { minimumHeight = dp(ctx, h) }

    /** A round icon button (an emoji or symbol on a coloured circle). */
    fun iconButton(ctx: Context, icon: String, description: String, gradient: Boolean = false, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = description
            background = if (gradient) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT_1, ACCENT_2)).apply { shape = GradientDrawable.OVAL }
                         else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(FIELD) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Screen header: optional back arrow, title and subtitle, then any [actions] on the right. */
    fun topBar(ctx: Context, title: String, subtitle: String?, back: (() -> Unit)? = null, vararg actions: View) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            if (back != null) addView(iconButton(ctx, "←", "Back", onClick = back), LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply { marginEnd = dp(ctx, 12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(ctx, title, 21f, bold = true))
                if (subtitle != null) addView(text(ctx, subtitle, 12.5f, MUTED))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actions.forEach { addView(it, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply { marginStart = dp(ctx, 8) }) }
        }

    /** A suggestion chip: tapping it asks or starts typing a common request. */
    fun chip(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13.5f
        setTextColor(TEXT)
        setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(ctx, 18).toFloat(); setStroke(dp(ctx, 1), FIELD) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** A chat bubble: yours on the right in the accent colours, Mehitha's on the left. */
    fun bubble(ctx: Context, s: String, mine: Boolean, color: Int = TEXT) = text(ctx, s, 15.5f, if (mine) Color.WHITE else color).apply {
        val p = dp(ctx, 14)
        setPadding(p, dp(ctx, 10), p, dp(ctx, 10))
        val r = dp(ctx, 18).toFloat()
        val small = dp(ctx, 4).toFloat()
        background = if (mine) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT_1, ACCENT_2))
                         .apply { cornerRadii = floatArrayOf(r, r, small, small, r, r, r, r) }
                     else GradientDrawable().apply { setColor(CARD); cornerRadii = floatArrayOf(small, small, r, r, r, r, r, r) }
        if (!mine) setTextIsSelectable(true)
    }
}
