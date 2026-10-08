package app.betterhabits.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Today
import androidx.compose.ui.graphics.vector.ImageVector
import app.betterhabits.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object TodayRoute
@Serializable data object ChoresRoute
@Serializable data object HabitsRoute
@Serializable data object HouseholdRoute
@Serializable data object ProfileRoute

// Nested destinations (no bottom bar)
@Serializable data object HouseholdSetupRoute
@Serializable data class MemberDetailRoute(val householdId: String, val userId: String)
@Serializable data class HouseholdSettingsRoute(val householdId: String)
@Serializable data class ChoreDetailRoute(val choreId: String)
/** choreId null = create a new chore. */
@Serializable data class ChoreEditorRoute(val choreId: String? = null, val templateId: String? = null)
@Serializable data object ChoreHistoryRoute
@Serializable data object TemplatesRoute

/** Edit [templateId], save chore [fromChoreId] as a new template, or copy template [copyOf]; none = blank. */
@Serializable data class TemplateEditorRoute(val templateId: String? = null, val fromChoreId: String? = null, val copyOf: String? = null)
/** memberId null = the signed-in user. */
@Serializable data class PreferencesRoute(val memberId: String? = null)
@Serializable data object BalanceRoute
@Serializable data object AllocationReviewRoute

enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    TODAY(TodayRoute, TodayRoute::class, R.string.nav_today, Icons.Filled.Today, Icons.Outlined.Today),
    CHORES(ChoresRoute, ChoresRoute::class, R.string.nav_chores, Icons.Filled.CleaningServices, Icons.Outlined.CleaningServices),
    HABITS(HabitsRoute, HabitsRoute::class, R.string.nav_habits, Icons.Filled.SelfImprovement, Icons.Outlined.SelfImprovement),
    HOUSEHOLD(HouseholdRoute, HouseholdRoute::class, R.string.nav_household, Icons.Filled.Home, Icons.Outlined.Home),
    PROFILE(ProfileRoute, ProfileRoute::class, R.string.nav_profile, Icons.Filled.Person, Icons.Outlined.Person),
}
