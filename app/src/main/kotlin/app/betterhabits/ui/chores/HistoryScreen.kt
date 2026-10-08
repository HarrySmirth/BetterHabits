package app.betterhabits.ui.chores

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.ChoreOccurrence
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
    ) { padding ->
        val context = state.context
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        FilterChip(selected = state.person == null, onClick = { viewModel.setPerson(null) }, label = { Text(stringResource(R.string.filter_everyone)) })
                        context.details.members.forEach { member ->
                            FilterChip(
                                selected = state.person == member.userId,
                                onClick = { viewModel.setPerson(member.userId) },
                                label = { Text(member.displayName) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        pluralStringResource(R.plurals.history_window, HistoryViewModel.DAYS.toInt(), HistoryViewModel.DAYS.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.visible.isEmpty()) {
                    item {
                        EmptyState(
                            frog = FrogMood.HAPPY,
                            title = stringResource(R.string.history_empty_title),
                            body = stringResource(R.string.history_empty_body),
                            modifier = Modifier.padding(top = 48.dp),
                        )
                    }
                }
                items(state.visible, key = { "${it.chore.id}-${it.key}" }) { entry -> HistoryRow(entry, context) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ChoreOccurrence, context: HouseholdContext) {
    val locale = currentLocale()
    val name = { id: String? -> context.member(id)?.displayName }
    // Icon + text: the status never relies on colour alone.
    val (icon, text) = when (entry.state) {
        OccurrenceState.COMPLETED -> Icons.Outlined.CheckCircle to
            stringResource(R.string.history_completed_by, name(entry.completedBy) ?: stringResource(R.string.member_former))
        OccurrenceState.SKIPPED -> Icons.Outlined.SkipNext to stringResource(R.string.occurrence_skipped)
        else -> Icons.Outlined.RemoveCircleOutline to
            stringResource(R.string.history_missed, name(entry.assigneeId) ?: stringResource(R.string.chore_unassigned))
    }
    ListItem(
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (entry.state == OccurrenceState.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(entry.chore.name) },
        supportingContent = { Text("$text · ${shortDate(entry.key.date, locale)}") },
    )
}
