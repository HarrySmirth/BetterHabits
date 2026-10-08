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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the Supabase session status means for us, independent of the SDK's types (for testing). */
internal sealed interface SessionSignal {
    /** Loading the stored session, or refreshing it before reporting (can last a while offline). */
    data object Initializing : SessionSignal
    data class Authenticated(val user: AuthUser?) : SessionSignal
    /** The access token expired and couldn't be refreshed: almost always "offline". */
    data object RefreshFailed : SessionSignal
    /** No session, an explicit sign-out, or a session the server revoked. */
    data object NotAuthenticated : SessionSignal
}

/**
 * Offline-first sign-in state. Only a real sign-out (or revoked session) signs the user out:
 * being offline, even with an expired token, keeps the last signed-in user, because signing out
 * wipes the local data. Without a remembered user, startup waits for Supabase as before.
 */
internal fun resolveAuthState(signal: SessionSignal, lastUser: AuthUser?): AuthState = when (signal) {
    SessionSignal.Initializing -> lastUser?.let(AuthState::SignedIn) ?: AuthState.Loading
    is SessionSignal.Authenticated -> signal.user?.let(AuthState::SignedIn) ?: lastUser?.let(AuthState::SignedIn) ?: AuthState.Loading
    SessionSignal.RefreshFailed -> lastUser?.let(AuthState::SignedIn) ?: AuthState.Loading
    SessionSignal.NotAuthenticated -> AuthState.SignedOut
}

class SupabaseAuthRepository(
    private val client: SupabaseClient?,
    private val childEmailDomain: String,
    private val lastUserStore: LastUserStore,
) : AuthRepository {

    override val authState: Flow<AuthState> = client?.auth?.sessionStatus?.let { statuses ->
        flow {
            var lastUser = lastUserStore.get()
            statuses.collect { status ->
                val signal = when (status) {
                    is SessionStatus.Initializing -> SessionSignal.Initializing
                    is SessionStatus.Authenticated -> SessionSignal.Authenticated(status.session.user?.toAuthUser())
                    is SessionStatus.RefreshFailure -> SessionSignal.RefreshFailed
                    is SessionStatus.NotAuthenticated -> SessionSignal.NotAuthenticated
                }
                val state = resolveAuthState(signal, lastUser)
                val remembered = (state as? AuthState.SignedIn)?.user
                if (remembered != lastUser && (remembered != null || state == AuthState.SignedOut)) {
                    lastUser = remembered
                    lastUserStore.set(remembered)
                }
                emit(state)
            }
        }.distinctUntilChanged()
    } ?: flowOf(AuthState.NotConfigured)

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
