package dev.caraalink.video

import android.view.MotionEvent

internal data class TouchContact(val id: Int, val x: Float, val y: Float)

internal data class TouchFrame(val action: Int, val actionIndex: Int, val contacts: List<TouchContact>) {
    val androidAction: Int
        get() = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
}

/** Converts individual CarbitLink slot updates into complete Android touch frames. Main-thread owned. */
internal class TouchContacts {
    private val active = linkedMapOf<Int, TouchContact>()

    fun update(action: Int, slot: Int, x: Float, y: Float): TouchFrame? {
        // Android pointer IDs are limited to 0..31. Never create unbounded state from the wire.
        if (slot !in 0..31 || !x.isFinite() || !y.isFinite()) return null
        val contact = TouchContact(slot, x, y)
        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (slot in active) return null
                active[slot] = contact
                val kind = if (active.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
                TouchFrame(kind, active.size - 1, active.values.toList())
            }
            MotionEvent.ACTION_MOVE -> {
                if (slot !in active) return null
                active[slot] = contact
                TouchFrame(MotionEvent.ACTION_MOVE, 0, active.values.toList())
            }
            MotionEvent.ACTION_UP -> {
                if (slot !in active) return null
                active[slot] = contact
                val frame = TouchFrame(
                    if (active.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP,
                    active.keys.indexOf(slot),
                    active.values.toList(),
                )
                active.remove(slot)
                frame
            }
            else -> null
        }
    }

    fun cancel(): TouchFrame? {
        if (active.isEmpty()) return null
        return TouchFrame(MotionEvent.ACTION_CANCEL, 0, active.values.toList()).also { active.clear() }
    }
}
