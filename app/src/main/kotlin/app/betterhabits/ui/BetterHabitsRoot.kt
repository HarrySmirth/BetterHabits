package app.betterhabits.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.betterhabits.R
import app.betterhabits.data.household.SessionState
import app.betterhabits.ui.auth.AuthNavHost
import app.betterhabits.ui.components.EmptyState
import app.betterhabits.ui.components.FrogMood
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.household.HouseholdSetupScreen

/** Chooses the top-level flow from the session: setup problems, signed-out, onboarding or the app. */
@Composable
fun BetterHabitsRoot(session: SessionState, onRetry: () -> Unit, onSignOut: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (session) {
            SessionState.Loading -> LoadingState()
            SessionState.NotConfigured -> EmptyState(
                frog = FrogMood.PUZZLED,
                title = stringResource(R.string.not_configured_title),
                body = stringResource(R.string.not_configured_body),
            )
            SessionState.SignedOut -> AuthNavHost()
            is SessionState.Failed -> Box(Modifier.fillMaxSize()) {
                ErrorState(
                    error = session.error,
                    onRetry = onRetry,
                    extraAction = { TextButton(onClick = onSignOut) { Text(stringResource(R.string.action_sign_out)) } },
                )
            }
            is SessionState.Ready ->
                if (session.households.isEmpty()) HouseholdSetupScreen(onClose = null) else BetterHabitsApp()
        }
    }
}
