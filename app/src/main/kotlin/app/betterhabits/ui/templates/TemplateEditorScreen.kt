package app.betterhabits.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.RepeatKind
import app.betterhabits.domain.model.TemplateScope
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.chores.MINUTE_PRESETS
import app.betterhabits.ui.chores.effortText
import app.betterhabits.ui.chores.labelRes
import app.betterhabits.ui.chores.resources
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.FormError
import app.betterhabits.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(onClose: () -> Unit, viewModel: TemplateEditorViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.template_editor_new else R.string.template_editor_edit)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) }
                },
                actions = {
                    if (state.context != null) {
                        TextButton(onClick = viewModel::save, enabled = !state.saving) { Text(stringResource(R.string.action_save)) }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> EditorForm(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun EditorForm(state: TemplateEditorUiState, vm: TemplateEditorViewModel, modifier: Modifier) {
    val form = state.form
    val res = resources()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Section(R.string.editor_q_what) {
            OutlinedTextField(
                value = form.name,
                onValueChange = vm::onName,
                label = { Text(stringResource(R.string.field_template_name)) },
                singleLine = true,
                isError = state.showValidation && !form.nameValid,
                supportingText = if (state.showValidation && !form.nameValid) {
                    { Text(stringResource(R.string.validation_template_name)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("templateName"),
            )
            OutlinedTextField(
                value = form.description,
                onValueChange = vm::onDescription,
                label = { Text(stringResource(R.string.field_description)) },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section(R.string.editor_category) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoreCategory.entries.forEach { category ->
                    FilterChip(
                        selected = form.category == category,
                        onClick = { vm.onCategory(category) },
                        label = { Text(stringResource(category.labelRes())) },
                    )
                }
            }
        }

        Section(R.string.editor_q_how_long) {
            val custom = form.minutes != null && form.minutes !in MINUTE_PRESETS
            var showCustom by rememberSaveable { mutableStateOf(custom) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MINUTE_PRESETS.forEach { minutes ->
                    FilterChip(
                        selected = !showCustom && form.minutes == minutes,
                        onClick = { showCustom = false; vm.onMinutes(minutes) },
                        label = { Text(effortText(res, Effort(minutes))) },
                    )
                }
                FilterChip(selected = showCustom, onClick = { showCustom = true }, label = { Text(stringResource(R.string.editor_other_duration)) })
            }
            if (showCustom) {
                OutlinedTextField(
                    value = form.minutes?.toString().orEmpty(),
                    onValueChange = { text -> vm.onMinutes(text.filter(Char::isDigit).take(4).toIntOrNull()) },
                    label = { Text(stringResource(R.string.field_minutes)) },
                    singleLine = true,
                    isError = state.showValidation && !form.minutesValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp),
                )
            }
        }

        Section(R.string.editor_difficulty) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { level ->
                    FilterChip(selected = form.difficulty == level, onClick = { vm.onDifficulty(level) }, label = { Text(level.toString()) })
                }
            }
        }

        Section(R.string.editor_q_how_often) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatKind.entries.forEach { kind ->
                    FilterChip(
                        selected = form.repeat == kind,
                        onClick = { vm.onRepeat(kind) },
                        label = {
                            Text(
                                stringResource(
                                    when (kind) {
                                        RepeatKind.ONCE -> R.string.repeat_once
                                        RepeatKind.DAILY -> R.string.repeat_daily
                                        RepeatKind.WEEKLY -> R.string.repeat_weekly
                                        RepeatKind.MONTHLY -> R.string.repeat_monthly
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            if (form.repeat != RepeatKind.ONCE) {
                val unit = when (form.repeat) {
                    RepeatKind.DAILY -> R.plurals.unit_days
                    RepeatKind.WEEKLY -> R.plurals.unit_weeks
                    else -> R.plurals.unit_months
                }
                val unitText = pluralStringResource(unit, form.interval)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.editor_every), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { vm.onInterval(form.interval - 1) }, enabled = form.interval > 1) { Text("−") }
                    Text(
                        form.interval.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { contentDescription = "${form.interval} $unitText" },
                    )
                    OutlinedButton(onClick = { vm.onInterval(form.interval + 1) }) { Text("+") }
                    Text(unitText, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Section(R.string.template_q_steps) {
            Text(stringResource(R.string.template_steps_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            form.checklist.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. $step", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { vm.onMoveStep(index, -1) }, enabled = index > 0) {
                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_move_step_up, step))
                    }
                    IconButton(onClick = { vm.onMoveStep(index, 1) }, enabled = index < form.checklist.lastIndex) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_move_step_down, step))
                    }
                    IconButton(onClick = { vm.onRemoveStep(index) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_remove_step, step))
                    }
                }
            }
            var newStep by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = newStep,
                onValueChange = { newStep = it.take(120) },
                label = { Text(stringResource(R.string.field_checklist_item)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.onAddStep(newStep); newStep = "" }),
                trailingIcon = {
                    IconButton(onClick = { vm.onAddStep(newStep); newStep = "" }, enabled = newStep.isNotBlank()) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.field_checklist_item))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("newStep"),
            )
        }

        Section(R.string.template_q_who_sees) {
            Column(Modifier.selectableGroup()) {
                ScopeOption(
                    text = stringResource(R.string.template_scope_household_option),
                    selected = form.scope == TemplateScope.HOUSEHOLD,
                    enabled = state.isNew && state.canShareWithHousehold,
                ) { vm.onScope(TemplateScope.HOUSEHOLD) }
                ScopeOption(
                    text = stringResource(R.string.template_scope_personal_option),
                    selected = form.scope == TemplateScope.PERSONAL,
                    enabled = state.isNew,
                ) { vm.onScope(TemplateScope.PERSONAL) }
            }
            if (!state.isNew) {
                Text(stringResource(R.string.template_scope_fixed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        FormError(state.saveError)
    }
}

@Composable
private fun Section(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun ScopeOption(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
