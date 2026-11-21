package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox

/**
 * Experimental first-paint quality gate. All candidates were translated before
 * this runs. Do not draw an empty skeleton, a corrupt translation, a dropped
 * percentage, or a duplicate in exactly the same source footprint.
 *
 * Failure leaves the source visible and eligible for re-translation later;
 * it does not silently forge a guessed translation.
 */
internal object FirstPassDisplayGate {
    fun accept(boxes: List<TextBox>): List<TextBox> {
        val valid = boxes.filter { box ->
            box.bounds.width() > 0 && box.bounds.height() > 0 &&
                credible(box.sourceText, box.translatedText)
        }
        if (valid.size < 2) return valid
        return valid.filterIndexed { index, candidate ->
            val duplicate = valid.indices.any { otherIndex ->
                if (index == otherIndex) return@any false
                val other = valid[otherIndex]
                val sameSource = candidate.sourceText.trim().equals(
                    other.sourceText.trim(), ignoreCase = true)
                sameSource && overlapFraction(candidate.bounds, other.bounds) > 0.90f &&
                    (otherIndex < index)
            }
            !duplicate
        }
    }

    internal fun credible(source: String, translation: String): Boolean {
        val result = translation.trim()
        if (source.isBlank() || result.isBlank() || '\uFFFD' in result) return false
        if (result.length > 200 && result.length > source.length * 12) return false
        // A strength/stat percentage cannot disappear or become a different
        // number. Ordinary numeric labels (e.g. 2-Piece translated as words)
        // are deliberately NOT constrained by this rule.
        val values = percentageValues(source)
        if (values.isEmpty()) return true
        val translatedNumbers = numberValues(result).toMutableList()
        return values.all { number ->
            val idx = translatedNumbers.indexOf(number)
            if (idx < 0) false else {
                translatedNumbers.removeAt(idx)
                true
            }
        }
    }

    private val percentage = Regex("""([0-9٠-٩۰-۹]+(?:[.,٫][0-9٠-٩۰-۹]+)?)\s*[%٪]""")
    private val number = Regex("""[0-9٠-٩۰-۹]+(?:[.,٫][0-9٠-٩۰-۹]+)?""")

    private fun normalized(raw: String): String {
        val n = buildString {
            for (c in raw) append(when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫', ',' -> '.'
                else -> c
            })
        }
        return n.trimStart('0').ifEmpty { "0" }
    }
    private fun percentageValues(text: String) =
        percentage.findAll(text).map { normalized(it.groupValues[1]) }.toList()
    private fun numberValues(text: String) =
        number.findAll(text).map { normalized(it.value) }.toList()

    private fun overlapFraction(a: Rect, b: Rect): Float {
        val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val minArea = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        if (minArea <= 0L) return 0f
        return w.toLong() * h / minArea.toFloat()
    }
}
