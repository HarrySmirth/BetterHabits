package app.betterhabits.data.remote

import android.util.Log
import app.betterhabits.BuildConfig
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import java.io.IOException

private const val TAG = "BH"

/**
 * Runs a backend call, converting any failure into an [AppException] with a user-meaningful
 * [AppError]. Technical details are logged in debug builds only and never shown to users.
 */
suspend fun <T> backendCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val error = e.toAppError()
        if (BuildConfig.DEBUG) Log.w(TAG, "Backend call failed: $error", e)
        Result.failure(if (e is AppException) e else AppException(error, e))
    }

/** Stable message keys raised by our RPCs / Edge Functions (see supabase/migrations). */
private val messageKeys: List<Pair<String, (String) -> AppError>> = listOf(
    "transfer_ownership_required" to { text -> AppError.TransferOwnershipRequired(text.substringAfter("households\":\"", "").substringBefore('"').ifBlank { null }) },
    "invitation_not_found" to { _ -> AppError.InvitationNotFound },
    "invitation_expired" to { _ -> AppError.InvitationExpired },
    "already_member" to { _ -> AppError.AlreadyMember },
    "children_cannot" to { _ -> AppError.ChildrenNotAllowed },
    "child_accounts_must_be_child_role" to { _ -> AppError.ChildrenNotAllowed },
    "children_cannot_own_households" to { _ -> AppError.ChildrenNotAllowed },
    "permission_denied" to { _ -> AppError.PermissionDenied },
    "invalid_pin" to { _ -> AppError.WeakPassword },
    "email_not_configured" to { _ -> AppError.InviteEmailNotSent },
    "email_send_failed" to { _ -> AppError.InviteEmailNotSent },
)

fun Throwable.toAppError(): AppError = when (this) {
    is AppException -> error
    is AuthRestException -> when (errorCode) {
        AuthErrorCode.InvalidCredentials -> AppError.InvalidCredentials
        AuthErrorCode.EmailNotConfirmed -> AppError.EmailNotConfirmed
        AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists -> AppError.EmailAlreadyRegistered
        AuthErrorCode.WeakPassword, AuthErrorCode.SamePassword -> AppError.WeakPassword
        AuthErrorCode.OtpExpired -> AppError.InvalidCode
        AuthErrorCode.OverRequestRateLimit, AuthErrorCode.OverEmailSendRateLimit -> AppError.RateLimited
        AuthErrorCode.SessionExpired, AuthErrorCode.SessionNotFound, AuthErrorCode.RefreshTokenNotFound,
        AuthErrorCode.RefreshTokenAlreadyUsed, AuthErrorCode.BadJwt -> AppError.SessionExpired
        else -> AppError.Unknown(this)
    }
    is PostgrestRestException -> fromText(listOfNotNull(message, error, description, hint, details?.toString()).joinToString(" "))
        ?: when {
            code == "42501" -> AppError.PermissionDenied
            // Expired or invalid JWT (PGRST301/302): temporary until the session refreshes.
            statusCode == 401 -> AppError.SessionExpired
            else -> AppError.Unknown(this)
        }
    is RestException -> fromText(listOfNotNull(message, error, description).joinToString(" "))
        ?: when (statusCode) {
            401 -> AppError.SessionExpired
            403 -> AppError.PermissionDenied
            429 -> AppError.RateLimited
            else -> AppError.Unknown(this)
        }
    is HttpRequestException, is HttpRequestTimeoutException, is IOException -> AppError.Network
    else -> AppError.Unknown(this)
}

private fun fromText(text: String): AppError? =
    messageKeys.firstOrNull { (key, _) -> text.contains(key) }?.second?.invoke(text)

/**
 * Waits until Supabase has a confirmed session before a data request. Without one, requests
 * would go out with the anonymous key and be refused as "permission denied", which sync would
 * mistake for a real rejection. A missing session is reported as [AppError.SessionExpired],
 * which callers treat as temporary (queued changes are kept and retried).
 */
suspend fun io.github.jan.supabase.SupabaseClient.requireSession(): io.github.jan.supabase.SupabaseClient {
    // Offline, the SDK can stay "initialising" while it retries a refresh; don't wait forever.
    kotlinx.coroutines.withTimeoutOrNull(SESSION_WAIT_MS) { auth.awaitInitialization() }
    if (auth.currentSessionOrNull() == null) throw AppException(AppError.SessionExpired)
    return this
}

private const val SESSION_WAIT_MS = 5_000L
