package app.betterhabits.ui.habits

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.betterhabits.R
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.TopLevelScaffold

// Phase 0 shell: habits arrive in Phase 6.
@Composable
fun HabitsScreen() {
    TopLevelScaffold(title = stringResource(R.string.nav_habits)) { padding ->
        EmptyState(
            frog = FrogMood.HAPPY,
            title = stringResource(R.string.habits_empty_title),
            body = stringResource(R.string.habits_empty_body),
            modifier = Modifier.padding(padding),
        )
    }
}
