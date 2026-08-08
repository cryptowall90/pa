package com.pictureperfectx.app.layers

/**
 * The layer stack for one photo, ordered **bottom-first** so index 0 sits nearest the original and
 * the last entry renders on top — the reverse of how a layers panel lists them.
 *
 * A document holds only descriptions of edits, never rendered pixels, so it is cheap to copy. Every
 * operation returns a new document rather than mutating this one, which is what lets undo be a
 * simple stack of snapshots instead of a set of inverse operations.
 */
data class Document(
    val layers: List<Layer> = emptyList(),
    val selectedId: Long? = null,
    private val nextId: Long = 1L,
) {
    val selected: Layer? get() = layers.firstOrNull { it.id == selectedId }

    /** Top-first, the order a layers panel shows. */
    val topDown: List<Layer> get() = layers.asReversed()

    val isEmpty: Boolean get() = layers.isEmpty()

    /** Adds [build] on top and selects it. Ids are never reused, so undo can't confuse two layers. */
    fun add(build: (Long) -> Layer): Document {
        val layer = build(nextId)
        return copy(layers = layers + layer, selectedId = layer.id, nextId = nextId + 1)
    }

    fun remove(id: Long): Document {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = layers.filterNot { it.id == id }
        // Select the neighbour that took its place, so the panel doesn't end up with nothing chosen.
        val nextSelection = when {
            selectedId != id -> selectedId
            remaining.isEmpty() -> null
            else -> remaining[index.coerceAtMost(remaining.lastIndex)].id
        }
        return copy(layers = remaining, selectedId = nextSelection)
    }

    fun select(id: Long?): Document = copy(selectedId = id?.takeIf { candidate -> layers.any { it.id == candidate } })

    fun update(id: Long, transform: (Layer) -> Layer): Document =
        copy(layers = layers.map { if (it.id == id) transform(it) else it })

    fun toggleVisibility(id: Long): Document =
        update(id) { it.withCommon(isVisible = !it.isVisible) }

    fun setOpacity(id: Long, opacity: Float): Document =
        update(id) { it.withCommon(opacity = opacity.coerceIn(0f, 1f)) }

    fun setBlend(id: Long, blend: BlendMode): Document = update(id) { it.withCommon(blend = blend) }

    fun setMask(id: Long, mask: Mask): Document = update(id) { it.withCommon(mask = mask) }

    /** Moves a layer one step up (towards the top) or down, clamped at the ends. */
    fun move(id: Long, up: Boolean): Document {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return this
        val target = if (up) index + 1 else index - 1
        if (target !in layers.indices) return this
        val reordered = layers.toMutableList()
        reordered[index] = layers[target]
        reordered[target] = layers[index]
        return copy(layers = reordered)
    }

    /** Only visible layers actually contribute to a render. */
    fun renderable(): List<Layer> = layers.filter { it.isVisible && it.opacity > 0f }
}

/**
 * Undo/redo as a stack of whole documents.
 *
 * Snapshots are viable precisely because a [Document] stores descriptions rather than pixels — the
 * usual objection to snapshot undo, that it costs an image per step, doesn't apply here.
 */
data class History(
    val current: Document = Document(),
    private val past: List<Document> = emptyList(),
    private val future: List<Document> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records a new state. Redo is dropped, since the timeline has branched. */
    fun push(next: Document): History {
        if (next == current) return this
        return History(current = next, past = (past + current).takeLast(LIMIT), future = emptyList())
    }

    fun undo(): History {
        val previous = past.lastOrNull() ?: return this
        return History(current = previous, past = past.dropLast(1), future = listOf(current) + future)
    }

    fun redo(): History {
        val next = future.firstOrNull() ?: return this
        return History(current = next, past = past + current, future = future.drop(1))
    }

    private companion object {
        /** Deep enough to cover any real editing session, bounded so memory can't creep. */
        const val LIMIT = 50
    }
}
