package com.playtranslate.ui

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One-to-one user-facing slider mapping. These functions are intentionally
 * Android-free so the end points, monotonicity and fade coverage are tested
 * without a device.
 */
internal object TranslationGlassMath {
    /** Percent is TRANSPARENCY, not opacity: 0 = opaque, 100 = no tint. */
    fun tintAlpha(transparentPercent: Int): Int =
        (255f * (100 - transparentPercent.coerceIn(0, 100)) / 100f).roundToInt()

    /** Each side extends by 1–15% of the text box's own selected width. */
    fun horizontalOverhang(boxWidth: Float, percentPerSide: Int): Float =
        boxWidth.coerceAtLeast(0f) * percentPerSide.coerceIn(1, 15) / 100f

    /** The horizontal fading band must end BEFORE the source rectangle starts,
     *  so the original game glyphs never appear through a feathered side.
     *  Vertical fading still uses the pre-existing independent feather width. */
    fun horizontalFeatherWidth(extendedWidth: Float, sideOverhang: Float, fadePercent: Int): Float {
        if (fadePercent <= 0 || sideOverhang <= 0f) return 0f
        return minOf(extendedWidth * featherFraction(fadePercent), sideOverhang).coerceAtLeast(0f)
    }

    /** Broad feather: even 10% spreads over ~19% of either side, while
     *  100% still reserves a visible central core for text and pinhole
     *  change detection. No hard cutoff is introduced inside the band. */
    fun featherFraction(percent: Int): Float {
        val p = percent.coerceIn(0, 100)
        if (p == 0) return 0f
        return (0.07 + 0.37 * sqrt(p / 100.0)).toFloat()
    }

    /** Smoothstep-shaped alpha removal: 1 at the outer edge, 0 deep inside.
     *  Used to form shader stops with a flat slope at BOTH ends. */
    fun erasedAt(normalizedDistanceFromEdge: Float): Int {
        val x = normalizedDistanceFromEdge.coerceIn(0f, 1f)
        val smooth = x * x * (3f - 2f * x)
        return ((1f - smooth) * 255f).roundToInt().coerceIn(0, 255)
    }
}
