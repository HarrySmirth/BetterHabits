package app.betterhabits.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A checkbox drawn as a lily pad: a pale pad when unticked, a filled green pad with a tick when
 * ticked. Behaves exactly like a Material checkbox for touch and accessibility (48dp target, Checkbox role).
 */
@Composable
fun LilyPadCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 26.dp,
) {
    val colors = MaterialTheme.colorScheme
    val padColor by animateColorAsState(
        when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            checked -> colors.primary
            else -> colors.outline
        },
        label = "pad",
    )
    val tick by animateFloatAsState(if (checked) 1f else 0f, label = "tick")
    val tickColor = colors.onPrimary
    val padFill = if (enabled) colors.secondaryContainer else colors.surfaceVariant
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Box(modifier.then(toggle).size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            // The pad: a circle with a notch cut towards the top right, like a lily pad.
            if (tick > 0f) {
                drawArc(padColor, startAngle = -40f, sweepAngle = 330f, useCenter = true, topLeft = Offset(inset, inset), size = arcSize)
            }
            if (tick < 1f) {
                drawArc(padFill, startAngle = -40f, sweepAngle = 330f, useCenter = true, topLeft = Offset(inset, inset), size = arcSize, alpha = 1f - tick)
                drawArc(
                    padColor, startAngle = -40f, sweepAngle = 330f, useCenter = true, topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, join = StrokeJoin.Round),
                    alpha = 1f - tick,
                )
            }
            if (tick > 0f) {
                val w = this.size.width
                val path = Path().apply {
                    moveTo(w * 0.28f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.68f)
                    lineTo(w * 0.72f, w * 0.38f)
                }
                drawPath(path, tickColor, alpha = tick, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
