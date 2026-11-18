package dev.pocketinput.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("MissingPermission")
class HidTransport(context: Context, private val onLinkChanged: (LinkSnapshot) -> Unit) {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val preferences = app.getSharedPreferences("pocket_input_hid", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(app)
    private val measurements = LinkMeasurements()
    private val history = ArrayDeque<LinkEvent>(HISTORY_SIZE)
    private val profileBackoff = LinkBackoff()
    private val hostBackoff = LinkBackoff()

    var link: LinkSnapshot by mutableStateOf(LinkSnapshot())
        private set

    private var running = false
    private var inputEnabled = false
    private var proxyGeneration = 0L
    private var proxy: BluetoothHidDevice? = null
    private var registered = false
    private var requestedHost: BluetoothDevice? = null

    private data class Session(val id: Long, val device: BluetoothDevice)

    @Volatile
    private var session: Session? = null

    private var measurementPublishPending = false
    private val publishMeasurements = Runnable {
        measurementPublishPending = false
        publish(measurements.update(link))
    }
    private val input = HidInputQueue(onSendResult = ::recordReport)

    private fun recordReport(id: Long, queueMs: Float, ok: Boolean) {
        main.post {
            if (session?.id == id) {
                measurements.record(queueMs, ok)
                val keyboardRevision = input.keyboardRevision
                if (keyboardRevision != link.keyboardRevision) {
                    publish(measurements.update(link.copy(
                        keyboardRevision = keyboardRevision,
                        keyboardFailureRevision = keyboardRevision,
                    )))
                } else if (!measurementPublishPending) {
                    measurementPublishPending = true
                    main.postDelayed(publishMeasurements, MEASUREMENTS_INTERVAL_MS)
                }
            }
        }
    }

    private val requestProfileAgain = Runnable { requestProfile() }
    private val stableRegistration = Runnable { profileBackoff.reset() }
    private val profileTimeout = Runnable {
        if (proxy == null) {
            recoverProfile(LinkCause.PROFILE_TIMEOUT, LinkState.NO_PROFILE)
        } else if (!registered) {
            recoverProfile(LinkCause.REGISTRATION_FAILED, LinkState.FAILED)
        }
    }
    private val requestHostAgain = Runnable { requestHost() }
    private val hostTimeout = Runnable {
        if (session == null && registered && !confirmConnectedHost()) {
            requestedHost = null
            waitForHost(LinkCause.HOST_UNAVAILABLE)
        }
    }

    fun start() {
        if (running) return
        running = true
        profileBackoff.reset()
        hostBackoff.reset()
        requestProfile()
    }

    fun stop() {
        running = false
        invalidateProfile()
        transition(LinkState.STOPPED)
    }

    fun close() {
        stop()
        input.close()
    }

    fun serviceStartFailed() {
        if (!hasBluetoothPermission(app)) {
            transition(LinkState.NEEDS_PERMISSION)
        } else {
            transition(LinkState.FAILED, LinkCause.SERVICE_FAILED)
        }
    }

    fun refreshBluetooth() {
        if (!running) return
        val blocked = prerequisite()
        if (blocked != null) {
            invalidateProfile()
            transition(blocked)
        } else if (link.state in bluetoothUnavailable) {
            profileBackoff.reset()
            hostBackoff.reset()
            requestProfile()
        } else if (registered && session == null) {
            confirmConnectedHost()
        }
    }

    fun refreshBondedHost() {
        if (!running || prerequisite() != null) return
        val address = preferences.getString(HOST_KEY, null) ?: return
        val stillBonded = runCatching { adapter?.bondedDevices?.any { it.address == address } }.getOrNull()
        if (stillBonded != false) return
        preferences.edit().remove(HOST_KEY).apply()
        if (session?.device?.address == address || requestedHost?.address == address) {
            closeSession()
            cancelHostAttempts()
            if (registered) waitForHost(LinkCause.HOST_DISCONNECTED)
        }
    }

    fun retry() {
        if (!running || session != null) return
        profileBackoff.reset()
        hostBackoff.reset()
        if (registered && prerequisite() == null) {
            cancelHostAttempts()
            waitForHost(null)
        } else {
            invalidateProfile()
            requestProfile()
        }
    }

    fun setInputEnabled(enabled: Boolean) {
        if (inputEnabled == enabled) return
        inputEnabled = enabled
        if (!enabled) resetInput()
    }

    fun tap(button: Int = 1) = submitInput { input.tap(button) }
    fun pressButton(bit: Int) = submitInput { input.pressButton(bit) }
    fun releaseButton(bit: Int) = submitInput { input.releaseButton(bit) }
    fun releaseAllButtons() = input.releaseAllButtons()
    fun cancelPointerHold(keepLeft: Boolean) = input.cancelMouseInput(if (keepLeft) 1 else 0)

    fun resetInput() {
        input.resetInput()
        publish(link.copy(keyboardRevision = input.keyboardRevision))
    }
    fun sendMotion(dx: Int, dy: Int) = submitInput { input.sendMotion(dx, dy) }
    fun scroll(horizontal: Int, vertical: Int) = submitInput { input.scroll(horizontal, vertical) }
    fun zoom(units: Int) = submitInput { input.zoom(units) }
    fun cancelScroll() = input.cancelScroll()

    fun keyTap(usage: Int, mods: Int = 0, expectedSessionId: Long? = null): Boolean =
        inputEnabled && input.keyTap(usage, mods, expectedSessionId = expectedSessionId)

    fun keyTaps(keys: List<Pair<Int, Int>>, expectedSessionId: Long, expectedKeyboardRevision: Long): Boolean =
        inputEnabled && input.keyTaps(keys, expectedSessionId, expectedKeyboardRevision)

    fun keyUp(expectedSessionId: Long? = null) {
        input.keyUp(expectedSessionId = expectedSessionId)
    }

    fun selfTest(onResult: (Boolean) -> Unit) {
        val probeSession = session
        input.probe { sent ->
            main.post { onResult(sent && probeSession != null && session === probeSession) }
        }
    }

    private inline fun submitInput(send: () -> Unit) {
        if (inputEnabled) send()
    }

    private fun prerequisite(): LinkState? = when {
        adapter == null -> LinkState.NO_ADAPTER
        !hasBluetoothPermission(app) -> LinkState.NEEDS_PERMISSION
        !adapter.isEnabled -> LinkState.BLUETOOTH_OFF
        else -> null
    }

    private fun requestProfile() {
        if (!running) return
        prerequisite()?.let {
            transition(it)
            return
        }
        val generation = ++proxyGeneration
        if (link.state != LinkState.RECOVERING) transition(LinkState.INIT)
        val accepted = runCatching {
            adapter?.getProfileProxy(app, profileListener(generation), BluetoothProfile.HID_DEVICE)
        }.getOrNull() == true
        if (accepted) {
            main.postDelayed(profileTimeout, PROFILE_TIMEOUT_MS)
        } else {
            recoverProfile(LinkCause.PROFILE_UNAVAILABLE, LinkState.NO_PROFILE)
        }
    }

    private fun profileListener(generation: Long) = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
            mainExecutor.execute {
                if (!running || generation != proxyGeneration) {
                    if (proxy !== service) closeProxy(service)
                } else if (proxy !== service) {
                    main.removeCallbacks(profileTimeout)
                    val hid = service as BluetoothHidDevice
                    proxy = hid
                    register(hid, generation)
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            mainExecutor.execute {
                if (running && generation == proxyGeneration) {
                    recoverProfile(LinkCause.PROFILE_LOST, LinkState.NO_PROFILE)
                }
            }
        }
    }

    private fun register(hid: BluetoothHidDevice, generation: Long) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "PocketInput", "PocketInput HID", "dev.pocketinput",
            BluetoothHidDevice.SUBCLASS1_COMBO, Descriptors.HID_DESCRIPTOR,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
        )
        val accepted = runCatching {
            hid.registerApp(sdp, null, qos, mainExecutor, hidCallback(hid, generation))
        }.getOrDefault(false)
        if (accepted) {
            main.postDelayed(profileTimeout, PROFILE_TIMEOUT_MS)
        } else {
            recoverProfile(LinkCause.REGISTRATION_FAILED, LinkState.FAILED)
        }
    }

    private fun hidCallback(hid: BluetoothHidDevice, generation: Long) =
        object : BluetoothHidDevice.Callback() {
            private fun isCurrent(): Boolean = running && generation == proxyGeneration && proxy === hid

            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (!isCurrent()) return
                if (registered && this@HidTransport.registered) return
                main.removeCallbacks(profileTimeout)
                this@HidTransport.registered = registered
                if (!registered) {
                    recoverProfile(LinkCause.REGISTRATION_LOST, LinkState.FAILED)
                    return
                }
                main.postDelayed(stableRegistration, REGISTRATION_STABLE_MS)
                if (session == null && !confirmConnectedHost()) {
                    waitForHost(null)
                }
            }

            override fun onConnectionStateChanged(dev: BluetoothDevice, state: Int) {
                if (!isCurrent() || !registered) return
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> openSession(hid, dev)
                    BluetoothProfile.STATE_CONNECTING -> {
                        if (session == null && (requestedHost == null || requestedHost == dev)) {
                            requestedHost = dev
                            main.removeCallbacks(requestHostAgain)
                            startHostTimeout()
                            transition(LinkState.CONNECTING)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED -> {
                        if (session?.device == dev || requestedHost == dev) {
                            closeSession()
                            cancelHostAttempts()
                            waitForHost(LinkCause.HOST_DISCONNECTED)
                        }
                    }
                }
            }

            override fun onVirtualCableUnplug(dev: BluetoothDevice?) {
                if (!isCurrent() || dev == null) return
                if (dev.address == preferences.getString(HOST_KEY, null)) {
                    preferences.edit().remove(HOST_KEY).apply()
                }
                if (session?.device == dev || requestedHost == dev) {
                    closeSession()
                    cancelHostAttempts()
                    waitForHost(LinkCause.HOST_DISCONNECTED)
                }
            }

            override fun onGetReport(dev: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                if (!isCurrent() || !registered || dev == null) return
                val connected = session
                if (connected != null && connected.device != dev) return
                val report = if (connected != null) input.currentReport(id.toInt()) else when (id) {
                    Descriptors.MOUSE_REPORT_ID -> Descriptors.mouseReport(0, 0, 0, 0)
                    Descriptors.KEYBOARD_REPORT_ID -> Descriptors.keyboardReport(0)
                    Descriptors.HORIZONTAL_SCROLL_REPORT_ID -> Descriptors.horizontalScrollReport(0)
                    else -> null
                }
                if (report == null) return
                runCatching { hid.replyReport(dev, type, id, report) }
            }
        }

    private fun openSession(hid: BluetoothHidDevice, dev: BluetoothDevice) {
        if (session?.device == dev) return
        cancelHostAttempts()
        hostBackoff.reset()
        profileBackoff.reset()
        measurements.reset()
        val connected = Session(nextSessionId.incrementAndGet(), dev)
        session = connected
        if (dev.address != preferences.getString(HOST_KEY, null)) {
            preferences.edit().putString(HOST_KEY, dev.address).apply()
        }
        input.open(connected.id) { id, report ->
            session === connected && hid.sendReport(dev, id, report)
        }
        transition(LinkState.CONNECTED, peer = runCatching { dev.name }.getOrNull())
    }

    private fun confirmConnectedHost(): Boolean {
        val hid = proxy ?: return false
        val expected = requestedHost
        val connected = runCatching {
            hid.connectedDevices.firstOrNull { expected == null || it == expected }
        }.getOrNull() ?: return false
        openSession(hid, connected)
        return true
    }

    private fun closeSession() {
        session = null
        input.disconnect()
        main.removeCallbacks(publishMeasurements)
        measurementPublishPending = false
        measurements.reset()
    }

    private fun waitForHost(cause: LinkCause?) {
        main.removeCallbacks(requestHostAgain)
        val delay = if (knownHost() != null) hostBackoff.takeDelayMs() else null
        if (delay == null) {
            transition(LinkState.REGISTERED, cause)
        } else {
            transition(
                if (cause == null) LinkState.REGISTERED else LinkState.RECOVERING,
                cause,
                attemptsRemaining = hostBackoff.remaining + 1,
            )
            main.postDelayed(requestHostAgain, delay)
        }
    }

    private fun requestHost() {
        val hid = proxy ?: return
        if (!registered || session != null || prerequisite() != null) return
        val host = knownHost()
        if (host == null) {
            transition(LinkState.REGISTERED, LinkCause.HOST_UNAVAILABLE)
            return
        }
        requestedHost = host
        if (runCatching { hid.connect(host) }.getOrDefault(false)) {
            transition(LinkState.CONNECTING)
            startHostTimeout()
        } else {
            requestedHost = null
            waitForHost(LinkCause.HOST_UNAVAILABLE)
        }
    }

    private fun knownHost(): BluetoothDevice? {
        val address = preferences.getString(HOST_KEY, null) ?: return null
        return runCatching { adapter?.bondedDevices?.firstOrNull { it.address == address } }.getOrNull()
    }

    private fun startHostTimeout() {
        main.removeCallbacks(hostTimeout)
        main.postDelayed(hostTimeout, HOST_TIMEOUT_MS)
    }

    private fun cancelHostAttempts() {
        main.removeCallbacks(requestHostAgain)
        main.removeCallbacks(hostTimeout)
        requestedHost = null
    }

    private fun recoverProfile(cause: LinkCause, failure: LinkState) {
        invalidateProfile()
        if (!running) return
        prerequisite()?.let {
            transition(it)
            return
        }
        val delay = profileBackoff.takeDelayMs()
        if (delay == null) {
            transition(failure, cause)
        } else {
            transition(LinkState.RECOVERING, cause, attemptsRemaining = profileBackoff.remaining + 1)
            main.postDelayed(requestProfileAgain, delay)
        }
    }

    private fun invalidateProfile() {
        proxyGeneration++
        main.removeCallbacks(requestProfileAgain)
        main.removeCallbacks(profileTimeout)
        main.removeCallbacks(stableRegistration)
        cancelHostAttempts()
        closeSession()
        val oldProxy = proxy
        proxy = null
        registered = false
        if (oldProxy != null) {
            runCatching { oldProxy.unregisterApp() }
            closeProxy(oldProxy)
        }
    }

    private fun closeProxy(oldProxy: BluetoothProfile) {
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, oldProxy) }
    }

    private fun transition(
        state: LinkState,
        cause: LinkCause? = null,
        peer: String? = null,
        attemptsRemaining: Int = 0,
    ) {
        val sessionId = session?.id ?: 0L
        if (state != link.state || cause != link.cause || sessionId != link.sessionId) {
            if (history.size == HISTORY_SIZE) history.removeFirst()
            history.addLast(LinkEvent(System.currentTimeMillis(), state, cause))
            Log.i(TAG, "state=$state cause=$cause")
        }
        publish(measurements.update(LinkSnapshot(
            state = state,
            cause = cause,
            sessionId = sessionId,
            keyboardRevision = input.keyboardRevision,
            peer = peer,
            attemptsRemaining = attemptsRemaining,
            history = history.toList(),
        )))
    }

    private fun publish(snapshot: LinkSnapshot) {
        if (link == snapshot) return
        link = snapshot
        onLinkChanged(snapshot)
    }

    companion object {
        private const val TAG = "PocketInputHID"
        private const val HOST_KEY = "last_connected_host"
        private const val HISTORY_SIZE = 12
        private const val PROFILE_TIMEOUT_MS = 6_000L
        private const val REGISTRATION_STABLE_MS = 30_000L
        private const val HOST_TIMEOUT_MS = 8_000L
        private const val MEASUREMENTS_INTERVAL_MS = 200L

        private val nextSessionId = AtomicLong()
        private val bluetoothUnavailable = setOf(
            LinkState.NEEDS_PERMISSION, LinkState.NO_ADAPTER, LinkState.BLUETOOTH_OFF,
        )

        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }
}
