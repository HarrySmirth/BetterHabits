package app.betterhabits.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Validation
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.components.CenteredFormColumn
import app.betterhabits.ui.components.Frog
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.FormError
import app.betterhabits.ui.components.PasswordField
import app.betterhabits.ui.components.ProgressButton
import app.betterhabits.ui.components.ScreenTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScaffold(onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        CenteredFormColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) { content() }
    }
}

@Composable
private fun EmailField(value: String, onValueChange: (String) -> Unit, isError: Boolean, imeAction: ImeAction = ImeAction.Next, onIme: () -> Unit = {}) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_email)) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError) ({ Text(stringResource(R.string.validation_email)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("email"),
    )
}

@Composable
fun SignInScreen(
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    onChildSignIn: () -> Unit,
    onVerifyEmail: (String) -> Unit,
    viewModel: SignInViewModel = viewModel(factory = AppViewModelFactory.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.verifyEmail) {
        state.verifyEmail?.let {
            viewModel.onVerifyHandled()
            onVerifyEmail(it)
        }
    }

    AuthScaffold(onBack = null) {
        Frog(FrogMood.HAPPY, width = 120.dp)
        ScreenTitle(stringResource(R.string.sign_in_title), stringResource(R.string.sign_in_subtitle))
        Spacer(Modifier.height(8.dp))
        EmailField(state.email, viewModel::onEmailChange, isError = state.showValidation && !state.emailValid)
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.field_password),
            isError = state.showValidation && !state.passwordValid,
            onImeAction = viewModel::submit,
            modifier = Modifier.testTag("password"),
        )
        FormError(state.error)
        ProgressButton(
            text = stringResource(R.string.action_sign_in),
            onClick = viewModel::submit,
            loading = state.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onForgotPassword) { Text(stringResource(R.string.action_forgot_password)) }

        if (GoogleSignIn.isConfigured) {
            OrDivider()
            OutlinedButton(
                onClick = {
                    scope.launch {
                        GoogleSignIn.requestIdToken(context)
                            .onSuccess { viewModel.signInWithGoogle(it.idToken, it.rawNonce) }
                            .onFailure { viewModel.onGoogleFailed(it.appError) }
                    }
                },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_continue_google)) }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCreateAccount) { Text(stringResource(R.string.action_create_account)) }
        TextButton(onClick = onChildSignIn) { Text(stringResource(R.string.action_child_sign_in)) }
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text(stringResource(R.string.label_or), style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
fun SignUpScreen(
    onBack: () -> Unit,
    onVerifyEmail: (String) -> Unit,
    viewModel: SignUpViewModel = viewModel(factory = AppViewModelFactory.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.verifyEmail) {
        state.verifyEmail?.let {
            viewModel.onVerifyHandled()
            onVerifyEmail(it)
        }
    }
    AuthScaffold(onBack = onBack) {
        ScreenTitle(stringResource(R.string.sign_up_title), stringResource(R.string.sign_up_subtitle))
        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::onNameChange,
            label = { Text(stringResource(R.string.field_your_name)) },
            singleLine = true,
            isError = state.showValidation && !state.nameValid,
            supportingText = if (state.showValidation && !state.nameValid) ({ Text(stringResource(R.string.validation_name)) }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("displayName"),
        )
        EmailField(state.email, viewModel::onEmailChange, isError = state.showValidation && !state.emailValid)
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.field_password),
            isError = state.showValidation && !state.passwordValid,
            supportingText = pluralStringResource(R.plurals.validation_password, Validation.MIN_PASSWORD_LENGTH, Validation.MIN_PASSWORD_LENGTH),
            onImeAction = viewModel::submit,
            modifier = Modifier.testTag("password"),
        )
        FormError(state.error)
        ProgressButton(
            text = stringResource(R.string.action_create_account),
            onClick = viewModel::submit,
            loading = state.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun VerifyEmailScreen(onBack: () -> Unit, viewModel: VerifyEmailViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthScaffold(onBack = onBack) {
        ScreenTitle(stringResource(R.string.verify_title), stringResource(R.string.verify_subtitle, state.email))
        OtpField(state.code, viewModel::onCodeChange, onDone = viewModel::submit)
        FormError(state.error)
        ProgressButton(
            text = stringResource(R.string.action_verify),
            onClick = viewModel::submit,
            loading = state.submitting,
            enabled = Validation.isValidOtp(state.code),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = viewModel::resend, enabled = !state.resent) {
            Text(stringResource(if (state.resent) R.string.verify_resent else R.string.action_resend_code))
        }
    }
}

@Composable
private fun OtpField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_code)) },
        singleLine = true,
        isError = isError,
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().testTag("otp"),
    )
}

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, viewModel: ForgotPasswordViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthScaffold(onBack = onBack) {
        if (!state.codeSent) {
            ScreenTitle(stringResource(R.string.forgot_title), stringResource(R.string.forgot_subtitle))
            EmailField(
                state.email,
                viewModel::onEmailChange,
                isError = state.showValidation && !Validation.isValidEmail(state.email),
                imeAction = ImeAction.Done,
                onIme = viewModel::sendCode,
            )
            FormError(state.error)
            ProgressButton(
                text = stringResource(R.string.action_send_code),
                onClick = viewModel::sendCode,
                loading = state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ScreenTitle(stringResource(R.string.reset_title), stringResource(R.string.verify_subtitle, state.email.trim()))
            OtpField(
                state.code,
                viewModel::onCodeChange,
                onDone = {},
                isError = state.showValidation && !Validation.isValidOtp(state.code),
            )
            PasswordField(
                value = state.newPassword,
                onValueChange = viewModel::onPasswordChange,
                label = stringResource(R.string.field_new_password),
                isError = state.showValidation && !Validation.isValidPassword(state.newPassword),
                supportingText = pluralStringResource(R.plurals.validation_password, Validation.MIN_PASSWORD_LENGTH, Validation.MIN_PASSWORD_LENGTH),
                onImeAction = viewModel::resetPassword,
            )
            FormError(state.error)
            ProgressButton(
                text = stringResource(R.string.action_reset_password),
                onClick = viewModel::resetPassword,
                loading = state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ChildSignInScreen(onBack: () -> Unit, viewModel: ChildSignInViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthScaffold(onBack = onBack) {
        ScreenTitle(stringResource(R.string.child_sign_in_title), stringResource(R.string.child_sign_in_subtitle))
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text(stringResource(R.string.field_username)) },
            singleLine = true,
            isError = state.showValidation && state.username.isBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            value = state.pin,
            onValueChange = viewModel::onPinChange,
            label = stringResource(R.string.field_pin),
            isError = state.showValidation && !Validation.isValidChildPin(state.pin),
            onImeAction = viewModel::submit,
        )
        FormError(state.error)
        ProgressButton(
            text = stringResource(R.string.action_sign_in),
            onClick = viewModel::submit,
            loading = state.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
