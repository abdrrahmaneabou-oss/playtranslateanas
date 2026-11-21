package com.playtranslate.ocr.core

/** Conservative three-row dialogue-choice recognition. Never invents a row:
 *  only affects groups already recognized by OCR. */
internal object SpacedDialogueChoices {
    fun isChoiceStack(
        texts: List<String>,
        lefts: List<Double>,
        centers: List<Double>,
        width: Double,
        screenWidth: Float,
        em: Double,
    ): Boolean {
        if (texts.size !in 3..5 || texts.size != lefts.size || lefts.size != centers.size ||
            screenWidth <= 0f || em <= 0.0) return false
        if (texts.count { it.trimEnd().endsWith("?") || it.trimEnd().endsWith("؟") } < 2)
            return false
        if (texts.any { it.length > 95 }) return false
        if (width > screenWidth * 0.38) return false
        if (lefts.maxOrNull()!! - lefts.minOrNull()!! > em * 1.0) return false
        val pitches = centers.zipWithNext { a, b -> b - a }
        if (pitches.any { it < em * 1.35 }) return false
        return true
    }
}
