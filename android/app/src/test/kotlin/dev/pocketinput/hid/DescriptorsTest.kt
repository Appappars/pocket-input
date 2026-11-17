package dev.pocketinput.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptorsTest {

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun descriptorIsLongEnough() {
        assertTrue(
            "HID_DESCRIPTOR length must be > 40 bytes",
            Descriptors.HID_DESCRIPTOR.size > 40
        )
    }

    @Test
    fun descriptorContainsUsagePageGenericDesktop() {
        assertTrue(
            "HID_DESCRIPTOR must contain 0x05, 0x01 (Usage Page Generic Desktop)",
            containsSequence(Descriptors.HID_DESCRIPTOR, byteArrayOf(0x05, 0x01))
        )
    }

    @Test
    fun keyboardReportLayout() {
        val report = Descriptors.keyboardReport(0x02, 0x04)
        assertEquals(8, report.size)
        assertEquals(0x02.toByte(), report[0])
        assertEquals(0x00.toByte(), report[1])
        assertEquals(0x04.toByte(), report[2])
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00),
            report
        )
    }

    @Test
    fun mouseReportLayout() {
        val report = Descriptors.mouseReport(1, -5, 7, 0)
        assertEquals(4, report.size)
        assertEquals(1.toByte(), report[0])
        assertEquals((-5).toByte(), report[1])
        assertEquals(7.toByte(), report[2])
        assertEquals(0.toByte(), report[3])
    }

    @Test
    fun reportIds() {
        assertEquals(1.toByte(), Descriptors.KEYBOARD_REPORT_ID)
        assertEquals(2.toByte(), Descriptors.MOUSE_REPORT_ID)
    }
}
