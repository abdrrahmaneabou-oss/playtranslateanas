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
        // The experiment targets spaced-script UI descriptions; other scripts
        // keep the corpus-tested FlowGraph result untouched.
        if (!ctx.spacedScript || regions.size > 180) return baseline

        // Three independent partitions of the ONE recognized frame. The
        // calibrated FlowGraph is authoritative unless the other strategies
        // supply whole-group coverage plus strong geometry/language evidence.
        val candidates = if (baseline.size >= 2) (
            exploratory.group(regions, ctx) +
                DefaultGroupingStrategy().group(regions, ctx)
            ) else emptyList()
        val owners = IdentityHashMap<RecognizedRegion, Int>()
        baseline.forEachIndexed { index, group ->
            group.regions.forEach { owners[it] = index }
        }
        val used = HashSet<Int>()
        val replacements = HashMap<Int, ProposedGroup>()

        for (candidate in candidates) {
            if (candidate.frame != null || candidate.regions.isEmpty()) continue
            val indices = candidate.regions.mapNotNull { owners[it] }.distinct()
            if (indices.size !in 2..3 || indices.any { it in used }) continue
            val parts = indices.sortedBy { boundsOf(baseline[it]).top }.map { baseline[it] }
            val first = parts.first()
            val second = parts.last()
            // Never consume part of a previously accepted group, change a
            // slanted group, or merge a candidate that did not cover both
            // original groups in their entirety.
            if (parts.any { it.frame != null }) continue
            if (parts.sumOf { it.regions.size } != candidate.regions.size) continue
            if (parts.any { p -> p.regions.any { it !in candidate.regions } }) continue
            if (parts.flatMap { it.regions }.any {
                    it.orientation != com.playtranslate.language.TextOrientation.HORIZONTAL
                }) continue
            if (!parts.zipWithNext().all { (top, bottom) ->
                shouldMerge(
                    top.regions.joinToString(" ") { it.text },
                    bottom.regions.joinToString(" ") { it.text },
                    boundsOf(top), boundsOf(bottom),
                )
            }) continue
            // Do not allow a many-to-one giant box even when the exploratory
            // grouping groups several plausible neighbours.
            val merged = boundsOf(candidate)
            val maxHeight = parts.sumOf { boundsOf(it).height() }
            if (merged.height() > maxHeight * 1.75f) continue
            used.addAll(indices)
            val topIndex = indices.minBy { boundsOf(baseline[it]).top }
            replacements[topIndex] = ProposedGroup(candidate.regions)
        }

        val refined = if (replacements.isEmpty()) baseline
            else baseline.flatMapIndexed { i, group ->
            when {
                i in replacements -> listOf(replacements.getValue(i))
                i in used -> emptyList()
                else -> listOf(group)
            }
        }
        return refined.flatMap { splitClearSectionHeadings(it) }
    }

    /** A menu title and a numbered description are distinct units even when
     *  some OCR segmentation hypothesis erroneously joins them. */
    private fun splitClearSectionHeadings(group: ProposedGroup): List<ProposedGroup> {
        if (group.frame != null || group.regions.size < 2) return listOf(group)
        val parts = group.regions
        val cut = (1 until parts.size).firstOrNull { i ->
            val t = parts[i].text.trim()
            val previous = parts[i - 1].text.trim()
            // English-like numbered UI paragraphs (e.g. 2-Piece Set).
            Regex("^\\d+\\s*[-–]\\s*[\\p{L} ]{3,25}[:：]", RegexOption.IGNORE_CASE)
                .containsMatchIn(t) &&
                previous.none { it == ':' || it == '：' } &&
                parts[i].box.bounds.top > parts[i - 1].box.bounds.top
        } ?: return listOf(group)
        return listOf(ProposedGroup(parts.take(cut)), ProposedGroup(parts.drop(cut)))
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
