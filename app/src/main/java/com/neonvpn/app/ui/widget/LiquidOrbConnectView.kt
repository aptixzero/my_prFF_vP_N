package com.neonvpn.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * v3.8 §4.3 — LIQUID ORB connect control.
 *
 * Replaces the old reptilian-eye connect control. A single glassy sphere of
 * living "liquid energy" whose colour, wave turbulence and inner light react to
 * the connection lifecycle. A connect-progress arc (0..100, fed by
 * [com.neonvpn.app.ui.VpnStateBus.progress]) sweeps the rim while CONNECTING.
 *
 * FIVE states (per brief):
 *   IDLE          — calm violet, slow swell, dim.
 *   CONNECTING    — amber, fast churn + progress arc.
 *   CONNECTED     — emerald, bright steady pulse.
 *   DISCONNECTING — cooling violet, settling waves.
 *   ERROR         — red, sharp flicker.
 *
 * THREE render tiers (graceful degradation):
 *   • AGSL [RuntimeShader] (Android 13 / API 33+) — true per-pixel liquid.
 *   • GLES3 — reserved hook; falls through to Canvas if a GL context can't be
 *     created (kept Canvas-equivalent so behaviour is identical everywhere).
 *   • CANVAS — universal fallback, layered radial gradients + Bézier wave caps.
 *
 * The Canvas path is the guaranteed renderer and is always visually complete; the
 * AGSL path simply adds crisper per-pixel turbulence on capable devices.
 */
class LiquidOrbConnectView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class State { IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    var onClick: (() -> Unit)? = null

    @Volatile var state: State = State.IDLE
        private set

    /** 0..100 connect progress (only meaningful while CONNECTING). */
    @Volatile private var progress: Int = 0

    // animation drivers
    private var phase = 0f          // 0..1 looping
    private var intensity = 0.3f    // current glow intensity (eased toward target)
    private var pressPulse = 0f
    private var shownProgress = 0f  // eased progress for the arc

    private var loopAnim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val wave = Path()

    // ---- AGSL tier (API 33+) -------------------------------------------
    private val useAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private var orbShader: RuntimeShader? = null

