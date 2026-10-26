package com.personal.heyclaude

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/** The animated assistant orb: a glowing gradient sphere that breathes while active. */
class OrbView(context: Context) : View(context) {

    var active = true
        set(value) { field = value; invalidate() }

    private var phase = 0f
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val pulse = if (active) 1f + 0.06f * sin(phase) else 0.92f
        val alpha = if (active) 255 else 120

        glow.shader = RadialGradient(cx, cy, r, intArrayOf(Ui.ACCENT_2 and 0x66FFFFFF, Color.TRANSPARENT),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * pulse, glow)

        val cr = r * 0.52f * pulse
        core.shader = RadialGradient(cx - cr * 0.3f, cy - cr * 0.35f, cr * 1.4f,
            intArrayOf(Color.WHITE, Ui.ACCENT_2, Ui.ACCENT_1, Ui.ACCENT_3), floatArrayOf(0f, 0.25f, 0.7f, 1f),
            Shader.TileMode.CLAMP)
        core.alpha = alpha
        canvas.drawCircle(cx, cy, cr, core)

        ring.strokeWidth = r * 0.03f
        ring.shader = SweepGradient(cx, cy, intArrayOf(Ui.ACCENT_1, Ui.ACCENT_2, Color.TRANSPARENT, Ui.ACCENT_1), null)
        ring.alpha = if (active) 200 else 60
        canvas.save()
        canvas.rotate(Math.toDegrees(phase.toDouble()).toFloat(), cx, cy)
        canvas.drawCircle(cx, cy, cr * 1.28f, ring)
        canvas.restore()
    }
}
