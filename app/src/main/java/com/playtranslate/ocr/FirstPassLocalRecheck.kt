package com.playtranslate.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.engines.mlkit.MlKitOcr
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Best-effort local verification, never a second full-frame OCR. ML Kit only:
 * the native multi-model OCR engines can exceed the entire budget on a crop.
 *
 * A crop is considered only when the original OCR was already fast, the line
 * has known low confidence, and the region is bounded. No timer cancels a
 * native OCR call or recycles a bitmap while ML Kit still owns its pixels.
 * At most one ROI and one attempt per 3 seconds across cycles.
 */
internal object FirstPassLocalRecheck {
    private val lastAttemptMs = AtomicLong(-10_000L)

    fun eligible(r: RecognizedRegion, width: Int, height: Int): Boolean {
        val box = r.box.bounds
        return r.lines.size == 1 &&
            !r.box.isRotated && r.confidence in 0.20f..0.53f &&
            r.text.length in 5..90 &&
            box.width() in 30..850 && box.height() in 14..170 &&
            box.width() < width * 0.65f && box.height() < height * 0.22f &&
            box.left >= 0 && box.top >= 0 &&
            box.right <= width && box.bottom <= height
    }

    suspend fun improve(
        engine: OcrEngine,
        bitmap: Bitmap,
        regions: List<RecognizedRegion>,
        sourceLang: String,
        angleGate: Float,
        fullOcrMs: Long,
    ): List<RecognizedRegion> {
        // This is an exception, not a hidden second full-screen scan.
        if (engine !is MlKitOcr || fullOcrMs > 260L) return regions
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val prior = lastAttemptMs.get()
            if (now - prior < 3_000L) return regions
            if (lastAttemptMs.compareAndSet(prior, now)) break
        }
        val index = regions.indices
            .filter { eligible(regions[it], bitmap.width, bitmap.height) }
            .minByOrNull { regions[it].confidence }
            ?: return regions
        val original = regions[index]
        val rect = original.box.bounds
        val margin = minOf(14, maxOf(4, rect.height() / 6))
        val left = (rect.left - margin).coerceAtLeast(0)
        val top = (rect.top - margin).coerceAtLeast(0)
        val right = (rect.right + margin).coerceAtMost(bitmap.width)
        val bottom = (rect.bottom + margin).coerceAtMost(bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        try {
            val reread = runCatching {
                engine.recognize(OcrImage(crop, sourceLang, angleNoiseGateDeg = angleGate))
            }.getOrNull() ?: return regions
            // Neighbouring lines entering a crop make a substitution unsafe.
            if (reread.size != 1) return regions
            val better = reread[0]
            if (better.lines.size != 1 || better.confidence < original.confidence + 0.18f ||
                better.text.isBlank() || !similarEnough(original.text, better.text)) return regions
            val corrected = original.copy(
                text = better.text,
                confidence = better.confidence,
                lines = listOf(original.lines.single().copy(
                    text = better.text, confidence = better.confidence,
                    chars = emptyList(), elements = emptyList(),
                )),
            )
            return regions.toMutableList().also { it[index] = corrected }
        } finally {
            crop.recycle()
        }
    }

    /** Prevent a crop containing a different neighbouring label from replacing
     *  the old line. Valid single-glyph corrections pass; wholesale rewrites do not. */
    internal fun similarEnough(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > maxOf(3, a.length / 3)) return false
        val aa = a.lowercase().filter { it.isLetterOrDigit() }
        val bb = b.lowercase().filter { it.isLetterOrDigit() }
        if (aa.isEmpty() || bb.isEmpty()) return false
        val counts = aa.groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (ch in bb) {
            val n = counts[ch] ?: 0
            if (n > 0) { counts[ch] = n - 1; common++ }
        }
        return common.toFloat() / maxOf(aa.length, bb.length) >= 0.60f
    }
}
