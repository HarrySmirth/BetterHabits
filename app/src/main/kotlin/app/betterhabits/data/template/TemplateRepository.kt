package app.betterhabits.data.template

import app.betterhabits.data.local.CacheDao
import app.betterhabits.data.local.CacheEntity
import app.betterhabits.data.remote.backendCall
import app.betterhabits.data.remote.requireSession
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.AppException
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.ChoreTemplate
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.RepeatKind
import app.betterhabits.domain.model.TemplateScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Chore templates: the built-in library (bundled data, always available) plus household and
 * personal templates from the server. Reads fall back to the last copy offline; edits need a connection.
 */
interface TemplateRepository {
    /** Built-in, household and the current user's personal templates. */
    suspend fun templates(householdId: String): Result<List<ChoreTemplate>>

    /** Creates (blank [ChoreTemplate.id]) or updates a household/personal template. */
    suspend fun save(template: ChoreTemplate): Result<ChoreTemplate>

    suspend fun delete(template: ChoreTemplate): Result<Unit>
}

/** The bundled starter library, parsed from `assets/templates/builtin.json`. */
object BuiltInTemplates {
    @Serializable
    private data class Library(val version: Int, val categories: List<Group>)

    @Serializable
    private data class Group(val category: String, val templates: List<Item>)

    @Serializable
    private data class Item(
        val key: String,
        val name: String,
        val description: String? = null,
        val minutes: Int,
        val difficulty: Int = 3,
        val points: Int = 0,
        val repeat: String = "WEEKLY",
        val interval: Int = 1,
        val steps: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Ids look like "builtin.kitchen.clean_oven", so preferences and chores can refer to them. */
    fun parse(source: String): List<ChoreTemplate> =
        json.decodeFromString(Library.serializer(), source).categories.flatMap { group ->
            val category = ChoreCategory.valueOf(group.category)
            group.templates.map { item ->
                ChoreTemplate(
                    id = "${ChoreTemplate.BUILT_IN_PREFIX}${group.category.lowercase()}.${item.key}",
                    scope = TemplateScope.BUILT_IN,
                    name = item.name,
                    description = item.description,
                    category = category,
                    effort = Effort(item.minutes),
                    difficulty = item.difficulty,
                    points = item.points,
                    repeatKind = RepeatKind.valueOf(item.repeat),
                    repeatInterval = item.interval,
                    checklist = item.steps,
                )
            }
        }
}

@Serializable
internal data class TemplateDto(
    val id: String? = null,
    @SerialName("household_id") val householdId: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    val name: String,
    val description: String? = null,
    val category: String = "OTHER",
    @SerialName("estimated_minutes") val estimatedMinutes: Int,
    val difficulty: Int = 3,
    val points: Int = 0,
    @SerialName("repeat_kind") val repeatKind: String = "WEEKLY",
    @SerialName("repeat_interval") val repeatInterval: Int = 1,
    val checklist: List<String> = emptyList(),
) {
    fun toDomain(): ChoreTemplate? = runCatching {
        ChoreTemplate(
            id = requireNotNull(id),
            scope = if (householdId != null) TemplateScope.HOUSEHOLD else TemplateScope.PERSONAL,
            name = name,
            description = description,
            category = ChoreCategory.entries.firstOrNull { it.name == category } ?: ChoreCategory.OTHER,
            effort = Effort(estimatedMinutes),
            difficulty = difficulty,
            points = points,
            repeatKind = RepeatKind.entries.firstOrNull { it.name == repeatKind } ?: RepeatKind.WEEKLY,
            repeatInterval = repeatInterval,
            checklist = checklist,
            householdId = householdId,
            ownerId = ownerId,
        )
    }.getOrNull()

    companion object {
        fun from(t: ChoreTemplate) = TemplateDto(
            id = t.id.ifBlank { null },
            householdId = t.householdId,
            ownerId = t.ownerId,
            name = t.name.trim(),
            description = t.description?.trim()?.ifEmpty { null },
            category = t.category.name,
            estimatedMinutes = t.effort.minutes,
            difficulty = t.difficulty,
            points = t.points,
            repeatKind = t.repeatKind.name,
            repeatInterval = t.repeatInterval,
            checklist = t.checklist.map(String::trim).filter(String::isNotEmpty),
        )
    }
}

class SupabaseTemplateRepository(
    private val client: SupabaseClient?,
    private val cache: CacheDao,
    private val builtIn: () -> List<ChoreTemplate>,
) : TemplateRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(TemplateDto.serializer())

    private suspend fun db(): SupabaseClient = (client ?: throw AppException(AppError.NotConfigured)).requireSession()

    override suspend fun templates(householdId: String): Result<List<ChoreTemplate>> {
        val key = "templates:$householdId"
        // RLS returns this household's templates plus the caller's personal ones; other households'
        // templates are filtered out here.
        val remote = backendCall {
            db().from("chore_templates").select().decodeList<TemplateDto>()
                .filter { it.householdId == null || it.householdId == householdId }
        }
        remote.onSuccess { cache.put(CacheEntity(key, json.encodeToString(listSerializer, it))) }
        val rows = remote.getOrElse { e ->
            val error = e.appError
            if (error != AppError.Network && error != AppError.SessionExpired && error != AppError.NotConfigured) return Result.failure(e)
            cache.get(key)?.let { json.decodeFromString(listSerializer, it) }.orEmpty()
        }
        val saved = rows.mapNotNull(TemplateDto::toDomain).sortedBy { it.name.lowercase() }
        return Result.success(saved + builtIn())
    }

    override suspend fun save(template: ChoreTemplate): Result<ChoreTemplate> = backendCall {
        require(template.scope != TemplateScope.BUILT_IN) { "built-in templates are read-only" }
        val dto = TemplateDto.from(template)
        val table = db().from("chore_templates")
        val stored = if (dto.id == null) {
            table.insert(dto) { select() }.decodeSingle<TemplateDto>()
        } else {
            table.update(updateFields(dto)) {
                select()
                filter { eq("id", dto.id) }
            }.decodeSingle<TemplateDto>()
        }
        requireNotNull(stored.toDomain())
    }

    override suspend fun delete(template: ChoreTemplate): Result<Unit> = backendCall {
        db().from("chore_templates").delete { filter { eq("id", template.id) } }
        Unit
    }

    /** The editable columns only (scope columns are immutable and rejected by the server if sent). */
    private fun updateFields(dto: TemplateDto) = UpdateDto(
        dto.name, dto.description, dto.category, dto.estimatedMinutes, dto.difficulty, dto.points,
        dto.repeatKind, dto.repeatInterval, dto.checklist,
    )

    @Serializable
    private data class UpdateDto(
        val name: String,
        val description: String?,
        val category: String,
        @SerialName("estimated_minutes") val estimatedMinutes: Int,
        val difficulty: Int,
        val points: Int,
        @SerialName("repeat_kind") val repeatKind: String,
        @SerialName("repeat_interval") val repeatInterval: Int,
        val checklist: List<String>,
    )
}
