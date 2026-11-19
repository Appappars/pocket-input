package dev.pocketinput.ui.connect

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.LinkCause
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

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val cause = link.cause
    val pcUnavailable = link.state == LinkState.REGISTERED &&
        (cause == LinkCause.HOST_DISCONNECTED || cause == LinkCause.HOST_UNAVAILABLE)
    val stateLabel = stringResource(
        if (pcUnavailable) R.string.board_connection_pc_unavailable else link.state.label(),
    )
    val statusLabel = link.peer?.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.board_connection_peer_status, it, stateLabel)
    } ?: stateLabel
    val accent = when (link.state) {
        LinkState.INIT, LinkState.CONNECTING, LinkState.RECOVERING, LinkState.CONNECTED -> colors.primary
        LinkState.REGISTERED -> if (cause == null) colors.primary else colors.error
        LinkState.STOPPED -> colors.outline
        else -> colors.error
    }
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val headerGap = if (pcUnavailable && maxHeight >= 700.dp && fontScale <= 1.2f) 64.dp else 8.dp
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.connect_title),
                    modifier = Modifier.semantics { heading() },
                    color = colors.onBackground,
                    style = typography.headlineMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Text(
                        text = statusLabel,
                        color = if (accent == colors.error) colors.error else colors.onSurfaceVariant,
                        style = typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(headerGap))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(5.dp).background(accent))
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BluetoothMark(accent)
                        if (cause != null || link.state == LinkState.NO_ADAPTER) {
                            Text(
                                text = when {
                                    pcUnavailable && cause == LinkCause.HOST_UNAVAILABLE ->
                                        stringResource(R.string.board_connection_pc_not_responding)
                                    cause != null -> stringResource(cause.label())
                                    else -> stateLabel
                                },
                                modifier = Modifier.semantics {
                                    heading()
                                    if (cause != null) liveRegion = LiveRegionMode.Polite
                                },
                                style = typography.titleLarge,
                            )
                        }
                        when (link.state) {
                            LinkState.NEEDS_PERMISSION -> {
                                Text(
                                    stringResource(R.string.link_permission_explanation),
                                    color = colors.onSurfaceVariant,
                                    style = typography.bodyMedium,
                                )
                                ConnectAction(
                                    if (permissionSettingsRequired) R.string.link_app_settings
                                    else R.string.link_allow_bluetooth,
                                    onRequestPermission,
                                )
                            }

                            LinkState.STOPPED -> ConnectAction(R.string.link_start_session, onRetry)

                            LinkState.BLUETOOTH_OFF ->
                                ConnectAction(R.string.link_open_bluetooth, onOpenBluetoothSettings)

                            LinkState.INIT, LinkState.CONNECTING, LinkState.RECOVERING -> {
                                if (link.attemptsRemaining > 0 && link.state == LinkState.RECOVERING) {
                                    Text(
                                        stringResource(R.string.link_attempts_left, link.attemptsRemaining),
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        color = colors.onSurfaceVariant,
                                        style = typography.bodyMedium,
                                    )
                                }
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }

                            LinkState.REGISTERED -> {
                                val guidance = if (pcUnavailable) R.string.board_connection_check_pc
                                else R.string.link_pair_on_windows
                                Text(
                                    stringResource(guidance),
                                    color = colors.onSurfaceVariant,
                                    style = typography.bodyMedium,
                                )
                                if (pcUnavailable) {
                                    HorizontalDivider(color = colors.outlineVariant)
                                    ConnectAction(R.string.board_connection_retry, onRetry)
                                    ConnectAction(
                                        R.string.board_connection_bluetooth_settings,
                                        onOpenBluetoothSettings,
                                        primary = false,
                                    )
                                    Text(
                                        stringResource(R.string.board_connection_resume),
                                        color = colors.onSurfaceVariant,
                                        style = typography.bodySmall,
                                    )
                                } else {
                                    ConnectAction(R.string.link_open_bluetooth, onOpenBluetoothSettings)
                                    if (cause != null) ConnectAction(R.string.link_retry, onRetry, primary = false)
                                }
                            }

                            LinkState.NO_PROFILE, LinkState.FAILED -> ConnectAction(R.string.link_retry, onRetry)
                            LinkState.CONNECTED -> ConnectAction(R.string.link_open_controls, onConnected)
                            LinkState.NO_ADAPTER -> Unit
                        }
                    }
                }
            }
            if (onBack != null) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.back), textAlign = TextAlign.Center)
                }
            }
            if (onStopSession != null) {
                TextButton(onClick = onStopSession, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.link_stop_session), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ConnectAction(@StringRes label: Int, onClick: () -> Unit, primary: Boolean = true) {
    val text = stringResource(label)
    val shape = RoundedCornerShape(12.dp)
    if (primary) {
        Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = shape) {
            Text(text, textAlign = TextAlign.Center)
        }
    } else {
        FilledTonalButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = shape) {
            Text(text, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BluetoothMark(color: Color) {
    Canvas(Modifier.size(13.dp, 20.dp)) {
        val inset = 1.dp.toPx()
        val centerX = size.width / 2f
        val upperY = size.height * 0.27f
        val lowerY = size.height * 0.73f
        val path = Path().apply {
            moveTo(inset, lowerY)
            lineTo(size.width - inset, upperY)
            lineTo(centerX, inset)
            lineTo(centerX, size.height - inset)
            lineTo(size.width - inset, lowerY)
            lineTo(inset, upperY)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
