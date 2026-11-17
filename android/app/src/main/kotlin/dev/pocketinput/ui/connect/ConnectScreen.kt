package dev.pocketinput.ui.connect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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

    // Android 12+ (S): BLUETOOTH_CONNECT нужен ДО чтения bondedDevices и device.name
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
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!transport.isHidSupported()) {
            // Дословная строка из @string/error_no_hid: «Телефон не поддерживает HID; нужен агент».
            // Приложение не падает: только показываем текст.
            Text(
                text = errorNoHid,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selected == device,
                        onClick = { selected = device }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(device.name ?: device.address)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = {
            val device = selected
            // transport.connectTo(device) — если такой метод есть; иначе просто onConnected().
            val connectTo = transport.javaClass.methods.firstOrNull {
                it.name == "connectTo" && it.parameterTypes.size == 1
            }
            if (connectTo != null && device != null) {
                runCatching { connectTo.invoke(transport, device) }
            }
            onConnected()
        }) {
            Text(connectLabel)
        }
    }
}

@Suppress("MissingPermission")
private fun readBondedDevices(): List<BluetoothDevice> =
    BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
