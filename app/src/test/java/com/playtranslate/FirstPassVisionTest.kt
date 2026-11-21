package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstPassVisionTest {
    @Test fun adaptsWithoutExtraRecognition() {
        assertEquals(9f, FirstPassVision.sigmoidStrength(90, 130, 5))
        assertEquals(5.8f, FirstPassVision.sigmoidStrength(10, 230, 8))
        assertEquals(5.8f, FirstPassVision.sigmoidStrength(10, 150, 70))
        assertEquals(7f, FirstPassVision.sigmoidStrength(25, 185, 5))
    }
}
