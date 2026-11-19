package dev.pocketinput.ui.trackpad

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState

// Бит 1 = ЛКМ, бит 2 = ПКМ (HidTransport.pressButton/releaseButton).
private const val BUTTON_LEFT = 1
private const val BUTTON_RIGHT = 2

@Composable
internal fun TrackpadScreen(
    transport: HidTransport,
    settings: SettingsState,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val link = transport.link
    val sessionId = link.sessionId
    val connected = sessionId != 0L && link.state == LinkState.CONNECTED
    var lmbLatched by remember(transport, sessionId) { mutableStateOf(false) }
    var gestureHold by remember(transport, sessionId) { mutableStateOf(false) }

    fun sessionIsCurrent(): Boolean {
        val current = transport.link
        return connected && current.sessionId == sessionId && current.state == LinkState.CONNECTED
    }

    fun updateHold(latch: Boolean = lmbLatched, gesture: Boolean = gestureHold) {
        val wasHeld = lmbLatched || gestureHold
        lmbLatched = latch
        gestureHold = gesture
        val held = latch || gesture
        if (held == wasHeld || !sessionIsCurrent()) return
        if (held) transport.pressButton(BUTTON_LEFT) else transport.releaseButton(BUTTON_LEFT)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, transport, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) updateHold(latch = false, gesture = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            updateHold(latch = false, gesture = false)
        }
    }

    fun bump() {
        if (settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val showGuides = maxWidth >= 272.dp && maxHeight >= 360.dp
        val showHoldHint = maxHeight >= 220.dp
        val holdHint = if (lmbLatched || gestureHold) R.string.board_trackpad_edge else R.string.board_trackpad_hold

        Column(Modifier.fillMaxSize()) {
            if (showGuides) {
                BoardGestureGuides()
                Spacer(Modifier.height(11.dp))
            }

            TrackpadSurface(
                transport = transport,
                settings = settings,
                modifier = Modifier.fillMaxWidth().weight(1f),
                leftLatched = lmbLatched,
                onLeftHold = { updateHold(gesture = it) },
                onLeftHoldCancelled = {
                    gestureHold = false
                    lmbLatched
                },
                label = stringResource(R.string.board_trackpad_label),
                hint = if (showHoldHint) stringResource(holdHint) else null,
            )

            Spacer(Modifier.height(if (showGuides) 20.dp else 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoardMouseButton(
                    label = stringResource(if (lmbLatched) R.string.btn_lmb_latched else R.string.btn_lmb),
                    enabled = connected,
                    active = lmbLatched,
                    modifier = Modifier.weight(1f).semantics {
                        toggleableState = if (lmbLatched) ToggleableState.On else ToggleableState.Off
                    },
                    onClick = {
                        if (sessionIsCurrent()) {
                            updateHold(latch = !lmbLatched)
                            bump()
                        }
                    },
                )
                BoardMouseButton(
                    label = stringResource(R.string.btn_rmb),
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (sessionIsCurrent()) {
                            transport.tap(BUTTON_RIGHT)
                            bump()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BoardGestureGuides() {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoardGestureGuide(
            title = stringResource(R.string.board_trackpad_scroll),
            description = stringResource(R.string.board_trackpad_scroll_hint),
            zoom = false,
            modifier = Modifier.weight(1f).padding(start = 2.dp, top = 2.dp),
        )
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        BoardGestureGuide(
            title = stringResource(R.string.board_trackpad_zoom),
            description = stringResource(R.string.board_trackpad_zoom_hint),
            zoom = true,
            modifier = Modifier.weight(1f).padding(top = 2.dp),
        )
    }
}

@Composable
private fun BoardGestureGuide(title: String, description: String, zoom: Boolean, modifier: Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        BoardGestureIcon(zoom, Modifier.padding(top = 3.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BoardGestureIcon(zoom: Boolean, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(18.dp)) {
        val scale = size.minDimension / 18f
        val stroke = 1.6.dp.toPx()
        fun line(x1: Int, y1: Int, x2: Int, y2: Int) {
            drawLine(
                color,
                Offset(x1 * scale, y1 * scale),
                Offset(x2 * scale, y2 * scale),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        if (zoom) {
            line(2, 16, 16, 2)
            line(10, 2, 16, 2)
            line(16, 2, 16, 8)
            line(2, 10, 2, 16)
            line(2, 16, 8, 16)
        } else {
            line(1, 9, 17, 9)
            line(9, 1, 9, 17)
            line(1, 9, 5, 5)
            line(1, 9, 5, 13)
            line(17, 9, 13, 5)
            line(17, 9, 13, 13)
            line(9, 1, 5, 5)
            line(9, 1, 13, 5)
            line(9, 17, 5, 13)
            line(9, 17, 13, 13)
        }
    }
}

@Composable
private fun BoardMouseButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> colors.onSurface.copy(alpha = 0.12f)
                active -> colors.primary
                else -> colors.outline
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) colors.primary else colors.surface,
            contentColor = if (active) colors.onPrimary else colors.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(label, textAlign = TextAlign.Center)
    }
}
