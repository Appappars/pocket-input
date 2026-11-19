package dev.pocketinput.ui.keyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketinput.R

internal enum class KeyboardPanel { KEYS, SYMBOLS, LAYOUT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardCommandPanel(
    panel: KeyboardPanel,
    russianLayout: Boolean,
    compact: Boolean,
    onClose: () -> Unit,
    onKey: (Int) -> Unit,
    onSymbol: (Char) -> Unit,
    onAllSymbols: () -> Unit,
    onLayout: (Boolean) -> Unit,
    onSwitchLayout: () -> Unit,
    onChooseIme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(
        when (panel) {
            KeyboardPanel.KEYS -> R.string.kb_commands_title
            KeyboardPanel.SYMBOLS -> R.string.kb_symbols
            KeyboardPanel.LAYOUT -> R.string.kb_layout_title
        },
    )
    val closeLabel = stringResource(R.string.kb_close_panel)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().semantics { paneTitle = title },
        color = colors.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
                .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = closeLabel },
                ) {
                    Text("×", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            if (panel == KeyboardPanel.KEYS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommandKey("Tab", { onKey(Usages.TAB) }, Modifier.weight(1f))
                    CommandKey("Esc", { onKey(Usages.ESC) }, Modifier.weight(1f))
                }
                PanelLabel(stringResource(R.string.kb_symbols))
            }
            if (panel != KeyboardPanel.LAYOUT) {
                val symbols = if (panel == KeyboardPanel.SYMBOLS) {
                    CharMap.symbols(russianLayout)
                } else {
                    (if (russianLayout) "?!№:;-" else "?@#:;-").toList()
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = 6,
                ) {
                    for (symbol in symbols) {
                        CommandKey(symbol.toString(), { onSymbol(symbol) }, Modifier.width(48.dp))
                    }
                }
            }
            if (panel != KeyboardPanel.SYMBOLS) {
                if (panel == KeyboardPanel.KEYS) PanelLabel(stringResource(R.string.kb_layout_title))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommandKey("RU", { onLayout(true) }, Modifier.width(48.dp), russianLayout)
                    CommandKey("EN", { onLayout(false) }, Modifier.width(48.dp), !russianLayout)
                    CommandKey(stringResource(R.string.kb_win_space), onSwitchLayout, Modifier.weight(1f))
                }
                PanelLabel(stringResource(R.string.kb_layout_check))
            }
            if (compact && panel == KeyboardPanel.KEYS) {
                CommandKey(stringResource(R.string.kb_all_symbols), onAllSymbols, Modifier.fillMaxWidth())
            }
            if (panel == KeyboardPanel.LAYOUT || (compact && panel == KeyboardPanel.KEYS)) {
                CommandKey(stringResource(R.string.kb_android_keyboard), onChooseIme, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PanelLabel(label: String) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun CommandKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    active: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val state = if (active == null) Modifier else Modifier.semantics { selected = active }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).then(state),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active == true) colors.primary else colors.surfaceContainer,
            contentColor = if (active == true) colors.onPrimary else colors.onSurface,
        ),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}
