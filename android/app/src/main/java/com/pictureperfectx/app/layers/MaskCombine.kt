package com.pictureperfectx.app.layers

/**
 * Folding one area into another.
 *
 * This is what makes New, Add and Subtract mean anything against a layer. A drawn selection belongs
 * to the document until it is deliberately applied to a layer, and *applying* it is this: the shape
 * you drew combined with the area that layer already had.
 *
 * The semantics match [MaskLasso] and [MaskBrush] exactly — adding takes the greater coverage,
 * subtracting drives it back towards zero — so a shape combined at the moment it is drawn and the
 * same shape combined later mean the same thing.
 */
fun Mask.combinedWith(other: Mask, mode: SelectionMode): Mask {
    // Nothing coming in leaves this untouched — checked first, because an empty mask carries the
    // default grid size and would otherwise look like a mismatched one below.
    if (other.isEmpty) return this
    // An empty mask renders as "applies everywhere", but as an *operand* it is "no area yet", so
    // there is nothing here to add to or cut from. Mismatched grids go the same way rather than
    // being mixed: coverage would land offset from where it was drawn.
    if (isEmpty || columns != other.columns || rows != other.rows) {
        return if (mode == SelectionMode.Subtract) this else other
    }

    // Whichever orientation each side is *displayed* in is the one being combined, so an inverted
    // mask contributes the area you can see rather than the raw numbers underneath it.
    val existing = orientedCoverage()
    val incoming = other.orientedCoverage()
    val combined = when (mode) {
        SelectionMode.Replace -> incoming
        SelectionMode.Add -> FloatArray(existing.size) { maxOf(existing[it], incoming[it]) }
        SelectionMode.Subtract -> FloatArray(existing.size) {
            (existing[it] - incoming[it]).coerceAtLeast(0f)
        }
    }
    // Replacing adopts the other area whole, shape and all, so a lasso stays draggable and a fade
    // stays re-placeable after it has been applied. Combining leaves something no single polygon or
    // gradient describes any more.
    return if (mode == SelectionMode.Replace) {
        other
    } else {
        copy(coverage = combined, inverted = false, path = null, gradient = null)
    }
}

/**
 * Coverage as it is *seen*, with [Mask.inverted] already folded in.
 *
 * Combining raw numbers instead would make Add on an inverted mask subtract, which is the sort of
 * thing that reads as the button being broken.
 */
private fun Mask.orientedCoverage(): FloatArray =
    if (!inverted) coverage else FloatArray(coverage.size) { 1f - coverage[it] }
