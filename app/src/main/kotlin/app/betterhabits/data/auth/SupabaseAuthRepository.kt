package app.betterhabits.data.auth

import app.betterhabits.data.remote.backendCall
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.ChildLogin
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SupabaseAuthRepository(
    private val client: SupabaseClient?,
    private val childEmailDomain: String,
) : AuthRepository {

    override val authState: Flow<AuthState> = client?.auth?.sessionStatus
        ?.map { status ->
            when (status) {
                is SessionStatus.Initializing -> AuthState.Loading
                is SessionStatus.Authenticated -> status.session.user?.toAuthUser()?.let(AuthState::SignedIn)
                    ?: AuthState.SignedOut
                // Token refresh failed (typically offline): keep the stored session so the app stays usable.
                is SessionStatus.RefreshFailure -> client.auth.currentUserOrNull()?.toAuthUser()?.let(AuthState::SignedIn)
                    ?: AuthState.SignedOut
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
            }
        }
        ?.distinctUntilChanged()
        ?: flowOf(AuthState.NotConfigured)

    private fun UserInfo.toAuthUser(): AuthUser {
        val isChild = appMetadata?.get("is_child")?.jsonPrimitive?.booleanOrNull == true ||
            ChildLogin.isChildEmail(email, childEmailDomain)
        return AuthUser(id = id, email = email.takeUnless { isChild }, isChild = isChild)
    }

    private fun requireClient(): SupabaseClient = client ?: throw AppException(AppError.NotConfigured)

    override suspend fun signIn(email: String, password: String) = backendCall {
        requireClient().auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    override suspend fun signUp(displayName: String, email: String, password: String) = backendCall {
        val auth = requireClient().auth
        auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("display_name", displayName.trim()) }
        }
        if (auth.currentSessionOrNull() != null) SignUpResult.SignedIn else SignUpResult.ConfirmationRequired(email.trim())
    }

    override suspend fun verifySignUp(email: String, code: String) = backendCall {
        requireClient().auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = code.trim())
        Unit
    }

    override suspend fun resendSignUpCode(email: String) = backendCall {
        requireClient().auth.resendEmail(OtpType.Email.SIGNUP, email.trim())
    }

    override suspend fun signInChild(username: String, pin: String) = backendCall {
        requireClient().auth.signInWith(Email) {
            this.email = ChildLogin.emailFor(username, childEmailDomain)
            this.password = pin
        }
    }

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String) = backendCall {
        requireClient().auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            nonce = rawNonce
        }
    }

    override suspend fun sendPasswordResetCode(email: String) = backendCall {
        requireClient().auth.resetPasswordForEmail(email.trim())
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String) = backendCall {
        val auth = requireClient().auth
        auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email.trim(), token = code.trim())
        auth.updateUser { password = newPassword }
        Unit
    }

    override suspend fun signOut() {
        // Clear the local session even if the server can't be reached.
        val auth = client?.auth ?: return
        backendCall { auth.signOut() }.onFailure { auth.clearSession() }
    }

    override suspend fun deleteAccount() = backendCall {
        val client = requireClient()
        client.functions.invoke("delete-account")
        client.auth.signOut(SignOutScope.LOCAL)
    }
}
