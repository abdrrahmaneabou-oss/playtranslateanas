package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Cheap, bounded, SAME-FRAME visual analysis. Chooses among existing sigmoid
 * recipes without allocating alternative full-size bitmaps or running more OCR.
 * Non-default/user-selected recipes are left unchanged. The experiment is
 * activated only by the OcrPipeline's firstPassOptimization gate.
 */
internal object FirstPassVision {
    fun select(bitmap: Bitmap, requested: OcrPreprocessingRecipe): OcrPreprocessingRecipe {
        if (requested !== OcrPreprocessingRecipe.Default) return requested
        if (bitmap.width < 24 || bitmap.height < 24) return requested
        val luminances = IntArray(12 * 12)
        var chromatic = 0
        var index = 0
        for (y in 0 until 12) {
            val py = (y * (bitmap.height - 1) / 11)
            for (x in 0 until 12) {
                val px = (x * (bitmap.width - 1) / 11)
                val pixel = bitmap.getPixel(px, py)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                luminances[index++] = (54 * red + 183 * green + 19 * blue) / 256
                if (maxOf(red, green, blue) - minOf(red, green, blue) > 68) chromatic++
            }
        }
        luminances.sort()
        val low = luminances[14]
        val high = luminances[129]
        val steepness = sigmoidStrength(low, high, chromatic)
        return if (steepness == 7f) requested
        else OcrPreprocessingRecipe.SigmoidContrast(steepness, InvertMode.AUTO)
    }

    /** Lower k preserves anti-aliasing on high-contrast/coloured game screens;
     *  stronger k separates glyphs on low-contrast panels. */
    internal fun sigmoidStrength(low: Int, high: Int, coloredSamples: Int): Float {
        val span = (high - low).coerceAtLeast(0)
        return when {
            span < 62 -> 9f
            span > 198 || (span > 135 && coloredSamples >= 55) -> 5.8f
            else -> 7f
        }
    }
}
