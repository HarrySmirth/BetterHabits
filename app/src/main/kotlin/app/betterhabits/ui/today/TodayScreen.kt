package app.betterhabits.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import app.betterhabits.ui.components.Frog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.ChoreOccurrence
import app.betterhabits.domain.model.OccurrenceState
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.chores.currentLocale
import app.betterhabits.ui.chores.dueText
import app.betterhabits.ui.chores.effortText
import app.betterhabits.ui.chores.resources
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.SyncStatusBanner
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.TopLevelScaffold
import app.betterhabits.ui.components.messageRes
import java.time.ZoneId

@Composable
fun TodayScreen(
    onAddChore: () -> Unit,
    onOpenChore: (String) -> Unit,
    viewModel: TodayViewModel = viewModel(factory = AppViewModelFactory.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = resources()

    val messageText = state.message?.let { stringResource(it.messageRes()) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }
    LaunchedEffect(state.undo) {
        val undo = state.undo ?: return@LaunchedEffect
        val text = res.getString(
            if (undo.completed) R.string.today_marked_done else R.string.today_updated,
            undo.occurrence.chore.name,
        )
        val result = snackbar.showSnackbar(text, actionLabel = res.getString(R.string.action_undo), duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo(undo) else viewModel.undoShown()
    }

    TopLevelScaffold(
        title = stringResource(R.string.nav_today),
        snackbarHostState = snackbar,
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
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                TodayContent(state, viewModel, onOpenChore)
            }
        }
    }
}

@Composable
private fun TodayContent(state: TodayUiState, viewModel: TodayViewModel, onOpenChore: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SyncStatusBanner(state.sync, viewModel::dismissSyncProblem, Modifier.padding(bottom = 8.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                FilterChip(
                    selected = state.filter == AgendaFilter.MINE,
                    onClick = { viewModel.setFilter(AgendaFilter.MINE) },
                    label = { Text(stringResource(R.string.filter_mine)) },
                )
                FilterChip(
                    selected = state.filter == AgendaFilter.EVERYONE,
                    onClick = { viewModel.setFilter(AgendaFilter.EVERYONE) },
                    label = { Text(stringResource(R.string.filter_everyone)) },
                )
            }
        }
        if (state.totalCount == 0) {
            item {
                Box(Modifier.padding(top = 48.dp)) {
                    EmptyState(
                        frog = FrogMood.SLEEPY,
                        title = stringResource(R.string.today_empty_title),
                        body = stringResource(
                            if (state.filter == AgendaFilter.MINE) R.string.today_empty_mine else R.string.today_empty_body,
                        ),
                    )
                }
            }
            return@LazyColumn
        }
        item { ProgressCard(state) }
        section(R.string.today_overdue, state.overdue, state, viewModel, onOpenChore)
        section(R.string.today_due, state.dueToday, state, viewModel, onOpenChore)
        section(R.string.today_done, state.done, state, viewModel, onOpenChore)
        item { Box(Modifier.padding(bottom = 88.dp)) } // room for the FAB
    }
}

private fun LazyListScope.section(
    title: Int,
    items: List<ChoreOccurrence>,
    state: TodayUiState,
    viewModel: TodayViewModel,
    onOpenChore: (String) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") { SectionHeader(stringResource(title)) }
    items(items, key = { "${it.chore.id}-${it.key}" }) { occurrence ->
        OccurrenceRow(occurrence, state, viewModel, onOpenChore)
    }
}

