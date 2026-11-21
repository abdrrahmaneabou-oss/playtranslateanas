package com.playtranslate.ocr.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedDialogueChoicesTest {
    @Test fun genshinThreeAnswersRemainSeparate() {
        assertTrue(SpacedDialogueChoices.isChoiceStack(
            listOf("Who are you?", "What are you doing here?", "...Bye."),
            listOf(950.0, 950.0, 951.0),
            listOf(340.0, 403.0, 465.0),
            width = 360.0, screenWidth = 1536f, em = 30.0
        ))
    }

    @Test fun paragraphLinesAreNotTreatedAsOptions() {
        assertFalse(SpacedDialogueChoices.isChoiceStack(
            listOf("Who are you?", "What are you doing here?", "...Bye."),
            listOf(950.0, 950.0, 950.0),
            listOf(340.0, 367.0, 393.0),
            width = 360.0, screenWidth = 1536f, em = 30.0
        ))
    }

    @Test fun plainTextProseIsNotSplit() {
        assertFalse(SpacedDialogueChoices.isChoiceStack(
            listOf("The weapon is great.", "It gives more damage.", "Use it often."),
            listOf(950.0, 950.0, 950.0),
            listOf(340.0, 403.0, 465.0),
            width = 360.0, screenWidth = 1536f, em = 30.0
        ))
    }
}
