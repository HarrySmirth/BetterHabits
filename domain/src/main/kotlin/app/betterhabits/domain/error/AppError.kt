package app.betterhabits.domain.error

/** User-meaningful failure categories. Data sources map technical exceptions into these. */
sealed interface AppError {
    data object Network : AppError
    data object NotConfigured : AppError
    data object SessionExpired : AppError
    data object InvalidCredentials : AppError
    data object EmailNotConfirmed : AppError
    data object EmailAlreadyRegistered : AppError
    data object WeakPassword : AppError
    data object InvalidCode : AppError
    data object RateLimited : AppError
    data object PermissionDenied : AppError
    data object InviteInvalid : AppError
    data object InviteExpired : AppError
    data object InvitationNotFound : AppError
    data object InvitationExpired : AppError
    data object AlreadyMember : AppError
    data object ChildrenNotAllowed : AppError
    data class TransferOwnershipRequired(val households: String?) : AppError
    /** The invitation was created but its notification email could not be sent. */
    data object InviteEmailNotSent : AppError
    data object GoogleSignInCancelled : AppError
    data object GoogleSignInUnavailable : AppError
    data class Unknown(val cause: Throwable? = null) : AppError
}

class AppException(val error: AppError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** The [AppError] carried by a failed [Result], or [AppError.Unknown]. */
val Throwable.appError: AppError get() = (this as? AppException)?.error ?: AppError.Unknown(this)
