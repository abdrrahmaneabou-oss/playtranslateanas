package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirstPassDisplayGateTest {
    @Test fun refusesBlanksAndCorruption() {
        assertFalse(FirstPassDisplayGate.credible("ATK", ""))
        assertFalse(FirstPassDisplayGate.credible("ATK", "��"))
        assertTrue(FirstPassDisplayGate.credible("ATK", "الهجوم"))
    }

    @Test fun preservesPercentNumbersAcrossArabicDigitVariants() {
        assertTrue(FirstPassDisplayGate.credible(
            "Elemental Burst DMG +20%", "ضرر انفجار العنصر +٢٠٪"))
        assertFalse(FirstPassDisplayGate.credible(
            "Elemental Burst DMG +20%", "ضرر انفجار العنصر +٣٠٪"))
        // The item count may be spelled out in Arabic, so only the % is fixed.
        assertTrue(FirstPassDisplayGate.credible(
            "2-Piece Set: ATK +20%", "مكافأة قطعتين: الهجوم +٢٠٪"))
    }

    @Test fun suppressesDuplicateBoxesButNotOtherSources() {
        val a = TextBox(translatedText="الهجوم", sourceText="ATK",bounds=Rect(0,0,70,20))
        val b = a.copy()
        val c = TextBox(translatedText="الدفاع", sourceText="DEF",bounds=Rect(0,22,70,42))
        assertEquals(2, FirstPassDisplayGate.accept(listOf(a,b,c)).size)
    }
}
