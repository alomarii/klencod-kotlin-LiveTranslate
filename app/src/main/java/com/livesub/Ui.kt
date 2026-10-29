package com.livesub

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

fun dpOf(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

/** زر التشغيل الدائري مع حلقات نابضة تتفاعل مع شدة الصوت */
class PulseView(ctx: Context) : View(ctx) {
    var running = false
    var level = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.5f
        val lv = level.coerceIn(0f, 1f)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpOf(context, 2).toFloat()
        for (i in 0..2) {
            val p = (phase + i / 3f) % 1f
            val r = base * (1.05f + p * (if (running) 0.75f + lv * 0.6f else 0.3f))
            val a = ((if (running) 150 else 55) * (1f - p)).toInt().coerceIn(0, 255)
            paint.color = if (running) Color.argb(a, 251, 113, 133) else Color.argb(a, 34, 211, 238)
            c.drawCircle(cx, cy, r, paint)
        }
        paint.style = Paint.Style.FILL
        val colors = if (running)
            intArrayOf(Color.parseColor("#FB7185"), Color.parseColor("#E11D48"))
        else intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#8B5CF6"))
        paint.shader = LinearGradient(cx - base, cy - base, cx + base, cy + base, colors, null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, base * (1f + 0.07f * lv), paint)
        paint.shader = null
        paint.color = Color.WHITE
        val s = base * 0.5f
        if (running) {
            c.drawRoundRect(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, s * 0.25f, s * 0.25f, paint)
        } else {
            path.reset()
            path.moveTo(cx - s * 0.6f, cy - s)
            path.lineTo(cx - s * 0.6f, cy + s)
            path.lineTo(cx + s, cy)
            path.close()
            c.drawPath(path, paint)
        }
    }
}

/** موجة صوتية متحركة */
class WaveView(ctx: Context) : View(ctx) {
    var level = 0f
    var active = false
    private val n = 30
    private val bars = FloatArray(n)
    private var t = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun tick() {
        t += 0.2f
        for (i in 0 until n) {
            val target = if (active)
                level.coerceIn(0f, 1f) * (0.3f + 0.7f * abs(sin(t + i * 0.5f))) + 0.05f
            else 0.05f + 0.03f * abs(sin(t * 0.5f + i * 0.4f))
            bars[i] += (target - bars[i]) * 0.35f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            Color.parseColor("#22D3EE"), Color.parseColor("#8B5CF6"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val slot = width / n.toFloat()
        val bw = slot * 0.55f
        for (i in 0 until n) {
            val bh = height * bars[i].coerceIn(0.04f, 1f)
            val x = i * slot + (slot - bw) / 2f
            rect.set(x, (height - bh) / 2f, x + bw, (height + bh) / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }
}
