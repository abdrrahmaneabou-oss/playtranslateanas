package com.playtranslate.ocr.core

import android.graphics.Rect
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Optional, single-frame grouping refinement for live capture.
 *
 * The production FlowGraph remains authoritative. A more permissive grouping
 * candidate is computed from the SAME recognized regions (never another OCR
 * pass); we only accept candidate merges of exactly two complete FlowGraph
 * groups when geometry AND written-continuation evidence agree. There is no
 * dependence on the previous frame or on translations arriving later.
 *
 * Disabled by default from Capture & overlay; legacy grouping uses no instance
 * of this strategy at all.
 */
class FirstPassGroupingStrategy : GroupingStrategy {
    override val name: String = "first-pass-experimental"

    private val production = FlowGraphStrategy()
    // Exploration is not a replacement for the calibrated production rules:
    // the guard below rejects the dangerous menu/title and distant-line joins.
    private val exploratory = FlowGraphStrategy(
        pitchSolo = 2.40f,
        pitchCeiling = 2.50f,
        contSolo = 2.45f,
        rhythmTol = 0.28f,
        listEnabled = false,
    )

    override fun group(
        regions: List<RecognizedRegion>,
        ctx: GroupingContext,
    ): List<ProposedGroup> {
        val baseline = production.group(regions, ctx)
        // Keep all unspaced scripts, rotated boxes, and tiny reads on the
        // calibrated path. The experiment targets line-wrapped UI descriptions.
        if (!ctx.spacedScript || baseline.size < 2 || regions.size > 200) return baseline

        val candidates = exploratory.group(regions, ctx)
        val owners = IdentityHashMap<RecognizedRegion, Int>()
        baseline.forEachIndexed { index, group ->
            group.regions.forEach { owners[it] = index }
        }
        val used = HashSet<Int>()
        val replacements = HashMap<Int, ProposedGroup>()

        for (candidate in candidates) {
            if (candidate.frame != null || candidate.regions.isEmpty()) continue
            val indices = candidate.regions.mapNotNull { owners[it] }.distinct()
            if (indices.size != 2 || indices.any { it in used }) continue
            val first = baseline[indices[0]]
            val second = baseline[indices[1]]
            // Never consume part of a previously accepted group, change a
            // slanted group, or merge a candidate that did not cover both
            // original groups in their entirety.
            if (first.frame != null || second.frame != null) continue
            if (first.regions.size + second.regions.size != candidate.regions.size) continue
            if (first.regions.any { it !in candidate.regions } ||
                second.regions.any { it !in candidate.regions }) continue
            if ((first.regions + second.regions).any {
                    it.orientation != com.playtranslate.language.TextOrientation.HORIZONTAL
                }) continue
            val topIndex = indices.minBy { boundsOf(baseline[it]).top }
            val bottomIndex = indices.first { it != topIndex }
            val top = baseline[topIndex]
            val bottom = baseline[bottomIndex]
            if (!shouldMerge(
                    top.regions.joinToString(" ") { it.text },
                    bottom.regions.joinToString(" ") { it.text },
                    boundsOf(top), boundsOf(bottom),
                )) continue
            used.addAll(indices)
            // Keep the candidate's reading order but no inherited menu-column
            // pin: these two units are a wrapped sentence, not menu items.
            replacements[topIndex] = ProposedGroup(candidate.regions)
        }

        if (replacements.isEmpty()) return baseline
        return baseline.flatMapIndexed { i, group ->
            when {
                i in replacements -> listOf(replacements.getValue(i))
                i in used -> emptyList()
                else -> listOf(group)
            }
        }
    }

    private fun boundsOf(group: ProposedGroup): Rect = Rect(
        group.regions.minOf { it.box.bounds.left },
        group.regions.minOf { it.box.bounds.top },
        group.regions.maxOf { it.box.bounds.right },
        group.regions.maxOf { it.box.bounds.bottom },
    )

    /**
     * Conservative, explainable continuation evidence for the candidate
     * merger. Colon-with-body describes a wrapped item (e.g.
     * "2-Piece Set: Elemental" followed by "Burst DMG +20%"); a bare menu
     * heading ("Noblesse Oblige") must remain separate.
     */
    internal companion object {
        fun shouldMerge(upperText: String, lowerText: String, upper: Rect, lower: Rect): Boolean {
            val a = upperText.trim()
            val b = lowerText.trim()
            if (a.length < 8 || b.length < 3 || !a.any { it.isLetter() } ||
                !b.any { it.isLetter() }) return false
            val upperH = upper.height()
            val lowerH = lower.height()
            if (upperH <= 0 || lowerH <= 0) return false
            val large = maxOf(upperH, lowerH).toFloat()
            val small = minOf(upperH, lowerH).toFloat()
            if (large / small > 1.5f) return false
            val gap = lower.top - upper.bottom
            if (gap < -(small * 0.20f) || gap > large * 1.35f) return false
            if (lower.centerY() <= upper.centerY()) return false

            val leftAligned = abs(upper.left - lower.left) <= large * 1.5f
            val rightAligned = abs(upper.right - lower.right) <= large * 1.5f
            if (!leftAligned && !rightAligned) return false

            if (a.last() in ".!?。！？") return false
            val colon = a.indexOfAny(charArrayOf(':', '：'))
            val headingWithBody = colon >= 0 && colon < a.length - 3 &&
                a.substring(colon + 1).any { it.isLetter() }
            val lowercaseContinuation = b.firstOrNull { it.isLetter() }?.isLowerCase() == true
            val trailingContinuation = a.last() in ",،;؛-–("
            return headingWithBody || lowercaseContinuation || trailingContinuation
        }
    }
}
