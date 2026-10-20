package dev.vogy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import androidx.core.content.res.ResourcesCompat
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Segmented Ladder Arc Speedometer for OpenVOGE Neo-Brutalism.
 *
 * Replicates the exact arcade-style radial ladder gauge from mock.png:
 * - Outer and inner concentric black arcs with 4dp bold stroke
 * - 18 segments (exactly 10 km/h per segment, symmetric with vertical apex divider)
 * - Inactive cells: cream fill (#ECE8DF)
 * - Active cells: lime green (#C8F031) fill
 * - Massive bold center speed readout ("0")
 * - White/Lime KM/H or MP/H pill badge with black border and touch toggle
 * - Bold monospace end labels '0' and '180' aligned under the arc tips
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Structural border lines (outer arc, inner arc, radial dividers)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.0f * density
        color = 0xFF000000.toInt()
        strokeCap = Paint.Cap.BUTT
    }

    // Inactive cell fill: cream in light mode, muted slate in dark mode
    private val inactiveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.neo_track)
    }

    // Active cell fill: user-selected accent (lime by default)
    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = MaterialColors.getColor(context, R.attr.neoAccent, 0xFFC8F031.toInt())
    }

    // Typography
    private val endpointNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.neo_ink)
        textSize = 17f * density
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
        textAlign = Paint.Align.CENTER
    }

    private val speedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.neo_ink)
        textSize = 11.5f * density
        letterSpacing = 0.16f
        typeface = ResourcesCompat.getFont(context, R.font.space_mono_bold)
        textAlign = Paint.Align.CENTER
    }

    private val speedNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.neo_ink)
        textSize = 96f * density
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
        textAlign = Paint.Align.CENTER
    }

    // KM/H pill badge: white fill with black border
    private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFF000000.toInt()
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textSize = 11f * density
        letterSpacing = 0.12f
        typeface = ResourcesCompat.getFont(context, R.font.space_mono_bold)
        textAlign = Paint.Align.CENTER
    }

    private val outerRect = RectF()
    private val innerRect = RectF()
    private val midRect = RectF()
    private val pillRect = RectF()

    private var speed = 0
    private var maxSpeed = SpeedUnit.KMH.maxGaugeSpeed
    // Exactly 18 cells: 10 km/h per cell (0 to 180), symmetric with apex divider
    private val cellCount = 18

    var speedUnit: SpeedUnit = SpeedUnit.KMH
        private set

    var onUnitClickListener: (() -> Unit)? = null

    private var isPillPressed = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun setSpeed(value: Int?) {
        speed = (value ?: 0).coerceAtLeast(0)
        invalidate()
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        if (speedUnit != unit) {
            speedUnit = unit
            maxSpeed = unit.maxGaugeSpeed
            invalidate()
        }
    }

    fun setMaxSpeed(value: Int) {
        maxSpeed = value.coerceAtLeast(1)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (width * 0.82f).toInt().coerceAtLeast((270 * density).toInt())
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f

        // Outer radius fills ~79% of available width, matching mock.png
        val rOuter = min((w / 2f) * 0.82f, (h - 36f * density) / 1.67f)
        val trackThickness = 26f * density
        val rInner = rOuter - trackThickness
        val rMid = (rOuter + rInner) / 2f

        val centerY = 12f * density + rOuter

        inactiveFillPaint.strokeWidth = trackThickness - 1.5f * density
        activeFillPaint.strokeWidth = trackThickness - 1.5f * density

        val startAngle = 138f
        val sweepAngle = 264f
        val cellSweep = sweepAngle / cellCount.toFloat()

        outerRect.set(centerX - rOuter, centerY - rOuter, centerX + rOuter, centerY + rOuter)
        innerRect.set(centerX - rInner, centerY - rInner, centerX + rInner, centerY + rInner)
        midRect.set(centerX - rMid, centerY - rMid, centerX + rMid, centerY + rMid)

        val cappedSpeed = speed.coerceIn(0, maxSpeed)
        val activeCells = ((cappedSpeed.toFloat() / maxSpeed) * cellCount).toInt()

        // 1. Draw Inactive / Active Segment Fills
        for (i in 0 until cellCount) {
            val angle = startAngle + i * cellSweep
            val paint = if (i < activeCells) activeFillPaint else inactiveFillPaint
            canvas.drawArc(midRect, angle + 0.8f, cellSweep - 1.6f, false, paint)
        }

        // 2. Draw Ladder Frame (Outer arc & Inner arc)
        canvas.drawArc(outerRect, startAngle, sweepAngle, false, framePaint)
        canvas.drawArc(innerRect, startAngle, sweepAngle, false, framePaint)

        // 3. Draw Radial Divider Rungs
        for (i in 0..cellCount) {
            val angle = startAngle + i * cellSweep
            val rad = Math.toRadians(angle.toDouble())
            val x1 = centerX + cos(rad).toFloat() * rInner
            val y1 = centerY + sin(rad).toFloat() * rInner
            val x2 = centerX + cos(rad).toFloat() * rOuter
            val y2 = centerY + sin(rad).toFloat() * rOuter
            canvas.drawLine(x1, y1, x2, y2, framePaint)
        }

        // 4. Endpoint Numbers: 0 and 180 directly below the left and right tips
        val radStart = Math.toRadians(startAngle.toDouble())
        val radEnd = Math.toRadians((startAngle + sweepAngle).toDouble())
        val x0 = centerX + cos(radStart).toFloat() * rMid
        val x180 = centerX + cos(radEnd).toFloat() * rMid
        val yBaseline = centerY + sin(radStart).toFloat() * rOuter + (21f * density)

        canvas.drawText("0", x0, yBaseline, endpointNumberPaint)
        canvas.drawText(maxSpeed.toString(), x180, yBaseline, endpointNumberPaint)

        // 5. Center Speed Readout
        val speedLabel = context.getString(R.string.dashboard_speed_label).uppercase()
        canvas.drawText(speedLabel, centerX, centerY - (46f * density), speedLabelPaint)

        val speedText = cappedSpeed.toString()
        val fontMetrics = speedNumberPaint.fontMetrics
        val speedY = centerY + (3f * density) - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(speedText, centerX, speedY, speedNumberPaint)

        // 6. White/Lime KM/H or MP/H Pill Badge with black border and black text
        val pillWidth = 60f * density
        val pillHeight = 23f * density
        val pillTop = centerY + (48f * density)
        pillRect.set(centerX - pillWidth / 2f, pillTop, centerX + pillWidth / 2f, pillTop + pillHeight)

        val fillPaint = if (isPillPressed) activeFillPaint else pillFillPaint
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, fillPaint)
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, pillStrokePaint)
        val unitMetrics = unitTextPaint.fontMetrics
        val unitY = pillRect.centerY() - (unitMetrics.ascent + unitMetrics.descent) / 2f
        canvas.drawText(speedUnit.badgeText, centerX, unitY, unitTextPaint)
    }

    private fun isPointInPillTouchTarget(x: Float, y: Float): Boolean {
        val horizontalPadding = 32f * density
        val topPadding = 24f * density
        val bottomPadding = 24f * density
        return (x >= pillRect.left - horizontalPadding &&
                x <= pillRect.right + horizontalPadding &&
                y >= pillRect.top - topPadding &&
                y <= pillRect.bottom + bottomPadding)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isPointInPillTouchTarget(event.x, event.y)) {
                    isPillPressed = true
                    downX = event.x
                    downY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPillPressed) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop ||
                        !isPointInPillTouchTarget(event.x, event.y)) {
                        isPillPressed = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isPillPressed) {
                    isPillPressed = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    performClick()
                    playSoundEffect(SoundEffectConstants.CLICK)
                    onUnitClickListener?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isPillPressed) {
                    isPillPressed = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
