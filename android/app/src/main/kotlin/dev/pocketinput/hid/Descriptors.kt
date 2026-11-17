package dev.pocketinput.hid

/**
 * HID report descriptor and report builders for PocketInput (Phase 0 HID spike).
 *
 * Source of HID_DESCRIPTOR (copied verbatim, bytes unchanged):
 *   Repository: https://github.com/ToxicOrca/ghostboard-android-bluetooth-mouse-keyboard
 *   Commit:     135a3f7333553afe45467b04da514430c7383e0c
 *   File:       app/src/main/java/com/example/remoteinput/bluetooth/BluetoothHidManager.kt
 *   Note:       скопировано без изменений (copied without changes)
 */
object Descriptors {

    const val KEYBOARD_REPORT_ID: Byte = 1
    const val MOUSE_REPORT_ID: Byte = 2

    // Boot interface subclasses (BluetoothHidDeviceAppSdpSettings)
    val SUBCLASS1: Byte = 1.toByte()
    val SUBCLASS2: Byte = 2.toByte()

    val HID_DESCRIPTOR: ByteArray = byteArrayOf(
            // --- Keyboard ---
            0x05.toByte(), 0x01,
            0x09.toByte(), 0x06,
            0xA1.toByte(), 0x01,
            0x85.toByte(), 0x01,
            0x05.toByte(), 0x07,
            0x19.toByte(), 0xE0.toByte(),
            0x29.toByte(), 0xE7.toByte(),
            0x15.toByte(), 0x00,
            0x25.toByte(), 0x01,
            0x75.toByte(), 0x01,
            0x95.toByte(), 0x08,
            0x81.toByte(), 0x02,
            0x75.toByte(), 0x08,
            0x95.toByte(), 0x01,
            0x81.toByte(), 0x01,
            0x75.toByte(), 0x08,
            0x95.toByte(), 0x06,
            0x15.toByte(), 0x00,
            0x25.toByte(), 0x65,
            0x05.toByte(), 0x07,
            0x19.toByte(), 0x00,
            0x29.toByte(), 0x65,
            0x81.toByte(), 0x00,
            0xC0.toByte(),

            // --- Mouse ---
            0x05.toByte(), 0x01,
            0x09.toByte(), 0x02,
            0xA1.toByte(), 0x01,
            0x85.toByte(), 0x02,
            0x09.toByte(), 0x01,
            0xA1.toByte(), 0x00,
            0x05.toByte(), 0x09,
            0x19.toByte(), 0x01,
            0x29.toByte(), 0x03,
            0x15.toByte(), 0x00,
            0x25.toByte(), 0x01,
            0x75.toByte(), 0x01,
            0x95.toByte(), 0x03,
            0x81.toByte(), 0x02,
            0x75.toByte(), 0x05,
            0x95.toByte(), 0x01,
            0x81.toByte(), 0x01,
            0x05.toByte(), 0x01,
            0x09.toByte(), 0x30,
            0x09.toByte(), 0x31,
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7F,
            0x75.toByte(), 0x08,
            0x95.toByte(), 0x02,
            0x81.toByte(), 0x06,
            0x09.toByte(), 0x38,
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7F,
            0x75.toByte(), 0x08,
            0x95.toByte(), 0x01,
            0x81.toByte(), 0x06,
            0xC0.toByte(),
            0xC0.toByte()
    )

    /**
     * Standard 8-byte boot-keyboard report: modifier, reserved, up to 6 key codes.
     */
    fun keyboardReport(modifier: Int, vararg keys: Int): ByteArray {
        val report = ByteArray(8)
        report[0] = modifier.toByte()
        report[1] = 0x00
        for (i in keys.indices) {
            if (i >= 6) break
            report[2 + i] = keys[i].toByte()
        }
        return report
    }

    /**
     * 4-byte mouse report: buttons, dx, dy, wheel.
     */
    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray =
        byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte())
}
