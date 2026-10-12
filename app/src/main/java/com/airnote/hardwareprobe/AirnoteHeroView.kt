package com.airnote.hardwareprobe

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class AirnoteHeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                AirnoteUi.color("#D9EEFF"),
                AirnoteUi.color("#F7FBFF"),
                AirnoteUi.color("#FFF0DE")
            ),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(0f, 0f, w, h, AirnoteUi.dp(context, 24).toFloat(), AirnoteUi.dp(context, 24).toFloat(), paint)
        paint.shader = null

        // soft horizon glow
        paint.color = Color.argb(85, 255, 214, 163)
        canvas.drawCircle(w * .88f, h * .28f, h * .34f, paint)

        // cloud layers
        paint.color = Color.argb(115, 255, 255, 255)
        val cloudY = h * .78f
        canvas.drawOval(RectF(-w*.08f, cloudY, w*.38f, h*1.08f), paint)
        canvas.drawOval(RectF(w*.18f, cloudY*.94f, w*.72f, h*1.1f), paint)
        canvas.drawOval(RectF(w*.58f, cloudY*.98f, w*1.08f, h*1.08f), paint)

        // route arc
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = AirnoteUi.dp(context, 1).toFloat()
        paint.color = Color.argb(95, 36, 120, 243)
        val path = Path().apply {
            moveTo(w * .42f, h * .58f)
            cubicTo(w*.58f, h*.38f, w*.72f, h*.37f, w*.9f, h*.46f)
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = AirnoteUi.color(AirnoteUi.AIR)
        canvas.drawCircle(w*.7f, h*.42f, AirnoteUi.dp(context, 2).toFloat(), paint)

        // subtle border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = AirnoteUi.dp(context, 1).toFloat()
        paint.color = AirnoteUi.color("#D6E6F3")
        canvas.drawRoundRect(0.5f, 0.5f, w-0.5f, h-0.5f, AirnoteUi.dp(context, 24).toFloat(), AirnoteUi.dp(context, 24).toFloat(), paint)
        paint.style = Paint.Style.FILL
    }
}
