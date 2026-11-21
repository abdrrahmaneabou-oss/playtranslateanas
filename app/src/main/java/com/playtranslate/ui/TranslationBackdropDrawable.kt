package com.playtranslate.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * Overlay background material, drawn separately from the sharp Arabic text.
 *
 *  - Transparency is exclusively the alpha of the sampled colour TINT:
 *    0 = 255/255 opaque, 100 = 0/255. It never changes blur strength.
 *  - Tint retains each text box's sampled background colour.
 *  - Horizontal extension is a percent of each individual text box's width.
 *  - Edge fading affects only this material, never the sharp glyphs.
 *
 * Note: an Android TYPE_APPLICATION_OVERLAY window can still be globally
 * capped at alpha ~=0.8 to keep touch-through. This Drawable cannot and
 * must not circumvent that system security limit.
 */
internal class TranslationBackdropDrawable(
    private val fillColor: Int,
    private val transparency: Int,
    private val rounded: Boolean,
    private val edgeFade: Int,
    private val horizontalExpansionPercent: Int,
    private val density: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val clipping = Path()
    private var drawableAlpha = 255
    private var cachedSize: Pair<Float, Float>? = null
    private var featherShaders: Array<Shader>? = null

    override fun draw(canvas: Canvas) {
        val originalWidth = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (originalWidth <= 0f || h <= 0f) return
        val extraX = TranslationGlassMath.horizontalOverhang(originalWidth, horizontalExpansionPercent)
        val w = originalWidth + 2f * extraX

        // DST_OUT must only erase *this* background, never already-drawn
        // game overlays or the glyphs which TextView draws after background.
        val layer = if (edgeFade > 0) {
            canvas.saveLayer(
                bounds.left.toFloat() - extraX, bounds.top.toFloat(),
                bounds.right.toFloat() + extraX, bounds.bottom.toFloat(), null,
            )
        } else canvas.save()
        canvas.translate(bounds.left.toFloat() - extraX, bounds.top.toFloat())

        if (rounded) {
            clipping.reset()
            val radius = minOf(14f * density, minOf(w, h) * 0.48f)
            clipping.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
            canvas.clipPath(clipping)
        }

        val alpha = (TranslationGlassMath.tintAlpha(transparency) *
            drawableAlpha / 255f).roundToInt().coerceIn(0, 255)
        if (alpha > 0) {
            paint.shader = null
            paint.color = Color.argb(
                alpha, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor)
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        if (edgeFade > 0) {
            val gradients = shaders(w, h)
            val fx = (w * TranslationGlassMath.featherFraction(edgeFade)).coerceAtLeast(0.5f)
            val fy = (h * TranslationGlassMath.featherFraction(edgeFade)).coerceAtLeast(0.5f)
            erase.shader = gradients[0]
            canvas.drawRect(0f, 0f, fx, h, erase)
            erase.shader = gradients[1]
            canvas.drawRect(w - fx, 0f, w, h, erase)
            erase.shader = gradients[2]
            canvas.drawRect(0f, 0f, w, fy, erase)
            erase.shader = gradients[3]
            canvas.drawRect(0f, h - fy, w, h, erase)
            erase.shader = null
        }
        canvas.restoreToCount(layer)
    }

    /** Smoothstep alpha stops over a broad range; avoids the narrow, harsh
     *  rectangular erase at low slider values. Reused on every draw. */
    private fun shaders(w: Float, h: Float): Array<Shader> {
        val current = cachedSize
        val present = featherShaders
        if (current?.first == w && current.second == h && present != null) return present
        val fraction = TranslationGlassMath.featherFraction(edgeFade)
        val fx = (w * fraction).coerceAtLeast(0.5f)
        val fy = (h * fraction).coerceAtLeast(0.5f)
        val stops = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
        val colors = stops.map { Color.argb(TranslationGlassMath.erasedAt(it), 0, 0, 0) }
            .toIntArray()
        val reversed = colors.reversedArray()
        val gradients = arrayOf<Shader>(
            LinearGradient(0f, 0f, fx, 0f, colors, stops, Shader.TileMode.CLAMP),
            LinearGradient(w - fx, 0f, w, 0f, reversed, stops, Shader.TileMode.CLAMP),
            LinearGradient(0f, 0f, 0f, fy, colors, stops, Shader.TileMode.CLAMP),
            LinearGradient(0f, h - fy, 0f, h, reversed, stops, Shader.TileMode.CLAMP),
        )
        cachedSize = w to h
        featherShaders = gradients
        return gradients
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

