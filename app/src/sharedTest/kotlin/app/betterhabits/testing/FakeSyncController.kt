package app.betterhabits.testing

import app.betterhabits.data.sync.SyncController
import app.betterhabits.data.sync.SyncProblem
import app.betterhabits.data.sync.SyncStatus
import app.betterhabits.domain.error.AppError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeSyncController : SyncController {
    override val status = MutableStateFlow(SyncStatus())
    val refreshed = mutableListOf<String>()

    override suspend fun refresh(householdId: String): Result<Unit> {
        refreshed += householdId
        return Result.success(Unit)
    }

    override fun dismissProblem(id: Long) = status.update { s -> s.copy(problems = s.problems.filterNot { it.id == id }) }

    fun reportProblem(error: AppError) = status.update { s -> s.copy(problems = s.problems + SyncProblem(s.problems.size + 1L, error)) }
}
