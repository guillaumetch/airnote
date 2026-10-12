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

        val r = AirnoteUi.dp(context, 24).toFloat()
        val clip = Path().apply { addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)

        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                AirnoteUi.color("#BFE3FF"),
                AirnoteUi.color("#EAF6FF"),
                AirnoteUi.color("#FFF0DD")
            ),
            floatArrayOf(0f, .58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // sun / sunrise glow
        paint.color = Color.argb(70, 255, 190, 110)
        canvas.drawCircle(w * .87f, h * .24f, h * .30f, paint)
        paint.color = Color.argb(90, 255, 232, 190)
        canvas.drawCircle(w * .87f, h * .24f, h * .16f, paint)

        // distant mountain range
        paint.color = Color.argb(110, 145, 190, 225)
        val mountains = Path().apply {
            moveTo(0f, h*.70f)
            lineTo(w*.16f, h*.58f)
            lineTo(w*.25f, h*.64f)
            lineTo(w*.38f, h*.47f)
            lineTo(w*.50f, h*.63f)
            lineTo(w*.64f, h*.51f)
            lineTo(w*.76f, h*.66f)
            lineTo(w, h*.56f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(mountains, paint)

        // bright snowy faces
        paint.color = Color.argb(150, 245, 252, 255)
        val snow = Path().apply {
            moveTo(w*.32f,h*.55f)
            lineTo(w*.38f,h*.47f)
            lineTo(w*.43f,h*.56f)
            lineTo(w*.40f,h*.54f)
            lineTo(w*.37f,h*.58f)
            close()
        }
        canvas.drawPath(snow, paint)

        // cloud layers
        paint.color = Color.argb(165, 255, 255, 255)
        canvas.drawOval(RectF(-w*.08f, h*.72f, w*.34f, h*1.02f), paint)
        canvas.drawOval(RectF(w*.12f, h*.68f, w*.62f, h*1.04f), paint)
        canvas.drawOval(RectF(w*.50f, h*.71f, w*.93f, h*1.04f), paint)
        canvas.drawOval(RectF(w*.76f, h*.69f, w*1.08f, h*1.03f), paint)

        // route arc
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = AirnoteUi.dp(context, 1).toFloat()
        paint.color = Color.argb(115, 36, 120, 243)
        val route = Path().apply {
            moveTo(w*.46f, h*.54f)
            cubicTo(w*.59f,h*.42f,w*.71f,h*.39f,w*.88f,h*.49f)
        }
        canvas.drawPath(route, paint)
        paint.style = Paint.Style.FILL
        paint.color = AirnoteUi.color(AirnoteUi.AIR)
        canvas.drawCircle(w*.70f, h*.42f, AirnoteUi.dp(context, 2).toFloat(), paint)

        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = AirnoteUi.dp(context, 1).toFloat()
        paint.color = AirnoteUi.color("#D3E3EF")
        canvas.drawRoundRect(.5f, .5f, w-.5f, h-.5f, r, r, paint)
        paint.style = Paint.Style.FILL
    }
}
