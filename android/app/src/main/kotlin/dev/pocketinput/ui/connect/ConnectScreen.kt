package dev.pocketinput.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.LinkSnapshot
import dev.pocketinput.hid.LinkState
import dev.pocketinput.ui.link.label

@Composable
fun ConnectScreen(
    link: LinkSnapshot,
    permissionSettingsRequired: Boolean,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRetry: () -> Unit,
    onConnected: () -> Unit,
    onBack: (() -> Unit)? = null,
    onStopSession: (() -> Unit)? = null,
) {
    LaunchedEffect(link.sessionId) {
        if (link.isConnected) onConnected()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineSmall)
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(link.state.label()), style = MaterialTheme.typography.titleMedium)
            link.cause?.let { Text(stringResource(it.label()), style = MaterialTheme.typography.bodyMedium) }
            link.peer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (link.attemptsRemaining > 0 && link.state == LinkState.RECOVERING) {
                Text(stringResource(R.string.link_attempts_left, link.attemptsRemaining))
            }
        }

        when (link.state) {
            LinkState.NEEDS_PERMISSION -> {
                Text(stringResource(R.string.link_permission_explanation))
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(
                        if (permissionSettingsRequired) R.string.link_app_settings else R.string.link_allow_bluetooth,
                    ))
                }
            }

            LinkState.STOPPED -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_start_session))
            }

            LinkState.BLUETOOTH_OFF -> Button(onClick = onOpenBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_open_bluetooth))
            }

            LinkState.INIT, LinkState.CONNECTING, LinkState.RECOVERING -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )

            LinkState.REGISTERED -> {
                Text(stringResource(R.string.link_pair_on_windows))
                Button(onClick = onOpenBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.link_open_bluetooth))
                }
                if (link.cause != null) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.link_retry))
                    }
                }
            }

            LinkState.NO_PROFILE, LinkState.FAILED -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_retry))
            }

            LinkState.CONNECTED -> Button(onClick = onConnected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_open_controls))
            }

            LinkState.NO_ADAPTER -> Unit
        }

        if (onBack != null) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_back_to_controls))
            }
        }
        if (onStopSession != null) {
            TextButton(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_stop_session))
            }
        }
    }
}
