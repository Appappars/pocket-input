package dev.pocketinput.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyBackspaceTest {
    @Test
    fun onlySingleBackwardDeleteCanReachPc() {
        val sent = mutableListOf<Int>()
        var canForward = false
        val backspace = EmptyBackspace({ canForward }, { sent.add(it) })

        assertFalse(backspace.deleteSurroundingText(1, 0))
        canForward = true
        assertFalse(backspace.deleteSurroundingText(0, 1))
        assertFalse(backspace.deleteSurroundingText(2, 0))
        assertTrue(backspace.deleteSurroundingText(1, 0))

        assertEquals(listOf(1), sent)
    }

    @Test
    fun repeatedBackspaceWaitsForEndOfBatch() {
        val sent = mutableListOf<Int>()
        val backspace = EmptyBackspace({ true }, { sent.add(it) })

        backspace.beginBatch()
        assertTrue(backspace.delete())
        assertTrue(backspace.deleteSurroundingText(1, 0))
        assertTrue(sent.isEmpty())
        backspace.endBatch()
        backspace.endBatch()

        assertEquals(listOf(2), sent)
    }

    @Test
    fun editingTextInSameBatchCancelsDirectDeletion() {
        val sent = mutableListOf<Int>()
        val backspace = EmptyBackspace({ true }, { sent.add(it) })

        backspace.beginBatch()
        assertTrue(backspace.deleteSurroundingText(1, 0))
        backspace.otherEdit()
        assertFalse(backspace.deleteSurroundingText(1, 0))
        backspace.endBatch()
        assertTrue(sent.isEmpty())

        assertTrue(backspace.delete())
        assertEquals(listOf(1), sent)
    }

    @Test
    fun losingEditorOrClosingConnectionDiscardsPendingBackspace() {
        val sent = mutableListOf<Int>()
        var canForward = true
        val backspace = EmptyBackspace({ canForward }, { sent.add(it) })

        backspace.beginBatch()
        assertTrue(backspace.delete())
        canForward = false
        backspace.endBatch()
        assertTrue(sent.isEmpty())

        canForward = true
        backspace.beginBatch()
        assertTrue(backspace.delete())
        backspace.close()
        backspace.endBatch()
        assertFalse(backspace.delete())
        assertTrue(sent.isEmpty())
    }
}
