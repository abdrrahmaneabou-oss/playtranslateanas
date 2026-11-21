package com.playtranslate.ocr.core

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirstPassGroupingStrategyTest {
    private val first = Rect(900, 200, 1250, 234)
    private val next = Rect(900, 242, 1160, 276)

    @Test fun acceptsSingleFrameWrappedBonusDescription() {
        assertTrue(FirstPassGroupingStrategy.shouldMerge(
            "2-Piece Set: Elemental", "Burst DMG +20%", first, next))
    }

    @Test fun protectsIndependentMenuRows() {
        assertFalse(FirstPassGroupingStrategy.shouldMerge(
            "Noblesse Oblige", "Berserker", first, next))
        assertFalse(FirstPassGroupingStrategy.shouldMerge(
            "Noblesse Oblige", "2-Piece Set: Elemental", first, next))
    }

    @Test fun rejectsDistantLinesAndOppositeColumns() {
        assertFalse(FirstPassGroupingStrategy.shouldMerge(
            "2-Piece Set: Elemental", "Burst DMG +20%",
            first, Rect(900, 400, 1160, 434)))
        assertFalse(FirstPassGroupingStrategy.shouldMerge(
            "2-Piece Set: Elemental", "Burst DMG +20%",
            first, Rect(150, 242, 420, 276)))
    }

    @Test fun acceptsClearLowercaseContinuationButNotHugeScaleDrift() {
        assertTrue(FirstPassGroupingStrategy.shouldMerge(
            "You have received", "another reward", first, next))
        assertFalse(FirstPassGroupingStrategy.shouldMerge(
            "2-Piece Set: Elemental", "Burst DMG +20%",
            first, Rect(900, 240, 1100, 340)))
    }
}
