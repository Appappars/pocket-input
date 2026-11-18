package dev.pocketinput.ui.link

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.LinkSnapshot

@Composable
fun LinkStatusBar(link: LinkSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val action = stringResource(if (link.isConnected) R.string.link_diagnostics else R.string.link_recover_action)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .clickable(onClickLabel = action, role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(link.state.label()),
                style = MaterialTheme.typography.labelLarge,
            )
            val detail = when {
                !link.isConnected -> action
                link.recentFailures > 0 -> stringResource(R.string.link_recent_failures_short, link.recentFailures)
                else -> action
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
