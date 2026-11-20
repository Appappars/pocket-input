package dev.pocketinput.ui.trackpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerFramesTest {
    @Test
    fun `empty samples produce no frames`() {
        assertTrue(buildPointerFrames(emptyList()).isEmpty())
    }

    @Test
    fun `frames are ordered by time`() {
        val frames = buildPointerFrames(
            listOf(
                PointerSample(1L, 10f, 0f, true, 30L),
                PointerSample(1L, 8f, 0f, true, 10L),
                PointerSample(1L, 9f, 0f, true, 20L),
            ),
        )

        assertEquals(listOf(10L, 20L, 30L), frames.map { it.timeMs })
    }

    @Test
    fun `all historical samples are kept`() {
        val frames = buildPointerFrames(
            listOf(
                PointerSample(1L, 1f, 1f, true, 1L),
                PointerSample(1L, 2f, 1f, true, 2L),
                PointerSample(1L, 3f, 1f, true, 3L),
                PointerSample(1L, 4f, 1f, true, 4L),
            ),
        )

        assertEquals(4, frames.size)
        assertEquals(4f, frames.last().contacts.single().position.x, 0.001f)
    }

    @Test
    fun `two fingers at the same moment share one frame`() {
        val frames = buildPointerFrames(
            listOf(
                PointerSample(1L, 1f, 1f, true, 5L),
                PointerSample(2L, 2f, 2f, true, 5L),
            ),
        )

        assertEquals(1, frames.size)
        assertEquals(listOf(1L, 2L), frames.single().contacts.map { it.id })
    }

    @Test
    fun `latest sample of a finger wins inside one frame`() {
        val frames = buildPointerFrames(
            listOf(
                PointerSample(7L, 1f, 1f, true, 5L),
                PointerSample(7L, 9f, 9f, true, 5L),
            ),
        )

        assertEquals(1, frames.size)
        assertEquals(9f, frames.single().contacts.single().position.x, 0.001f)
    }

    @Test
    fun `pressed state travels with the sample`() {
        val frames = buildPointerFrames(
            listOf(
                PointerSample(1L, 5f, 5f, true, 1L),
                PointerSample(1L, 6f, 6f, false, 2L),
            ),
        )

        assertTrue(frames.first().contacts.single().pressed)
        assertTrue(!frames.last().contacts.single().pressed)
    }
}
