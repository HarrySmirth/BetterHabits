package app.betterhabits.domain.model

/**
 * Estimated effort, in whole minutes. Effort — not chore count or points — is the primary
 * workload measure throughout the app.
 */
@JvmInline
value class Effort(val minutes: Int) : Comparable<Effort> {
    init {
        require(minutes >= 0) { "Effort cannot be negative: $minutes" }
    }

    val hoursPart: Int get() = minutes / 60
    val minutesPart: Int get() = minutes % 60

    operator fun plus(other: Effort): Effort = Effort(minutes + other.minutes)

    operator fun times(count: Int): Effort = Effort(minutes * count)

    override fun compareTo(other: Effort): Int = minutes.compareTo(other.minutes)

    companion object {
        val ZERO = Effort(0)
    }
}

fun Iterable<Effort>.sum(): Effort = fold(Effort.ZERO) { acc, e -> acc + e }
