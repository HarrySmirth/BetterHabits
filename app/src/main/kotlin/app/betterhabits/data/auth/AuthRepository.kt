package app.betterhabits.data.auth

import kotlinx.coroutines.flow.Flow

data class AuthUser(
    val id: String,
    /** Null for child accounts, whose internal login address is never shown. */
    val email: String?,
    val isChild: Boolean,
)

sealed interface AuthState {
    data object Loading : AuthState
    /** The build has no backend configuration. */
    data object NotConfigured : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: AuthUser) : AuthState
}

sealed interface SignUpResult {
    /** A 6-digit code was emailed; call [AuthRepository.verifySignUp]. */
    data class ConfirmationRequired(val email: String) : SignUpResult
    data object SignedIn : SignUpResult
}

interface AuthRepository {
    val authState: Flow<AuthState>

    suspend fun signIn(email: String, password: String): Result<Unit>

    suspend fun signUp(displayName: String, email: String, password: String): Result<SignUpResult>

    suspend fun verifySignUp(email: String, code: String): Result<Unit>

    suspend fun resendSignUpCode(email: String): Result<Unit>

    suspend fun signInChild(username: String, pin: String): Result<Unit>

    suspend fun signInWithGoogle(idToken: String, rawNonce: String): Result<Unit>

    suspend fun sendPasswordResetCode(email: String): Result<Unit>

    /** Verifies the emailed reset code (which signs the user in) and sets the new password. */
    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit>

    suspend fun signOut()

    suspend fun deleteAccount(): Result<Unit>
}
