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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 *
 * Метрики канала ([link]) — см. LinkSnapshot: состояние берётся из колбэков профиля, качество
 * считается по задержке очереди отправки, доле отказов `sendReport` и числу разрывов.
 * RSSI для классического HID публичным API Android недоступен, поэтому «силы сигнала» здесь нет.
 */
@SuppressLint("MissingPermission")
class HidTransport(private val context: Context) {

    companion object {
        private const val TAG = "PocketInputHID"

        /** Сколько последних замеров задержки держим для p50/p95. */
        private const val LATENCY_WINDOW = 64

        /** Как часто публиковать снимок канала при потоке отправок. */
        private const val PUBLISH_EVERY_SENDS = 8L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    private var profileProxyObtained: Boolean? = null

    /** Наблюдаемое состояние для UI (фаза 0: тест на живом телефоне без logcat). */
    var status: String by mutableStateOf("idle")
        private set

    /** Имя ПК, к которому подключён HID-канал (null — не подключён). */
    var peerName: String? by mutableStateOf(null)
        private set

    /** Снимок канала для индикатора и экрана диагностики. */
    var link: LinkSnapshot by mutableStateOf(LinkSnapshot())
        private set

    /** Все sendReport/replyReport уходят отсюда, не из UI-потока. */
    private val sendExecutor = Executors.newSingleThreadExecutor()

    // --- накопление метрик ---
    private val latencyWindow = ArrayDeque<Float>(LATENCY_WINDOW)
    private var sentOk = 0L
    private var sendFailed = 0L
    private var disconnectCount = 0
    private var connectedSinceMs = 0L
    private var lastDisconnectMs = 0L

    /**
     * Запрос прокси HID-профиля. getProfileProxy -> false/null — штатная
     * ситуация «OEM не отдал профиль» (spec 02, 2.3), не ошибка.
     */
    fun start(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "no bluetooth adapter")
            status = "no-adapter"
            publishLink()
            return false
        }
        val ok = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        profileProxyObtained = ok
        status = if (ok) "proxy-requested" else "no-profile"
        Log.d(TAG, "getProfileProxy(HID_DEVICE) = $ok")
        publishLink()
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
        peerName = null
        profileProxyObtained = null
        status = "stopped"
        sendExecutor.shutdown()
        Log.d(TAG, "profile proxy closed")
        publishLink()
    }

    // --- отправка с метриками ---

    /**
     * Ставит отправку в очередь и замеряет, сколько отчёт прождал в ней: на забитом канале
     * Bluetooth-стек придерживает `sendReport`, и очередь растёт. Это и есть измеряемое
     * «качество» вместо недоступного RSSI.
     */
    private fun submit(send: (BluetoothHidDevice, BluetoothDevice) -> Boolean) {
        val hid = hidDevice ?: return
        val dev = device ?: return
        val enqueuedNs = System.nanoTime()
        sendExecutor.execute {
            val waitedMs = (System.nanoTime() - enqueuedNs) / 1_000_000f
            val ok = runCatching { send(hid, dev) }.getOrElse { false }
            recordSend(waitedMs, ok)
        }
    }

    private fun recordSend(waitedMs: Float, ok: Boolean) {
        if (ok) sentOk++ else sendFailed++
        if (latencyWindow.size >= LATENCY_WINDOW) latencyWindow.removeFirst()
        latencyWindow.addLast(waitedMs)
        val total = sentOk + sendFailed
        if (total % PUBLISH_EVERY_SENDS == 0L) publishLink()
    }

    /**
     * Ручная проверка канала: ставит в очередь пустой отчёт. Возвращает false, если отправлять
     * некуда (нет профиля или ПК не подключён). Подтверждения от ПК HID не даёт — результат
     * виден только в счётчиках отправок и отказов.
     */
    fun selfTest(): Boolean {
        if (hidDevice == null || device == null) return false
        submit { h, d ->
            h.sendReport(d, Descriptors.MOUSE_REPORT_ID.toInt(), Descriptors.mouseReport(0, 0, 0, 0))
        }
        return true
    }

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        submit { hid, dev ->
            val ok = hid.sendReport(
                dev,
                Descriptors.MOUSE_REPORT_ID.toInt(),
                Descriptors.mouseReport(buttons, dx, dy, wheel),
            )
            if (!ok) Log.w(TAG, "sendReport failed (mouse)")
            ok
        }
    }

    /**
     * Клик: нажатие → пауза → отпускание. Пауза исполняется в очереди отправки (не в UI),
     * потому что Windows иногда склеивает фронт и спад, отправленные в одном кадре:
     * одиночный отчёт «нажато-и-отпущено» тогда читается как отсутствие клика.
     */
    fun tap(button: Int = 1, holdMs: Long = 40) {
        Log.d(TAG, "tap requested: button=$button, hid=${hidDevice != null}, dev=${device != null}")
        submit { hid, dev ->
            hid.sendReport(
                dev,
                Descriptors.MOUSE_REPORT_ID.toInt(),
                Descriptors.mouseReport(button, 0, 0, 0),
            )
        }
        submit { hid, dev ->
            if (holdMs > 0) {
                try {
                    Thread.sleep(holdMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            hid.sendReport(
                dev,
                Descriptors.MOUSE_REPORT_ID.toInt(),
                Descriptors.mouseReport(0, 0, 0, 0),
            )
        }
    }

    // --- Кнопки мыши, колесо, клавиатура (фаза 1) ---

    /** Накопленное состояние кнопок: бит 0 ЛКМ, 1 ПКМ, 2 СКМ, 3 назад, 4 вперёд. */
    private var buttonsMask = 0

    val currentButtons: Int get() = buttonsMask

    /** Отправить отчёт с текущей маской кнопок (движение при зажатой кнопке). */
    fun sendMotion(dx: Int, dy: Int) {
        submit { hid, dev ->
            val ok = hid.sendReport(dev, Descriptors.MOUSE_REPORT_ID.toInt(),
                Descriptors.mouseReport(buttonsMask, dx, dy, 0))
            if (!ok) Log.w(TAG, "sendReport failed (motion)")
            ok
        }
    }

    /** Новое состояние маски кнопок одним отчётом. */
    fun setButtons(mask: Int, wheel: Int = 0) {
        buttonsMask = mask and 0x1F
        submit { hid, dev ->
            val ok = hid.sendReport(dev, Descriptors.MOUSE_REPORT_ID.toInt(),
                Descriptors.mouseReport(buttonsMask, 0, 0, wheel))
            if (!ok) Log.w(TAG, "sendReport failed (buttons)")
            ok
        }
    }

    fun pressButton(bit: Int) {
        Log.d(TAG, "pressButton bit=$bit")
        setButtons(buttonsMask or (1 shl (bit - 1)))
    }

    fun releaseButton(bit: Int) {
        Log.d(TAG, "releaseButton bit=$bit")
        setButtons(buttonsMask and (1 shl (bit - 1)).inv())
    }

    fun releaseAllButtons() = setButtons(0)

    /** Вертикальный скролл: единица — 1/120 щелчка колеса, знак = направление. */
    fun wheel(units: Int) {
        val clamped = units.coerceIn(-127, 127)
        if (clamped == 0) return
        submit { hid, dev ->
            val ok = hid.sendReport(dev, Descriptors.MOUSE_REPORT_ID.toInt(),
                Descriptors.mouseReport(buttonsMask, 0, 0, clamped))
            if (!ok) Log.w(TAG, "sendReport failed (wheel)")
            ok
        }
    }

    /** Нажатие клавиши с модификаторами: down + пауза + up (без автоповтора). */
    fun keyTap(usage: Int, mods: Int = 0, holdMs: Long = 20) {
        Log.d(TAG, "keyTap usage=0x${usage.toString(16)} mods=0x${mods.toString(16)}")
        submit { hid, dev ->
            hid.sendReport(dev, Descriptors.KEYBOARD_REPORT_ID.toInt(),
                Descriptors.keyboardReport(mods, usage))
        }
        submit { hid, dev ->
            if (holdMs > 0) {
                try {
                    Thread.sleep(holdMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            hid.sendReport(dev, Descriptors.KEYBOARD_REPORT_ID.toInt(),
                Descriptors.keyboardReport(mods))
        }
    }

    /** Клавиша вниз без отпускания: автоповтор делает ОС ПК, не телефон. */
    fun keyDown(usage: Int, mods: Int = 0) {
        submit { hid, dev ->
            hid.sendReport(dev, Descriptors.KEYBOARD_REPORT_ID.toInt(),
                Descriptors.keyboardReport(mods, usage))
        }
    }

    fun keyUp(mods: Int = 0) {
        submit { hid, dev ->
            hid.sendReport(dev, Descriptors.KEYBOARD_REPORT_ID.toInt(),
                Descriptors.keyboardReport(mods))
        }
    }

    fun sendKeyboard(modifier: Int, vararg keys: Int) {
        submit { hid, dev ->
            val ok = hid.sendReport(
                dev,
                Descriptors.KEYBOARD_REPORT_ID.toInt(),
                Descriptors.keyboardReport(modifier, *keys),
            )
            if (!ok) Log.w(TAG, "sendReport failed (keyboard)")
            ok
        }
    }

    // --- снимок канала ---

    private fun publishLink() {
        val samples = latencyWindow.toList()
        val p50 = if (samples.isEmpty()) 0f else samples.sorted()[samples.size / 2]
        val total = sentOk + sendFailed
        val failedRatio = if (total == 0L) 0f else sendFailed.toFloat() / total
        val state = when {
            bluetoothAdapter == null -> LinkState.NO_ADAPTER
            status == "no-adapter" -> LinkState.NO_ADAPTER
            status == "no-profile" -> LinkState.NO_PROFILE
            status == "register-failed" -> LinkState.FAILED
            device != null && status == "connected" -> LinkState.CONNECTED
            status == "idle" || status == "proxy-requested" -> LinkState.INIT
            else -> LinkState.REGISTERED
        }
        // Балл имеет смысл только когда канал есть и были отправки.
        val score = if (state == LinkState.CONNECTED && total > 0L) {
            LinkQuality.score(p50, failedRatio, disconnectCount)
        } else {
            -1
        }
        link = LinkSnapshot(
            state = state,
            peer = peerName,
            quality = LinkQuality.level(score),
            score = score,
            p50Ms = p50,
            p95Ms = LinkQuality.percentile95(samples),
            sentOk = sentOk,
            sendFailed = sendFailed,
            disconnects = disconnectCount,
            connectedSinceMs = connectedSinceMs,
            lastDisconnectMs = lastDisconnectMs,
        )
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
            peerName = null
            status = "proxy-lost"
            publishLink()
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
        publishLink()
    }

    // --- BluetoothHidDevice.Callback (сигнатуры по эталону ghostboard) ---

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered device=${pluggedDevice?.name}")
            if (pluggedDevice != null) {
                device = pluggedDevice
                peerName = pluggedDevice.name ?: pluggedDevice.address
            }
            status = if (registered) "registered" else "unregistered"
            publishLink()
        }

        override fun onConnectionStateChanged(dev: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: state=$state device=${dev.name}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    device = dev
                    peerName = dev.name ?: dev.address
                    status = "connected"
                    connectedSinceMs = System.currentTimeMillis()
                    // Задержки прошлого соединения не смешиваем с новым каналом.
                    latencyWindow.clear()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == dev) device = null
                    peerName = null
                    status = "registered"
                    if (connectedSinceMs > 0L) {
                        disconnectCount++
                        lastDisconnectMs = System.currentTimeMillis()
                    }
                    connectedSinceMs = 0L
                }
            }
            publishLink()
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