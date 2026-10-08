package app.betterhabits.domain.allocation

import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreCategory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** How someone feels about a chore. [score] < 0 = wants it, > 0 = would rather not. */
enum class PreferenceLevel(val score: Int) {
    LOVE(-2),
    LIKE(-1),
    NEUTRAL(0),
    DISLIKE(1),
    HATE(2),

    /** A hard constraint (ability, allergy, health): never assigned. */
    CANNOT_DO(0),
    ;

    val isUndesirable: Boolean get() = this == DISLIKE || this == HATE
}

/**
 * One person's preferences: per chore, falling back to the template the chore came from, then its
 * category, then neutral.
 */
data class MemberPreferences(
    val memberId: String,
    val byChore: Map<String, PreferenceLevel> = emptyMap(),
    val byCategory: Map<ChoreCategory, PreferenceLevel> = emptyMap(),
    val byTemplate: Map<String, PreferenceLevel> = emptyMap(),
) {
    fun forChore(chore: Chore): PreferenceLevel =
        byChore[chore.id] ?: chore.templateId?.let(byTemplate::get) ?: byCategory[chore.category] ?: PreferenceLevel.NEUTRAL
}

enum class TimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    ;

    companion object {
        fun of(time: LocalTime): TimeOfDay = when {
            time.isBefore(LocalTime.NOON) -> MORNING
            time.isBefore(LocalTime.of(17, 0)) -> AFTERNOON
            else -> EVENING
        }
    }
}

data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "end before start" }
    }

    operator fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(end)
}

/**
 * When someone can do chores. Unavailable days and away periods make occurrences on those dates
 * unavailable; preferred times only nudge (an evening chore suits an "evenings" person better).
 */
data class Availability(
    val memberId: String,
    val unavailableDays: Set<DayOfWeek> = emptySet(),
    val preferredTimes: Set<TimeOfDay> = emptySet(),
    val awayPeriods: List<DateRange> = emptyList(),
) {
    fun isAvailableOn(date: LocalDate): Boolean = date.dayOfWeek !in unavailableDays && awayPeriods.none { date in it }

    /** True/false when the occurrence has a time and this person stated time preferences; null otherwise. */
    fun prefersTime(time: LocalTime?): Boolean? =
        if (time == null || preferredTimes.isEmpty()) null else TimeOfDay.of(time) in preferredTimes
}

/** Household-level knobs for automatic allocation. */
data class AllocationSettings(
    /**
     * 0 = fairness only (preferences ignored except "can't do"); 100 = preferences matter as much
     * as they can without breaking the fairness cap. Default: balanced.
     */
    val preferenceWeight: Int = DEFAULT_PREFERENCE_WEIGHT,
    /**
     * When false (default), disliked chores are shared out so nobody can permanently avoid all
     * undesirable work just by marking it. When true, strong dislikes are honoured where possible.
     */
    val allowAvoidance: Boolean = false,
) {
    init {
        require(preferenceWeight in 0..100) { "preferenceWeight must be 0..100" }
    }

    companion object {
        const val DEFAULT_PREFERENCE_WEIGHT = 50
    }
}

/** A member as the allocator sees them. [share] is their relative fair share of work (1 = standard). */
data class AllocationMember(
    val id: String,
    val name: String,
    val share: Double = 1.0,
    val preferences: MemberPreferences = MemberPreferences(id),
    val availability: Availability = Availability(id),
) {
    init {
        require(share > 0) { "share must be positive" }
    }
}
