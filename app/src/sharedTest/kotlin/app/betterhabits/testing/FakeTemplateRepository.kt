package app.betterhabits.testing

import app.betterhabits.data.template.TemplateRepository
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.ChoreTemplate
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.RepeatKind
import app.betterhabits.domain.model.TemplateScope
import java.util.UUID

class FakeTemplateRepository : TemplateRepository {
    val builtIn = mutableListOf(
        ChoreTemplate(
            id = "builtin.bathroom.deep_clean", scope = TemplateScope.BUILT_IN, name = "Bathroom deep clean",
            category = ChoreCategory.BATHROOM, effort = Effort(45), difficulty = 4, repeatKind = RepeatKind.WEEKLY,
            checklist = listOf("Clean toilet", "Clean sink", "Clean shower", "Clean mirror", "Mop floor"),
        ),
        ChoreTemplate(
            id = "builtin.household.take_bins_out", scope = TemplateScope.BUILT_IN, name = "Take bins out",
            category = ChoreCategory.HOUSEHOLD, effort = Effort(10), repeatKind = RepeatKind.WEEKLY,
        ),
    )
    val saved = mutableMapOf<String, ChoreTemplate>()
    var nextError: AppError? = null

    private fun fail(): AppException? = nextError?.let { nextError = null; AppException(it) }

    override suspend fun templates(householdId: String): Result<List<ChoreTemplate>> {
        fail()?.let { return Result.failure(it) }
        val mine = saved.values.filter { it.householdId == null || it.householdId == householdId }.sortedBy { it.name }
        return Result.success(mine + builtIn)
    }

    override suspend fun save(template: ChoreTemplate): Result<ChoreTemplate> {
        fail()?.let { return Result.failure(it) }
        val stored = if (template.id.isBlank()) template.copy(id = UUID.randomUUID().toString()) else template
        saved[stored.id] = stored
        return Result.success(stored)
    }

    override suspend fun delete(template: ChoreTemplate): Result<Unit> {
        fail()?.let { return Result.failure(it) }
        saved.remove(template.id)
        return Result.success(Unit)
    }
}
