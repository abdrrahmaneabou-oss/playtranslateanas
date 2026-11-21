package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirstPassLayoutOptimizerTest {
    @Test fun trimsOnlyExtraPaddingWithoutExposingSourceText() {
        val source = listOf(RectF(10f,10f,110f,30f), RectF(10f,38f,110f,58f))
        val initial = listOf(
            ResolvedBox(RectF(4f,4f,116f,40f), RenderMode.LEGACY_HORIZONTAL),
            ResolvedBox(RectF(4f,26f,116f,64f), RenderMode.LEGACY_HORIZONTAL),
        )
        val boxes = listOf(
            TextBox("مترجم",Rect(10,10,110,30)),
            TextBox("مترجم",Rect(10,38,110,58)),
        )
        val polished = FirstPassLayoutOptimizer.refine(initial,source,boxes)
        assertTrue(FirstPassLayoutOptimizer.covers(polished[0].rect,source[0]))
        assertTrue(FirstPassLayoutOptimizer.covers(polished[1].rect,source[1]))
        assertEquals(0f,FirstPassLayoutOptimizer.overlapArea(polished[0].rect,polished[1].rect),0f)
        assertTrue(initial[0].rect.bottom == 40f) // old branch unmodified
    }
}
