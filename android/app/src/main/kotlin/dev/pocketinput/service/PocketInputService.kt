package dev.pocketinput.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.pocketinput.R

/**
 * Foreground service фазы 0: удерживает процесс живым, пока телефон
 * работает как HID-устройство. Wake lock в фазе 0 не нужен.
 */
class PocketInputService : Service() {

    companion object {
        private const val CHANNEL_ID = "pocket"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PocketInputSvc"

        /** Статус HID-подключения; обновляется транспортным слоем (фаза 1). */
        @Volatile
        var connected: Boolean = false
    }

    /** true, если startForeground прошёл: иначе уведомление слать некуда. */
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (e: SecurityException) {
            // Находка эмулятора (фаза 0): при targetSdk 34 FGS типа connectedDevice требует
            // ВЫДАННОГО runtime-разрешения BLUETOOTH_CONNECT, одного <uses-permission> мало.
            // Пока пользователь не дал доступ к Bluetooth, работаем без foreground, но не падаем.
            foregroundStarted = false
            Log.w(TAG, "startForeground denied: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Перестраивает и показывает уведомление с текущим статусом подключения. */
    fun updateNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val statusText = getString(
            if (connected) R.string.notif_connected else R.string.notif_disconnected,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
