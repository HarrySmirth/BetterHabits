package app.betterhabits.ui.today

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.betterhabits.R
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.TopLevelScaffold

// Phase 0 shell: no data source yet. Dashboard content arrives with chores (Phase 2) and habits (Phase 6).
@Composable
fun TodayScreen() {
    TopLevelScaffold(title = stringResource(R.string.nav_today)) { padding ->
        EmptyState(
            icon = Icons.Outlined.WbSunny,
            title = stringResource(R.string.today_empty_title),
            body = stringResource(R.string.today_empty_body),
            modifier = Modifier.padding(padding),
        )
    }
}
