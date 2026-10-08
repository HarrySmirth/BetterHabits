package app.betterhabits.domain.model

/** Invite code format shared with the database (see household_invite_codes.code check). */
object InviteCodes {
    const val LENGTH = 8

    /** No 0/O, 1/I/L: easy to read aloud and type. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    /** Uppercases and strips spaces, dashes and other separators the user may type. */
    fun normalize(input: String): String = input.filter { it.isLetterOrDigit() }.uppercase()

    fun isWellFormed(normalized: String): Boolean =
        normalized.length == LENGTH && normalized.all { it in ALPHABET }

    /** "H7K4P9QX" -> "H7K4-P9QX" for display. */
    fun format(code: String): String =
        if (code.length == LENGTH) "${code.take(LENGTH / 2)}-${code.drop(LENGTH / 2)}" else code
}
