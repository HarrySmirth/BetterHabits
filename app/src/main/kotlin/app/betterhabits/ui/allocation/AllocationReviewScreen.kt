package app.betterhabits.ui.allocation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.allocation.AllocationProposal
import app.betterhabits.domain.model.Effort
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.chores.effortText
import app.betterhabits.ui.chores.resources
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllocationReviewScreen(onClose: () -> Unit, viewModel: AllocationReviewViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { stringResource(it.messageRes()) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }
    LaunchedEffect(state.applied) { if (state.applied) onClose() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) } },
            )
        },
        bottomBar = {
            if (state.changes.isNotEmpty()) {
                BottomAppBar {
                    Button(
                        onClick = viewModel::apply,
                        enabled = state.accepted.isNotEmpty() && !state.applying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) { Text(pluralStringResource(R.plurals.review_apply, state.accepted.size, state.accepted.size)) }
                }
            }
        },
    ) { padding ->
        val plan = state.plan
        val context = state.context
        when {
            state.loading && plan == null -> LoadingState(Modifier.padding(padding))
            plan == null || context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.review_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilterChip(
                            selected = state.onlyUnassigned,
                            onClick = { viewModel.setOnlyUnassigned(!state.onlyUnassigned) },
                            label = { Text(stringResource(R.string.review_only_unassigned)) },
                        )
                    }
                }
                item { SectionHeader(stringResource(R.string.review_projected)) }
                item {
                    val res = resources()
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        plan.result.projected.forEach { load ->
                            Row {
                                Text(context.member(load.memberId)?.displayName.orEmpty(), Modifier.weight(1f))
                                Text(stringResource(R.string.review_per_week, effortText(res, Effort(load.minutesPerWeek))))
                            }
                        }
                    }
                }
                if (state.changes.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.CheckCircle,
                            title = stringResource(R.string.review_no_changes_title),
                            body = stringResource(R.string.review_no_changes_body),
                            modifier = Modifier.padding(top = 32.dp),
                        )
                    }
                } else {
                    item { SectionHeader(stringResource(R.string.review_changes)) }
                    items(state.changes, key = { it.choreId }) { proposal ->
                        ProposalRow(proposal, state, accepted = proposal.choreId in state.accepted, onToggle = { viewModel.toggle(proposal.choreId) })
                    }
                }
                if (state.unchanged.isNotEmpty()) {
                    item { UnchangedSection(state) }
                }
            }
        }
    }
}

@Composable
private fun ProposalRow(proposal: AllocationProposal, state: ReviewUiState, accepted: Boolean, onToggle: () -> Unit) {
    val res = resources()
    val context = state.context ?: return
    val unassigned = stringResource(R.string.chore_unassigned)
    val nameOf = { id: String? -> id?.let { context.member(it)?.displayName } ?: unassigned }
    val chore = state.choresById[proposal.choreId] ?: return
    ListItem(
        leadingContent = { Checkbox(checked = accepted, onCheckedChange = null) },
        headlineContent = { Text(chore.name) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.review_change, nameOf(proposal.previousAssigneeId), nameOf(proposal.assigneeId)), style = MaterialTheme.typography.bodyMedium)
                proposal.reasons.forEach { reason ->
                    Text("• " + reasonText(res, reason, nameOf(proposal.assigneeId)) { nameOf(it) }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        modifier = Modifier.toggleable(value = accepted, role = Role.Checkbox, onValueChange = { onToggle() }),
    )
}

@Composable
private fun UnchangedSection(state: ReviewUiState) {
    var open by rememberSaveable { mutableStateOf(false) }
    val res = resources()
    val context = state.context ?: return
    val unassigned = stringResource(R.string.chore_unassigned)
    val nameOf = { id: String? -> id?.let { context.member(it)?.displayName } ?: unassigned }
    Column {
        TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(pluralStringResource(R.plurals.review_unchanged, state.unchanged.size, state.unchanged.size))
        }
        if (open) {
            state.unchanged.forEach { proposal ->
                val chore = state.choresById[proposal.choreId] ?: return@forEach
                ListItem(
                    headlineContent = { Text(chore.name) },
                    supportingContent = {
                        Text(
                            nameOf(proposal.assigneeId) + " — " + proposal.reasons.joinToString("; ") { reasonText(res, it, nameOf(proposal.assigneeId)) { id -> nameOf(id) } },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
    }
}
