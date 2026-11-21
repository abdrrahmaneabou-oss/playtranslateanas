package com.playtranslate.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * A sampled-game translation background. Only the background is drawn here:
 * text and its outline are still rendered by the owning TextView, unblurred.
 * Coordinates are screen-aligned even though this drawable is box-local.
 */
internal class TranslationBackdropDrawable(
    private val fillColor: Int,
    private val transparency: Int,
    private val rounded: Boolean,
    private val edgeFade: Int,
    private val blurredFrame: Bitmap?,
    private val screenBounds: RectF,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val density: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val clipping = Path()
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        // Allocate an offscreen compositing layer only when alpha feathering
        // requires a DST_OUT mask. Plain transparency and rounded corners use
        // a lightweight canvas save/clip, even on busy 60-fps game screens.
        val layer = if (edgeFade > 0) {
            canvas.saveLayer(bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(), null)
        } else {
            canvas.save()
        }
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        if (rounded) {
            clipping.reset()
            val radius = minOf(14f * density, minOf(w, h) * 0.48f)
            clipping.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
            canvas.clipPath(clipping)
        }

        // A small cached screenshot is blurred before it reaches the UI
        // thread's drawing loop. Never blur the TextView itself.
        if (blurredFrame != null && !blurredFrame.isRecycled &&
            screenWidth > 0 && screenHeight > 0) {
            val sw = blurredFrame.width
            val sh = blurredFrame.height
            val src = Rect(
                (screenBounds.left * sw / screenWidth).toInt().coerceIn(0, sw - 1),
                (screenBounds.top * sh / screenHeight).toInt().coerceIn(0, sh - 1),
                (screenBounds.right * sw / screenWidth).toInt().coerceIn(1, sw),
                (screenBounds.bottom * sh / screenHeight).toInt().coerceIn(1, sh),
            )
            if (src.right > src.left && src.bottom > src.top) {
                paint.alpha = drawableAlpha
                paint.shader = null
                canvas.drawBitmap(blurredFrame, src, RectF(0f, 0f, w, h), paint)
            }
        }

        val baseAlpha = (255f * (100 - transparency.coerceIn(0, 100)) / 100f).roundToInt()
        val alpha = (baseAlpha * drawableAlpha / 255f).roundToInt().coerceIn(0, 255)
        paint.color = Color.argb(alpha, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor))
        paint.shader = null
        canvas.drawRect(0f, 0f, w, h, paint)

        // True alpha feather, applied to the combined blur+colour background.
        // The outlined letters are not in this layer and remain sharp.
        if (edgeFade > 0) {
            val fraction = edgeFade.coerceIn(0, 100) / 100f
            val fx = (w * 0.42f * fraction).coerceAtLeast(0.5f)
            val fy = (h * 0.42f * fraction).coerceAtLeast(0.5f)
            erase.shader = LinearGradient(0f, 0f, fx, 0f,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, fx, h, erase)
            erase.shader = LinearGradient(w - fx, 0f, w, 0f,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(w - fx, 0f, w, h, erase)
            erase.shader = LinearGradient(0f, 0f, 0f, fy,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, fy, erase)
            erase.shader = LinearGradient(0f, h - fy, 0f, h,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h - fy, w, h, erase)
            erase.shader = null
        }
        canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** A deliberately small software-blurred screenshot snapshot.
 *  Downsampling bounds both memory use and CPU cost. It is computed only
 *  when text boxes actually change, not once per captured video frame. */
internal object TranslationBackdropPixels {
    fun blur(frame: Bitmap, percent: Int): Bitmap? {
        if (frame.isRecycled || percent <= 0 || frame.width <= 0 || frame.height <= 0) return null
        val scale = minOf(1f, 288f / maxOf(frame.width, frame.height))
        val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
        val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
        val source = Bitmap.createScaledBitmap(frame, w, h, true)
        val radius = (percent.coerceIn(0, 100) * 12f / 100f).roundToInt().coerceAtLeast(1)
        val original = IntArray(w * h)
        source.getPixels(original, 0, w, 0, 0, w, h)
        val horizontal = IntArray(original.size)
        val result = IntArray(original.size)
        for (y in 0 until h) for (x in 0 until w) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (k in -radius..radius) {
                val c = original[y * w + (x + k).coerceIn(0, w - 1)]
                red += Color.red(c); green += Color.green(c); blue += Color.blue(c); count++
            }
            horizontal[y * w + x] = Color.rgb(red / count, green / count, blue / count)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (k in -radius..radius) {
                val c = horizontal[(y + k).coerceIn(0, h - 1) * w + x]
                red += Color.red(c); green += Color.green(c); blue += Color.blue(c); count++
            }
            result[y * w + x] = Color.rgb(red / count, green / count, blue / count)
        }
        val out = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
        if (source !== frame) source.recycle()
        return out
    }
}
