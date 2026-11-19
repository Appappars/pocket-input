package dev.pocketinput.ui.keyboard

import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState
import dev.pocketinput.ui.trackpad.TrackpadSurface
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardScreen(
    transport: HidTransport,
    settings: SettingsState,
    onLayoutChange: (String) -> Unit,
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
    var queuedText by remember(transport, sessionId, editRevision, editorRevision) { mutableStateOf("") }
    val imeText = remember(transport, sessionId, editRevision, editorRevision) { ImeTextRebase() }
    var trimRevision by remember(imeText) { mutableIntStateOf(0) }
    var russianLayout by remember(transport, sessionId) {
        mutableStateOf(settings.layout.equals("ru", ignoreCase = true))
    }
    var skipped by remember(transport, sessionId, editRevision) { mutableIntStateOf(0) }
    var queueRejected by remember(transport, sessionId, editRevision) { mutableStateOf(false) }
    var panel by remember(transport, sessionId, editRevision, editorRevision) {
        mutableStateOf<KeyboardPanel?>(null)
    }
    var openIme by remember(transport, sessionId) { mutableStateOf(editorActive) }

    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val window = LocalWindowInfo.current

    LaunchedEffect(editorActive) {
        if (!editorActive) ime?.hide()
    }

    DisposableEffect(transport, sessionId) {
        onDispose {
            if (editorActive) transport.keyUp(expectedSessionId = sessionId)
        }
    }

    fun isCurrentEditor(): Boolean {
        val current = transport.link
        return editorActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            current.state == LinkState.CONNECTED && current.sessionId == sessionId &&
            current.keyboardRevision == editRevision && revision == editorRevision
    }

    fun submitKeys(keys: List<Pair<Int, Int>>): Boolean {
        if (!isCurrentEditor()) return false
        val accepted = transport.keyTaps(keys, sessionId, editRevision)
        queueRejected = !accepted
        return accepted
    }

    fun handleChange(
        value: TextFieldValue,
        previousEditorText: String? = null,
        prefixInIme: (Int) -> String = { length -> value.text.take(length) },
    ) {
        if (!isCurrentEditor()) return
        if (value.text == field.text) {
            field = value
            return
        }
        val edit = planKeyboardEdit(queuedText, value.text, russianLayout)
        if (edit.keys.isNotEmpty() && !submitKeys(edit.keys)) return
        queueRejected = false
        skipped = edit.skippedCharacters
        queuedText = edit.queuedText
        if (edit.droppedPrefix != 0 || previousEditorText != null) {
            trimRevision = imeText.discardPrefix(prefixInIme(edit.droppedPrefix), previousEditorText)
        }
        field = value.withEditableTail(edit)
    }

    fun resetEditor() {
        if (revision != editorRevision) return
        skipped = 0
        openIme = openIme || imeVisible
        revision++
    }

    fun selectLayout(russian: Boolean) {
        if (!isCurrentEditor() || russianLayout == russian) return
        resetEditor()
        russianLayout = russian
        onLayoutChange(if (russian) "ru" else "en")
    }

    fun insertAtSelection(text: String) {
        val previous = field
        val range = previous.selection
        handleChange(
            TextFieldValue(
                text = previous.text.replaceRange(range.min, range.max, text),
                selection = TextRange(range.min + text.length),
            ),
            previousEditorText = previous.text,
        ) { length ->
            val oldLength = if (length <= range.min) {
                length
            } else {
                range.max + (length - range.min - text.length).coerceAtLeast(0)
            }
            previous.text.take(oldLength)
        }
    }

    fun openPanel(target: KeyboardPanel) {
        if (isCurrentEditor()) panel = target
    }

    if (!editorActive) return
    val visiblePanel = panel
    BackHandler(enabled = visiblePanel != null) { panel = null }

    val inputStatus = when {
        queueRejected -> R.string.kb_input_queue_rejected
        link.keyboardFailureRevision == editRevision -> R.string.kb_input_send_failed
        skipped > 0 -> R.string.kb_input_skipped
        else -> null
    }
    val layoutLabel = stringResource(R.string.kb_pc_layout, if (russianLayout) "RU" else "EN")
    val keysLabel = stringResource(R.string.kb_special_keys)
    val density = LocalDensity.current
    var editorHeightPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < 216.dp || maxWidth < 304.dp || density.fontScale > 1.25f
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            key(transport, sessionId, editRevision, editorRevision) {
                var editorFocused by remember { mutableStateOf(false) }
                val renderedTrimRevision = trimRevision
                Row(
                    modifier = Modifier.fillMaxWidth().onSizeChanged { editorHeightPx = it.height },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyboardEditorInput(
                        canForward = {
                            isCurrentEditor() && editorFocused && window.isWindowFocused &&
                                field.text.isEmpty() && field.composition == null && queuedText.isEmpty()
                        },
                        onBackspaces = { count -> submitKeys(List(count) { Usages.BACKSPACE to 0 }) },
                    ) {
                        CompactEditor(
                            value = field,
                            onValueChange = { value ->
                                imeText.editableValue(value)?.let { handleChange(it) }
                            },
                            onValueApplied = { imeText.onValueApplied(renderedTrimRevision) },
                            onEnter = { insertAtSelection("\n") },
                            focused = editorFocused,
                            modifier = Modifier.weight(1f)
                                .focusRequester(focus)
                                .onFocusChanged { editorFocused = it.isFocused },
                        )
                    }
                    if (compact) {
                        InputAction(keysLabel, onClick = { openPanel(KeyboardPanel.KEYS) })
                    }
                }
                LaunchedEffect(Unit) {
                    if (openIme || imeVisible) {
                        snapshotFlow { window.isWindowFocused }.first { it }
                        openIme = false
                        focus.requestFocus()
                        ime?.show()
                    }
                }
            }

            if (visiblePanel == null) {
                if (!compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InputAction(layoutLabel, { openPanel(KeyboardPanel.LAYOUT) }, Modifier.weight(1.05f))
                        InputAction(
                            stringResource(R.string.kb_symbols),
                            { openPanel(KeyboardPanel.SYMBOLS) },
                            Modifier.weight(0.8f),
                        )
                        InputAction(keysLabel, { openPanel(KeyboardPanel.KEYS) }, Modifier.weight(1.25f))
                    }
                }
                if (inputStatus != null) {
                    Text(
                        text = stringResource(inputStatus),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                TrackpadSurface(
                    transport = transport,
                    settings = settings,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = stringResource(R.string.workspace_mini_pad),
                    hint = stringResource(R.string.workspace_tap_click),
                    onFocusChangingInput = {
                        if (field.text.isNotEmpty() || field.composition != null) resetEditor()
                    },
                )
            }
        }

        if (visiblePanel != null) {
            val inset = if (maxHeight < 260.dp) 0.dp else with(density) { editorHeightPx.toDp() } + 10.dp
            val closeLabel = stringResource(R.string.kb_close_panel)
            Box(modifier = Modifier.matchParentSize().padding(top = inset)) {
                Box(
                    modifier = Modifier.matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.16f))
                        .clickable(onClickLabel = closeLabel) { panel = null },
                )
                KeyboardCommandPanel(
                    panel = visiblePanel,
                    russianLayout = russianLayout,
                    compact = compact,
                    onClose = { panel = null },
                    onKey = { usage ->
                        panel = null
                        if (submitKeys(listOf(usage to 0))) resetEditor()
                    },
                    onSymbol = { symbol ->
                        panel = null
                        insertAtSelection(symbol.toString())
                    },
                    onAllSymbols = { openPanel(KeyboardPanel.SYMBOLS) },
                    onLayout = { russian ->
                        panel = null
                        selectLayout(russian)
                    },
                    onSwitchLayout = {
                        panel = null
                        if (submitKeys(listOf(Usages.SPACE to CharMap.LGUI))) selectLayout(!russianLayout)
                    },
                    onChooseIme = {
                        panel = null
                        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onValueApplied: () -> Unit,
    onEnter: () -> Unit,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val accessibleName = stringResource(R.string.kb_editor)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { contentDescription = accessibleName },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontSize = 19.sp),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { onEnter() }),
        decorationBox = { innerTextField ->
            // CoreTextField has installed the displayed value in its edit buffer.
            SideEffect(onValueApplied)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(shape)
                    .background(colors.surface)
                    .border(1.dp, if (focused) colors.primary else colors.outlineVariant, shape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.kb_editor_prefix),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                VerticalDivider(Modifier.height(24.dp), color = colors.outlineVariant)
                Box(modifier = Modifier.weight(1f)) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.kb_editor_placeholder),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun InputAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surface,
            contentColor = colors.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

internal fun TextFieldValue.withEditableTail(edit: KeyboardEdit): TextFieldValue = withoutPrefix(edit.droppedPrefix)
