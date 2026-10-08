package app.betterhabits.ui.household

import android.content.ClipData
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.HouseholdMember
import app.betterhabits.domain.model.HouseholdPermissions
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.Invitation
import app.betterhabits.domain.model.InviteCode
import app.betterhabits.domain.model.InviteCodes
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
import kotlinx.coroutines.launch
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(
    onOpenMember: (householdId: String, userId: String) -> Unit,
    onOpenSettings: (householdId: String) -> Unit,
    onAddHousehold: () -> Unit,
    onOpenBalance: () -> Unit,
    viewModel: HouseholdViewModel = viewModel(factory = AppViewModelFactory.Factory),
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    HouseholdSwitcher(
                        currentName = state.details?.household?.name ?: stringResource(R.string.nav_household),
                        households = state.households.map { it.household.id to it.household.name },
                        currentId = state.details?.household?.id,
                        onSelect = viewModel::selectHousehold,
                        onAddHousehold = onAddHousehold,
                    )
                },
                actions = {
                    val details = state.details
                    if (details != null && state.canOpenSettings) {
                        IconButton(onClick = { onOpenSettings(details.household.id) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.household_settings))
                        }
                    }
                    if (state.canLeave) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_leave_household)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.confirmLeave()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val details = state.details
        when {
            state.loading && details == null -> LoadingState(Modifier.padding(padding))
            details == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::refresh, modifier = Modifier.padding(padding))
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                HouseholdContent(
                    state = state,
                    details = details,
                    onOpenBalance = onOpenBalance,
                    onOpenMember = { onOpenMember(details.household.id, it.userId) },
                    viewModel = viewModel,
                )
            }
        }
    }

    HouseholdDialogs(state, viewModel)
}

@Composable
private fun HouseholdSwitcher(
    currentName: String,
    households: List<Pair<String, String>>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onAddHousehold: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val switchLabel = stringResource(R.string.cd_switch_household)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(role = Role.DropdownList, onClick = { open = true })
                .semantics { contentDescription = "$currentName. $switchLabel" }
                .padding(vertical = 8.dp),
        ) {
            Text(currentName, maxLines = 1)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            households.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { if (id == currentId) Icon(Icons.Outlined.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_household)) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    open = false
                    onAddHousehold()
                },
            )
        }
    }
}

@Composable
private fun HouseholdContent(
    state: HouseholdUiState,
    details: HouseholdDetails,
    onOpenBalance: () -> Unit,
    onOpenMember: (HouseholdMember) -> Unit,
    viewModel: HouseholdViewModel,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Balance, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.balance_title)) },
                supportingContent = { Text(stringResource(R.string.balance_entry_body)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenBalance),
            )
        }
        item { SectionHeader(stringResource(R.string.household_members, details.members.size)) }
        items(details.members, key = { it.userId }) { member ->
            MemberRow(details, member, onClick = { onOpenMember(member) })
        }

        if (state.canInvite || state.canManageChildren) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (state.canInvite) {
                        FilledTonalButton(onClick = viewModel::createInviteCode) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                            Text(stringResource(R.string.action_create_invite_code), Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = viewModel::showInviteByEmail) {
                            Icon(Icons.Outlined.Email, contentDescription = null)
                            Text(stringResource(R.string.action_invite_by_email), Modifier.padding(start = 8.dp))
                        }
                    }
                    if (state.canManageChildren) {
                        OutlinedButton(onClick = viewModel::showAddChild) {
                            Icon(Icons.Outlined.ChildCare, contentDescription = null)
                            Text(stringResource(R.string.action_add_child), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        val codes = state.activeCodes
        if (state.canInvite && codes.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.household_invite_codes)) }
            items(codes, key = { "code-${it.id}" }) { code ->
                InviteCodeCard(code, details.household.name, onRevoke = { viewModel.revokeInviteCode(code) })
            }
        }

        if (state.canInvite && state.invitations.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.household_pending_invitations)) }
            items(state.invitations, key = { "inv-${it.id}" }) { invitation ->
                InvitationRow(invitation, onRevoke = { viewModel.revokeInvitation(invitation) })
            }
        }
    }
}

