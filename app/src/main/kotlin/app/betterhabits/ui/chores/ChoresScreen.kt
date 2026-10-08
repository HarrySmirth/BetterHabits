package app.betterhabits.ui.chores

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.betterhabits.R
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.TopLevelScaffold

// Phase 0 shell: chore list arrives in Phase 2.
@Composable
fun ChoresScreen() {
    TopLevelScaffold(title = stringResource(R.string.nav_chores)) { padding ->
        EmptyState(
            icon = Icons.Outlined.CleaningServices,
            title = stringResource(R.string.chores_empty_title),
            body = stringResource(R.string.chores_empty_body),
            modifier = Modifier.padding(padding),
        )
    }
}
