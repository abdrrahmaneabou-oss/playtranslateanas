package com.playtranslate.ui

import android.graphics.RectF

/**
 * Experimental final geometry polish after all legacy passes. Does not merge
 * unrelated text, move source footprints, or alter rotated chips. Only trims
 * *padding* in places where two distinct source rectangles are already
 * disjoint and the padded/resized presentation still overlaps.
 */
internal object FirstPassLayoutOptimizer {
    fun refine(
        resolved: List<ResolvedBox>,
        source: List<RectF>,
        boxes: List<TextBox>,
    ): List<ResolvedBox> {
        if (resolved.size != source.size || boxes.size != resolved.size ||
            resolved.size < 2 || resolved.size > 80) return resolved
        val rects = resolved.map { RectF(it.rect) }
        val touched = BooleanArray(resolved.size)
        for (i in rects.indices) {
            if (resolved[i].chip != null || boxes[i].isFurigana) continue
            for (j in i + 1 until rects.size) {
                if (resolved[j].chip != null || boxes[j].isFurigana) continue
                val a = rects[i]
                val b = rects[j]
                if (overlapArea(a, b) <= 0f) continue
                val sa = source[i]
                val sb = source[j]
                // Sources on independent rows or columns may have padded
                // overlap. Choose the axis that loses the least padding area.
                val upDown = sa.bottom <= sb.top || sb.bottom <= sa.top
                val leftRight = sa.right <= sb.left || sb.right <= sa.left
                if (!upDown && !leftRight) continue
                val vA = RectF(a); val vB = RectF(b)
                if (upDown) {
                    val mid = if (sa.bottom <= sb.top) (sa.bottom + sb.top) / 2f
                        else (sb.bottom + sa.top) / 2f
                    if (sa.bottom <= sb.top) {
                        vA.bottom = minOf(vA.bottom, mid)
                        vB.top = maxOf(vB.top, mid)
                    } else {
                        vB.bottom = minOf(vB.bottom, mid)
                        vA.top = maxOf(vA.top, mid)
                    }
                }
                val hA = RectF(a); val hB = RectF(b)
                if (leftRight) {
                    val mid = if (sa.right <= sb.left) (sa.right + sb.left) / 2f
                        else (sb.right + sa.left) / 2f
                    if (sa.right <= sb.left) {
                        hA.right = minOf(hA.right, mid)
                        hB.left = maxOf(hB.left, mid)
                    } else {
                        hB.right = minOf(hB.right, mid)
                        hA.left = maxOf(hA.left, mid)
                    }
                }
                val before = overlapArea(a, b)
                val vImprovement = if (upDown && covers(vA, sa) && covers(vB, sb))
                    before - overlapArea(vA, vB) else 0f
                val hImprovement = if (leftRight && covers(hA, sa) && covers(hB, sb))
                    before - overlapArea(hA, hB) else 0f
                if (vImprovement <= 0f && hImprovement <= 0f) continue
                val chooseVertical = vImprovement >= hImprovement
                val nextA = if (chooseVertical) vA else hA
                val nextB = if (chooseVertical) vB else hB
                a.set(nextA); b.set(nextB)
                touched[i] = true; touched[j] = true
            }
        }
        if (touched.none { it }) return resolved
        return resolved.mapIndexed { i, b ->
            if (touched[i]) b.copy(rect = rects[i], floorPx = null, grownFrom = null)
            else b
        }
    }

    internal fun covers(outer: RectF, inner: RectF): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom &&
            outer.width() > 0f && outer.height() > 0f

    internal fun overlapArea(a: RectF, b: RectF): Float {
        val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return w * h
    }
}
