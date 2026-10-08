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
import app.betterhabits.ui.chores.ChoresScreen
import app.betterhabits.ui.habits.HabitsScreen
import app.betterhabits.ui.household.HouseholdScreen
import app.betterhabits.ui.navigation.ChoresRoute
import app.betterhabits.ui.navigation.HabitsRoute
import app.betterhabits.ui.navigation.HouseholdRoute
import app.betterhabits.ui.navigation.ProfileRoute
import app.betterhabits.ui.navigation.TodayRoute
import app.betterhabits.ui.navigation.TopLevelDestination
import app.betterhabits.ui.profile.ProfileScreen
import app.betterhabits.ui.today.TodayScreen

@Composable
fun BetterHabitsApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
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
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TodayRoute,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable<TodayRoute> { TodayScreen() }
            composable<ChoresRoute> { ChoresScreen() }
            composable<HabitsRoute> { HabitsScreen() }
            composable<HouseholdRoute> { HouseholdScreen() }
            composable<ProfileRoute> { ProfileScreen() }
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
