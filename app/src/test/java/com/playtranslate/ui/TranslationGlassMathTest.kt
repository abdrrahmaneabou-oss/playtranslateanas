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

    @Test fun zeroBlurIsDisabledAndSliderNeverGetsWeaker() {
        assertEquals(0, TranslationGlassMath.blurRadius(0))
        val all = (0..100).map(TranslationGlassMath::blurRadius)
        assertTrue(all.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(TranslationGlassMath.blurRadius(100) > TranslationGlassMath.blurRadius(10))
        assertTrue(TranslationGlassMath.blurRadius(10) > 0)
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