@Composable
private fun MemberRow(details: HouseholdDetails, member: HouseholdMember, onClick: () -> Unit) {
    val isMe = member.userId == details.currentUserId
    val roleText = stringResource(member.role.labelRes())
    ListItem(
        leadingContent = { MemberAvatar(member.displayName) },
        headlineContent = {
            Text(if (isMe) stringResource(R.string.member_you, member.displayName) else member.displayName)
        },
        supportingContent = { Text(roleText) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Absolute short date-time ("11 Oct, 12:38") in the device locale and timezone. */
@Composable
private fun expiryTime(instant: Instant): String = DateUtils.formatDateTime(
    LocalContext.current,
    instant.toEpochMilli(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
)

@Composable
private fun InviteCodeCard(code: InviteCode, householdName: String, onRevoke: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val formatted = InviteCodes.format(code.code)
    val shareText = stringResource(R.string.invite_share_text, householdName, formatted)
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatted,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = formatted.toCharArray().joinToString(" ") },
            )
            Text(
                stringResource(R.string.invite_code_expires, expiryTime(code.expiresAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    context.startActivity(Intent.createChooser(intent, null))
                }) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(stringResource(R.string.action_share), Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invite code", code.code))) }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.action_copy), Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { confirmRevoke = true }) { Text(stringResource(R.string.action_revoke)) }
            }
        }
    }
    if (confirmRevoke) {
        ConfirmDialog(
            title = stringResource(R.string.revoke_code_title),
            body = stringResource(R.string.revoke_code_body),
            confirmLabel = stringResource(R.string.action_revoke),
            destructive = true,
            onConfirm = {
                confirmRevoke = false
                onRevoke()
            },
            onDismiss = { confirmRevoke = false },
        )
    }
}

@Composable
private fun InvitationRow(invitation: Invitation, onRevoke: () -> Unit) {
    ListItem(
        headlineContent = { Text(invitation.email) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.invitation_pending_detail,
                    stringResource(invitation.role.labelRes()),
                    expiryTime(invitation.expiresAt),
                ),
            )
        },
        trailingContent = { TextButton(onClick = onRevoke) { Text(stringResource(R.string.action_revoke)) } },
    )
}

@Composable
private fun HouseholdDialogs(state: HouseholdUiState, viewModel: HouseholdViewModel) {
    when (val dialog = state.dialog) {
        HouseholdDialog.None -> Unit
        is HouseholdDialog.InviteByEmail -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.invite_email_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.invite_email_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = dialog.email,
                        onValueChange = viewModel::onInviteEmailChange,
                        label = { Text(stringResource(R.string.field_email)) },
                        singleLine = true,
                        isError = dialog.showValidation,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.canInviteAdmins) {
                        Column(Modifier.selectableGroup()) {
                            listOf(HouseholdRole.MEMBER, HouseholdRole.ADMIN).forEach { role ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(dialog.role == role, role = Role.RadioButton) { viewModel.onInviteRoleChange(role) },
                                ) {
                                    RadioButton(selected = dialog.role == role, onClick = null)
                                    Text(stringResource(role.labelRes()), Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                    FormError(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::sendEmailInvite, enabled = !dialog.submitting) {
                    Text(stringResource(R.string.action_send_invite))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        is HouseholdDialog.AddChild -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.add_child_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.add_child_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = dialog.name,
                        onValueChange = viewModel::onChildNameChange,
                        label = { Text(stringResource(R.string.field_child_name)) },
                        singleLine = true,
                        isError = dialog.showValidation && dialog.name.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordField(
                        value = dialog.pin,
                        onValueChange = viewModel::onChildPinChange,
                        label = stringResource(R.string.field_pin),
                        supportingText = stringResource(R.string.validation_pin),
                        isError = dialog.showValidation && dialog.pin.length < 6,
                    )
                    FormError(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::createChild, enabled = !dialog.submitting) {
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        is HouseholdDialog.ChildCreated -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.child_created_title, dialog.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.child_created_body))
                    Text(
                        dialog.username,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(stringResource(R.string.child_created_pin_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_done)) } },
        )
        HouseholdDialog.ConfirmLeave -> ConfirmDialog(
            title = stringResource(R.string.leave_title, state.details?.household?.name.orEmpty()),
            body = stringResource(
                if (state.details?.members?.size == 1) R.string.leave_body_last else R.string.leave_body,
            ),
            confirmLabel = stringResource(R.string.action_leave),
            destructive = true,
            onConfirm = viewModel::leave,
            onDismiss = viewModel::dismissDialog,
        )
    }
}
