package app.betterhabits.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.betterhabits.ui.allocation.AllocationReviewScreen
import app.betterhabits.ui.allocation.BalanceScreen
import app.betterhabits.ui.allocation.PreferencesScreen
import app.betterhabits.ui.chores.ChoreDetailScreen
import app.betterhabits.ui.chores.ChoreEditorScreen
import app.betterhabits.ui.chores.ChoresScreen
import app.betterhabits.ui.chores.HistoryScreen
import app.betterhabits.ui.habits.HabitsScreen
import app.betterhabits.ui.household.HouseholdScreen
import app.betterhabits.ui.household.HouseholdSettingsScreen
import app.betterhabits.ui.household.HouseholdSetupScreen
import app.betterhabits.ui.household.MemberDetailScreen
import app.betterhabits.ui.navigation.AllocationReviewRoute
import app.betterhabits.ui.navigation.BalanceRoute
import app.betterhabits.ui.navigation.PreferencesRoute
import app.betterhabits.ui.navigation.ChoreDetailRoute
import app.betterhabits.ui.navigation.ChoreEditorRoute
import app.betterhabits.ui.navigation.ChoreHistoryRoute
import app.betterhabits.ui.navigation.ChoresRoute
import app.betterhabits.ui.navigation.HabitsRoute
import app.betterhabits.ui.navigation.HouseholdRoute
import app.betterhabits.ui.navigation.HouseholdSettingsRoute
import app.betterhabits.ui.navigation.HouseholdSetupRoute
import app.betterhabits.ui.navigation.MemberDetailRoute
import app.betterhabits.ui.navigation.ProfileRoute
import app.betterhabits.ui.navigation.TemplateEditorRoute
import app.betterhabits.ui.navigation.TemplatesRoute
import app.betterhabits.ui.navigation.TodayRoute
import app.betterhabits.ui.templates.TemplateEditorScreen
import app.betterhabits.ui.templates.TemplatesScreen
import app.betterhabits.ui.navigation.TopLevelDestination
import app.betterhabits.ui.profile.ProfileScreen
import app.betterhabits.ui.today.TodayScreen

/** Main signed-in shell: bottom navigation between top-level tabs plus nested screens. */
@Composable
fun BetterHabitsApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = currentDestination == null ||
        TopLevelDestination.entries.any { dest -> currentDestination.hierarchy.any { it.hasRoute(dest.routeClass) } }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    // The label below already names the item for screen readers.
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TodayRoute,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable<TodayRoute> {
                TodayScreen(
                    onAddChore = { navController.navigate(ChoreEditorRoute()) },
                    onOpenChore = { navController.navigate(ChoreDetailRoute(it)) },
                )
            }
            composable<ChoresRoute> {
                ChoresScreen(
                    onAddChore = { navController.navigate(ChoreEditorRoute()) },
                    onOpenChore = { navController.navigate(ChoreDetailRoute(it)) },
                    onOpenHistory = { navController.navigate(ChoreHistoryRoute) },
                    onSuggestAssignments = { navController.navigate(AllocationReviewRoute) },
                    onOpenTemplates = { navController.navigate(TemplatesRoute) },
                )
            }
            composable<HabitsRoute> { HabitsScreen() }
            composable<HouseholdRoute> {
                HouseholdScreen(
                    onOpenMember = { householdId, userId -> navController.navigate(MemberDetailRoute(householdId, userId)) },
                    onOpenSettings = { navController.navigate(HouseholdSettingsRoute(it)) },
                    onAddHousehold = { navController.navigate(HouseholdSetupRoute) },
                    onOpenBalance = { navController.navigate(BalanceRoute) },
                )
            }
            composable<ProfileRoute> { ProfileScreen(onOpenPreferences = { navController.navigate(PreferencesRoute()) }) }
            composable<HouseholdSetupRoute> { HouseholdSetupScreen(onClose = navController::popBackStack) }
            composable<MemberDetailRoute> {
                MemberDetailScreen(onBack = navController::popBackStack, onOpenPreferences = { navController.navigate(PreferencesRoute(it)) })
            }
            composable<HouseholdSettingsRoute> { HouseholdSettingsScreen(onBack = navController::popBackStack) }
            composable<ChoreDetailRoute> {
                ChoreDetailScreen(
                    onBack = navController::popBackStack,
                    onEdit = { navController.navigate(ChoreEditorRoute(it)) },
                    onSaveAsTemplate = { navController.navigate(TemplateEditorRoute(fromChoreId = it)) },
                )
            }
            composable<ChoreEditorRoute> { ChoreEditorScreen(onClose = navController::popBackStack) }
            composable<ChoreHistoryRoute> { HistoryScreen(onBack = navController::popBackStack) }
            composable<TemplatesRoute> {
                TemplatesScreen(
                    onBack = navController::popBackStack,
                    onUse = { navController.navigate(ChoreEditorRoute(templateId = it)) },
                    onEdit = { navController.navigate(TemplateEditorRoute(templateId = it)) },
                    onCopy = { navController.navigate(TemplateEditorRoute(copyOf = it)) },
                    onNew = { navController.navigate(TemplateEditorRoute()) },
                )
            }
            composable<TemplateEditorRoute> { TemplateEditorScreen(onClose = navController::popBackStack) }
            composable<PreferencesRoute> { PreferencesScreen(onBack = navController::popBackStack) }
            composable<BalanceRoute> {
                BalanceScreen(onBack = navController::popBackStack, onSuggest = { navController.navigate(AllocationReviewRoute) })
            }
            composable<AllocationReviewRoute> { AllocationReviewScreen(onClose = navController::popBackStack) }
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
