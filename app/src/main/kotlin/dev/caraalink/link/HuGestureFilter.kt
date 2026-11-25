package dev.caraalink.link

import android.view.MotionEvent

/** Legacy single-contact profile; secondary contacts never become phantom moves or ups. */
internal class HuGestureFilter {
    private var primary: Int? = null

    fun accepts(action: Int, slot: Int): Boolean {
        if (slot !in 0..31) return false
        if (action == MotionEvent.ACTION_DOWN) {
            if (primary != null) return false
            primary = slot
            return true
        }
        if (slot != primary) return false
        return when (action) {
            MotionEvent.ACTION_MOVE -> true
            MotionEvent.ACTION_UP -> { primary = null; true }
            else -> false
        }
    }

    fun reset() { primary = null }
}
