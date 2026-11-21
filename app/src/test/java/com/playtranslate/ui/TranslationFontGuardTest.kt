package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationFontGuardTest {
    @Test fun regularMenuLabelStaysNearSourceScale() {
        // 36 screenshot px at scaledDensity 2 -> max around 20sp, not 200sp.
        assertEquals(20, TranslationFontGuard.sourceCeilingSp(36f, 1f, 2f, 12, 96))
    }

    @Test fun oversizedOcrBoxDoesNotEnlargeOneDialogueOption() {
        // Two adjacent options are only 34px high. One erroneously measures
        // 180px; the stable peer median bounds its typography.
        assertEquals(23, TranslationFontGuard.sourceCeilingSp(180f, 1f, 2f, 12, 96, 34f))
    }

    @Test fun trueLargeSourceTextCanStillBeLarge() {
        assertEquals(69, TranslationFontGuard.sourceCeilingSp(120f, 1f, 2f, 12, 96))
    }

    @Test fun minimumRemainsLegibleIfOcrHeightIsBad() {
        assertEquals(13, TranslationFontGuard.sourceCeilingSp(1f, 1f, 2f, 12, 96))
    }

    @Test fun twoQuestionChoicesAreProtectedWhenThirdIsMissing() {
        assertEquals(34f, TranslationFontGuard.pairedQuestionReferenceHeight(
            "What are you doing here?", "Who are you?", 180f, 34f))
    }

    @Test fun headingNearDialogueIsNotForcedSmall() {
        org.junit.Assert.assertNull(TranslationFontGuard.pairedQuestionReferenceHeight(
            "Character", "Who are you?", 180f, 34f))
    }
}
