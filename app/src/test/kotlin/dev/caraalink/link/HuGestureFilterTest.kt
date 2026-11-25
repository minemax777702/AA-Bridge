package dev.caraalink.link

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class HuGestureFilterTest {
    @Test fun secondaryContactsNeverCreatePhantomEvents() {
        val filter = HuGestureFilter()
        assertFalse(filter.accepts(MotionEvent.ACTION_MOVE, 0))
        assertTrue(filter.accepts(MotionEvent.ACTION_DOWN, 1))
        assertFalse(filter.accepts(MotionEvent.ACTION_DOWN, 1))
        assertFalse(filter.accepts(MotionEvent.ACTION_DOWN, 2))
        assertFalse(filter.accepts(MotionEvent.ACTION_MOVE, 2))
        assertTrue(filter.accepts(MotionEvent.ACTION_MOVE, 1))
        assertTrue(filter.accepts(MotionEvent.ACTION_UP, 1))
        assertFalse(filter.accepts(MotionEvent.ACTION_UP, 2))
        assertFalse(filter.accepts(MotionEvent.ACTION_MOVE, 2))
        assertTrue(filter.accepts(MotionEvent.ACTION_DOWN, 2))
        filter.reset()
        assertFalse(filter.accepts(MotionEvent.ACTION_UP, 2))
        assertFalse(filter.accepts(MotionEvent.ACTION_DOWN, 32))
        assertTrue(filter.accepts(MotionEvent.ACTION_DOWN, 0))
    }
}
