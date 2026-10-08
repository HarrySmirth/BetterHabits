package app.betterhabits.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.betterhabits.R

// Nunito (SIL OFL, see docs/licenses): rounded and friendly, bundled so it works offline.
private fun nunito(weight: Int) = Font(
    R.font.nunito,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

private fun TextStyle.nunito(weight: FontWeight? = null) = copy(fontFamily = Nunito, fontWeight = weight ?: fontWeight)

internal val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.nunito(FontWeight.ExtraBold),
        displayMedium = displayMedium.nunito(FontWeight.ExtraBold),
        displaySmall = displaySmall.nunito(FontWeight.Bold),
        headlineLarge = headlineLarge.nunito(FontWeight.Bold),
        headlineMedium = headlineMedium.nunito(FontWeight.Bold),
        headlineSmall = headlineSmall.nunito(FontWeight.Bold),
        titleLarge = titleLarge.nunito(FontWeight.Bold),
        titleMedium = titleMedium.nunito(FontWeight.Bold),
        titleSmall = titleSmall.nunito(FontWeight.Bold),
        bodyLarge = bodyLarge.nunito(),
        bodyMedium = bodyMedium.nunito(),
        bodySmall = bodySmall.nunito(),
        labelLarge = labelLarge.nunito(FontWeight.Bold),
        labelMedium = labelMedium.nunito(FontWeight.SemiBold),
        labelSmall = labelSmall.nunito(FontWeight.SemiBold),
    )
}

// Softer, lily-pad-round corners.
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)
