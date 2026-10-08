package app.betterhabits.data.household

import app.betterhabits.data.local.CacheDao
import app.betterhabits.data.local.CacheEntity
import app.betterhabits.domain.error.AppError
import app.betterhabits.testing.FakeHouseholdRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingHouseholdRepositoryTest {

    private class MemoryCache : CacheDao {
        val entries = mutableMapOf<String, String>()
        override suspend fun get(key: String) = entries[key]
        override suspend fun put(entry: CacheEntity) { entries[entry.key] = entry.json }
    }

    private val remote = FakeHouseholdRepository { "harry" }.apply { names["harry"] = "Harry" }
    private val cache = MemoryCache()
    private var online = true
    private val repo = CachingHouseholdRepository(remote, cache, isOnline = { online })

    @Test
    fun `online reads refresh the cache, and offline reads come from it without touching the network`() = runTest {
        val id = remote.seedHousehold("Home", "harry")
        assertEquals("Home", repo.myHouseholds("harry").getOrThrow().single().household.name)
        assertEquals("Harry", repo.details(id, "harry").getOrThrow().members.single().displayName)

        online = false
        remote.nextError = AppError.PermissionDenied // would fail if the network were used
        assertEquals("Home", repo.myHouseholds("harry").getOrThrow().single().household.name)
        assertEquals("Harry", repo.details(id, "harry").getOrThrow().members.single().displayName)
    }

    @Test
    fun `unreachable server or unready session falls back to the cache`() = runTest {
        remote.seedHousehold("Home", "harry")
        repo.myHouseholds("harry")

        remote.nextError = AppError.Network
        assertTrue(repo.myHouseholds("harry").isSuccess)
        remote.nextError = AppError.SessionExpired
        assertTrue(repo.myHouseholds("harry").isSuccess)
    }

    @Test
    fun `a hanging request gives up and uses the cache`() = runTest {
        remote.seedHousehold("Home", "harry")
        repo.myHouseholds("harry")
        val slow = object : HouseholdRepository by remote {
            override suspend fun myHouseholds(userId: String) = run { delay(60_000); remote.myHouseholds(userId) }
        }
        val result = CachingHouseholdRepository(slow, cache, isOnline = { true }).myHouseholds("harry")
        assertEquals("Home", result.getOrThrow().single().household.name)
    }

    @Test
    fun `real errors are not hidden by the cache`() = runTest {
        remote.seedHousehold("Home", "harry")
        repo.myHouseholds("harry")
        remote.nextError = AppError.PermissionDenied
        assertEquals(AppError.PermissionDenied, (repo.myHouseholds("harry").exceptionOrNull() as app.betterhabits.domain.error.AppException).error)
    }
}
