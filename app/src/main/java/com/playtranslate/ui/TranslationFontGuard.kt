package com.playtranslate.ui

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import kotlin.math.floor

/**
 * Limit auto-sized translations to the OCR source's *glyph* scale instead of
 * the often over-generous destination rectangle height. The latter can include
 * menu-row padding or a transient merged OCR box; letting Android maximize font
 * into it produced 200sp Arabic covering neighbouring dialogue choices.
 *
 * Source height, not translated text length, anchors the size. Nearby peers
 * that occupy the same x lane and form a stack of UI choices supply a robust
 * median: one unusually tall OCR rectangle cannot make only one choice huge.
 */
internal object TranslationFontGuard {
    fun sourceCeilingSp(
        sourceLineHeightPx: Float,
        displayScaleY: Float,
        scaledDensity: Float,
        minSp: Int,
        globalMaxSp: Int,
        peerMedianHeightPx: Float? = null,
    ): Int {
        val safeHeight = sourceLineHeightPx.coerceAtLeast(1f)
        val stable = if (peerMedianHeightPx != null && peerMedianHeightPx > 0f)
            minOf(safeHeight, peerMedianHeightPx * 1.2f) else safeHeight
        val px = stable * displayScaleY.coerceAtLeast(0.01f)
        // Arabic and Latin can have different glyph proportions; 15% headroom
        // retains legibility while protecting the source's menu-row footprint.
        val cap = floor(px * 1.15f / scaledDensity.coerceAtLeast(0.5f)).toInt()
        return cap.coerceIn(minSp + 1, globalMaxSp)
    }

    fun medianPeerHeight(candidate: TextBox, boxes: List<TextBox>): Float? {
        if (candidate.orientation != TextOrientation.HORIZONTAL || candidate.isFurigana ||
            candidate.angleDeg != 0f) return null
        val rect = candidate.bounds
        val ownHeight = (rect.height().toFloat() / candidate.lineCount.coerceAtLeast(1))
            .coerceAtLeast(1f)
        val peers = boxes.filter { other ->
            if (other === candidate || other.orientation != TextOrientation.HORIZONTAL ||
                other.isFurigana || other.angleDeg != 0f || other.translatedText.isBlank()) return@filter false
            val b = other.bounds
            val overlapX = minOf(rect.right, b.right) - maxOf(rect.left, b.left)
            val minWidth = minOf(rect.width(), b.width()).coerceAtLeast(1)
            val yGap = kotlin.math.abs(rect.centerY() - b.centerY()).toFloat()
            overlapX >= minWidth * 0.40f &&
                yGap <= 6f * maxOf(ownHeight, b.height().toFloat() / other.lineCount.coerceAtLeast(1))
        }
        val peerHeights = peers.map {
            it.bounds.height().toFloat() / it.lineCount.coerceAtLeast(1)
        }.filter { it > 0f }
        if (peerHeights.size == 1) {
            // An incomplete three-answer menu may leave two OCR boxes:
            // "Who are you?" and "What are you doing here?" (Bye missing).
            // The second must not balloon just because its OCR height is
            // corrupted. Restrict this asymmetric clamp to TWO question rows.
            return pairedQuestionReferenceHeight(
                candidate.sourceText, peers[0].sourceText,
                ownHeight, peerHeights.single()
            )
        }
        if (peerHeights.size < 2) return null
        val sorted = (peerHeights + ownHeight).sorted()
        return sorted[sorted.size / 2]
    }

    /** A one-neighbour rescue is safe only for two independently punctuated
     *  questions whose OCR heights disagree strongly. It deliberately does
     *  not clamp a genuine large heading next to a small body paragraph. */
    fun pairedQuestionReferenceHeight(
        candidateText: String,
        peerText: String,
        candidateHeightPx: Float,
        peerHeightPx: Float,
    ): Float? {
        fun isQuestion(t: String): Boolean =
            t.trim().endsWith("?") || t.trim().endsWith("؟")
        if (!isQuestion(candidateText) || !isQuestion(peerText)) return null
        if (peerHeightPx <= 0f || candidateHeightPx <= peerHeightPx * 1.5f) return null
        return peerHeightPx
    }
}
