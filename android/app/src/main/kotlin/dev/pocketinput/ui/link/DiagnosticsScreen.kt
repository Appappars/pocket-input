package dev.pocketinput.ui.link

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState
import java.util.Date

private enum class ProbeResult { IDLE, PENDING, SENT, FAILED }

@Composable
fun DiagnosticsScreen(
    transport: HidTransport,
    onBack: () -> Unit,
    onOpenConnection: () -> Unit,
    onStopSession: () -> Unit,
    onEnableNotifications: (() -> Unit)?,
) {
    val snapshot = transport.link
    var probe by remember(snapshot.sessionId) { mutableStateOf(ProbeResult.IDLE) }
    val context = LocalContext.current
    val time = remember(context) { DateFormat.getTimeFormat(context) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.link_diagnostics), style = MaterialTheme.typography.headlineSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(snapshot.state.label()), style = MaterialTheme.typography.titleMedium)
            snapshot.cause?.let { Text(stringResource(it.label())) }
            snapshot.peer?.let { Text(it) }
        }

        if (snapshot.isConnected) {
            if (snapshot.sampleCount > 0) {
                Text(stringResource(R.string.link_recent_reports, snapshot.sampleCount, snapshot.recentFailures))
                Text(stringResource(R.string.link_queue_delay, snapshot.queueP95Ms))
                Text(stringResource(R.string.link_session_reports, snapshot.sentOk, snapshot.sendFailed))
            } else {
                Text(stringResource(R.string.link_no_reports))
            }

            Button(
                onClick = {
                    probe = ProbeResult.PENDING
                    transport.selfTest { sent -> probe = if (sent) ProbeResult.SENT else ProbeResult.FAILED }
                },
                enabled = probe != ProbeResult.PENDING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.link_probe))
            }
            Text(
                text = stringResource(when (probe) {
                    ProbeResult.IDLE -> R.string.link_probe_help
                    ProbeResult.PENDING -> R.string.link_probe_pending
                    ProbeResult.SENT -> R.string.link_probe_sent
                    ProbeResult.FAILED -> R.string.link_probe_failed
                }),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Button(onClick = onOpenConnection, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_recover_action))
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.link_history), style = MaterialTheme.typography.titleMedium)
        if (snapshot.history.isEmpty()) {
            Text(stringResource(R.string.link_no_events))
        } else {
            for (event in snapshot.history.asReversed()) {
                Column {
                    Text(stringResource(
                        R.string.link_history_event,
                        time.format(Date(event.atMs)),
                        stringResource(event.state.label()),
                    ))
                    event.cause?.let {
                        Text(stringResource(it.label()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (snapshot.state != LinkState.STOPPED && snapshot.state != LinkState.NEEDS_PERMISSION) {
            if (onEnableNotifications != null) {
                OutlinedButton(onClick = onEnableNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.link_enable_notifications))
                }
            }
            OutlinedButton(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_stop_session))
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.link_back_to_controls))
        }
    }
}
