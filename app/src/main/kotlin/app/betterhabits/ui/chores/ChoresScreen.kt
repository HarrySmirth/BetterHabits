package app.betterhabits.ui.chores

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.SyncStatusBanner
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.TopLevelScaffold
import app.betterhabits.ui.components.messageRes
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ChoresScreen(
    onAddChore: () -> Unit,
    onOpenChore: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onSuggestAssignments: () -> Unit,
    onOpenTemplates: () -> Unit,
    viewModel: ChoresViewModel = viewModel(factory = AppViewModelFactory.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { stringResource(it.messageRes()) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }

    TopLevelScaffold(
        title = stringResource(R.string.nav_chores),
        snackbarHostState = snackbar,
        actions = {
            if (state.canAssign && state.active.isNotEmpty()) {
                IconButton(onClick = onSuggestAssignments) {
                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = stringResource(R.string.action_suggest_assignments))
                }
            }
            IconButton(onClick = onOpenTemplates) {
                Icon(Icons.AutoMirrored.Outlined.LibraryBooks, contentDescription = stringResource(R.string.cd_templates))
            }
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.history_title))
            }
        },
        floatingActionButton = {
            if (state.canCreate) {
                val addChoreLabel = stringResource(R.string.action_add_chore)
                ExtendedFloatingActionButton(
                    onClick = onAddChore,
                    // Explicit label: the animated text isn't exposed to screen readers on its own.
                    modifier = Modifier.semantics { contentDescription = addChoreLabel },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_chore)) },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.context == null -> ErrorState(state.loadError ?: return@TopLevelScaffold, onRetry = viewModel::refresh, modifier = Modifier.padding(padding))
            state.active.isEmpty() && state.paused.isEmpty() -> EmptyState(
                frog = FrogMood.HAPPY,
                title = stringResource(R.string.chores_empty_title),
                body = stringResource(if (state.canCreate) R.string.chores_empty_body else R.string.chores_empty_body_readonly),
                modifier = Modifier.padding(padding),
                action = if (state.canCreate) {
                    { OutlinedButton(onClick = onOpenTemplates) { Text(stringResource(R.string.action_browse_templates)) } }
                } else {
                    null
                },
            )
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SyncStatusBanner(state.sync, viewModel::dismissSyncProblem, Modifier.padding(bottom = 8.dp)) }
                    items(state.active, key = { it.chore.id }) { ChoreRow(it, state, onOpenChore) }
                    if (state.paused.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.chores_paused)) }
                        items(state.paused, key = { it.chore.id }) { ChoreRow(it, state, onOpenChore) }
                    }
                    item { Box(Modifier.padding(bottom = 88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ChoreRow(item: ChoreListItem, state: ChoresUiState, onOpenChore: (String) -> Unit) {
    val res = resources()
    val locale = currentLocale()
    val context = state.context ?: return
    val chore = item.chore
    val assignee = context.member(chore.assigneeId)?.displayName ?: stringResource(R.string.chore_unassigned)
    val next = item.next?.let {
        res.getString(
            R.string.chore_next,
            dueText(res, it.key, it.dueAt, LocalDate.now(ZoneId.systemDefault()), ZoneId.systemDefault(), locale),
        )
    }
    ListItem(
        headlineContent = { Text(chore.name) },
        supportingContent = {
            Text(listOfNotNull(scheduleText(res, chore.schedule, locale), effortText(res, chore.effort), assignee).joinToString(" · "))
        },
        trailingContent = next?.takeIf { chore.active }?.let { { Text(it) } },
        modifier = Modifier.clickable { onOpenChore(chore.id) },
    )
}
