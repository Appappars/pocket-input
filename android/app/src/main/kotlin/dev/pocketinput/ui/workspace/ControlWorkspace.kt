package dev.pocketinput.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.LinkSnapshot

@Composable
internal fun ControlWorkspace(
    link: LinkSnapshot,
    inputSelected: Boolean,
    onSelectInput: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        val compact = maxHeight < 330.dp
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            if (compact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ModeTabs(inputSelected, onSelectInput, Modifier.weight(1f))
                    WorkspaceMenu(onOpenSettings, onOpenDiagnostics)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.titleLarge)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.size(8.dp).background(
                                    MaterialTheme.colorScheme.tertiary,
                                    CircleShape,
                                ),
                            )
                            Text(
                                text = stringResource(
                                    R.string.workspace_connected,
                                    link.peer?.takeIf { it.isNotBlank() } ?: stringResource(R.string.workspace_pc),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    WorkspaceMenu(onOpenSettings, onOpenDiagnostics)
                }
                ModeTabs(inputSelected, onSelectInput, Modifier.fillMaxWidth())
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
        }
    }
}

@Composable
private fun ModeTabs(inputSelected: Boolean, onSelectInput: (Boolean) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .background(colors.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (input in listOf(true, false)) {
            val selected = input == inputSelected
            Box(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.primary else colors.surface)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelectInput(input) })
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (input) R.string.workspace_input else R.string.workspace_board),
                    color = if (selected) colors.onPrimary else colors.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceMenu(onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.workspace_menu)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val w = size.width
                val h = size.height
                val bluetooth = Path().apply {
                    moveTo(w * 0.22f, h * 0.24f)
                    lineTo(w * 0.75f, h * 0.74f)
                    lineTo(w * 0.48f, h * 0.95f)
                    lineTo(w * 0.48f, h * 0.05f)
                    lineTo(w * 0.75f, h * 0.26f)
                    lineTo(w * 0.22f, h * 0.76f)
                }
                drawPath(bluetooth, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.link_diagnostics)) },
                onClick = {
                    open = false
                    onOpenDiagnostics()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_settings)) },
                onClick = {
                    open = false
                    onOpenSettings()
                },
            )
        }
    }
}