    private var lastFrameMs = 0L
    private val frameIntervalMs = 22L   // ~45fps, smooth liquid without burning battery

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            triggerPress()
            onClick?.invoke()
        }
        if (useAgsl) {
            try { orbShader = buildAgslShader() } catch (_: Throwable) { orbShader = null }
        }
        startLoop()
    }

    fun setState(s: State) {
        state = s
        invalidate()
    }

    /** Push the live connect progress (0..100) — drives the rim arc. */
    fun setProgress(percent: Int) {
        progress = percent.coerceIn(0, 100)
        invalidate()
    }

    // ---------------------------------------------------------------- anim
    private fun startLoop() {
        loopAnim?.cancel()
        loopAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedFraction
                // ease the glow intensity toward the per-state target
                val target = when (state) {
                    State.IDLE -> 0.34f
                    State.CONNECTING -> 0.62f + 0.22f * (0.5f + 0.5f * sin(phase * 6f * PI).toFloat())
                    State.CONNECTED -> 0.78f + 0.14f * (0.5f + 0.5f * sin(phase * 2f * PI).toFloat())
                    State.DISCONNECTING -> 0.30f
                    State.ERROR -> 0.55f + 0.40f * (0.5f + 0.5f * sin(phase * 14f * PI).toFloat())
                }
                intensity += (target - intensity) * 0.12f
                shownProgress += (progress - shownProgress) * 0.18f
                val now = System.currentTimeMillis()
                if (now - lastFrameMs >= frameIntervalMs) {
                    lastFrameMs = now
                    invalidate()
                }
            }
            start()
        }
    }

    private fun triggerPress() {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 360
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pressPulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loopAnim?.cancel(); loopAnim = null
        pressAnim?.cancel(); pressAnim = null
    }

    // ---------------------------------------------------------------- draw
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f * 0.86f

        val (core, edge, glow) = palette()

        // ---- outer soft glow (no hard box; fades fully transparent) ----
        val glowR = r * (1.18f + 0.16f * intensity + 0.10f * pressPulse)
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(
                withAlpha(glow, (150 * intensity + 40 * pressPulse).toInt().coerceIn(0, 230)),
                withAlpha(glow, (44 * intensity).toInt().coerceIn(0, 110)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        // ---- the orb sphere ----
        if (useAgsl && orbShader != null) {
            drawAgslOrb(canvas, cx, cy, r, core, edge, glow)
        } else {
            drawCanvasOrb(canvas, cx, cy, r, core, edge, glow)
        }

        // ---- progress rim arc (CONNECTING only) ----
        if (state == State.CONNECTING) drawProgressArc(canvas, cx, cy, r, glow)
    }

    private fun palette(): Triple<Int, Int, Int> = when (state) {
        State.CONNECTED -> Triple(0xFF7BFFC0.toInt(), 0xFF0B7A45.toInt(), 0xFF22FF9C.toInt())
        State.CONNECTING -> Triple(0xFFFFD24A.toInt(), 0xFF9A5A00.toInt(), 0xFFFFB020.toInt())
        State.DISCONNECTING -> Triple(0xFFB79BE8.toInt(), 0xFF4B2E86.toInt(), 0xFF8E6BD8.toInt())
        State.ERROR -> Triple(0xFFFF8A8A.toInt(), 0xFF7A0B1E.toInt(), 0xFFFF1E3C.toInt())
        State.IDLE -> Triple(0xFFC084FC.toInt(), 0xFF6D28D9.toInt(), 0xFFA855F7.toInt())
    }

    // ---- AGSL per-pixel liquid (API 33+) -------------------------------
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun buildAgslShader(): RuntimeShader = RuntimeShader(AGSL_SRC)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawAgslOrb(canvas: Canvas, cx: Float, cy: Float, r: Float, core: Int, edge: Int, glow: Int) {
        val sh = orbShader ?: return drawCanvasOrb(canvas, cx, cy, r, core, edge, glow)
        try {
            sh.setFloatUniform("uTime", phase * 6.2831853f)
            sh.setFloatUniform("uCenter", cx, cy)
            sh.setFloatUniform("uRadius", r)
            sh.setFloatUniform("uIntensity", intensity)
            sh.setFloatUniform("uCore", Color.red(core) / 255f, Color.green(core) / 255f, Color.blue(core) / 255f)
            sh.setFloatUniform("uEdge", Color.red(edge) / 255f, Color.green(edge) / 255f, Color.blue(edge) / 255f)
            paint.shader = sh
            // clip to the circle so the shader only paints the sphere
            val saved = canvas.save()
            clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawCircle(cx, cy, r, paint)
            canvas.restoreToCount(saved)
            paint.shader = null
            drawSphereSheen(canvas, cx, cy, r)
        } catch (_: Throwable) {
            // any AGSL hiccup → never crash, fall back to Canvas this frame
            paint.shader = null
            drawCanvasOrb(canvas, cx, cy, r, core, edge, glow)
        }
    }

    // ---- Canvas universal fallback -------------------------------------
    private fun drawCanvasOrb(canvas: Canvas, cx: Float, cy: Float, r: Float, core: Int, edge: Int, glow: Int) {
        // base sphere body — top-lit radial depth gradient
        paint.shader = RadialGradient(
            cx - r * 0.22f, cy - r * 0.28f, r * 1.25f,
            intArrayOf(
                blend(core, Color.WHITE, 0.20f),
                core,
                blend(core, edge, 0.6f),
                edge,
                0xFF05060A.toInt()
            ),
            floatArrayOf(0f, 0.32f, 0.62f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // liquid waves: a couple of phase-shifted bands clipped to the sphere
        val saved = canvas.save()
        clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(clip)
        drawWaveBand(canvas, cx, cy, r, glow, 1.0f, 0f, 0.16f)
        drawWaveBand(canvas, cx, cy, r, blend(glow, Color.WHITE, 0.3f), 1.4f, 0.5f, 0.10f)
        // inner moving light blob (energy core)
        val bx = cx + cos(phase * 2f * PI).toFloat() * r * 0.22f
        val by = cy + sin(phase * 2f * PI).toFloat() * r * 0.16f
        paint.shader = RadialGradient(
            bx, by, r * 0.7f,
            intArrayOf(withAlpha(blend(core, Color.WHITE, 0.5f), (130 * intensity).toInt().coerceIn(0, 200)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(bx, by, r * 0.7f, paint)
        paint.shader = null
        canvas.restoreToCount(saved)

        drawSphereSheen(canvas, cx, cy, r)
    }

    /** A single translucent liquid wave band sweeping across the sphere. */
    private fun drawWaveBand(
        canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int,
        freq: Float, phaseOffset: Float, heightFrac: Float
    ) {
        val top = cy - r + 2f * r * ((phase + phaseOffset) % 1f)   // descending band
        val amp = r * 0.10f
        wave.reset()
        wave.moveTo(cx - r, top)
        var x = -r
        while (x <= r) {
            val px = cx + x
            val py = top + sin((x / r) * PI.toFloat() * freq * 2f + phase * 6.2831853f) * amp
            wave.lineTo(px, py)
            x += r / 16f
        }
        wave.lineTo(cx + r, top + r * heightFrac * 4f)
        wave.lineTo(cx - r, top + r * heightFrac * 4f)
        wave.close()
        paint.color = withAlpha(color, (60 * intensity).toInt().coerceIn(0, 110))
        canvas.drawPath(wave, paint)
    }

    /** Glassy top sheen + crisp catch-light shared by all tiers. */
    private fun drawSphereSheen(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.shader = RadialGradient(
            cx - r * 0.30f, cy - r * 0.40f, r * 0.95f,
            intArrayOf(withAlpha(Color.WHITE, 120), withAlpha(Color.WHITE, 22), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.color = withAlpha(Color.WHITE, 220)
        canvas.drawCircle(cx - r * 0.30f, cy - r * 0.38f, r * 0.06f, paint)

        // thin rim for crisp definition
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.02f
        paint.color = withAlpha(Color.WHITE, 40)
        canvas.drawCircle(cx, cy, r * 0.99f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawProgressArc(canvas: Canvas, cx: Float, cy: Float, r: Float, glow: Int) {
        val sweep = (shownProgress / 100f).coerceIn(0f, 1f) * 360f
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = r * 0.06f
        arcPaint.strokeCap = Paint.Cap.ROUND
        // track
        arcPaint.color = withAlpha(glow, 50)
        val rr = r * 1.06f
        canvas.drawCircle(cx, cy, rr, arcPaint)
        // progress
        arcPaint.color = withAlpha(blend(glow, Color.WHITE, 0.25f), 230)
        canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, sweep, false, arcPaint)
    }

    // --------------------------------------------------------- color utils
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
        )
    }

    companion object {
        // AGSL liquid-orb shader (Android 13+). Domain-warped fbm noise tinted
        // between the core and edge colours, brightened by uIntensity. Kept short
        // and dependency-free so it compiles on every API-33+ GPU.
        private val AGSL_SRC = """
            uniform float uTime;
            uniform float2 uCenter;
            uniform float uRadius;
            uniform float uIntensity;
            uniform float3 uCore;
            uniform float3 uEdge;

            float hash(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }
            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float a = hash(i);
                float b = hash(i + float2(1.0, 0.0));
                float c = hash(i + float2(0.0, 1.0));
                float d = hash(i + float2(1.0, 1.0));
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
            }
            float fbm(float2 p) {
                float v = 0.0; float amp = 0.5;
                for (int i = 0; i < 4; i++) { v += amp * noise(p); p *= 2.0; amp *= 0.5; }
                return v;
            }

            half4 main(float2 fragCoord) {
                float2 d = (fragCoord - uCenter) / uRadius;
                float dist = length(d);
                if (dist > 1.0) { return half4(0.0); }
                // domain warp for a churning liquid look
                float2 q = d * 2.2;
                float2 warp = float2(fbm(q + uTime * 0.15), fbm(q - uTime * 0.12));
                float n = fbm(q + warp * 1.5 + uTime * 0.10);
                float3 col = mix(uEdge, uCore, clamp(n + (1.0 - dist) * 0.4, 0.0, 1.0));
                // top-lit spherical shading + intensity boost
                float light = clamp(0.6 + 0.7 * (-d.y) + 0.3 * (1.0 - dist), 0.0, 1.4);
                col *= light * (0.7 + 0.6 * uIntensity);
                float edgeFade = smoothstep(1.0, 0.82, dist);
                return half4(col * edgeFade, edgeFade);
            }
        """.trimIndent()
    }
}
