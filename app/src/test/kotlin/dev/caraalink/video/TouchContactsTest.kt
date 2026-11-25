package dev.caraalink.video

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchContactsTest {
    @Test
    fun `pinch keeps both contacts and stable IDs through pointer transitions`() {
        val contacts = TouchContacts()
        fun update(action: Int, slot: Int, x: Float) = contacts.update(action, slot, x, 100f)!!
        val down = update(MotionEvent.ACTION_DOWN, 4, 100f)
        assertEquals(MotionEvent.ACTION_DOWN, down.action)
        val second = update(MotionEvent.ACTION_DOWN, 9, 200f)
        assertEquals(MotionEvent.ACTION_POINTER_DOWN, second.action)
        assertEquals(1, second.actionIndex)
        assertEquals(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), second.androidAction)
        val move = update(MotionEvent.ACTION_MOVE, 9, 250f)
        assertEquals(listOf(4, 9), move.contacts.map { it.id })
        assertEquals(listOf(100f, 250f), move.contacts.map { it.x })
        val up = update(MotionEvent.ACTION_UP, 4, 100f)
        assertEquals(MotionEvent.ACTION_POINTER_UP, up.action)
        assertEquals(0, up.actionIndex)
        assertEquals(listOf(4, 9), up.contacts.map { it.id })
        val last = update(MotionEvent.ACTION_UP, 9, 250f)
        assertEquals(MotionEvent.ACTION_UP, last.action)
        assertEquals(listOf(9), last.contacts.map { it.id })
        assertNull(contacts.cancel())
    }

    @Test
    fun `invalid and orphan events cannot create contacts and cancel resets state`() {
        val contacts = TouchContacts()
        assertNull(contacts.update(MotionEvent.ACTION_MOVE, 1, 0f, 0f))
        assertNull(contacts.update(MotionEvent.ACTION_DOWN, 32, 0f, 0f))
        assertNull(contacts.update(MotionEvent.ACTION_DOWN, 1, Float.NaN, 0f))
        contacts.update(MotionEvent.ACTION_DOWN, 1, 0f, 0f)
        assertNull(contacts.update(MotionEvent.ACTION_DOWN, 1, 1f, 0f))
        val cancel = contacts.cancel()!!
        assertEquals(MotionEvent.ACTION_CANCEL, cancel.action)
        assertEquals(listOf(1), cancel.contacts.map { it.id })
        assertNull(contacts.update(MotionEvent.ACTION_UP, 1, 0f, 0f))
        assertEquals(MotionEvent.ACTION_DOWN, contacts.update(MotionEvent.ACTION_DOWN, 1, 0f, 0f)!!.action)
    }
}
