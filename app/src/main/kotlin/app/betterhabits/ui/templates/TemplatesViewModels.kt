package app.betterhabits.ui.templates

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterhabits.data.allocation.AllocationRepository
import app.betterhabits.data.allocation.PreferenceTarget
import app.betterhabits.data.chore.ChoreRepository
import app.betterhabits.data.household.HouseholdRepository
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.data.template.TemplateRepository
import app.betterhabits.domain.allocation.PreferenceLevel
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.error.appError
import app.betterhabits.domain.model.Chore
import app.betterhabits.domain.model.ChoreCategory
import app.betterhabits.domain.model.ChoreTemplate
import app.betterhabits.domain.model.Effort
import app.betterhabits.domain.model.HouseholdPermission
import app.betterhabits.domain.model.RepeatKind
import app.betterhabits.domain.model.TemplateScope
import app.betterhabits.ui.chores.HouseholdContext
import app.betterhabits.ui.chores.loadContext
import app.betterhabits.ui.chores.selectedHousehold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------

data class TemplatesUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    val templates: List<ChoreTemplate> = emptyList(),
    val query: String = "",
    /** My preference per template id. */
    val preferences: Map<String, PreferenceLevel> = emptyMap(),
    /** The template shown in the details sheet. */
    val selected: ChoreTemplate? = null,
    val confirmDelete: ChoreTemplate? = null,
    val message: AppError? = null,
) {
    val canUse get() = context?.details?.iCan(HouseholdPermission.CREATE_CHORES) == true
    val canManageHousehold get() = context?.details?.iCan(HouseholdPermission.MANAGE_TEMPLATES) == true

    fun canEdit(t: ChoreTemplate) = when (t.scope) {
        TemplateScope.BUILT_IN -> false
        TemplateScope.HOUSEHOLD -> canManageHousehold
        TemplateScope.PERSONAL -> t.ownerId == context?.myId
    }

    private val filtered: List<ChoreTemplate>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return templates
            return templates.filter { t ->
                t.name.contains(q, ignoreCase = true) || t.description.orEmpty().contains(q, ignoreCase = true) ||
                    t.checklist.any { it.contains(q, ignoreCase = true) }
            }
        }

    val household get() = filtered.filter { it.scope == TemplateScope.HOUSEHOLD }
    val personal get() = filtered.filter { it.scope == TemplateScope.PERSONAL }

    /** The starter library by category, in display order. */
    val builtIn: List<Pair<ChoreCategory, List<ChoreTemplate>>>
        get() = filtered.filter { it.scope == TemplateScope.BUILT_IN }.groupBy { it.category }.toList().sortedBy { it.first.ordinal }

    val nothingMatches get() = templates.isNotEmpty() && filtered.isEmpty()
}

