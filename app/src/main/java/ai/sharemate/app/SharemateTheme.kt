package ai.sharemate.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalTextApi::class)
private val Pretendard = FontFamily((300..800 step 100).map { weight ->
    Font(R.font.pretendard_variable, weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = -0.2) =
    TextStyle(fontFamily = Pretendard, fontWeight = weight, fontSize = size.sp,
        lineHeight = line.sp, letterSpacing = tracking.sp)

private val SharemateTypography = Typography(
    displayLarge = type(56, 64, FontWeight.SemiBold, -1.5),
    displayMedium = type(46, 56, FontWeight.SemiBold, -1.2),
    displaySmall = type(40, 50, FontWeight.SemiBold, -1.0),
    headlineLarge = type(34, 44, FontWeight.SemiBold, -0.8),
    headlineMedium = type(30, 40, FontWeight.SemiBold, -0.6),
    headlineSmall = type(27, 36, FontWeight.SemiBold, -0.5),
    titleLarge = type(25, 34, FontWeight.SemiBold, -0.5),
    titleMedium = type(21, 30, FontWeight.SemiBold),
    titleSmall = type(18, 26, FontWeight.SemiBold),
    bodyLarge = type(20, 30),
    bodyMedium = type(17, 26),
    bodySmall = type(15, 22),
    labelLarge = type(18, 26, FontWeight.SemiBold),
    labelMedium = type(15, 22, FontWeight.Medium, 0.0),
    labelSmall = type(13, 18, FontWeight.Medium, 0.2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF65D995), onPrimary = Color(0xFF092817),
    primaryContainer = Color(0xFF173224), onPrimaryContainer = Color(0xFFB6F2CD),
    inversePrimary = Color(0xFF006B70),
    secondary = Color(0xFFC0CBD9), onSecondary = Color(0xFF25313F),
    secondaryContainer = Color(0xFF1E3A2B), onSecondaryContainer = Color(0xFFB6F2CD),
    tertiary = Color(0xFFA4D0C8), onTertiary = Color(0xFF173D37),
    tertiaryContainer = Color(0xFF294C45), onTertiaryContainer = Color(0xFFC8EEE5),
    background = Color(0xFF07090B), onBackground = Color(0xFFF2F4F5),
    surface = Color(0xFF101316), onSurface = Color(0xFFF2F4F5),
    surfaceVariant = Color(0xFF1A2024), onSurfaceVariant = Color(0xFFAEB7BD),
    surfaceTint = Color.Transparent, inverseSurface = Color(0xFFE6EAF0), inverseOnSurface = Color(0xFF252A33),
    outline = Color(0xFF737E8D), outlineVariant = Color(0xFF2B3339),
    error = Color(0xFFFFB4AC), onError = Color(0xFF680D0D),
    errorContainer = Color(0xFF572B2B), onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black, surfaceBright = Color(0xFF2F363C), surfaceDim = Color(0xFF07090B),
    surfaceContainerLowest = Color(0xFF101318), surfaceContainerLow = Color(0xFF101619),
    surfaceContainer = Color(0xFF191E22), surfaceContainerHigh = Color(0xFF22282D),
    surfaceContainerHighest = Color(0xFF2B3339),
)

@Composable
fun SharemateTheme(compact: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = if (!compact) SharemateTypography else SharemateTypography.copy(
            bodyLarge = type(16, 24), bodyMedium = type(14, 22), bodySmall = type(12, 18),
            titleLarge = type(22, 30, FontWeight.SemiBold), titleMedium = type(18, 26, FontWeight.SemiBold),
            titleSmall = type(16, 24, FontWeight.SemiBold), labelLarge = type(15, 22, FontWeight.SemiBold)),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content,
    )
}
