package app.betterhabits.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.ConfirmDialog
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.labelRes
import app.betterhabits.ui.components.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdSettingsScreen(onBack: () -> Unit, viewModel: HouseholdSettingsViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.household_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        val details = state.details
        when {
            state.loading && details == null -> LoadingState(Modifier.padding(padding))
            details == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                SectionHeader(stringResource(R.string.settings_general))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text(stringResource(R.string.field_household_name)) },
                        singleLine = true,
                        enabled = state.canRename,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.canRename) {
                        Button(onClick = viewModel::saveName, enabled = state.nameChanged && !state.busy) {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }

                listOf(HouseholdRole.ADMIN, HouseholdRole.MEMBER, HouseholdRole.CHILD).forEach { role ->
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    SectionHeader(stringResource(R.string.settings_role_permissions, stringResource(role.labelRes())))
                    val editable = state.canEditRole(role)
                    if (!editable) {
                        Text(
                            stringResource(R.string.settings_role_readonly),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    HouseholdPermission.entries.forEach { permission ->
                        val checked = state.roleHas(role, permission)
                        ListItem(
                            headlineContent = { Text(stringResource(permission.labelRes())) },
                            trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = editable && !state.busy) },
                            modifier = Modifier.toggleable(
                                value = checked,
                                enabled = editable && !state.busy,
                                role = Role.Switch,
                                onValueChange = { viewModel.setRolePermission(role, permission, it) },
                            ),
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(top = 16.dp))
                SectionHeader(stringResource(R.string.settings_allocation))
                AllocationSettingsSection(state, viewModel)

                if (state.canDelete) {
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    SectionHeader(stringResource(R.string.settings_danger_zone))
                    Text(
                        stringResource(R.string.settings_delete_household_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    OutlinedButton(
                        onClick = viewModel::requestDelete,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_delete_household), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_household_title, state.details?.household?.name.orEmpty()),
            body = stringResource(R.string.delete_household_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** How chores are shared: preference weight, avoidance, and each person's fair share. */
@Composable
private fun AllocationSettingsSection(state: HouseholdSettingsUiState, vm: HouseholdSettingsViewModel) {
    val editable = state.canConfigureAllocation && !state.busy
    val weight = state.allocation.preferenceWeight
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.canConfigureAllocation) {
            Text(stringResource(R.string.settings_allocation_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.settings_preference_weight), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = weight.toFloat(),
            onValueChange = { vm.onPreferenceWeight(it.toInt()) },
            onValueChangeFinished = vm::saveAllocation,
            valueRange = 0f..100f,
            steps = 3,
            enabled = editable,
        )
        Text(
            stringResource(
                when {
                    weight == 0 -> R.string.settings_weight_fairness_only
                    weight < 50 -> R.string.settings_weight_mostly_fairness
                    weight < 75 -> R.string.settings_weight_balanced
                    else -> R.string.settings_weight_preferences
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_allow_avoidance)) },
            supportingContent = { Text(stringResource(R.string.settings_allow_avoidance_body)) },
            trailingContent = { Switch(checked = state.allocation.allowAvoidance, onCheckedChange = null, enabled = editable) },
            modifier = Modifier.toggleable(
                value = state.allocation.allowAvoidance,
                enabled = editable,
                role = Role.Switch,
                onValueChange = vm::onAllowAvoidance,
            ),
        )
        Text(stringResource(R.string.settings_fair_shares), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_fair_shares_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.details?.members.orEmpty().forEach { member ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.displayName, Modifier.weight(1f))
                OutlinedButton(onClick = { vm.setShare(member.userId, (member.workloadShare - 0.25).coerceAtLeast(0.25)) }, enabled = editable && member.workloadShare > 0.25) { Text("−") }
                Text(
                    stringResource(R.string.settings_share_value, member.workloadShare),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { vm.setShare(member.userId, (member.workloadShare + 0.25).coerceAtMost(3.0)) }, enabled = editable && member.workloadShare < 3.0) { Text("+") }
            }
        }
    }
}
