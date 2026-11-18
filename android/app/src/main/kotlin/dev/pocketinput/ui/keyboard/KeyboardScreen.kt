package dev.pocketinput.ui.keyboard

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState
import dev.pocketinput.ui.link.LinkStatusBar
import dev.pocketinput.ui.trackpad.TrackpadSurface
import kotlinx.coroutines.flow.first

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
    var queuedText by remember(transport, sessionId, editRevision, editorRevision) { mutableStateOf("") }
    var russianLayout by remember(transport, sessionId) {
        mutableStateOf(settings.layout.equals("ru", ignoreCase = true))
    }
    var skipped by remember(transport, sessionId, editRevision) { mutableIntStateOf(0) }
    var queueRejected by remember(transport, sessionId, editRevision) { mutableStateOf(false) }
    var layoutMenu by remember(transport, sessionId) { mutableStateOf(false) }
    var symbolMenu by remember(transport, sessionId) { mutableStateOf(false) }
    var specialKeysMenu by remember(transport, sessionId, editRevision, editorRevision) { mutableStateOf(false) }
    var restoreImeOnDismiss by remember(transport, sessionId, editRevision, editorRevision) { mutableStateOf(false) }

    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val window = LocalWindowInfo.current
    var keyboardRequest by remember(transport, sessionId) { mutableIntStateOf(if (editorActive) 1 else 0) }
    var appliedKeyboardRequest by remember(transport, sessionId) { mutableIntStateOf(0) }

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

    fun handleChange(value: TextFieldValue) {
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
        field = value.withEditableTail(edit)
    }

    fun resetEditor() {
        if (revision != editorRevision) return
        skipped = 0
        revision++
    }

    fun selectLayout(russian: Boolean) {
        if (!isCurrentEditor() || russianLayout == russian) return
        resetEditor()
        russianLayout = russian
        onLayoutChange(if (russian) "ru" else "en")
    }

    fun insertSymbol(symbol: Char) {
        val range = field.selection
        val cursor = range.min + 1
        handleChange(TextFieldValue(
            text = field.text.replaceRange(range.min, range.max, symbol.toString()),
            selection = TextRange(cursor),
        ))
    }

    fun openSpecialKeys() {
        if (!isCurrentEditor()) return
        layoutMenu = false
        symbolMenu = false
        restoreImeOnDismiss = imeVisible
        specialKeysMenu = true
    }

    fun dismissSpecialKeys() {
        if (!specialKeysMenu) return
        specialKeysMenu = false
        if (restoreImeOnDismiss) keyboardRequest++
        restoreImeOnDismiss = false
    }

    fun sendSpecialKey(usage: Int) {
        if (!specialKeysMenu) return
        dismissSpecialKeys()
        if (submitKeys(listOf(usage to 0))) resetEditor()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        val compact = editorActive && maxHeight < 220.dp
        Column(modifier = Modifier.fillMaxSize()) {
            if (!compact) {
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
                        textAlign = if (editorActive) TextAlign.Center else TextAlign.End,
                    )
                    if (editorActive) {
                        SpecialKeysMenu(
                            expanded = specialKeysMenu,
                            onOpen = ::openSpecialKeys,
                            onDismiss = ::dismissSpecialKeys,
                            onKey = ::sendSpecialKey,
                        )
                    }
                }

                LinkStatusBar(
                    link = link,
                    onClick = {
                        if (transport.link.state == LinkState.CONNECTED) onOpenDiagnostics() else onOpenConnection()
                    },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (editorActive) {
                key(transport, sessionId, editRevision, editorRevision) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        OutlinedTextField(
                            value = field,
                            onValueChange = ::handleChange,
                            modifier = Modifier.weight(1f).focusRequester(focus),
                            label = { Text(stringResource(R.string.kb_editor)) },
                            maxLines = if (compact) 1 else 2,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.None,
                            ),
                        )
                        if (compact) {
                            SpecialKeysMenu(
                                expanded = specialKeysMenu,
                                onOpen = ::openSpecialKeys,
                                onDismiss = ::dismissSpecialKeys,
                                onKey = ::sendSpecialKey,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                    }
                    LaunchedEffect(keyboardRequest) {
                        if (keyboardRequest != appliedKeyboardRequest || imeVisible) {
                            snapshotFlow { window.isWindowFocused }.first { it }
                            appliedKeyboardRequest = keyboardRequest
                            focus.requestFocus()
                            ime?.show()
                        }
                    }
                }
            } else if (!connected) {
                Text(stringResource(R.string.kb_connection_hint), style = MaterialTheme.typography.labelSmall)
            }

            TrackpadSurface(
                transport = transport,
                settings = settings,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onFocusChangingInput = {
                    if (field.text.isNotEmpty() || field.composition != null) resetEditor()
                },
            )

            if (editorActive) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { layoutMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.kb_pc_layout, if (russianLayout) "RU" else "EN"))
                        }
                        DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.kb_switch_windows_layout)) },
                                onClick = {
                                    layoutMenu = false
                                    if (submitKeys(listOf(Usages.SPACE to CharMap.LGUI))) selectLayout(!russianLayout)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.kb_windows_now_en)) },
                                onClick = {
                                    layoutMenu = false
                                    selectLayout(false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.kb_windows_now_ru)) },
                                onClick = {
                                    layoutMenu = false
                                    selectLayout(true)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.kb_android_keyboard)) },
                                onClick = {
                                    layoutMenu = false
                                    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                                },
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { symbolMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.kb_symbols))
                        }
                        DropdownMenu(expanded = symbolMenu, onDismissRequest = { symbolMenu = false }) {
                            FlowRow(
                                modifier = Modifier.width(256.dp).padding(horizontal = 8.dp),
                                maxItemsInEachRow = 5,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                for (symbol in CharMap.symbols(russianLayout)) {
                                    TextButton(
                                        modifier = Modifier.width(48.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        onClick = {
                                            symbolMenu = false
                                            insertSymbol(symbol)
                                        },
                                    ) {
                                        Text(symbol.toString())
                                    }
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            if (submitKeys(listOf(Usages.BACKSPACE to 0))) resetEditor()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(stringResource(R.string.kb_delete_on_pc))
                    }
                }

                val status = when {
                    queueRejected -> R.string.kb_input_queue_rejected
                    link.keyboardFailureRevision == editRevision -> R.string.kb_input_send_failed
                    skipped > 0 -> R.string.kb_input_skipped
                    else -> null
                }
                if (status != null) {
                    Text(
                        stringResource(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecialKeysMenu(
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TextButton(onClick = onOpen) { Text(stringResource(R.string.kb_special_keys)) }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kb_tab_on_pc)) },
                onClick = { onKey(Usages.TAB) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kb_escape_on_pc)) },
                onClick = { onKey(Usages.ESC) },
            )
        }
    }
}

internal fun TextFieldValue.withEditableTail(edit: KeyboardEdit): TextFieldValue {
    if (edit.droppedPrefix == 0) return this

    fun TextRange.inTail(): TextRange {
        val start = (start - edit.droppedPrefix).coerceIn(0, edit.localText.length)
        val end = (end - edit.droppedPrefix).coerceIn(0, edit.localText.length)
        return TextRange(start, end)
    }

    return TextFieldValue(
        text = edit.localText,
        selection = selection.inTail(),
        composition = composition?.inTail()?.takeUnless { it.collapsed },
    )
}
