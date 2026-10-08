package app.betterhabits.ui.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.betterhabits.BuildConfig
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

data class GoogleIdToken(val idToken: String, val rawNonce: String)

/**
 * Google sign-in through Android Credential Manager. The ID token is exchanged with Supabase;
 * the hashed nonce binds the token to this request to prevent replay.
 */
object GoogleSignIn {
    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Needs an Activity context so the account picker can be shown. */
    suspend fun requestIdToken(activityContext: Context): Result<GoogleIdToken> {
        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(hashedNonce).build(),
            )
            .build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Result.success(GoogleIdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken, rawNonce))
            } else {
                Result.failure(AppException(AppError.GoogleSignInUnavailable))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(AppException(AppError.GoogleSignInCancelled, e))
        } catch (e: NoCredentialException) {
            // No Google account on the device (or none usable for this app).
            Result.failure(AppException(AppError.GoogleSignInUnavailable, e))
        } catch (e: GetCredentialException) {
            Result.failure(AppException(AppError.GoogleSignInUnavailable, e))
        }
    }
}
