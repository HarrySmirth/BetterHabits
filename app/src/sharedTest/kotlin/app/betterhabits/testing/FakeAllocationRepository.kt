package app.betterhabits.testing

import app.betterhabits.data.allocation.AllocationInputs
import app.betterhabits.data.allocation.AllocationRepository
import app.betterhabits.data.allocation.AwayPeriod
import app.betterhabits.data.allocation.PreferenceTarget
import app.betterhabits.domain.allocation.AllocationSettings
import app.betterhabits.domain.allocation.Availability
import app.betterhabits.domain.allocation.DateRange
import app.betterhabits.domain.allocation.MemberPreferences
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.allocation.TimeOfDay
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import java.time.DayOfWeek
import java.util.UUID

class FakeAllocationRepository : AllocationRepository {
    val preferences = mutableMapOf<String, MemberPreferences>()
    val availability = mutableMapOf<String, Availability>()
    val away = mutableListOf<AwayPeriod>()
    var settings = AllocationSettings()
    val shares = mutableMapOf<String, Double>()
    var nextError: AppError? = null

    private inline fun call(block: () -> Unit): Result<Unit> = try {
        nextError?.let {
            nextError = null
            throw AppException(it)
        }
        block()
        Result.success(Unit)
    } catch (e: AppException) {
        Result.failure(e)
    }

    override suspend fun inputs(householdId: String) = Result.success(
        AllocationInputs(
            preferences.toMap(),
            availability.mapValues { (id, a) -> a.copy(awayPeriods = away.filter { it.memberId == id }.map { it.range }) },
            away.toList(),
            settings,
        ),
    )

    override suspend fun setPreference(householdId: String, userId: String, target: PreferenceTarget, level: PreferenceLevel?) = call {
        val current = preferences[userId] ?: MemberPreferences(userId)
        preferences[userId] = when (target) {
            is PreferenceTarget.ChoreTarget ->
                current.copy(byChore = if (level == null) current.byChore - target.choreId else current.byChore + (target.choreId to level))
            is PreferenceTarget.CategoryTarget ->
                current.copy(byCategory = if (level == null) current.byCategory - target.category else current.byCategory + (target.category to level))
        }
    }

    override suspend fun setAvailability(householdId: String, userId: String, unavailableDays: Set<DayOfWeek>, preferredTimes: Set<TimeOfDay>) = call {
        availability[userId] = Availability(userId, unavailableDays, preferredTimes)
    }

    override suspend fun addAwayPeriod(householdId: String, userId: String, range: DateRange, note: String?) = call {
        away += AwayPeriod(UUID.randomUUID().toString(), userId, range, note)
    }

    override suspend fun removeAwayPeriod(id: String) = call { away.removeIf { it.id == id } }

    override suspend fun saveSettings(householdId: String, settings: AllocationSettings) = call { this.settings = settings }

    override suspend fun setWorkloadShare(householdId: String, userId: String, share: Double) = call { shares[userId] = share }
}
