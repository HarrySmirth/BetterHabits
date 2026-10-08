package app.betterhabits.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.auth.SignUpResult
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Validation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Successful sign-in needs no navigation: the session state changes and the root switches screens.

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val showValidation: Boolean = false,
    val submitting: Boolean = false,
    val error: AppError? = null,
    /** Set when the account exists but the email isn't verified yet. */
    val verifyEmail: String? = null,
) {
    val emailValid get() = Validation.isValidEmail(email)
    val passwordValid get() = password.isNotEmpty()
}

class SignInViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onVerifyHandled() = _state.update { it.copy(verifyEmail = null) }

    fun submit() {
        val s = _state.value
        if (!s.emailValid || !s.passwordValid) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            auth.signIn(s.email, s.password).onFailure { e ->
                val error = e.appError
                _state.update {
                    it.copy(
                        submitting = false,
                        error = error.takeUnless { error == AppError.EmailNotConfirmed },
                        verifyEmail = s.email.trim().takeIf { error == AppError.EmailNotConfirmed },
                    )
                }
                if (error == AppError.EmailNotConfirmed) auth.resendSignUpCode(s.email)
            }.onSuccess { _state.update { it.copy(submitting = false) } }
        }
    }

    fun signInWithGoogle(idToken: String, rawNonce: String) {
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            auth.signInWithGoogle(idToken, rawNonce)
                .onFailure { e -> _state.update { it.copy(submitting = false, error = e.appError) } }
                .onSuccess { _state.update { it.copy(submitting = false) } }
        }
    }

    fun onGoogleFailed(error: AppError) {
        if (error != AppError.GoogleSignInCancelled) _state.update { it.copy(error = error) }
    }
}

data class SignUpUiState(
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val showValidation: Boolean = false,
    val submitting: Boolean = false,
    val error: AppError? = null,
    val verifyEmail: String? = null,
) {
    val nameValid get() = Validation.isValidDisplayName(displayName)
    val emailValid get() = Validation.isValidEmail(email)
    val passwordValid get() = Validation.isValidPassword(password)
}

class SignUpViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignUpUiState())
    val state: StateFlow<SignUpUiState> = _state.asStateFlow()

    fun onNameChange(value: String) = _state.update { it.copy(displayName = value, error = null) }
    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onVerifyHandled() = _state.update { it.copy(verifyEmail = null) }

    fun submit() {
        val s = _state.value
        if (!s.nameValid || !s.emailValid || !s.passwordValid) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            auth.signUp(s.displayName, s.email, s.password)
                .onSuccess { result ->
                    _state.update {
                        it.copy(submitting = false, verifyEmail = (result as? SignUpResult.ConfirmationRequired)?.email)
                    }
                }
                .onFailure { e -> _state.update { it.copy(submitting = false, error = e.appError) } }
        }
    }
}

data class VerifyEmailUiState(
    val email: String,
    val code: String = "",
    val submitting: Boolean = false,
    val error: AppError? = null,
    val resent: Boolean = false,
)

class VerifyEmailViewModel(savedStateHandle: SavedStateHandle, private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(VerifyEmailUiState(email = requireNotNull(savedStateHandle.get<String>("email"))))
    val state: StateFlow<VerifyEmailUiState> = _state.asStateFlow()

    fun onCodeChange(value: String) {
        val digits = value.filter(Char::isDigit).take(Validation.OTP_LENGTH)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == Validation.OTP_LENGTH) submit()
    }

    fun submit() {
        val s = _state.value
        if (!Validation.isValidOtp(s.code) || s.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            auth.verifySignUp(s.email, s.code)
                .onFailure { e -> _state.update { it.copy(submitting = false, error = e.appError, code = "") } }
                .onSuccess { _state.update { it.copy(submitting = false) } }
        }
    }

    fun resend() {
        viewModelScope.launch {
            auth.resendSignUpCode(_state.value.email)
                .onSuccess { _state.update { it.copy(resent = true, error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.appError) } }
        }
    }
}

data class ForgotPasswordUiState(
    val email: String = "",
    val codeSent: Boolean = false,
    val code: String = "",
    val newPassword: String = "",
    val showValidation: Boolean = false,
    val submitting: Boolean = false,
    val error: AppError? = null,
)

class ForgotPasswordViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onCodeChange(value: String) =
        _state.update { it.copy(code = value.filter(Char::isDigit).take(Validation.OTP_LENGTH), error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(newPassword = value, error = null) }

    fun sendCode() {
        val s = _state.value
        if (!Validation.isValidEmail(s.email)) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            auth.sendPasswordResetCode(s.email)
                .onSuccess { _state.update { it.copy(submitting = false, codeSent = true, showValidation = false) } }
                .onFailure { e -> _state.update { it.copy(submitting = false, error = e.appError) } }
        }
    }

    fun resetPassword() {
        val s = _state.value
        if (!Validation.isValidOtp(s.code) || !Validation.isValidPassword(s.newPassword)) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // On success the user is signed in and the root navigates away.
            auth.resetPassword(s.email, s.code, s.newPassword)
                .onSuccess { _state.update { it.copy(submitting = false) } }
                .onFailure { e -> _state.update { it.copy(submitting = false, error = e.appError) } }
        }
    }
}

data class ChildSignInUiState(
    val username: String = "",
    val pin: String = "",
    val showValidation: Boolean = false,
    val submitting: Boolean = false,
    val error: AppError? = null,
)

class ChildSignInViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ChildSignInUiState())
    val state: StateFlow<ChildSignInUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPinChange(value: String) = _state.update { it.copy(pin = value, error = null) }

    fun submit() {
        val s = _state.value
        if (s.username.isBlank() || !Validation.isValidChildPin(s.pin)) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            auth.signInChild(s.username, s.pin)
                .onSuccess { _state.update { it.copy(submitting = false) } }
                .onFailure { e -> _state.update { it.copy(submitting = false, error = e.appError) } }
        }
    }
}
