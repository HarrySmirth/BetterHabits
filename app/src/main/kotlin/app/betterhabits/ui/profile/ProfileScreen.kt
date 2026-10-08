package app.betterhabits.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.BuildConfig
import app.betterhabits.R
import app.betterhabits.data.preferences.ThemeMode
import app.betterhabits.domain.error.AppError
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.ConfirmDialog
import app.betterhabits.ui.components.FormError
import app.betterhabits.ui.components.SectionHeader
import app.betterhabits.ui.components.TopLevelScaffold
import app.betterhabits.ui.components.messageRes
import app.betterhabits.ui.theme.supportsDynamicColor

@Composable
fun ProfileScreen(
    onOpenPreferences: () -> Unit,
    viewModel: ProfileViewModel = viewModel(factory = AppViewModelFactory.Factory),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = account.message
    val messageText = message?.let {
        if (it is AppError.TransferOwnershipRequired && it.households != null) {
            stringResource(R.string.error_transfer_ownership_named, it.households!!)
        } else {
            stringResource(it.messageRes())
        }
    }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }

    TopLevelScaffold(title = stringResource(R.string.nav_profile), snackbarHostState = snackbar) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (account.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())

            SectionHeader(stringResource(R.string.profile_account))
            ListItem(
                headlineContent = { Text(account.displayName ?: "…") },
                supportingContent = {
                    Text(
                        account.user?.let { user -> user.email ?: stringResource(R.string.profile_child_account) }.orEmpty(),
                    )
                },
                trailingContent = { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.cd_edit_name)) },
                modifier = Modifier.clickable(onClick = viewModel::editName),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.prefs_title_mine)) },
                supportingContent = { Text(stringResource(R.string.prefs_entry_body)) },
                leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenPreferences),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_sign_out)) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                modifier = Modifier.clickable { viewModel.showDialog(ProfileDialog.ConfirmSignOut) },
            )
            if (account.user?.isChild == false) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_delete_account), color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable(enabled = !account.deleting) { viewModel.showDialog(ProfileDialog.ConfirmDelete) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.profile_appearance))
            Text(
                text = stringResource(R.string.profile_theme),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ThemeModeSelector(
                selected = preferences.themeMode,
                onSelect = viewModel::setThemeMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.profile_dynamic_color)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (supportsDynamicColor) R.string.profile_dynamic_color_body
                            else R.string.profile_dynamic_color_unsupported,
                        ),
                    )
                },
                trailingContent = {
                    // Toggle handled by the whole row so the touch target is the full list item.
                    Switch(checked = preferences.useDynamicColor, onCheckedChange = null, enabled = supportsDynamicColor)
                },
                modifier = Modifier.toggleable(
                    value = preferences.useDynamicColor,
                    enabled = supportsDynamicColor,
                    role = Role.Switch,
                    onValueChange = viewModel::setUseDynamicColor,
                ),
            )
            Text(
                text = stringResource(R.string.profile_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    when (val dialog = account.dialog) {
        ProfileDialog.None -> Unit
        is ProfileDialog.EditName -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.edit_name_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialog.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text(stringResource(R.string.field_your_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormError(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveName, enabled = !dialog.submitting && dialog.name.isNotBlank()) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        ProfileDialog.ConfirmSignOut -> ConfirmDialog(
            title = stringResource(R.string.sign_out_title),
            body = stringResource(R.string.sign_out_body),
            confirmLabel = stringResource(R.string.action_sign_out),
            onConfirm = viewModel::signOut,
            onDismiss = viewModel::dismissDialog,
        )
        ProfileDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.delete_account_title),
            body = stringResource(R.string.delete_account_body),
            confirmLabel = stringResource(R.string.action_delete_account),
            destructive = true,
            onConfirm = viewModel::deleteAccount,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.selectableGroup()) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(stringResource(mode.label)) },
            )
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
