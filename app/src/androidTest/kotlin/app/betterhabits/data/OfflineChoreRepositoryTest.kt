package app.betterhabits.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.betterhabits.data.chore.ChoreDto
import app.betterhabits.data.chore.OfflineChoreRepository
import app.betterhabits.data.local.AppDatabase
import app.betterhabits.data.sync.EntityKeys
import app.betterhabits.data.sync.PendingOp
import app.betterhabits.data.sync.RoomSyncStore
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.OccurrenceStatus
import app.betterhabits.domain.schedule.OccurrenceKey
import app.betterhabits.domain.schedule.Recurrence
import app.betterhabits.domain.schedule.Schedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The real Room schema, repository writes and outbox, on an in-memory database. */
@RunWith(AndroidJUnit4::class)
class OfflineChoreRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OfflineChoreRepository
    private lateinit var store: RoomSyncStore
    private val synced = mutableListOf<String>()
    private var me = "harry"
    private val zone = ZoneId.of("Europe/London")
    private val day = LocalDate.parse("2026-10-08")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = OfflineChoreRepository(db, currentUserId = { me }, requestSync = { synced += it })
        store = RoomSyncStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun chore(name: String = "Dishes") = Chore(
        id = "c1", householdId = "h1", name = name, effort = Effort(10),
        schedule = Schedule(Recurrence.Daily(), day, zone = zone), assigneeId = "harry",
    )

    @Test
    fun createdChoreIsVisibleAtOnceAndQueuedWithoutServerColumns() = runTest {
        repo.createChore(chore())

        assertEquals(listOf("Dishes"), repo.observeChores("h1", zone).first().map { it.name })
        val op = store.pendingOps("h1").single().op as PendingOp.CreateChore
        assertEquals("c1", op.choreId)
        assertFalse(op.row.containsKey("created_by"))
        assertFalse(op.row.containsKey("updated_at"))
        assertEquals(listOf("h1"), synced)
    }

    @Test
    fun checkedStepsAreStoredAndQueuedWithoutTouchingStatus() = runTest {
        repo.createChore(chore().copy(checklist = listOf("Wash", "Dry", "Put away")))
        val key = OccurrenceKey(day, null)
        repo.complete("h1", "c1", key, Instant.parse("2026-10-08T09:00:00Z"))
        repo.setCheckedSteps("h1", "c1", key, setOf(2, 0))

        val record = repo.observeRecords("h1", day, day).first().single()
        assertEquals(setOf(0, 2), record.checkedSteps)
        assertEquals(OccurrenceStatus.COMPLETED, record.status)
        val op = store.pendingOps("h1").last().op as PendingOp.UpsertOccurrence
        assertEquals("[0,2]", op.row.getValue("checked_steps").toString())
        assertFalse(op.row.containsKey("status"))
    }

    @Test
    fun editsQueueOnlyTheChangedFields() = runTest {
        val original = chore()
        repo.createChore(original)
        repo.updateChore(original, original.copy(name = "Wash up", assigneeId = "sarah"))

        val update = store.pendingOps("h1").last().op as PendingOp.UpdateChore
        assertEquals(setOf("name", "assignee_id"), update.fields.keys)
        assertEquals("Wash up", update.fields.getValue("name").jsonPrimitive.content)
        assertEquals("Wash up", repo.observeChore("c1", zone).first()?.name)

        repo.updateChore(original.copy(name = "Wash up", assigneeId = "sarah"), original.copy(name = "Wash up", assigneeId = "sarah"))
        assertEquals("no-op edits queue nothing", 2, store.pendingOps("h1").size)
    }

    @Test
    fun firstCompletionWinsLocallyToo() = runTest {
        repo.createChore(chore())
        val key = OccurrenceKey(day)
        repo.complete("h1", "c1", key, Instant.parse("2026-10-08T09:00:00Z"))
        me = "sarah"
        repo.complete("h1", "c1", key, Instant.parse("2026-10-08T10:00:00Z"))

        val record = repo.observeRecords("h1", day, day).first().single()
        assertEquals(OccurrenceStatus.COMPLETED, record.status)
        assertEquals("harry", record.completedBy)

        repo.reset("h1", "c1", key)
        val reset = repo.observeRecords("h1", day, day).first().single()
        assertEquals(OccurrenceStatus.PENDING, reset.status)
        assertNull(reset.completedBy)
    }

    @Test
    fun deletedChoresDisappearButStayQueued() = runTest {
        repo.createChore(chore())
        repo.deleteChore("h1", "c1")
        assertTrue(repo.observeChores("h1", zone).first().isEmpty())
        val delete = store.pendingOps("h1").last().op as PendingOp.UpdateChore
        assertTrue(delete.fields.containsKey("deleted_at"))
    }

    @Test
    fun pulledRowsSkipPendingEntitiesAndDiscardRemovesLocalCopies() = runTest {
        repo.createChore(chore(name = "Local"))
        val server = ChoreDto(
            id = "c1", householdId = "h1", name = "Server", estimatedMinutes = 10,
            recurrenceType = "DAILY", startDate = day.toString(), updatedAt = "2026-10-08T10:00:00+00:00",
        )
        store.applyChores("h1", listOf(server), store.pendingKeys("h1"))
        assertEquals("Local", repo.observeChore("c1", zone).first()?.name)

        store.discardLocal(EntityKeys.chore("c1"))
        assertNull(repo.observeChore("c1", zone).first())

        store.applyChores("h1", listOf(server), emptySet())
        assertEquals("Server", repo.observeChore("c1", zone).first()?.name)
    }
}
