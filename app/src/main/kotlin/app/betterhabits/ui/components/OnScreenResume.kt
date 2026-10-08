package app.betterhabits.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Runs [action] when the screen becomes visible again after being covered (e.g. returning from a
 * screen that changed what this one shows), but not the first time it appears.
 */
@Composable
fun OnScreenResume(action: () -> Unit) {
    val seen = remember { BooleanArray(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (seen[0]) action() else seen[0] = true
    }
}
