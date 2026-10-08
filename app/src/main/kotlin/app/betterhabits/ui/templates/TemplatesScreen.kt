package app.betterhabits.ui.templates

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.ChoreTemplate
import app.betterhabits.domain.model.RepeatKind
import app.betterhabits.domain.model.TemplateScope
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.allocation.PreferenceRow
import app.betterhabits.ui.allocation.labelRes
import app.betterhabits.ui.chores.effortText
import app.betterhabits.ui.chores.labelRes
import app.betterhabits.ui.chores.resources
import app.betterhabits.ui.components.ConfirmDialog
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.Frog
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.OnScreenResume
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onUse: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCopy: (String) -> Unit,
    onNew: () -> Unit,
    viewModel: TemplatesViewModel = viewModel(factory = AppViewModelFactory.Factory),
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
    OnScreenResume(viewModel::load)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.templates_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
        floatingActionButton = {
            if (state.context != null) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_new_template)) },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> TemplateList(state, viewModel, Modifier.padding(padding))
        }
    }

    state.selected?.let { template ->
        TemplateSheet(
            template = template,
            state = state,
            onDismiss = { viewModel.select(null) },
            onUse = { viewModel.select(null); onUse(template.id) },
            onCopy = { viewModel.select(null); onCopy(template.id) },
            onEdit = { viewModel.select(null); onEdit(template.id) },
            onDelete = { viewModel.requestDelete(template) },
            onPreference = { viewModel.setPreference(template, it) },
        )
    }

    state.confirmDelete?.let { template ->
        ConfirmDialog(
            title = stringResource(R.string.template_delete_title, template.name),
            body = stringResource(R.string.template_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::delete,
            onDismiss = { viewModel.requestDelete(null) },
            destructive = true,
        )
    }
}

@Composable
private fun TemplateList(state: TemplatesUiState, vm: TemplatesViewModel, modifier: Modifier) {
    val res = resources()
    LazyColumn(modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQuery,
                placeholder = { Text(stringResource(R.string.templates_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("templateSearch"),
            )
        }
        if (state.nothingMatches) {
            item {
                EmptyState(
                    title = stringResource(R.string.templates_no_match_title),
                    body = stringResource(R.string.templates_no_match_body),
                    frog = FrogMood.PUZZLED,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
            return@LazyColumn
        }
        templateSection(R.string.templates_household, state.household, state, res, vm)
        templateSection(R.string.templates_personal, state.personal, state, res, vm)
        if (state.builtIn.isNotEmpty()) {
            item(key = "builtin-header") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.templates_builtin),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            stringResource(R.string.templates_builtin_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Frog(FrogMood.HAPPY, Modifier.padding(start = 8.dp), width = 80.dp)
                }
            }
            state.builtIn.forEach { (category, list) ->
                item(key = "cat-$category") { SectionHeader(stringResource(category.labelRes())) }
                items(list, key = { it.id }) { TemplateRow(it, state, res, vm) }
            }
        }
        item { Box(Modifier.padding(bottom = 96.dp)) } // room for the FAB
    }
}

private fun LazyListScope.templateSection(title: Int, list: List<ChoreTemplate>, state: TemplatesUiState, res: Resources, vm: TemplatesViewModel) {
    if (list.isEmpty()) return
    item(key = "header-$title") { SectionHeader(stringResource(title)) }
    items(list, key = { it.id }) { TemplateRow(it, state, res, vm) }
}

@Composable
private fun TemplateRow(template: ChoreTemplate, state: TemplatesUiState, res: Resources, vm: TemplatesViewModel) {
    val preference = state.preferences[template.id]
    ListItem(
        headlineContent = { Text(template.name) },
        supportingContent = { Text(templateSummary(res, template)) },
        trailingContent = preference?.let { { Text(stringResource(it.labelRes()), style = MaterialTheme.typography.labelMedium) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(role = Role.Button) { vm.select(template) },
    )
}

/** "45 min · Weekly · 5 steps" */
fun templateSummary(res: Resources, t: ChoreTemplate): String = buildList {
    add(effortText(res, t.effort))
    add(
        when (t.repeatKind) {
            RepeatKind.ONCE -> res.getString(R.string.template_repeat_once)
            RepeatKind.DAILY -> res.getQuantityString(R.plurals.template_repeat_daily, t.repeatInterval, t.repeatInterval)
            RepeatKind.WEEKLY -> res.getQuantityString(R.plurals.template_repeat_weekly, t.repeatInterval, t.repeatInterval)
            RepeatKind.MONTHLY -> res.getQuantityString(R.plurals.template_repeat_monthly, t.repeatInterval, t.repeatInterval)
        },
    )
    if (t.checklist.isNotEmpty()) add(res.getQuantityString(R.plurals.template_steps, t.checklist.size, t.checklist.size))
}.joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(
    template: ChoreTemplate,
    state: TemplatesUiState,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPreference: (app.betterhabits.domain.allocation.PreferenceLevel?) -> Unit,
) {
    val res = resources()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(template.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(
                        when (template.scope) {
                            TemplateScope.BUILT_IN -> R.string.template_scope_builtin
                            TemplateScope.HOUSEHOLD -> R.string.template_scope_household
                            TemplateScope.PERSONAL -> R.string.template_scope_personal
                        },
                    ) + " · " + stringResource(template.category.labelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    templateSummary(res, template) + " · " + stringResource(R.string.editor_difficulty_value, template.difficulty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                template.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp)) }
            }
            if (template.checklist.isNotEmpty()) {
                SectionHeader(stringResource(R.string.steps_heading), Modifier.padding(horizontal = 8.dp))
                template.checklist.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            PreferenceRow(
                title = stringResource(R.string.template_my_preference),
                current = state.preferences[template.id],
                fallbackLabel = stringResource(R.string.template_pref_none),
                enabled = true,
                onSelect = onPreference,
            )
            Text(
                stringResource(R.string.template_preference_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            FlowRow(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.canUse) Button(onClick = onUse) { Text(stringResource(R.string.action_use_template)) }
                OutlinedButton(onClick = onCopy) { Text(stringResource(R.string.action_make_copy)) }
                if (state.canEdit(template)) {
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.action_edit_template)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
