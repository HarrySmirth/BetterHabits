package app.betterhabits.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.model.PendingInvitation
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.FormError
import app.betterhabits.ui.components.ProgressButton
import app.betterhabits.ui.components.labelRes

/**
 * Create a household, join one with a code, or accept an email invitation.
 * [onClose] null = onboarding (no household yet, offers sign-out instead of back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdSetupScreen(
    onClose: (() -> Unit)?,
    viewModel: HouseholdSetupViewModel = viewModel(factory = AppViewModelFactory.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.completedHouseholdId) {
        if (state.completedHouseholdId != null) onClose?.invoke()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (onClose == null) R.string.setup_title else R.string.setup_title_add)) },
                navigationIcon = {
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    }
                },
                actions = {
                    if (onClose == null) TextButton(onClick = viewModel::signOut) { Text(stringResource(R.string.action_sign_out)) }
                },
            )
        },
    ) { padding ->
        if (state.isChild) {
            EmptyState(
                frog = FrogMood.HAPPY,
                title = stringResource(R.string.setup_child_title),
                body = stringResource(R.string.setup_child_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onClose == null) {
                Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
            }

            state.invitations.forEach { invitation ->
                InvitationCard(
                    invitation = invitation,
                    busy = state.busy != null,
                    onAccept = { viewModel.respond(invitation, accept = true) },
                    onDecline = { viewModel.respond(invitation, accept = false) },
                )
            }
            FormError(state.invitationError)

            SetupCard(title = stringResource(R.string.setup_create_title), body = stringResource(R.string.setup_create_body)) {
                OutlinedTextField(
                    value = state.householdName,
                    onValueChange = viewModel::onNameChange,
                    label = { Text(stringResource(R.string.field_household_name)) },
                    placeholder = { Text(stringResource(R.string.field_household_name_hint)) },
                    singleLine = true,
                    isError = state.showNameValidation && state.householdName.isBlank(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.create() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("householdName"),
                )
                FormError(state.createError)
                ProgressButton(
                    text = stringResource(R.string.action_create_household),
                    onClick = viewModel::create,
                    loading = state.busy == SetupAction.CREATE,
                    enabled = state.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SetupCard(title = stringResource(R.string.setup_join_title), body = stringResource(R.string.setup_join_body)) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = viewModel::onCodeChange,
                    label = { Text(stringResource(R.string.field_invite_code)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    visualTransformation = InviteCodeTransformation,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { viewModel.join() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inviteCode"),
                )
                FormError(state.joinError)
                ProgressButton(
                    text = stringResource(R.string.action_join),
                    onClick = viewModel::join,
                    loading = state.busy == SetupAction.JOIN,
                    enabled = state.busy == null && state.codeWellFormed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun InvitationCard(invitation: PendingInvitation, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (invitation.invitedByName != null) {
                    stringResource(R.string.invitation_from, invitation.invitedByName!!, invitation.householdName)
                } else {
                    stringResource(R.string.invitation_to, invitation.householdName)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.invitation_role, stringResource(invitation.role.labelRes())),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDecline, enabled = !busy) { Text(stringResource(R.string.action_decline)) }
                ProgressButton(text = stringResource(R.string.action_join), onClick = onAccept, loading = false, enabled = !busy)
            }
        }
    }
}

/** Shows "H7K4P9QX" as "H7K4-P9QX" while the stored value stays normalized. */
internal object InviteCodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = if (raw.length > 4) raw.take(4) + "-" + raw.drop(4) else raw
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 4) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 4) (offset - 1).coerceAtMost(raw.length) else offset
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}
