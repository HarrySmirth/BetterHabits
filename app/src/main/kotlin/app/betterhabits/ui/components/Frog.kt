package app.betterhabits.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.betterhabits.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pip, the app's frog mascot. */
enum class FrogMood(@DrawableRes val drawable: Int) {
    HAPPY(R.drawable.frog_happy),
    CELEBRATE(R.drawable.frog_celebrate),
    SLEEPY(R.drawable.frog_sleepy),
    PUZZLED(R.drawable.frog_puzzled),
}

/**
 * Pip with a gentle idle bob. Tapping Pip makes him hop and ribbit. Purely decorative (always paired
 * with text), so it's hidden from screen readers.
 */
@Composable
fun Frog(mood: FrogMood, modifier: Modifier = Modifier, width: Dp = 168.dp) {
    val bob by rememberInfiniteTransition(label = "frog").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val density = LocalDensity.current
    val lift = with(density) { 3.dp.toPx() }
    val jumpHeight = with(density) { (width / 6).toPx() }
    val hop = remember { Animatable(0f) }
    var ribbits by remember { mutableIntStateOf(0) }
    var showRibbit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ribbits) {
        if (ribbits == 0) return@LaunchedEffect
        showRibbit = true
        delay(1200)
        showRibbit = false
    }

    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.TopEnd) {
        Image(
            painter = painterResource(mood.drawable),
            contentDescription = null,
            modifier = Modifier
                .size(width, width * 5 / 6)
                .graphicsLayer { translationY = -lift * bob - jumpHeight * hop.value }
                .pointerInput(Unit) {
                    detectTapGestures {
                        ribbits++
                        scope.launch {
                            hop.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                            hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                },
        )
        AnimatedVisibility(visible = showRibbit, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
            Surface(
                shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shadowElevation = 1.dp,
            ) {
                Text(stringResource(R.string.frog_ribbit), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** Full-screen loading: Pip hopping on the spot. Announced as "Loading". */
@Composable
fun HoppingFrog(modifier: Modifier = Modifier, width: Dp = 96.dp) {
    val jump by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 900
                0f at 0
                1f at 300 using FastOutSlowInEasing
                0f at 600
                0f at 900
            },
        ),
        label = "hop",
    )
    val height = with(LocalDensity.current) { (width / 4).toPx() }
    val loading = stringResource(R.string.loading)
    Image(
        painter = painterResource(FrogMood.HAPPY.drawable),
        contentDescription = null,
        modifier = modifier
            .semantics {
                contentDescription = loading
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
            .size(width, width * 5 / 6)
            .graphicsLayer { translationY = -height * jump },
    )
}