@Composable
private fun ProgressCard(state: TodayUiState) {
    val res = resources()
    val allDone = state.totalCount > 0 && state.doneCount == state.totalCount
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (allDone) {
                        stringResource(R.string.today_all_done_title)
                    } else {
                        pluralStringResource(R.plurals.today_progress, state.totalCount, state.doneCount, state.totalCount)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LinearProgressIndicator(
                    progress = { if (state.totalCount == 0) 0f else state.doneCount.toFloat() / state.totalCount },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    strokeCap = StrokeCap.Round,
                )
                when {
                    allDone -> Text(
                        stringResource(R.string.today_all_done_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    state.remainingEffort.minutes > 0 -> Text(
                        stringResource(R.string.today_remaining, effortText(res, state.remainingEffort)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            AnimatedVisibility(visible = allDone, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                Frog(FrogMood.CELEBRATE, Modifier.padding(start = 8.dp), width = 88.dp)
            }
        }
    }
}

@Composable
private fun OccurrenceRow(occurrence: ChoreOccurrence, state: TodayUiState, viewModel: TodayViewModel, onOpenChore: (String) -> Unit) {
    val res = resources()
    val locale = currentLocale()
    val context = state.context ?: return
    val canAct = state.canAct(occurrence)
    val done = occurrence.isDone
    val completed = occurrence.state == OccurrenceState.COMPLETED
    val assignee = context.member(occurrence.assigneeId)?.displayName ?: stringResource(R.string.chore_unassigned)
    val supporting = buildList {
        add(effortText(res, occurrence.chore.effort))
        add(assignee)
        when (occurrence.state) {
            OccurrenceState.OVERDUE -> add(
                res.getString(R.string.occurrence_overdue, dueText(res, occurrence.key, occurrence.dueAt, state.today, ZoneId.systemDefault(), locale)),
            )
            OccurrenceState.UPCOMING -> occurrence.key.time?.let {
                add(res.getString(R.string.occurrence_due, dueText(res, occurrence.key, occurrence.dueAt, state.today, ZoneId.systemDefault(), locale)))
            }
            OccurrenceState.COMPLETED -> occurrence.completedBy?.takeIf { it != occurrence.assigneeId }?.let { by ->
                add(res.getString(R.string.occurrence_done_by, context.member(by)?.displayName ?: res.getString(R.string.member_former)))
            }
            OccurrenceState.SKIPPED -> add(res.getString(R.string.occurrence_skipped))
            else -> Unit
        }
    }.joinToString(" · ")
    val stateLabel = stringResource(
        when (occurrence.state) {
            OccurrenceState.COMPLETED -> R.string.state_done
            OccurrenceState.SKIPPED -> R.string.state_skipped
            OccurrenceState.OVERDUE -> R.string.state_overdue
            else -> R.string.state_not_done
        },
    )
    var menuOpen by remember { mutableStateOf(false) }

    // A small hop when a chore is ticked off.
    val hop = remember { Animatable(1f) }
    var wasCompleted by remember { mutableStateOf(completed) }
    LaunchedEffect(completed) {
        if (completed && !wasCompleted) {
            hop.animateTo(1.3f, tween(110))
            hop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
        wasCompleted = completed
    }

    ListItem(
        leadingContent = {
            Checkbox(
                checked = occurrence.state == OccurrenceState.COMPLETED,
                onCheckedChange = if (canAct) ({ viewModel.toggle(occurrence) }) else null,
                enabled = canAct,
                modifier = Modifier
                    .graphicsLayer { scaleX = hop.value; scaleY = hop.value }
                    .semantics { stateDescription = stateLabel },
            )
        },
        headlineContent = {
            Text(
                occurrence.chore.name,
                // Strikethrough only for completed, matching the ticked checkbox; skipped items are just dimmed.
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                supporting,
                color = if (occurrence.state == OccurrenceState.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (canAct && !done) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.cd_chore_options, occurrence.chore.name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_snooze_hour)) },
                            onClick = { menuOpen = false; viewModel.snooze(occurrence, SnoozeOption.ONE_HOUR) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_snooze_tomorrow)) },
                            onClick = { menuOpen = false; viewModel.snooze(occurrence, SnoozeOption.TOMORROW_MORNING) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_skip)) },
                            onClick = { menuOpen = false; viewModel.skip(occurrence) },
                        )
                    }
                }
            } else if (canAct && occurrence.state == OccurrenceState.SKIPPED) {
                TextButton(onClick = { viewModel.reset(occurrence) }) { Text(stringResource(R.string.action_undo)) }
            }
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = { onOpenChore(occurrence.chore.id) }),
    )
}
