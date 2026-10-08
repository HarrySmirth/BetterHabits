package app.betterhabits.data.sync

import app.betterhabits.data.chore.ChoreDto
import app.betterhabits.data.chore.OccurrenceDto
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class SyncEngineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val household = "h1"

    private class FakeStore : SyncStore {
        val ops = MutableStateFlow<List<QueuedOp>>(emptyList())
        val cursors = mutableMapOf<String, String>()
        val chores = mutableMapOf<String, ChoreDto>()
        val occurrences = mutableMapOf<String, OccurrenceDto>()
        val discarded = mutableListOf<String>()
        private var seq = 0L

        fun queue(op: PendingOp) = ops.value.let { ops.value = it + QueuedOp(++seq, op, 0) }

        override val pendingCount = ops.map { it.size }
        override suspend fun pendingOps(householdId: String) = ops.value
        override suspend fun householdsWithPending() = if (ops.value.isEmpty()) emptyList() else listOf("h1")
        override suspend fun pendingKeys(householdId: String) = ops.value.map { it.op.entityKey() }.toSet()
        override suspend fun removeOp(seq: Long) { ops.value = ops.value.filterNot { it.seq == seq } }
        override suspend fun recordFailedAttempt(seq: Long) {
            ops.value = ops.value.map { if (it.seq == seq) it.copy(attempts = it.attempts + 1) else it }
        }
        override suspend fun cursor(householdId: String, stream: String) = cursors[stream]
        override suspend fun setCursor(householdId: String, stream: String, cursor: String) { cursors[stream] = cursor }
        override suspend fun resetCursors(householdId: String) = cursors.clear()
        override suspend fun discardLocal(entityKey: String) { discarded += entityKey }
        override suspend fun applyChores(householdId: String, rows: List<ChoreDto>, skipKeys: Set<String>) {
            rows.filter { EntityKeys.chore(it.id) !in skipKeys }.forEach { chores[it.id] = it }
        }
        override suspend fun applyOccurrences(householdId: String, rows: List<OccurrenceDto>, skipKeys: Set<String>) {
            rows.forEach { occurrences["${it.choreId}|${it.occurrenceDate}"] = it }
        }
    }

    private class FakeRemote : ChoreRemote {
        val pushed = mutableListOf<PendingOp>()
        val failures = mutableMapOf<String, ArrayDeque<AppError>>() // choreId -> errors to throw in turn
        var serverChores = listOf<ChoreDto>()
        val pullCursors = mutableListOf<String?>()

        override suspend fun pullChores(householdId: String, since: String?): List<ChoreDto> {
            pullCursors += since
            return serverChores
        }

        override suspend fun pullOccurrences(householdId: String, since: String?, minDate: LocalDate) = emptyList<OccurrenceDto>()

        override suspend fun push(op: PendingOp) {
            val id = (op as? PendingOp.UpdateChore)?.choreId ?: (op as? PendingOp.CreateChore)?.choreId
            failures[id]?.removeFirstOrNull()?.let { throw AppException(it) }
            pushed += op
        }
    }

    private val store = FakeStore()
    private val remote = FakeRemote()
    private val online = MutableStateFlow(true)
    private var retries = 0
    private val engine by lazy { SyncEngine(store, remote, mainDispatcherRule.appScope, online, scheduleRetry = { retries++ }) }

    private fun update(choreId: String, name: String) =
        PendingOp.UpdateChore(choreId, JsonObject(mapOf("name" to JsonPrimitive(name))))

    private fun dto(id: String, updatedAt: String, name: String = id) = ChoreDto(
        id = id, householdId = household, name = name, estimatedMinutes = 5, recurrenceType = "DAILY",
        startDate = "2026-10-01", updatedAt = updatedAt,
    )

    @Test
    fun `pushes queued changes in order, then pulls`() = runTest {
        store.queue(update("a", "first"))
        store.queue(update("b", "second"))
        remote.serverChores = listOf(dto("a", "2026-10-08T10:00:00+00:00"))

        assertTrue(engine.sync(household).isSuccess)
        assertEquals(listOf("a", "b"), remote.pushed.map { (it as PendingOp.UpdateChore).choreId })
        assertTrue(store.ops.value.isEmpty())
        assertEquals("a", store.chores.getValue("a").id)
        assertEquals("2026-10-08T10:00:00+00:00", store.cursors[EntityKeys.CHORES_STREAM])
    }

    @Test
    fun `offline keeps everything queued, in order, and schedules a retry`() = runTest {
        store.queue(update("a", "first"))
        store.queue(update("b", "second"))
        remote.failures["a"] = ArrayDeque(listOf(AppError.Network))

        assertTrue(engine.sync(household).isFailure)
        assertEquals("nothing after the failed op is sent", emptyList<PendingOp>(), remote.pushed)
        assertEquals(2, store.ops.value.size)
        assertEquals(1, retries)

        assertTrue(engine.sync(household).isSuccess) // back online
        assertEquals(listOf("a", "b"), remote.pushed.map { (it as PendingOp.UpdateChore).choreId })
    }

    @Test
    fun `a rejected change is dropped, rolled back and reported, and the rest still sync`() = runTest {
        store.queue(update("a", "not allowed"))
        store.queue(update("b", "fine"))
        store.cursors[EntityKeys.CHORES_STREAM] = "2026-10-08T10:00:00+00:00"
        remote.failures["a"] = ArrayDeque(listOf(AppError.PermissionDenied))

        assertTrue(engine.sync(household).isSuccess)
        assertEquals(listOf("b"), remote.pushed.map { (it as PendingOp.UpdateChore).choreId })
        assertEquals(listOf(EntityKeys.chore("a")), store.discarded)
        assertEquals("cursor reset forces a full re-download", null, remote.pullCursors.single())
        assertEquals(listOf(AppError.PermissionDenied), engine.status.value.problems.map { it.error })
        assertEquals(0, retries)

        engine.dismissProblem(engine.status.value.problems.single().id)
        assertTrue(engine.status.value.problems.isEmpty())
    }

    @Test
    fun `unrecognised errors are retried a few times, then treated as rejections`() = runTest {
        store.queue(update("a", "x"))
        remote.failures["a"] = ArrayDeque(List(SyncEngine.MAX_UNKNOWN_ATTEMPTS) { AppError.Unknown() })

        repeat(SyncEngine.MAX_UNKNOWN_ATTEMPTS - 1) { assertTrue(engine.sync(household).isFailure) }
        assertEquals(1, store.ops.value.size)

        assertTrue(engine.sync(household).isSuccess)
        assertTrue("dropped so the queue can't jam", store.ops.value.isEmpty())
        assertEquals(1, engine.status.value.problems.size)
    }

    @Test
    fun `pulled rows don't overwrite changes still waiting to be pushed`() = runTest {
        store.chores["a"] = dto("a", "2026-10-08T09:00:00+00:00", name = "local edit")
        store.queue(update("a", "local edit"))
        remote.failures["a"] = ArrayDeque(listOf(AppError.Network)) // push fails, pull isn't reached
        engine.sync(household)

        // Simulate a pull while the op is still queued.
        store.applyChores(household, listOf(dto("a", "2026-10-08T10:00:00+00:00", name = "server")), store.pendingKeys(household))
        assertEquals("local edit", store.chores.getValue("a").name)
    }

    @Test
    fun `pending count is reported in status`() = runTest {
        store.queue(update("a", "x"))
        assertEquals(1, engine.status.value.pending)
        online.value = false
        assertEquals(false, engine.status.value.online)
    }
}
