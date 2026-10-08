package app.betterhabits.testing

import app.betterhabits.data.auth.AuthRepository
import app.betterhabits.data.auth.AuthState
import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.auth.SignUpResult
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory accounts. Passwords are compared in plain text: test-only. */
class FakeAuthRepository(initial: AuthState = AuthState.SignedOut) : AuthRepository {

    data class Account(val user: AuthUser, val password: String, val confirmed: Boolean = true, val name: String)

    private val state = MutableStateFlow(initial)
    override val authState: StateFlow<AuthState> = state

    val accounts = mutableMapOf<String, Account>()
    var nextError: AppError? = null
    val signUpCode = "123456"

    fun addAccount(email: String, password: String, name: String = "Test", id: String = "user-$email", isChild: Boolean = false) {
        accounts[email.lowercase()] = Account(AuthUser(id, if (isChild) null else email, isChild), password, name = name)
    }

    fun signInAs(user: AuthUser) {
        state.value = AuthState.SignedIn(user)
    }

    private fun failIfRequested() {
        nextError?.let {
            nextError = null
            throw AppException(it)
        }
    }

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        failIfRequested()
        Result.success(block())
    } catch (e: AppException) {
        Result.failure(e)
    }

    override suspend fun signIn(email: String, password: String) = attempt {
        val account = accounts[email.trim().lowercase()]
        if (account == null || account.password != password) throw AppException(AppError.InvalidCredentials)
        if (!account.confirmed) throw AppException(AppError.EmailNotConfirmed)
        state.value = AuthState.SignedIn(account.user)
    }

    override suspend fun signUp(displayName: String, email: String, password: String) = attempt<SignUpResult> {
        val key = email.trim().lowercase()
        if (key in accounts) throw AppException(AppError.EmailAlreadyRegistered)
        accounts[key] = Account(AuthUser("user-$key", key, false), password, confirmed = false, name = displayName)
        SignUpResult.ConfirmationRequired(email.trim())
    }

    override suspend fun verifySignUp(email: String, code: String) = attempt {
        if (code != signUpCode) throw AppException(AppError.InvalidCode)
        val key = email.lowercase()
        val account = accounts.getValue(key).copy(confirmed = true)
        accounts[key] = account
        state.value = AuthState.SignedIn(account.user)
    }

    override suspend fun resendSignUpCode(email: String) = attempt { }

    override suspend fun signInChild(username: String, pin: String) = signIn(username, pin)

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String) = attempt<Unit> {
        throw AppException(AppError.GoogleSignInUnavailable)
    }

    override suspend fun sendPasswordResetCode(email: String) = attempt { }

    override suspend fun resetPassword(email: String, code: String, newPassword: String) = attempt {
        if (code != signUpCode) throw AppException(AppError.InvalidCode)
        val key = email.lowercase()
        val account = accounts.getValue(key).copy(password = newPassword)
        accounts[key] = account
        state.value = AuthState.SignedIn(account.user)
    }

    override suspend fun signOut() {
        state.value = AuthState.SignedOut
    }

    override suspend fun deleteAccount() = attempt { state.value = AuthState.SignedOut }
}
