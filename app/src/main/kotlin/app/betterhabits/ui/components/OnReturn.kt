package app.betterhabits.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Runs [action] when the screen resumes after having been left (e.g. back from an editor), not on first show. */
@Composable
fun OnReturn(action: () -> Unit) {
    val seen = remember { BooleanArray(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (seen[0]) action() else seen[0] = true
    }
}
