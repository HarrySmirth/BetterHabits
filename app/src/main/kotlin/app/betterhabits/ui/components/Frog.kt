package app.betterhabits.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.betterhabits.R

/** Pip, the app's frog mascot. Used sparingly: empty states, sign-in and celebrations. */
enum class FrogMood(@DrawableRes val drawable: Int) {
    HAPPY(R.drawable.frog_happy),
    CELEBRATE(R.drawable.frog_celebrate),
    SLEEPY(R.drawable.frog_sleepy),
    PUZZLED(R.drawable.frog_puzzled),
}

/** Decorative frog illustration with a gentle idle bob. Always paired with text, so it has no description. */
@Composable
fun Frog(mood: FrogMood, modifier: Modifier = Modifier, width: Dp = 168.dp) {
    val bob by rememberInfiniteTransition(label = "frog").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val lift = with(LocalDensity.current) { 3.dp.toPx() }
    Image(
        painter = painterResource(mood.drawable),
        contentDescription = null,
        modifier = modifier
            .size(width, width * 5 / 6)
            .graphicsLayer { translationY = -lift * bob },
    )
}
