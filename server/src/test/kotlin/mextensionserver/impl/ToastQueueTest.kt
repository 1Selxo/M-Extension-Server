package mextensionserver.impl

import android.widget.Toast
import xyz.nulldev.androidcompat.ToastQueue
import kotlin.test.Test
import kotlin.test.assertEquals

class ToastQueueTest {
    @Test
    fun `shown toasts are handed over once and stay bounded`() {
        ToastQueue.drain()
        Toast.makeText(null, "Login successful", Toast.LENGTH_SHORT).show()
        assertEquals(listOf("Login successful"), ToastQueue.drain())
        assertEquals(emptyList(), ToastQueue.drain())

        repeat(25) { ToastQueue.add("toast $it") }
        val kept = ToastQueue.drain()
        assertEquals(20, kept.size)
        assertEquals("toast 24", kept.last())
    }
}
