package app.betterhabits.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.pluralStringResource
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
import app.betterhabits.ui.components.FormError
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.MemberAvatar
import app.betterhabits.ui.components.PasswordField
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.labelRes
import app.betterhabits.ui.components.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(onBack: () -> Unit, viewModel: MemberDetailViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
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
                title = { Text(state.member?.displayName.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        val member = state.member
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            member == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ListItem(
                    leadingContent = { MemberAvatar(member.displayName) },
                    headlineContent = { Text(member.displayName, style = MaterialTheme.typography.titleLarge) },
                    supportingContent = {
                        Text(
                            stringResource(member.role.labelRes()) +
                                if (member.isChildAccount) " · " + stringResource(R.string.member_child_account) else "",
                        )
                    },
                )

                if (state.assignableRoles.size > 1) {
                    HorizontalDivider()
                    SectionHeader(stringResource(R.string.member_role))
                    Column(Modifier.selectableGroup()) {
                        state.assignableRoles.forEach { role ->
                            RoleOption(role, selected = role == member.role, enabled = !state.busy) { viewModel.changeRole(role) }
                        }
                    }
                }

                if (state.canEditPermissions) {
                    HorizontalDivider()
                    SectionHeader(stringResource(R.string.member_permissions))
                    Text(
                        stringResource(R.string.member_permissions_help, stringResource(member.role.labelRes())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    var expanded by rememberSaveable { mutableStateOf(state.overrides.isNotEmpty()) }
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            if (expanded) stringResource(R.string.member_permissions_hide)
                            else if (state.overrides.isEmpty()) stringResource(R.string.member_permissions_customise_none)
                            else pluralStringResource(R.plurals.member_permissions_customise, state.overrides.size, state.overrides.size),
                        )
                    }
                    if (expanded) {
                        HouseholdPermission.entries.forEach { permission ->
                            PermissionOverrideRow(
                                permission = permission,
                                roleDefault = state.roleDefault(permission),
                                override = state.overrides[permission],
                                enabled = !state.busy,
                                onChange = { viewModel.setOverride(permission, it) },
                            )
                        }
                    }
                }

                val hasActions = state.canManageChild || state.canTransfer || state.canRemove
                if (hasActions) {
                    HorizontalDivider()
                    SectionHeader(stringResource(R.string.member_actions))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.canManageChild) {
                            OutlinedButton(onClick = { viewModel.showDialog(MemberDialog.ResetPin()) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_reset_pin))
                            }
                        }
                        if (state.canTransfer) {
                            OutlinedButton(onClick = { viewModel.showDialog(MemberDialog.ConfirmTransfer) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_transfer_ownership))
                            }
                        }
                        if (state.canRemove) {
                            OutlinedButton(onClick = { viewModel.showDialog(MemberDialog.ConfirmRemove) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_remove_member), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (state.canManageChild) {
                            OutlinedButton(onClick = { viewModel.showDialog(MemberDialog.ConfirmDeleteChild) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_delete_child_account), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    val name = state.member?.displayName.orEmpty()
    when (val dialog = state.dialog) {
        MemberDialog.None -> Unit
        MemberDialog.ConfirmRemove -> ConfirmDialog(
            title = stringResource(R.string.remove_member_title, name),
            body = stringResource(R.string.remove_member_body, name),
            confirmLabel = stringResource(R.string.action_remove),
            destructive = true,
            onConfirm = viewModel::remove,
            onDismiss = viewModel::dismissDialog,
        )
        MemberDialog.ConfirmTransfer -> ConfirmDialog(
            title = stringResource(R.string.transfer_title, name),
            body = stringResource(R.string.transfer_body, name),
            confirmLabel = stringResource(R.string.action_transfer),
            onConfirm = viewModel::transferOwnership,
            onDismiss = viewModel::dismissDialog,
        )
        MemberDialog.ConfirmDeleteChild -> ConfirmDialog(
            title = stringResource(R.string.delete_child_title, name),
            body = stringResource(R.string.delete_child_body, name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = viewModel::deleteChild,
            onDismiss = viewModel::dismissDialog,
        )
        is MemberDialog.ResetPin -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.reset_pin_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(
                        value = dialog.pin,
                        onValueChange = viewModel::onPinChange,
                        label = stringResource(R.string.field_new_pin),
                        supportingText = stringResource(R.string.validation_pin),
                    )
                    FormError(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::resetPin, enabled = !dialog.submitting) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        MemberDialog.PinReset -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            text = { Text(stringResource(R.string.pin_reset_done, name)) },
            confirmButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_done)) } },
        )
    }
}

@Composable
private fun RoleOption(role: HouseholdRole, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(role.labelRes()), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(role.descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun HouseholdRole.descriptionRes(): Int = when (this) {
    HouseholdRole.OWNER -> R.string.role_owner_description
    HouseholdRole.ADMIN -> R.string.role_admin_description
    HouseholdRole.MEMBER -> R.string.role_member_description
    HouseholdRole.CHILD -> R.string.role_child_description
}

/** Three-way choice: follow the role default, always allow, or never allow. */
@Composable
private fun PermissionOverrideRow(
    permission: HouseholdPermission,
    roleDefault: Boolean,
    override: Boolean?,
    enabled: Boolean,
    onChange: (Boolean?) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(permission.labelRes()), style = MaterialTheme.typography.bodyLarge)
        val options = listOf<Pair<Boolean?, String>>(
            null to stringResource(if (roleDefault) R.string.override_default_on else R.string.override_default_off),
            true to stringResource(R.string.override_allow),
            false to stringResource(R.string.override_deny),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = override == value,
                    onClick = { onChange(value) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}
