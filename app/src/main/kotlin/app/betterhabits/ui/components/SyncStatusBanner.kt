package app.betterhabits.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.betterhabits.R
import app.betterhabits.data.sync.SyncStatus

/**
 * Quiet sync indicator. Shows nothing when all is well; otherwise explains (in words, not just an
 * icon colour) that the phone is offline, that changes are waiting to save, or that one was refused.
 */
@Composable
fun SyncStatusBanner(status: SyncStatus, onDismissProblem: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        status.problems.forEach { problem ->
            Banner(
                icon = Icons.Outlined.ErrorOutline,
                text = stringResource(R.string.sync_problem, stringResource(problem.error.messageRes())),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                action = { TextButton(onClick = { onDismissProblem(problem.id) }) { Text(stringResource(R.string.action_dismiss)) } },
            )
        }
        when {
            !status.online -> Banner(
                icon = Icons.Outlined.CloudOff,
                text = if (status.pending > 0) {
                    pluralStringResource(R.plurals.sync_offline_pending, status.pending, status.pending)
                } else {
                    stringResource(R.string.sync_offline)
                },
            )
            status.pending > 0 && !status.syncing -> Banner(
                icon = Icons.Outlined.CloudSync,
                text = pluralStringResource(R.plurals.sync_pending, status.pending, status.pending),
            )
        }
    }
}

@Composable
private fun Banner(
    icon: ImageVector,
    text: String,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}
