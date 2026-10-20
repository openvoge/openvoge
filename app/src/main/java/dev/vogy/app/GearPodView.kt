package dev.vogy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.res.ResourcesCompat
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Neo-Brutalist Gear Pod View matching mock.png:
 * - Square 1:1 aspect ratio
 * - Tilted 4.1 degrees clockwise
 * - Cream background (#ECE8DF)
 * - Hard black drop shadow (+dx, +dy) hugging bottom-right edges with matching corner radius
 * - Heavy black stroke (3.5dp)
 * - Upper centered "GEAR" (or localized R.string.dashboard_gear_label)
 * - Two solid rectangular blocks (▬ ▬) when disconnected/idle ("--")
 * - Bold neo-brutalist gear value ("N", "1", "2", ...) when engaged
 */
class GearPodView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    var gearValue: String = "--"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    // Shadow Paint (solid black)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }

    // Card Fill Paint (Cream #ECE8DF)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFECE8DF.toInt()
    }

    // Card Stroke Paint (Heavy black 3.5dp)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = 0xFF000000.toInt()
    }

    // "GEAR" label paint
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
        typeface = ResourcesCompat.getFont(context, R.font.space_mono_bold)
        letterSpacing = 0.12f
    }

    // Gear Value paint (for "N", "1", "2", etc.)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 28f * density
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
    }

    // Dash Paint (for the solid rectangular blocks ▬ ▬)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }

    private val shadowRect = RectF()
    private val bodyRect = RectF()
    private val dash1Rect = RectF()
    private val dash2Rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (74 * density).toInt()
        val width = resolveSize(defaultSize, widthMeasureSpec)
        val height = resolveSize(defaultSize, heightMeasureSpec)
        val finalSize = min(width, height)
        setMeasuredDimension(finalSize, finalSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = min(w, h)
        val stroke = strokePaint.strokeWidth
        val shadowOffset = 4.0f * density
        val cornerRadius = 14f * density

        // Leave margin for rotation so corners don't clip against view bounds
        val margin = 4.5f * density
        val cardW = size - shadowOffset - stroke - margin
        val cardH = cardW

        val cx = w / 2f
        val cy = h / 2f

        canvas.save()
        // Rotate 4.1 degrees clockwise matching mock.png
        canvas.rotate(4.1f, cx, cy)

        val left = cx - cardW / 2f - shadowOffset / 2f
        val top = cy - cardH / 2f - shadowOffset / 2f
        val right = left + cardW
        val bottom = top + cardH

        bodyRect.set(left, top, right, bottom)
        shadowRect.set(left + shadowOffset, top + shadowOffset, right + shadowOffset, bottom + shadowOffset)

        // 1. Draw Drop Shadow
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)

        // 2. Draw Cream Fill
        canvas.drawRoundRect(bodyRect, cornerRadius, cornerRadius, fillPaint)

        // 3. Draw Heavy Black Stroke
        canvas.drawRoundRect(bodyRect, cornerRadius, cornerRadius, strokePaint)

        // 4. Draw Label ("GEAR" or "MARCHA")
        val labelText = context.getString(R.string.dashboard_gear_label).uppercase()
        val labelY = top + cardH * 0.32f
        canvas.drawText(labelText, bodyRect.centerX(), labelY, labelPaint)

        // 5. Draw Value: Dashes or Gear number
        if (gearValue.isBlank() || gearValue == "--" || gearValue == "-") {
            // Draw two solid rectangular blocks matching mock.png
            val dashW = cardW * 0.155f
            val dashH = cardH * 0.065f
            val dashGap = cardW * 0.065f
            val dashY = top + cardH * 0.65f

            val totalDashSpan = dashW * 2 + dashGap
            val dash1Left = bodyRect.centerX() - totalDashSpan / 2f
            val dash2Left = dash1Left + dashW + dashGap

            dash1Rect.set(dash1Left, dashY - dashH / 2f, dash1Left + dashW, dashY + dashH / 2f)
            dash2Rect.set(dash2Left, dashY - dashH / 2f, dash2Left + dashW, dashY + dashH / 2f)

            canvas.drawRect(dash1Rect, dashPaint)
            canvas.drawRect(dash2Rect, dashPaint)
        } else {
            // Draw active gear text ("N", "1", "2", ...)
            val fontMetrics = valuePaint.fontMetrics
            val baseline = top + cardH * 0.68f - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(gearValue, bodyRect.centerX(), baseline, valuePaint)
        }

        canvas.restore()
    }
}
