package dev.pocketinput.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Phase 0 HID spike (ветка A): телефон как композитное HID-устройство
 * (клавиатура + мышь) через BluetoothHidDevice (API 28+).
 *
 * Callback signatures и способ вызова registerApp/replyReport/sendReport —
 * по эталону ToxicOrca/ghostboard-android-bluetooth-mouse-keyboard,
 * коммит 135a3f7333553afe45467b04da514430c7383e0c (BluetoothHidManager.kt).
 *
 * Дескриптор и сборка репортов — Descriptors.kt.
 */
@SuppressLint("MissingPermission")
class HidTransport(private val context: Context) {

    companion object {
        private const val TAG = "PocketInputHID"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    private var profileProxyObtained: Boolean? = null

    @Volatile
    var status: String = "idle"
        private set

    /** Все sendReport/replyReport уходят отсюда, не из UI-потока. */
    private val sendExecutor = Executors.newSingleThreadExecutor()

    /**
     * Запрос прокси HID-профиля. getProfileProxy -> false/null — штатная
     * ситуация «OEM не отдал профиль» (spec 02, 2.3), не ошибка.
     */
    fun start(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "no bluetooth adapter")
            status = "no-adapter"
            return false
        }
        val ok = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        profileProxyObtained = ok
        status = if (ok) "proxy-requested" else "no-profile"
        Log.d(TAG, "getProfileProxy(HID_DEVICE) = $ok")
        return ok
    }

    /** true, если getProfileProxy вернул не null (профиль получен). */
    fun isHidSupported(): Boolean = profileProxyObtained == true

    /** Остановка: unregisterApp + closeProfileProxy. */
    fun stop() {
        hidDevice?.unregisterApp()
        hidDevice?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null
        device = null
        profileProxyObtained = null
        status = "stopped"
        sendExecutor.shutdown()
        Log.d(TAG, "profile proxy closed")
    }

    // --- Отправка репортов (на sendExecutor, не в UI-потоке) ---

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val hid = hidDevice ?: return
        val dev = device ?: return
        sendExecutor.execute {
            val ok = hid.sendReport(
                dev,
                Descriptors.MOUSE_REPORT_ID.toInt(),
                Descriptors.mouseReport(buttons, dx, dy, wheel)
            )
            if (!ok) Log.w(TAG, "sendReport failed (mouse)")
        }
    }

    fun sendKeyboard(modifier: Int, vararg keys: Int) {
        val hid = hidDevice ?: return
        val dev = device ?: return
        sendExecutor.execute {
            val ok = hid.sendReport(
                dev,
                Descriptors.KEYBOARD_REPORT_ID.toInt(),
                Descriptors.keyboardReport(modifier, *keys)
            )
            if (!ok) Log.w(TAG, "sendReport failed (keyboard)")
        }
    }

    // --- Profile proxy ---

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            Log.d(TAG, "HID profile proxy connected")
            hidDevice = proxy as BluetoothHidDevice
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "HID profile proxy disconnected")
            hidDevice = null
            device = null
            status = "proxy-lost"
        }
    }

    /** ОДИН вызов registerApp с составным дескриптором (клавиатура+мышь). */
    private fun registerHidApp() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "PocketInput",
            "PocketInput HID",
            "dev.pocketinput",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            Descriptors.HID_DESCRIPTOR
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )
        val result = hid.registerApp(sdp, null, qos, sendExecutor, hidCallback)
        Log.d(TAG, "registerApp result: $result")
        if (result != true) status = "register-failed"
    }

    // --- BluetoothHidDevice.Callback (сигнатуры по эталону ghostboard) ---

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered device=${pluggedDevice?.name}")
            if (pluggedDevice != null) device = pluggedDevice
            status = if (registered) "registered" else "unregistered"
        }

        override fun onConnectionStateChanged(dev: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: state=$state device=${dev.name}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    device = dev
                    status = "connected"
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == dev) device = null
                    status = "registered"
                }
            }
        }

        override fun onGetReport(dev: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            // Как в эталоне: отвечаем нулевым репортом нужной длины.
            when (id.toInt()) {
                Descriptors.KEYBOARD_REPORT_ID.toInt() ->
                    hidDevice?.replyReport(dev, type, id, ByteArray(8))
                Descriptors.MOUSE_REPORT_ID.toInt() ->
                    hidDevice?.replyReport(dev, type, id, ByteArray(4))
            }
        }

        override fun onSetReport(dev: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            // заглушка
        }

        override fun onInterruptData(dev: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            // заглушка
        }
    }
}
