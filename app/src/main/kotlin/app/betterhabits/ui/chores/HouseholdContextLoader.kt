package app.betterhabits.ui.chores

import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.household.SessionState
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.HouseholdMember
import app.betterhabits.domain.model.HouseholdRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import java.time.DateTimeException
import java.time.ZoneId

/** The selected household as chore screens need it: id, schedule timezone, members and my rights. */
data class HouseholdContext(val details: HouseholdDetails) {
    val householdId: String get() = details.household.id
    val myId: String get() = details.currentUserId
    val isChild: Boolean get() = details.me?.role == HouseholdRole.CHILD

    /** Schedules are evaluated in the household's timezone (falls back to the device's if invalid). */
    val zone: ZoneId = try {
        ZoneId.of(details.household.timezone)
    } catch (_: DateTimeException) {
        ZoneId.systemDefault()
    }

    fun member(userId: String?): HouseholdMember? = userId?.let(details::member)
}

/** Emits the selected household id + current user whenever either changes. */
fun HouseholdSession.selectedHousehold(): Flow<Pair<String, String>> =
    state.filterIsInstance<SessionState.Ready>()
        .mapNotNull { ready -> ready.selected?.household?.id?.let { it to ready.user.id } }
        .distinctUntilChanged()

suspend fun HouseholdRepository.loadContext(householdId: String, userId: String): Result<HouseholdContext> =
    details(householdId, userId).map(::HouseholdContext)
