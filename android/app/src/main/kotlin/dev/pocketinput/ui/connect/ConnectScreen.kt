package dev.pocketinput.ui.connect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.HidTransport
import kotlinx.coroutines.delay

/**
 * Фаза 0, spec/05-roadmap-qa.md 5.3 п.5: список спаренных устройств,
 * кнопка «Подключить», текст ошибки при null-профиле HID.
 */
@Suppress("MissingPermission") // BLUETOOTH_CONNECT запрашивается ниже до чтения bondedDevices
@Composable
fun ConnectScreen(transport: HidTransport, onConnected: () -> Unit) {
    val errorNoHid = stringResource(R.string.error_no_hid)
    val connectLabel = stringResource(R.string.connect_button)

    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
    // Профиль HID выдаётся асинхронно (getProfileProxy), поэтому статус перечитываем.
    var hidAvailable by remember { mutableStateOf(transport.isHidSupported()) }
    var btEnabled by remember {
        mutableStateOf(BluetoothAdapter.getDefaultAdapter()?.isEnabled == true)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            devices = readBondedDevices()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            devices = readBondedDevices()
        }
        // ждём колбэк профиля: на части прошивок он приходит через 200-800 мс
        repeat(6) {
            delay(300)
            hidAvailable = transport.isHidSupported()
            btEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
            if (devices.isEmpty()) devices = readBondedDevices()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))

        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(
                        if (hidAvailable) R.string.status_hid_ok else R.string.status_hid_none
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hidAvailable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = stringResource(
                        if (btEnabled) R.string.status_bt_on else R.string.status_bt_off
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (!hidAvailable) {
            Spacer(Modifier.height(8.dp))
            // Дословная строка из @string/error_no_hid: «Телефон не поддерживает HID; нужен агент».
            Text(
                text = errorNoHid,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (devices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (btEnabled) R.string.empty_devices else R.string.empty_devices_bt_off
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(devices) { device ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    RadioButton(
                        selected = selected == device,
                        onClick = { selected = device },
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(device.name ?: device.address)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Button(
            onClick = {
                val device = selected
                val connectTo = transport.javaClass.methods.firstOrNull {
                    it.name == "connectTo" && it.parameterTypes.size == 1
                }
                if (connectTo != null && device != null) {
                    runCatching { connectTo.invoke(transport, device) }
                }
                onConnected()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(connectLabel)
        }
    }
}

@Suppress("MissingPermission")
private fun readBondedDevices(): List<BluetoothDevice> =
    BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
