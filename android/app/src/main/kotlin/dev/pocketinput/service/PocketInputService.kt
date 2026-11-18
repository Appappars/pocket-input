package dev.pocketinput.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.pocketinput.MainActivity
import dev.pocketinput.R
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkSnapshot
import dev.pocketinput.hid.LinkState
import dev.pocketinput.ui.link.label

class PocketInputService : Service() {
    lateinit var transport: HidTransport
        private set

    inner class LocalBinder : Binder() {
        val service: PocketInputService get() = this@PocketInputService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val stopTimeout = Runnable { finishStop() }
    private var foreground = false
    private var controlVisible = false
    private var notificationState: LinkState? = null
    private var stopPending = false
    private var stopGeneration = 0L

    private val bluetoothEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            transport.refreshBluetooth()
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                transport.refreshBondedHost()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        transport = HidTransport(this, ::linkChanged)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_title), NotificationManager.IMPORTANCE_LOW),
        )
        ContextCompat.registerReceiver(
            this,
            bluetoothEvents,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
        } else if (stopPending) {
            stopPending = false
            main.removeCallbacks(stopTimeout)
            setPaused(false)
            updateInputEnabled()
        } else if (!foreground) {
            startSession(startId)
        }
        return START_NOT_STICKY
    }

    fun stopSession() {
        if (stopPending) return
        setPaused(true)
        stopPending = true
        val generation = ++stopGeneration
        updateInputEnabled()
        if (transport.link.isConnected) {
            transport.resetInput()
            main.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
            transport.selfTest {
                if (stopPending && stopGeneration == generation) finishStop()
            }
        } else {
            finishStop()
        }
    }

    private fun finishStop() {
        stopPending = false
        main.removeCallbacks(stopTimeout)
        foreground = false
        transport.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setControlVisible(visible: Boolean) {
        controlVisible = visible
        updateInputEnabled()
    }

    private fun updateInputEnabled() {
        transport.setInputEnabled(controlVisible && foreground && !stopPending)
    }

    private fun startSession(startId: Int) {
        if (!HidTransport.hasBluetoothPermission(this)) {
            transport.serviceStartFailed()
            stopSelfResult(startId)
            return
        }

        try {
            val notice = notification(LinkState.INIT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notice)
            }
        } catch (_: SecurityException) {
            transport.serviceStartFailed()
            stopSelfResult(startId)
            return
        }

        notificationState = LinkState.INIT
        foreground = true
        setPaused(false)
        transport.start()
        updateInputEnabled()
    }

    private fun linkChanged(link: LinkSnapshot) {
        if (!foreground || notificationState == link.state) return
        notificationState = link.state
        refreshNotification()
    }

    fun refreshNotification() {
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(transport.link.state))
    }

    private fun notification(state: LinkState): Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val stop = Intent(this, PocketInputService::class.java).setAction(ACTION_STOP)
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(state.label()))
            .setContentIntent(PendingIntent.getActivity(this, 0, open, pendingFlags))
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.link_stop_session),
                    PendingIntent.getService(this, 0, stop, pendingFlags),
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        foreground = false
        stopPending = false
        main.removeCallbacks(stopTimeout)
        unregisterReceiver(bluetoothEvents)
        transport.close()
        super.onDestroy()
    }

    private fun setPaused(paused: Boolean) {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(PAUSED, paused).apply()
    }

    companion object {
        private const val CHANNEL_ID = "pocket"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.pocketinput.action.STOP_HID"
        private const val PREFERENCES = "pocket_input_service"
        private const val PAUSED = "paused"
        private const val STOP_TIMEOUT_MS = 500L

        fun isPaused(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(PAUSED, false)
    }
}
