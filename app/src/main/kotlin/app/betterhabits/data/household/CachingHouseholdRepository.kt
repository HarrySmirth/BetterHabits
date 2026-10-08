package app.betterhabits.data.household

import app.betterhabits.data.local.CacheDao
import app.betterhabits.data.local.CacheEntity
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Household
import app.betterhabits.domain.model.HouseholdDetails
import app.betterhabits.domain.model.HouseholdMember
import app.betterhabits.domain.model.HouseholdSummary
import app.betterhabits.domain.model.PermissionOverride
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Remembers the household list and details so the app opens and works offline. Online, reads go
 * to the server (giving up after [REMOTE_TIMEOUT_MS]) and refresh the cache; offline, or when the
 * server can't be reached or the session isn't ready, the last known copy is used straight away.
 * Membership changes are not cached or queued: they must be checked by the server.
 */
class CachingHouseholdRepository(
    private val remote: HouseholdRepository,
    private val cache: CacheDao,
    private val isOnline: () -> Boolean,
) : HouseholdRepository by remote {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun myHouseholds(userId: String): Result<List<HouseholdSummary>> {
        val key = "households:$userId"
        cachedFirstIfOffline { cache.get(key)?.let { json.decodeFromString<List<CachedSummary>>(it).map(CachedSummary::toDomain) } }?.let { return Result.success(it) }
        return withTimeoutOrRemoteError { remote.myHouseholds(userId) }
            .onSuccess { list -> cache.put(CacheEntity(key, json.encodeToString(list.map(CachedSummary::from)))) }
            .recoverOffline { cache.get(key)?.let { json.decodeFromString<List<CachedSummary>>(it).map(CachedSummary::toDomain) } }
    }

    override suspend fun details(householdId: String, currentUserId: String): Result<HouseholdDetails> {
        val key = "household:$currentUserId:$householdId"
        cachedFirstIfOffline { cache.get(key)?.let { json.decodeFromString<CachedDetails>(it).toDomain(currentUserId) } }?.let { return Result.success(it) }
        return withTimeoutOrRemoteError { remote.details(householdId, currentUserId) }
            .onSuccess { cache.put(CacheEntity(key, json.encodeToString(CachedDetails.from(it)))) }
            .recoverOffline { cache.get(key)?.let { json.decodeFromString<CachedDetails>(it).toDomain(currentUserId) } }
    }

    private inline fun <T> cachedFirstIfOffline(cached: () -> T?): T? = if (isOnline()) null else cached()

    private suspend fun <T> withTimeoutOrRemoteError(call: suspend () -> Result<T>): Result<T> =
        withTimeoutOrNull(REMOTE_TIMEOUT_MS) { call() } ?: Result.failure(AppException(AppError.Network))

    /** Falls back to the cached copy when the server can't be reached or the session isn't ready yet. */
    private inline fun <T> Result<T>.recoverOffline(cached: () -> T?): Result<T> {
        val error = exceptionOrNull() ?: return this
        if (error.appError != AppError.Network && error.appError != AppError.SessionExpired) return this
        return cached()?.let { Result.success(it) } ?: this
    }

    private companion object {
        const val REMOTE_TIMEOUT_MS = 4_000L
    }
}

@Serializable
private data class CachedHousehold(val id: String, val name: String, val timezone: String) {
    fun toDomain() = Household(id, name, timezone)

    companion object {
        fun from(h: Household) = CachedHousehold(h.id, h.name, h.timezone)
    }
}

@Serializable
private data class CachedSummary(val household: CachedHousehold, val role: String) {
    fun toDomain() = HouseholdSummary(household.toDomain(), enumValueOf(role))

    companion object {
        fun from(s: HouseholdSummary) = CachedSummary(CachedHousehold.from(s.household), s.myRole.name)
    }
}

@Serializable
private data class CachedMember(val userId: String, val name: String, val role: String, val isChild: Boolean, val joinedAt: Long)

@Serializable
private data class CachedOverride(val userId: String, val permission: String, val granted: Boolean)

@Serializable
private data class CachedDetails(
    val household: CachedHousehold,
    val members: List<CachedMember>,
    val rolePermissions: Map<String, List<String>>,
    val overrides: List<CachedOverride>,
) {
    fun toDomain(currentUserId: String) = HouseholdDetails(
        household = household.toDomain(),
        members = members.mapNotNull { m ->
            m.role.toRole()?.let { HouseholdMember(m.userId, m.name, it, m.isChild, Instant.ofEpochMilli(m.joinedAt)) }
        },
        rolePermissions = rolePermissions.mapNotNull { (role, perms) ->
            role.toRole()?.let { it to perms.mapNotNull(String::toPermission).toSet() }
        }.toMap(),
        overrides = overrides.mapNotNull { o -> o.permission.toPermission()?.let { PermissionOverride(o.userId, it, o.granted) } },
        currentUserId = currentUserId,
    )

    companion object {
        fun from(d: HouseholdDetails) = CachedDetails(
            household = CachedHousehold.from(d.household),
            members = d.members.map { CachedMember(it.userId, it.displayName, it.role.name, it.isChildAccount, it.joinedAt.toEpochMilli()) },
            rolePermissions = d.rolePermissions.map { (role, perms) -> role.name to perms.map { it.name } }.toMap(),
            overrides = d.overrides.map { CachedOverride(it.userId, it.permission.name, it.granted) },
        )
    }
}