class TemplatesViewModel(
    private val templates: TemplateRepository,
    private val allocation: AllocationRepository,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
) : ViewModel() {

    private val _state = MutableStateFlow(TemplatesUiState())
    val state: StateFlow<TemplatesUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val (householdId, userId) = session.selectedHousehold().first()
            val context = households.loadContext(householdId, userId).getOrElse { e ->
                _state.update { it.copy(loading = false, loadError = e.appError) }
                return@launch
            }
            val list = templates.templates(householdId).getOrElse { e ->
                _state.update { it.copy(loading = false, loadError = e.appError) }
                return@launch
            }
            // Preferences are optional here: if they can't load, the library still works.
            val prefs = allocation.inputs(householdId).getOrNull()?.preferencesOf(userId)?.byTemplate.orEmpty()
            _state.update { s ->
                s.copy(
                    loading = false,
                    loadError = null,
                    context = context,
                    templates = list,
                    preferences = prefs,
                    selected = s.selected?.let { sel -> list.firstOrNull { it.id == sel.id } },
                )
            }
        }
    }

    fun onQuery(value: String) = _state.update { it.copy(query = value.take(80)) }
    fun select(template: ChoreTemplate?) = _state.update { it.copy(selected = template) }
    fun messageShown() = _state.update { it.copy(message = null) }

    /** Saves my preference for chores made from this template (optimistic, reverted on failure). */
    fun setPreference(template: ChoreTemplate, level: PreferenceLevel?) {
        val s = _state.value
        val context = s.context ?: return
        val previous = s.preferences
        _state.update { it.copy(preferences = if (level == null) previous - template.id else previous + (template.id to level)) }
        viewModelScope.launch {
            allocation.setPreference(context.householdId, context.myId, PreferenceTarget.TemplateTarget(template.id), level)
                .onFailure { e -> _state.update { it.copy(preferences = previous, message = e.appError) } }
        }
    }

    fun requestDelete(template: ChoreTemplate?) = _state.update { it.copy(confirmDelete = template) }

    fun delete() {
        val template = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null, selected = null) }
        viewModelScope.launch {
            templates.delete(template)
                .onSuccess { _state.update { s -> s.copy(templates = s.templates.filterNot { it.id == template.id }) } }
                .onFailure { e -> _state.update { it.copy(message = e.appError) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

data class TemplateForm(
    val name: String = "",
    val description: String = "",
    val category: ChoreCategory = ChoreCategory.OTHER,
    val minutes: Int? = 15,
    val difficulty: Int = Chore.DEFAULT_DIFFICULTY,
    val points: Int = 0,
    val repeat: RepeatKind = RepeatKind.WEEKLY,
    val interval: Int = 1,
    val checklist: List<String> = emptyList(),
    val scope: TemplateScope = TemplateScope.PERSONAL,
) {
    val nameValid get() = name.isNotBlank() && name.trim().length <= Chore.MAX_NAME_LENGTH
    val minutesValid get() = minutes != null && minutes in 1..Chore.MAX_MINUTES
    val isValid get() = nameValid && minutesValid

    companion object {
        fun from(t: ChoreTemplate, scope: TemplateScope) = TemplateForm(
            name = t.name,
            description = t.description.orEmpty(),
            category = t.category,
            minutes = t.effort.minutes,
            difficulty = t.difficulty,
            points = t.points,
            repeat = t.repeatKind,
            interval = t.repeatInterval,
            checklist = t.checklist,
            scope = scope,
        )
    }
}

data class TemplateEditorUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val context: HouseholdContext? = null,
    /** Set when editing a saved template (its scope can't change). */
    val existing: ChoreTemplate? = null,
    val form: TemplateForm = TemplateForm(),
    val showValidation: Boolean = false,
    val saving: Boolean = false,
    val saveError: AppError? = null,
    val saved: Boolean = false,
) {
    val isNew get() = existing == null
    val canShareWithHousehold get() = context?.details?.iCan(HouseholdPermission.MANAGE_TEMPLATES) == true
}

class TemplateEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val templates: TemplateRepository,
    private val chores: ChoreRepository,
    private val households: HouseholdRepository,
    private val session: HouseholdSession,
) : ViewModel() {

    /** Route args (see TemplateEditorRoute): edit a template, save a chore as one, or copy one. */
    private val templateId: String? = savedStateHandle.get<String>("templateId")
    private val fromChoreId: String? = savedStateHandle.get<String>("fromChoreId")
    private val copyOf: String? = savedStateHandle.get<String>("copyOf")

    private val _state = MutableStateFlow(TemplateEditorUiState())
    val state: StateFlow<TemplateEditorUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val (householdId, userId) = session.selectedHousehold().first()
            val context = households.loadContext(householdId, userId).getOrElse { e ->
                _state.update { it.copy(loading = false, loadError = e.appError) }
                return@launch
            }
            val defaultScope = if (context.details.iCan(HouseholdPermission.MANAGE_TEMPLATES)) TemplateScope.HOUSEHOLD else TemplateScope.PERSONAL
            val needsLibrary = templateId != null || copyOf != null
            val library = if (needsLibrary) {
                templates.templates(householdId).getOrElse { e ->
                    _state.update { it.copy(loading = false, loadError = e.appError) }
                    return@launch
                }
            } else {
                emptyList()
            }
            val existing = templateId?.let { id -> library.firstOrNull { it.id == id && it.scope != TemplateScope.BUILT_IN } }
            val form = when {
                existing != null -> TemplateForm.from(existing, existing.scope)
                copyOf != null -> library.firstOrNull { it.id == copyOf }?.let { TemplateForm.from(it, defaultScope) }
                fromChoreId != null -> chores.observeChore(fromChoreId, context.zone).first()?.let { chore ->
                    TemplateForm.from(ChoreTemplate.fromChore(chore, "", TemplateScope.PERSONAL, null, userId), defaultScope)
                }
                else -> TemplateForm(scope = defaultScope)
            }
            if (form == null || (templateId != null && existing == null)) {
                _state.update { it.copy(loading = false, loadError = AppError.Unknown()) }
                return@launch
            }
            _state.update { it.copy(loading = false, loadError = null, context = context, existing = existing, form = form) }
        }
    }

    private fun edit(transform: (TemplateForm) -> TemplateForm) = _state.update { it.copy(form = transform(it.form), saveError = null) }

    fun onName(value: String) = edit { it.copy(name = value.take(Chore.MAX_NAME_LENGTH)) }
    fun onDescription(value: String) = edit { it.copy(description = value.take(1000)) }
    fun onCategory(category: ChoreCategory) = edit { it.copy(category = category) }
    fun onMinutes(value: Int?) = edit { it.copy(minutes = value) }
    fun onDifficulty(value: Int) = edit { it.copy(difficulty = value.coerceIn(1, 5)) }
    fun onRepeat(kind: RepeatKind) = edit { it.copy(repeat = kind, interval = 1) }
    fun onInterval(value: Int) = edit { it.copy(interval = value.coerceIn(1, 365)) }
    fun onAddStep(text: String) = edit { if (text.isBlank() || it.checklist.size >= ChoreTemplate.MAX_STEPS) it else it.copy(checklist = it.checklist + text.trim()) }
    fun onRemoveStep(index: Int) = edit { it.copy(checklist = it.checklist.filterIndexed { i, _ -> i != index }) }
    fun onMoveStep(index: Int, by: Int) = edit {
        val target = index + by
        if (target !in it.checklist.indices) it else it.copy(checklist = it.checklist.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun onScope(scope: TemplateScope) {
        val s = _state.value
        if (!s.isNew || scope == TemplateScope.BUILT_IN) return
        if (scope == TemplateScope.HOUSEHOLD && !s.canShareWithHousehold) return
        edit { it.copy(scope = scope) }
    }

    fun save() {
        val s = _state.value
        val context = s.context ?: return
        val form = s.form
        if (!form.isValid) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        val scope = s.existing?.scope ?: form.scope
        val template = ChoreTemplate(
            id = s.existing?.id.orEmpty(),
            scope = scope,
            name = form.name.trim(),
            description = form.description.trim().ifEmpty { null },
            category = form.category,
            effort = Effort(form.minutes!!),
            difficulty = form.difficulty,
            points = form.points,
            repeatKind = form.repeat,
            repeatInterval = form.interval,
            checklist = form.checklist,
            householdId = if (scope == TemplateScope.HOUSEHOLD) s.existing?.householdId ?: context.householdId else null,
            ownerId = if (scope == TemplateScope.PERSONAL) s.existing?.ownerId ?: context.myId else null,
        )
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            templates.save(template)
                .onSuccess { _state.update { it.copy(saving = false, saved = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, saveError = e.appError) } }
        }
    }
}
