package app.betterhabits.ui.components

import androidx.annotation.StringRes
import app.betterhabits.R
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.HouseholdRole

/** Plain-language text for every error. Never shows technical details to users. */
@StringRes
fun AppError.messageRes(): Int = when (this) {
    AppError.Network -> R.string.error_network
    AppError.NotConfigured -> R.string.error_not_configured
    AppError.SessionExpired -> R.string.error_session_expired
    AppError.InvalidCredentials -> R.string.error_invalid_credentials
    AppError.EmailNotConfirmed -> R.string.error_email_not_confirmed
    AppError.EmailAlreadyRegistered -> R.string.error_email_registered
    AppError.WeakPassword -> R.string.error_weak_password
    AppError.InvalidCode -> R.string.error_invalid_code
    AppError.RateLimited -> R.string.error_rate_limited
    AppError.PermissionDenied -> R.string.error_permission_denied
    AppError.InviteInvalid -> R.string.error_invite_invalid
    AppError.InviteExpired -> R.string.error_invite_expired
    AppError.InvitationNotFound -> R.string.error_invitation_not_found
    AppError.InvitationExpired -> R.string.error_invitation_expired
    AppError.AlreadyMember -> R.string.error_already_member
    AppError.ChildrenNotAllowed -> R.string.error_children_not_allowed
    is AppError.TransferOwnershipRequired -> R.string.error_transfer_ownership
    AppError.InviteEmailNotSent -> R.string.error_invite_email_not_sent
    AppError.GoogleSignInCancelled -> R.string.error_google_cancelled
    AppError.GoogleSignInUnavailable -> R.string.error_google_unavailable
    is AppError.Unknown -> R.string.error_unknown
}

@StringRes
fun HouseholdRole.labelRes(): Int = when (this) {
    HouseholdRole.OWNER -> R.string.role_owner
    HouseholdRole.ADMIN -> R.string.role_admin
    HouseholdRole.MEMBER -> R.string.role_member
    HouseholdRole.CHILD -> R.string.role_child
}

@StringRes
fun HouseholdPermission.labelRes(): Int = when (this) {
    HouseholdPermission.CREATE_CHORES -> R.string.permission_create_chores
    HouseholdPermission.EDIT_CHORES -> R.string.permission_edit_chores
    HouseholdPermission.DELETE_CHORES -> R.string.permission_delete_chores
    HouseholdPermission.ASSIGN_CHORES -> R.string.permission_assign_chores
    HouseholdPermission.MANAGE_TEMPLATES -> R.string.permission_manage_templates
    HouseholdPermission.MANAGE_SETTINGS -> R.string.permission_manage_settings
    HouseholdPermission.INVITE_MEMBERS -> R.string.permission_invite_members
    HouseholdPermission.REMOVE_MEMBERS -> R.string.permission_remove_members
    HouseholdPermission.MANAGE_CHILDREN -> R.string.permission_manage_children
    HouseholdPermission.CONFIGURE_ALLOCATION -> R.string.permission_configure_allocation
}
