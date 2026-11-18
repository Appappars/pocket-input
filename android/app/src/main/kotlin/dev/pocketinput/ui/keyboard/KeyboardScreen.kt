package dev.pocketinput.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState
import dev.pocketinput.ui.link.LinkStatusBar
import dev.pocketinput.ui.trackpad.TrackpadSurface

private enum class InputStatus(val label: Int) {
    QUEUED(R.string.kb_input_queued),
    QUEUE_REJECTED(R.string.kb_input_queue_rejected),
    SEND_FAILED(R.string.kb_input_send_failed),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardScreen(
    transport: HidTransport,
    settings: SettingsState,
    onLayoutChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val link = transport.link
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val connected = link.state == LinkState.CONNECTED && link.sessionId != 0L
    val sessionId = if (resumed && connected) link.sessionId else 0L
    val editorActive = sessionId != 0L
    val editRevision = link.keyboardRevision
    var revision by remember(transport, sessionId, editRevision) { mutableIntStateOf(0) }
    val editorRevision = revision

    var field by remember(transport, sessionId, editRevision, editorRevision) {
        mutableStateOf(TextFieldValue())
    }
    var committedField by remember(transport, sessionId, editRevision, editorRevision) {
        mutableStateOf(TextFieldValue())
    }
    var queuedText by remember(transport, sessionId, editRevision, editorRevision) {
        mutableStateOf("")
    }
    var russianLayout by remember(transport, sessionId) {
        mutableStateOf(settings.layout.equals("ru", ignoreCase = true))
    }
    var skipped by remember(transport, sessionId, editRevision) { mutableIntStateOf(0) }
    var inputStatus by remember(transport, sessionId, editRevision) {
        mutableStateOf<InputStatus?>(null)
    }

    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var requestInitialKeyboard by remember(transport, sessionId) { mutableStateOf(editorActive) }

    fun showKeyboard() {
        focus.requestFocus()
        ime?.show()
    }

    LaunchedEffect(transport, sessionId, editRevision, editorRevision) {
        if (!editorActive) {
            ime?.hide()
        } else if (requestInitialKeyboard || imeVisible) {
            requestInitialKeyboard = false
            showKeyboard()
        }
    }

    DisposableEffect(transport, sessionId) {
        onDispose {
            if (editorActive) transport.keyUp(expectedSessionId = sessionId)
        }
    }

    fun handleChange(value: TextFieldValue) {
        val current = transport.link
        if (!editorActive || !resumed || current.state != LinkState.CONNECTED || current.sessionId != sessionId ||
            current.keyboardRevision != editRevision || revision != editorRevision
        ) {
            return
        }
        val edit = planKeyboardEdit(queuedText, value.text, value.composition != null, russianLayout)
        if (edit == null) {
            field = value
            return
        }
        if (edit.keys.isNotEmpty()) {
            if (!transport.keyTaps(edit.keys, sessionId, editRevision)) {
                field = committedField
                inputStatus = InputStatus.QUEUE_REJECTED
                return
            }
            inputStatus = InputStatus.QUEUED
            if (edit.russianLayout != russianLayout) {
                russianLayout = edit.russianLayout
                onLayoutChange(if (russianLayout) "ru" else "en")
            }
        }
        skipped = edit.skippedCharacters
        queuedText = edit.queuedText
        committedField = TextFieldValue(edit.localText, selection = TextRange(edit.localText.length))
        field = committedField
    }

    fun invalidateTail() {
        if (revision != editorRevision) return
        if (field.text.isEmpty() && field.composition == null && committedField.text.isEmpty()) return
        skipped = 0
        revision++
    }

    fun deleteOnPc() {
        if (!editorActive || !resumed) return
        val accepted = transport.keyTaps(listOf(Usages.BACKSPACE to 0), sessionId, editRevision)
        if (accepted) invalidateTail()
        inputStatus = if (accepted) InputStatus.QUEUED else InputStatus.QUEUE_REJECTED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(stringResource(R.string.back), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = stringResource(R.string.keyboard_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }

        LinkStatusBar(
            link = link,
            onClick = {
                if (transport.link.state == LinkState.CONNECTED) onOpenDiagnostics() else onOpenConnection()
            },
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = stringResource(if (connected) R.string.kb_windows_layout else R.string.kb_connection_hint),
            style = MaterialTheme.typography.labelSmall,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TrackpadSurface(
                transport = transport,
                settings = settings,
                modifier = Modifier.fillMaxSize(),
                onFocusChangingInput = ::invalidateTail,
            )
            if (editorActive) {
                key(transport, sessionId, editRevision, editorRevision) {
                    BasicTextField(
                        value = field,
                        onValueChange = ::handleChange,
                        modifier = Modifier
                            .size(1.dp)
                            .alpha(0.01f)
                            .focusRequester(focus)
                            .clearAndSetSemantics {},
                        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                        cursorBrush = SolidColor(Color.Transparent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.None,
                        ),
                    )
                }
            }
        }

        if (editorActive) {
            val failureStatus = InputStatus.SEND_FAILED.takeIf { link.keyboardFailureRevision == editRevision }
            (inputStatus ?: failureStatus)?.let { status ->
                Text(
                    text = stringResource(status.label),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = if (status == InputStatus.QUEUED) Modifier else Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    color = if (status == InputStatus.QUEUED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (skipped > 0) {
                Text(
                    text = stringResource(R.string.kb_input_skipped),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = ::showKeyboard, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.kb_show_keyboard))
                }
                TextButton(onClick = ::deleteOnPc, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.kb_delete_on_pc))
                }
            }
        }
    }
}
