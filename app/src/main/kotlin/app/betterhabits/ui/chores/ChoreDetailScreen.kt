package app.betterhabits.ui.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.ConfirmDialog
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.OnReturn
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.messageRes
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoreDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: ChoreDetailViewModel = viewModel(factory = AppViewModelFactory.Factory),
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
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    OnReturn(viewModel::load) // e.g. back from the editor

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.chore?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
                actions = {
                    val chore = state.chore
                    if (chore != null && state.canEdit) {
                        IconButton(onClick = { onEdit(chore.id) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val chore = state.chore
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            chore == null || state.context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> {
                val context = state.context!!
                val res = resources()
                val locale = currentLocale()
                val deviceZone = ZoneId.systemDefault()
                val today = LocalDate.now(deviceZone)
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scheduleText(res, chore.schedule, locale), style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(
                                effortText(res, chore.effort),
                                stringResource(chore.category.labelRes()),
                                stringResource(R.string.editor_difficulty_value, chore.difficulty),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                R.string.chore_assigned_to,
                                context.member(chore.assigneeId)?.displayName ?: stringResource(R.string.chore_unassigned),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!chore.active) {
                            Text(stringResource(R.string.chore_paused_note), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        chore.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp)) }
                    }

                    if (chore.checklist.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.editor_checklist))
                        chore.checklist.forEachIndexed { i, item ->
                            Text("${i + 1}. $item", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    chore.notes?.let {
                        SectionHeader(stringResource(R.string.field_notes))
                        Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }

                    if (chore.active) {
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                        SectionHeader(stringResource(R.string.chore_upcoming))
                        if (state.upcoming.isEmpty()) {
                            Text(stringResource(R.string.chore_no_upcoming), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        state.upcoming.forEach { occurrence ->
                            ListItem(
                                headlineContent = { Text(dueText(res, occurrence.key, occurrence.dueAt, today, deviceZone, locale)) },
                                supportingContent = { Text(context.member(occurrence.assigneeId)?.displayName ?: stringResource(R.string.chore_unassigned)) },
                            )
                        }
                    }

                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    SectionHeader(stringResource(R.string.chore_history))
                    if (state.history.isEmpty()) {
                        Text(stringResource(R.string.chore_no_history), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    state.history.forEach { record ->
                        val who = context.member(record.completedBy)?.displayName ?: stringResource(R.string.member_former)
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (record.status == OccurrenceStatus.COMPLETED) stringResource(R.string.history_completed_by, who)
                                    else stringResource(R.string.occurrence_skipped),
                                )
                            },
                            supportingContent = { Text(shortDate(record.key.date, locale)) },
                        )
                    }

                    if (state.canEdit || state.canDelete) {
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.canEdit) {
                                OutlinedButton(onClick = { viewModel.setActive(!chore.active) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(if (chore.active) R.string.action_pause_chore else R.string.action_resume_chore))
                                }
                            }
                            if (state.canDelete) {
                                OutlinedButton(onClick = viewModel::requestDelete, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.action_delete_chore), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_chore_title, state.chore?.name.orEmpty()),
            body = stringResource(R.string.delete_chore_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}
