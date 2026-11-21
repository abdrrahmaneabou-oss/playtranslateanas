package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationGlassMathTest {
    @Test fun zeroTransparencyIsFullyOpaqueFill() {
        assertEquals(255, TranslationGlassMath.tintAlpha(0))
        assertEquals(0, TranslationGlassMath.tintAlpha(100))
        assertEquals(128, TranslationGlassMath.tintAlpha(50))
    }

    @Test fun horizontalExtensionDependsOnEachTextWidth() {
        assertEquals(5f, TranslationGlassMath.horizontalOverhang(100f, 5), 0f)
        assertEquals(10f, TranslationGlassMath.horizontalOverhang(200f, 5), 0f)
        assertEquals(20f, TranslationGlassMath.horizontalOverhang(400f, 5), 0f)
        assertEquals(15f, TranslationGlassMath.horizontalOverhang(100f, 15), 0f)
        assertEquals(1f, TranslationGlassMath.horizontalOverhang(100f, 0), 0f)
    }

    @Test fun horizontalFadeNeverReachesTheOriginalTextFootprint() {
        for (width in listOf(40f, 100f, 200f, 400f)) {
            for (expansion in 1..15) {
                val extra = TranslationGlassMath.horizontalOverhang(width, expansion)
                for (fade in 1..100) {
                    val band = TranslationGlassMath.horizontalFeatherWidth(
                        width + 2f * extra, extra, fade
                    )
                    assertTrue("Horizontal fade leaked into text width=$width expansion=$expansion fade=$fade",
                        band <= extra)
                    assertTrue("Horizontal feather must have positive span", band > 0f)
                }
            }
        }
    }

    @Test fun fadeIsContinuousBroadAndMonotone() {
        assertEquals(0f, TranslationGlassMath.featherFraction(0), 0f)
        assertTrue(TranslationGlassMath.featherFraction(10) >= 0.18f)
        assertTrue(TranslationGlassMath.featherFraction(100) > 0.4f)
        val all = (0..100).map(TranslationGlassMath::featherFraction)
        assertTrue(all.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(255, TranslationGlassMath.erasedAt(0f))
        assertEquals(0, TranslationGlassMath.erasedAt(1f))
        assertTrue(kotlin.math.abs(TranslationGlassMath.erasedAt(0.5f) - 128) <= 1)
        assertTrue((0..100).zipWithNext().all { (a, b) ->
            TranslationGlassMath.erasedAt(a / 100f) >=
                TranslationGlassMath.erasedAt(b / 100f)
        })
    }
}
