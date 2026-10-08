package app.betterhabits.ui.household

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.betterhabits.R
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.TopLevelScaffold

// Phase 0 shell: households arrive in Phase 1.
@Composable
fun HouseholdScreen() {
    TopLevelScaffold(title = stringResource(R.string.nav_household)) { padding ->
        EmptyState(
            icon = Icons.Outlined.Home,
            title = stringResource(R.string.household_empty_title),
            body = stringResource(R.string.household_empty_body),
            modifier = Modifier.padding(padding),
        )
    }
}
