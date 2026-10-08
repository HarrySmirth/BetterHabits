package app.betterhabits.ui.allocation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.allocation.DateRange
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.allocation.TimeOfDay
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.chores.currentLocale
import app.betterhabits.ui.chores.labelRes
import app.betterhabits.ui.chores.shortDate
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.messageRes
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(onBack: () -> Unit, viewModel: PreferencesViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { stringResource(it.messageRes()) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }
    var addAway by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.isMe || state.memberName.isEmpty()) stringResource(R.string.prefs_title_mine) else stringResource(R.string.prefs_title_other, state.memberName))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    Text(
                        stringResource(if (state.canEdit) R.string.prefs_intro else R.string.prefs_readonly),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item { AvailabilitySection(state, viewModel, onAddAway = { addAway = true }) }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionHeader(stringResource(R.string.prefs_categories))
                }
                items(app.betterhabits.domain.model.ChoreCategory.entries, key = { "cat-$it" }) { category ->
                    PreferenceRow(
                        title = stringResource(category.labelRes()),
                        current = state.preferences.byCategory[category],
                        fallbackLabel = stringResource(R.string.pref_neutral),
                        enabled = state.canEdit,
                        onSelect = { viewModel.setCategoryPreference(category, it) },
                    )
                }
                if (state.preferences.byTemplate.isNotEmpty()) {
                    item(key = "templates-header") {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        SectionHeader(stringResource(R.string.prefs_templates))
                        Text(
                            stringResource(R.string.prefs_templates_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(state.preferences.byTemplate.keys.sortedBy { state.templateNames[it].orEmpty().lowercase() }, key = { "template-$it" }) { id ->
                        PreferenceRow(
                            title = state.templateNames[id] ?: id.substringAfterLast('.'),
                            current = state.preferences.byTemplate[id],
                            fallbackLabel = stringResource(R.string.template_pref_none),
                            enabled = state.canEdit,
                            onSelect = { viewModel.setTemplatePreference(id, it) },
                        )
                    }
                }
                state.choresByCategory.forEach { (category, chores) ->
                    item(key = "header-$category") {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        SectionHeader(stringResource(category.labelRes()))
                    }
                    items(chores, key = { "chore-${it.id}" }) { chore ->
                        val categoryLevel = state.preferences.byCategory[category] ?: PreferenceLevel.NEUTRAL
                        PreferenceRow(
                            title = chore.name,
                            current = state.preferences.byChore[chore.id],
                            fallbackLabel = stringResource(R.string.pref_same_as_category, stringResource(categoryLevel.labelRes())),
                            enabled = state.canEdit,
                            onSelect = { viewModel.setChorePreference(chore.id, it) },
                        )
                    }
                }
                item { Box(Modifier.padding(bottom = 32.dp)) }
            }
        }
    }

    if (addAway) {
        AwayPeriodPicker(
            onPick = { range ->
                viewModel.addAwayPeriod(range, note = null)
                addAway = false
            },
            onDismiss = { addAway = false },
        )
    }
}

@Composable
private fun AvailabilitySection(state: PreferencesUiState, vm: PreferencesViewModel, onAddAway: () -> Unit) {
    val locale = currentLocale()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.prefs_availability), Modifier.padding(horizontal = 0.dp))
        Text(stringResource(R.string.prefs_unavailable_days), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { day ->
                val full = day.getDisplayName(TextStyle.FULL, locale)
                FilterChip(
                    selected = day in state.availability.unavailableDays,
                    onClick = { vm.toggleUnavailableDay(day) },
                    enabled = state.canEdit,
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                    modifier = Modifier.semantics { contentDescription = full },
                )
            }
        }
        Text(stringResource(R.string.prefs_preferred_times), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeOfDay.entries.forEach { time ->
                FilterChip(
                    selected = time in state.availability.preferredTimes,
                    onClick = { vm.togglePreferredTime(time) },
                    enabled = state.canEdit,
                    label = { Text(stringResource(time.labelRes())) },
                )
            }
        }
        Text(stringResource(R.string.prefs_away), style = MaterialTheme.typography.bodyLarge)
        state.awayPeriods.forEach { period ->
            val label = stringResource(R.string.prefs_away_range, shortDate(period.range.start, locale), shortDate(period.range.end, locale))
            ListItem(
                headlineContent = { Text(label) },
                trailingContent = {
                    if (state.canEdit) {
                        IconButton(onClick = { vm.removeAwayPeriod(period) }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_remove, label))
                        }
                    }
                },
            )
        }
        if (state.canEdit) {
            OutlinedButton(onClick = onAddAway) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.prefs_add_away), Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** A row with a dropdown of preference levels. [current] null = not set (shows [fallbackLabel]). */
@Composable
internal fun PreferenceRow(title: String, current: PreferenceLevel?, fallbackLabel: String, enabled: Boolean, onSelect: (PreferenceLevel?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = current?.let { stringResource(it.labelRes()) } ?: fallbackLabel
    ListItem(
        headlineContent = { Text(title) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }, enabled = enabled) {
                    Text(currentLabel)
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    PreferenceLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(stringResource(level.labelRes())) },
                            onClick = {
                                open = false
                                onSelect(level)
                            },
                        )
                    }
                    if (current != null) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(fallbackLabel) }, onClick = { open = false; onSelect(null) })
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AwayPeriodPicker(onPick: (DateRange) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = pickerState.selectedStartDateMillis ?: return@TextButton
                    val end = pickerState.selectedEndDateMillis ?: start
                    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(DateRange(date(start), date(end)))
                },
                enabled = pickerState.selectedStartDateMillis != null,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DateRangePicker(state = pickerState, title = { Text(stringResource(R.string.prefs_add_away), Modifier.padding(16.dp)) })
    }
}

fun PreferenceLevel.labelRes(): Int = when (this) {
    PreferenceLevel.LOVE -> R.string.pref_love
    PreferenceLevel.LIKE -> R.string.pref_like
    PreferenceLevel.NEUTRAL -> R.string.pref_neutral
    PreferenceLevel.DISLIKE -> R.string.pref_dislike
    PreferenceLevel.HATE -> R.string.pref_hate
    PreferenceLevel.CANNOT_DO -> R.string.pref_cannot_do
}

fun TimeOfDay.labelRes(): Int = when (this) {
    TimeOfDay.MORNING -> R.string.time_morning
    TimeOfDay.AFTERNOON -> R.string.time_afternoon
    TimeOfDay.EVENING -> R.string.time_evening
}
