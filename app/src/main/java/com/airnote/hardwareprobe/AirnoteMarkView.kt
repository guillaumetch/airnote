package com.airnote.hardwareprobe

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class AirnoteMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val navy = AirnoteUi.color(AirnoteUi.AIR_DEEP)
        val blue = AirnoteUi.color(AirnoteUi.AIR)

        paint.style = Paint.Style.FILL
        paint.color = navy
        val a = Path().apply {
            moveTo(w * .50f, h * .06f)
            lineTo(w * .91f, h * .68f)
            lineTo(w * .72f, h * .61f)
            lineTo(w * .50f, h * .29f)
            lineTo(w * .31f, h * .58f)
            lineTo(w * .11f, h * .64f)
            close()
        }
        canvas.drawPath(a, paint)

        paint.color = blue
        val wave1 = Path().apply {
            moveTo(w*.10f, h*.66f)
            cubicTo(w*.27f,h*.73f,w*.39f,h*.68f,w*.52f,h*.61f)
            cubicTo(w*.67f,h*.54f,w*.77f,h*.55f,w*.91f,h*.61f)
            cubicTo(w*.75f,h*.64f,w*.66f,h*.71f,w*.51f,h*.77f)
            cubicTo(w*.36f,h*.84f,w*.22f,h*.83f,w*.10f,h*.76f)
            close()
        }
        canvas.drawPath(wave1, paint)

        paint.color = navy
        val wave2 = Path().apply {
            moveTo(w*.18f, h*.79f)
            cubicTo(w*.34f,h*.87f,w*.50f,h*.84f,w*.63f,h*.77f)
            cubicTo(w*.73f,h*.72f,w*.82f,h*.72f,w*.91f,h*.76f)
            cubicTo(w*.80f,h*.80f,w*.72f,h*.86f,w*.59f,h*.91f)
            cubicTo(w*.42f,h*.97f,w*.29f,h*.92f,w*.18f,h*.86f)
            close()
        }
        canvas.drawPath(wave2, paint)
    }
}
