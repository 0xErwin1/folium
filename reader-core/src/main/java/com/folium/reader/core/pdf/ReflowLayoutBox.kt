package com.folium.reader.core.pdf

/**
 * The page geometry a reflowable document is laid out against.
 *
 * A reflowable document has no pages until it is laid out, so its page count and every position a
 * reader stores are functions of this box rather than properties of the document itself. That
 * makes the box part of the meaning of a stored reading position, not a rendering preference: the
 * stored progress record carries no version of it, so nothing can tell a position written under
 * one box from a position written under another.
 *
 * Two rules follow from that. First, this is never derived from screen size and never exposed as a
 * preference — doing either would make every device, or every settings change, its own silent
 * migration. Second, a numbered constant here is never edited in place; a new box is added as
 * `BOX_2` and every earlier one is kept, because migrating a stored position requires resolving it
 * in the box it was written in, so the old box has to still exist to do that.
 *
 * The engine build is a second, unrecorded input to the same pagination: two engine versions can
 * paginate the same document differently even under an identical box. Freezing this box freezes
 * pagination against a settings change, a screen change and a box edit, but not against an engine
 * upgrade.
 */
data class ReflowLayoutBox(val widthPoints: Float, val heightPoints: Float, val emPoints: Float) {
    init {
        require(widthPoints > 0f) { "widthPoints must be positive, was $widthPoints" }
        require(heightPoints > 0f) { "heightPoints must be positive, was $heightPoints" }
        require(emPoints > 0f) { "emPoints must be positive, was $emPoints" }
    }

    companion object {
        val BOX_1 = ReflowLayoutBox(450f, 675f, 18f)
    }
}
