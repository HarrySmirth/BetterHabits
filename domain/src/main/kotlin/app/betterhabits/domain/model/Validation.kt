package app.betterhabits.domain.model

/** Input rules shared by forms. The server enforces its own limits; these give fast feedback. */
object Validation {
    const val MIN_PASSWORD_LENGTH = 8
    const val MIN_CHILD_PIN_LENGTH = 6
    const val MAX_DISPLAY_NAME_LENGTH = 50
    const val MAX_HOUSEHOLD_NAME_LENGTH = 60
    const val OTP_LENGTH = 6

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())

    fun isValidPassword(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH

    fun isValidChildPin(pin: String): Boolean = pin.length in MIN_CHILD_PIN_LENGTH..72

    fun isValidDisplayName(name: String): Boolean = name.trim().length in 1..MAX_DISPLAY_NAME_LENGTH

    fun isValidHouseholdName(name: String): Boolean = name.trim().length in 1..MAX_HOUSEHOLD_NAME_LENGTH

    fun isValidOtp(code: String): Boolean = code.length == OTP_LENGTH && code.all(Char::isDigit)
}

/** Child accounts sign in with a username that maps to an address on a non-routable domain. */
object ChildLogin {
    fun normalizeUsername(input: String): String = input.trim().lowercase()

    fun emailFor(username: String, domain: String): String = "${normalizeUsername(username)}@$domain"

    /** Child account emails are an implementation detail and should never be shown. */
    fun isChildEmail(email: String?, domain: String): Boolean = email?.endsWith("@$domain", ignoreCase = true) == true

    fun usernameFrom(email: String, domain: String): String = email.removeSuffix("@$domain")
}
