package app.betterhabits

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.BetterHabitsRoot
import app.betterhabits.ui.theme.BetterHabitsTheme
import app.betterhabits.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { AppViewModelFactory.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            // Preferences load from disk in milliseconds; render nothing (window background shows)
            // until then so the app never flashes the wrong theme.
            val ready = state as? MainUiState.Ready ?: return@setContent
            BetterHabitsTheme(
                darkTheme = isDarkTheme(ready.preferences.themeMode),
                dynamicColor = ready.preferences.useDynamicColor,
            ) {
                BetterHabitsRoot(session = ready.session, onRetry = viewModel::retry, onSignOut = viewModel::signOut)
            }
        }
    }
}
