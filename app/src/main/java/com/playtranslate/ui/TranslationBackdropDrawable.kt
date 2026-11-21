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
 * Overlay background material, drawn separately from the sharp Arabic text.
 *
 *  - Transparency is exclusively the alpha of the sampled colour TINT:
 *    0 = 255/255 opaque, 100 = 0/255. It never changes blur strength.
 *  - Blur is a genuine colour average over a *clean game screenshot*.
 *    Unlike a solid white wash, the pixels remain the game's own palette.
 *  - Edge fading masks the entire material, but never the TextView glyphs.
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
    private var cachedSize: Pair<Float, Float>? = null
    private var featherShaders: Array<Shader>? = null

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        // DST_OUT must only erase *this* background, never already-drawn
        // game overlays or the glyphs which TextView draws after background.
        val layer = if (edgeFade > 0) {
            canvas.saveLayer(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(), null,
            )
        } else canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())

        if (rounded) {
            clipping.reset()
            val radius = minOf(14f * density, minOf(w, h) * 0.48f)
            clipping.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
            canvas.clipPath(clipping)
        }

        // Render ONLY at strictly positive blur. At 0 there is no screenshot
        // paint of any kind (not even an already-downsampled screenshot).
        val backdrop = blurredFrame
        if (backdrop != null && !backdrop.isRecycled &&
            screenWidth > 0 && screenHeight > 0) {
            val src = Rect(
                (screenBounds.left * backdrop.width / screenWidth).roundToInt().coerceIn(0, backdrop.width),
                (screenBounds.top * backdrop.height / screenHeight).roundToInt().coerceIn(0, backdrop.height),
                (screenBounds.right * backdrop.width / screenWidth).roundToInt().coerceIn(0, backdrop.width),
                (screenBounds.bottom * backdrop.height / screenHeight).roundToInt().coerceIn(0, backdrop.height),
            )
            if (src.right > src.left && src.bottom > src.top) {
                paint.shader = null
                paint.color = Color.WHITE
                paint.alpha = drawableAlpha
                canvas.drawBitmap(backdrop, src, RectF(0f, 0f, w, h), paint)
            }
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

/**
 * A true, monotonic colour-mixing blur, not a diluted white overlay. Three
 * separable box passes approximate a Gaussian while each pass is O(w*h)
 * irrespective of radius. The cached frame is 960px on its long side, giving
 * enough image detail for low blur and a soft droplet-glass look at high blur.
 *
 * This runs only after a captured backdrop changes or after a coalesced slider
 * update; glyphs/outline remain outside this bitmap and never get blurred.
 */
internal object TranslationBackdropPixels {
    const val MAX_SAMPLE_LONG_SIDE = 960

    fun blur(frame: Bitmap, percent: Int): Bitmap? {
        if (frame.isRecycled || frame.width < 1 || frame.height < 1) return null
        val radius = TranslationGlassMath.blurRadius(percent)
        if (radius == 0f) return null

        val scale = minOf(1f, MAX_SAMPLE_LONG_SIDE.toFloat() /
            maxOf(frame.width, frame.height))
        val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
        val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
        val source = if (w == frame.width && h == frame.height) frame
            else Bitmap.createScaledBitmap(frame, w, h, true)

        try {
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            var input = pixels
            var output = IntArray(pixels.size)
            repeat(3) {
                horizontal(input, output, w, h, radius)
                val tmp = input; input = output; output = tmp
                vertical(input, output, w, h, radius)
                val tmp2 = input; input = output; output = tmp2
            }
            return Bitmap.createBitmap(input, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            if (source !== frame) source.recycle()
        }
    }

    /** Fractional box-filter border weights give 101 gradual intensities.
     *  A running sum makes each separable pass linear in pixel count. */
    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Float) {
        val whole = radius.toInt()
        val fraction = radius - whole
        val divisor = 2f * whole + 1f + 2f * fraction
        for (y in 0 until h) {
            val start = y * w
            var rs = 0; var gs = 0; var bs = 0
            for (k in -whole..whole) {
                val p = src[start + k.coerceIn(0, w - 1)]
                rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p)
            }
            for (x in 0 until w) {
                val l = src[start + (x - whole - 1).coerceIn(0, w - 1)]
                val r = src[start + (x + whole + 1).coerceIn(0, w - 1)]
                val red = ((rs + fraction * (Color.red(l) + Color.red(r))) / divisor)
                    .roundToInt().coerceIn(0, 255)
                val green = ((gs + fraction * (Color.green(l) + Color.green(r))) / divisor)
                    .roundToInt().coerceIn(0, 255)
                val blue = ((bs + fraction * (Color.blue(l) + Color.blue(r))) / divisor)
                    .roundToInt().coerceIn(0, 255)
                dst[start + x] = Color.rgb(red, green, blue)
                val leaving = src[start + (x - whole).coerceIn(0, w - 1)]
                val entering = src[start + (x + whole + 1).coerceIn(0, w - 1)]
                rs += Color.red(entering) - Color.red(leaving)
                gs += Color.green(entering) - Color.green(leaving)
                bs += Color.blue(entering) - Color.blue(leaving)
            }
        }
    }

    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Float) {
        val whole = radius.toInt()
        val fraction = radius - whole
        val divisor = 2f * whole + 1f + 2f * fraction
        for (x in 0 until w) {
            var rs = 0; var gs = 0; var bs = 0
            for (k in -whole..whole) {
                val p = src[k.coerceIn(0, h - 1) * w + x]
                rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p)
            }
            for (y in 0 until h) {
                val l = src[(y - whole - 1).coerceIn(0, h - 1) * w + x]
                val r = src[(y + whole + 1).coerceIn(0, h - 1) * w + x]
                val red = ((rs + fraction * (Color.red(l) + Color.red(r))) / divisor)
                    .roundToInt().coerceIn(0, 255)
                val green = ((gs + fraction * (Color.green(l) + Color.green(r))) / divisor)
                    .roundToInt().coerceIn(0, 255)
                val blue = ((bs + fraction * (Color.blue(l) + Color.blue(r))) / divisor)
                    .roundToInt().coerceIn(0, 255)
                dst[y * w + x] = Color.rgb(red, green, blue)
                val leaving = src[(y - whole).coerceIn(0, h - 1) * w + x]
                val entering = src[(y + whole + 1).coerceIn(0, h - 1) * w + x]
                rs += Color.red(entering) - Color.red(leaving)
                gs += Color.green(entering) - Color.green(leaving)
                bs += Color.blue(entering) - Color.blue(leaving)
            }
        }
    }
}
