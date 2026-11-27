// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.engine.InputEngine
import dev.duoime.core.model.CandidateItem
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.WindowCandidateItem
import java.util.TreeMap

/**
 * Pure-JVM multi-page candidate accumulator layered cleanly on top of the existing [InputEngine]
 * `page(forward)` + `selectCandidate(indexOnPage)` contract (zero changes to [InputEngine]).
 *
 * - Accumulates consecutive pages into [items] for smooth horizontal scrolling (`CandidatePaging.SCROLL`).
 * - Restores the backing engine page to its previous position after [loadMore] so `KeyAction.Space`
 *   always commits the primary candidate on page 0 rather than a pre-fetched trailing page.
 * - Steps `engine.page(...)` cleanly to `target.pageIndex` before delegating [selectWindowCandidate].
 */
internal class CandidateWindowTracker(
    private val maxPages: Int = MAX_WINDOW_PAGES
) {
    private data class CompositionKey(
        val mode: InputMode,
        val variant: ChineseVariant,
        val schemaId: String,
        val preedit: String,
        val selectedSyllableIndex: Int,
        val syllables: List<String>
    )

    private val pages = TreeMap<Int, List<CandidateItem>>()
    private var lastKey: CompositionKey? = null

    var items: List<WindowCandidateItem> = emptyList()
        private set

    var hasMore: Boolean = false
        private set

    fun syncWithSnapshot(snapshot: EngineSnapshot, forceReset: Boolean = false) {
        if (!snapshot.isComposing && snapshot.candidates.isEmpty()) {
            reset()
            return
        }
        val key = compositionKey(snapshot)
        if (forceReset || key != lastKey || pages.isEmpty()) {
            pages.clear()
            lastKey = key
            if (snapshot.candidates.isNotEmpty()) {
                pages[snapshot.pageIndex] = snapshot.candidates
            }
            hasMore = snapshot.hasNextPage && pages.size < maxPages
            rebuildItems()
            return
        }
        if (snapshot.candidates.isNotEmpty()) {
            pages[snapshot.pageIndex] = snapshot.candidates
            val highestPage = pages.lastKey()
            if (snapshot.pageIndex >= highestPage) {
                hasMore = snapshot.hasNextPage && pages.size < maxPages
            }
            rebuildItems()
        }
    }

    fun loadMore(engine: InputEngine): Boolean {
        if (!hasMore || pages.isEmpty()) return false
        val restorePage = engine.state.value.pageIndex
        val highestLoaded = pages.lastKey()
        if (!stepEngineToPage(engine, highestLoaded)) {
            hasMore = false
            stepEngineToPage(engine, restorePage)
            return false
        }
        val paged = engine.page(forward = true)
        val nextSnap = engine.state.value
        val added = if (paged && nextSnap.pageIndex > highestLoaded && nextSnap.candidates.isNotEmpty()) {
            pages[nextSnap.pageIndex] = nextSnap.candidates
            hasMore = nextSnap.hasNextPage && pages.size < maxPages
            rebuildItems()
            true
        } else {
            hasMore = false
            false
        }
        // Keep the backing engine on its prior page (e.g. page 0) so Space always commits primary.
        stepEngineToPage(engine, restorePage)
        return added
    }

    fun selectWindowCandidate(engine: InputEngine, globalIndex: Int): KeyProcessResult {
        val target = items.getOrNull(globalIndex) ?: return KeyProcessResult.PassThrough
        if (!stepEngineToPage(engine, target.pageIndex)) {
            return KeyProcessResult.PassThrough
        }
        val result = engine.selectCandidate(target.indexOnPage)
        syncWithSnapshot(engine.state.value, forceReset = true)
        return result
    }

    fun reset() {
        pages.clear()
        lastKey = null
        items = emptyList()
        hasMore = false
    }

    private fun stepEngineToPage(engine: InputEngine, targetPage: Int): Boolean {
        var safety = 0
        while (engine.state.value.pageIndex != targetPage && safety++ < maxPages + 2) {
            val forward = targetPage > engine.state.value.pageIndex
            if (!engine.page(forward)) return false
        }
        return engine.state.value.pageIndex == targetPage
    }

    private fun rebuildItems() {
        val built = ArrayList<WindowCandidateItem>()
        var globalIdx = 0
        for ((pageIdx, pageItems) in pages) {
            for (cand in pageItems) {
                built.add(
                    WindowCandidateItem(
                        globalIndex = globalIdx++,
                        pageIndex = pageIdx,
                        indexOnPage = cand.indexOnPage,
                        text = cand.text,
                        comment = cand.comment
                    )
                )
            }
        }
        items = built
    }

    private fun compositionKey(snapshot: EngineSnapshot): CompositionKey = CompositionKey(
        mode = snapshot.mode,
        variant = snapshot.variant,
        schemaId = snapshot.schemaId,
        preedit = snapshot.preedit,
        selectedSyllableIndex = snapshot.selectedSyllableIndex,
        syllables = snapshot.syllables
    )

    companion object {
        const val MAX_WINDOW_PAGES: Int = 32
    }
}
