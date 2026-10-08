package app.betterhabits.ui.chores

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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Switch
import app.betterhabits.ui.allocation.reasonText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.FormError
import app.betterhabits.ui.components.LoadingState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.TextStyle

private val MINUTE_PRESETS = listOf(5, 10, 15, 20, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoreEditorScreen(onClose: () -> Unit, viewModel: ChoreEditorViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.editor_new_title else R.string.editor_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) }
                },
                actions = {
                    if (state.context != null && state.canEdit) {
                        TextButton(onClick = viewModel::save, enabled = !state.saving) { Text(stringResource(R.string.action_save)) }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                EditorForm(state, viewModel)
            }
        }
    }
}

@Composable
private fun EditorForm(state: ChoreEditorUiState, vm: ChoreEditorViewModel) {
    val form = state.form
    val enabled = state.canEdit
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.isNew) focus.requestFocus() }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Question(R.string.editor_q_what) {
            OutlinedTextField(
                value = form.name,
                onValueChange = vm::onName,
                label = { Text(stringResource(R.string.field_chore_name)) },
                placeholder = { Text(stringResource(R.string.field_chore_name_hint)) },
                singleLine = true,
                enabled = enabled,
                isError = state.showValidation && !form.nameValid,
                supportingText = if (state.showValidation && !form.nameValid) ({ Text(stringResource(R.string.validation_name)) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("choreName"),
            )
        }

        Question(R.string.editor_q_how_often) { RepeatSection(state, vm) }

        Question(R.string.editor_q_how_long) { DurationSection(state, vm) }

        Question(R.string.editor_q_who) {
            if (!state.canAssign) {
                Text(
                    state.context?.member(form.assigneeId)?.displayName ?: stringResource(R.string.chore_unassigned),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.assigneeId == null,
                        onClick = { vm.onAssignee(null) },
                        label = { Text(stringResource(R.string.chore_unassigned)) },
                    )
                    state.context?.details?.members.orEmpty().forEach { member ->
                        FilterChip(
                            selected = form.assigneeId == member.userId,
                            onClick = { vm.onAssignee(member.userId) },
                            label = { Text(member.displayName) },
                        )
                    }
                }
                AssistChip(
                    onClick = vm::suggestAssignee,
                    enabled = !state.suggesting,
                    label = { Text(stringResource(R.string.editor_suggest)) },
                    leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                )
                SuggestionReasons(state)
            }
        }

        HorizontalDivider()
        TextButton(onClick = vm::toggleAdvanced) {
            Icon(if (state.showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            Text(stringResource(if (state.showAdvanced) R.string.editor_fewer_options else R.string.editor_more_options), Modifier.padding(start = 8.dp))
        }
        if (state.showAdvanced) AdvancedSection(state, vm)

        FormError(state.saveError)
    }
}

@Composable
private fun Question(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun RepeatSection(state: ChoreEditorUiState, vm: ChoreEditorViewModel) {
    val form = state.form
    val enabled = state.canEdit
    val locale = currentLocale()
    var pickStart by rememberSaveable { mutableStateOf(false) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RepeatKind.entries.forEach { kind ->
            FilterChip(
                selected = form.repeat == kind,
                onClick = { vm.onRepeat(kind) },
                enabled = enabled,
                label = { Text(stringResource(kind.labelRes())) },
            )
        }
    }

    if (form.repeat != RepeatKind.ONCE) {
        IntervalStepper(form.repeat, form.interval, enabled, vm::onInterval)
    }

    if (form.repeat == RepeatKind.WEEKLY) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { day ->
                val full = day.getDisplayName(TextStyle.FULL, locale)
                FilterChip(
                    selected = day in form.weekdays,
                    onClick = { vm.onToggleWeekday(day) },
                    enabled = enabled,
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                    modifier = Modifier.semantics { contentDescription = full },
                )
            }
        }
        if (state.showValidation && !form.weekdaysValid) {
            Text(stringResource(R.string.validation_weekdays), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (form.repeat == RepeatKind.MONTHLY) {
        val ordinal = resources().getStringArray(R.array.week_ordinals)[if (form.weekOrdinal == -1) 4 else form.weekOrdinal - 1]
        Column(Modifier.selectableGroup()) {
            RadioRow(
                text = stringResource(R.string.editor_monthly_on_day, form.startDate.dayOfMonth),
                selected = form.monthlyMode == MonthlyMode.DAY_OF_MONTH,
                enabled = enabled,
            ) { vm.onMonthlyMode(MonthlyMode.DAY_OF_MONTH) }
            RadioRow(
                text = stringResource(R.string.editor_monthly_on_weekday, ordinal, form.startDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale)),
                selected = form.monthlyMode == MonthlyMode.WEEKDAY,
                enabled = enabled,
            ) { vm.onMonthlyMode(MonthlyMode.WEEKDAY) }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (form.repeat == RepeatKind.ONCE) R.string.editor_on_date else R.string.editor_starting),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = { pickStart = true }, enabled = enabled) { Text(shortDate(form.startDate, locale)) }
    }
    if (pickStart) {
        DatePickerDialogFor(form.startDate, onPick = { vm.onStartDate(it); pickStart = false }, onDismiss = { pickStart = false })
    }
}

@Composable
private fun IntervalStepper(kind: RepeatKind, interval: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val unit = when (kind) {
        RepeatKind.DAILY -> R.plurals.unit_days
        RepeatKind.WEEKLY -> R.plurals.unit_weeks
        RepeatKind.MONTHLY -> R.plurals.unit_months
        else -> R.plurals.unit_years
    }
    val unitText = pluralStringResource(unit, interval)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.editor_every), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { onChange(interval - 1) }, enabled = enabled && interval > 1) { Text("−") }
        Text(
            interval.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = "$interval $unitText" },
        )
        OutlinedButton(onClick = { onChange(interval + 1) }, enabled = enabled) { Text("+") }
        Text(unitText, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DurationSection(state: ChoreEditorUiState, vm: ChoreEditorViewModel) {
    val form = state.form
    val enabled = state.canEdit
    val res = resources()
    val custom = form.minutes != null && form.minutes !in MINUTE_PRESETS
    var showCustom by rememberSaveable { mutableStateOf(custom) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MINUTE_PRESETS.forEach { minutes ->
            FilterChip(
                selected = !showCustom && form.minutes == minutes,
                onClick = { showCustom = false; vm.onMinutes(minutes) },
                enabled = enabled,
                label = { Text(effortText(res, app.betterhabits.domain.model.Effort(minutes))) },
            )
        }
        FilterChip(
            selected = showCustom,
            onClick = { showCustom = true },
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_other_duration)) },
        )
    }
    if (showCustom) {
        OutlinedTextField(
            value = form.minutes?.toString().orEmpty(),
            onValueChange = { text -> vm.onMinutes(text.filter(Char::isDigit).take(4).toIntOrNull()) },
            label = { Text(stringResource(R.string.field_minutes)) },
            singleLine = true,
            enabled = enabled,
            isError = state.showValidation && !form.minutesValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp),
        )
    }
    Text(
        stringResource(R.string.editor_duration_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AdvancedSection(state: ChoreEditorUiState, vm: ChoreEditorViewModel) {
    val form = state.form
    val enabled = state.canEdit
    val locale = currentLocale()
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var pickEnd by rememberSaveable { mutableStateOf(false) }
    var newItem by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Question(R.string.editor_category) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoreCategory.entries.forEach { category ->
                    FilterChip(
                        selected = form.category == category,
                        onClick = { vm.onCategory(category) },
                        enabled = enabled,
                        label = { Text(stringResource(category.labelRes())) },
                    )
                }
            }
        }

        Question(R.string.editor_due_times) {
            Text(
                stringResource(R.string.editor_due_times_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.times.forEach { time ->
                    val label = timeText(time, locale)
                    InputChip(
                        selected = false,
                        onClick = { vm.onRemoveTime(time) },
                        enabled = enabled,
                        label = { Text(label) },
                        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_remove, label)) },
                    )
                }
                OutlinedButton(onClick = { pickTime = true }, enabled = enabled) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.action_add_time), Modifier.padding(start = 4.dp))
                }
            }
        }

        if (state.canControlAssignment) {
            Question(R.string.editor_assignment) { AssignmentControls(state, vm) }
        }

        if (form.repeat != RepeatKind.ONCE) {
            Question(R.string.editor_end_date) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickEnd = true }, enabled = enabled) {
                        Text(form.endDate?.let { shortDate(it, locale) } ?: stringResource(R.string.editor_no_end_date))
                    }
                    if (form.endDate != null) {
                        TextButton(onClick = { vm.onEndDate(null) }, enabled = enabled) { Text(stringResource(R.string.action_clear)) }
                    }
                }
                if (state.showValidation && !form.endDateValid) {
                    Text(stringResource(R.string.validation_end_date), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Question(R.string.editor_difficulty) {
            Text(stringResource(R.string.editor_difficulty_value, form.difficulty), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = form.difficulty.toFloat(),
                onValueChange = { vm.onDifficulty(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3,
                enabled = enabled,
            )
        }

        OutlinedTextField(
            value = form.points.toString(),
            onValueChange = { vm.onPoints(it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) },
            label = { Text(stringResource(R.string.field_points)) },
            supportingText = { Text(stringResource(R.string.editor_points_help)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(200.dp),
        )

        OutlinedTextField(
            value = form.description,
            onValueChange = vm::onDescription,
            label = { Text(stringResource(R.string.field_description)) },
            enabled = enabled,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Question(R.string.editor_checklist) {
            form.checklist.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. $item", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { vm.onRemoveChecklistItem(index) }, enabled = enabled) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_remove, item))
                    }
                }
            }
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                label = { Text(stringResource(R.string.field_checklist_item)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.onAddChecklistItem(newItem); newItem = "" }),
                trailingIcon = {
                    IconButton(onClick = { vm.onAddChecklistItem(newItem); newItem = "" }, enabled = enabled && newItem.isNotBlank()) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_add_item))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = form.notes,
            onValueChange = vm::onNotes,
            label = { Text(stringResource(R.string.field_notes)) },
            enabled = enabled,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (pickTime) TimePickerDialogFor(onPick = { vm.onAddTime(it); pickTime = false }, onDismiss = { pickTime = false })
    if (pickEnd) {
        DatePickerDialogFor(form.endDate ?: form.startDate, onPick = { vm.onEndDate(it); pickEnd = false }, onDismiss = { pickEnd = false })
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogFor(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // DatePicker works in UTC-midnight millis.
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state = pickerState) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogFor(onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberTimePickerState(initialHour = 19, initialMinute = 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_time)) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(pickerState.hour, pickerState.minute)) }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun RepeatKind.labelRes(): Int = when (this) {
    RepeatKind.ONCE -> R.string.repeat_once
    RepeatKind.DAILY -> R.string.repeat_daily
    RepeatKind.WEEKLY -> R.string.repeat_weekly
    RepeatKind.MONTHLY -> R.string.repeat_monthly
    RepeatKind.YEARLY -> R.string.repeat_yearly
}

/** Why the allocator suggested this person, in plain sentences. */
@Composable
private fun SuggestionReasons(state: ChoreEditorUiState) {
    val proposal = state.suggestion ?: return
    val context = state.context ?: return
    val res = resources()
    val unassigned = stringResource(R.string.chore_unassigned)
    val nameOf = { id: String? -> id?.let { context.member(it)?.displayName } ?: unassigned }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        proposal.reasons.forEach { reason ->
            Text(
                "• " + reasonText(res, reason, nameOf(proposal.assigneeId)) { nameOf(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AssignmentControls(state: ChoreEditorUiState, vm: ChoreEditorViewModel) {
    val form = state.form
    SwitchRow(
        title = stringResource(R.string.editor_lock),
        body = stringResource(R.string.editor_lock_body),
        checked = form.assignmentLocked,
        enabled = form.assigneeId != null,
        onChange = vm::onLocked,
    )
    SwitchRow(
        title = stringResource(R.string.editor_rotate),
        body = stringResource(R.string.editor_rotate_body),
        checked = form.rotate,
        enabled = true,
        onChange = vm::onRotate,
    )
    Text(stringResource(R.string.editor_never_assign), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.context?.details?.members.orEmpty().forEach { member ->
            FilterChip(
                selected = member.userId in form.excludedMemberIds,
                onClick = { vm.onToggleExcluded(member.userId) },
                label = { Text(member.displayName) },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
