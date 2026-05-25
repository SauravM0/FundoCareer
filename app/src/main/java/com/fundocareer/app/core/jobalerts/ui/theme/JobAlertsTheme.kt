package com.fundocareer.app.core.jobalerts.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val FcBlue = Color(0xFF1A73E8)
val FcBlueDark = Color(0xFF1557B0)
val FcBlueLight = Color(0xFFD2E3FC)
val FcGreen = Color(0xFF34A853)
val FcGreenLight = Color(0xFFE6F4EA)
val FcRed = Color(0xFFDC2626)
val FcRedLight = Color(0xFFFEE2E2)
val FcAmber = Color(0xFFF59E0B)
val FcAmberLight = Color(0xFFFEF3C7)
val FcSlate50 = Color(0xFFF8FAFC)
val FcSlate100 = Color(0xFFF1F5F9)
val FcSlate200 = Color(0xFFE2E8F0)
val FcSlate500 = Color(0xFF64748B)
val FcSlate600 = Color(0xFF475569)
val FcSlate700 = Color(0xFF334155)
val FcSlate900 = Color(0xFF0F172A)
val FcWhite = Color(0xFFFFFFFF)
val FcTextPrimary = Color(0xFF1F1F1F)
val FcTextSecondary = Color(0xFF666666)

private val LightColors = lightColorScheme(
    primary = FcBlue,
    onPrimary = FcWhite,
    primaryContainer = FcBlueLight,
    onPrimaryContainer = FcBlueDark,
    secondary = FcGreen,
    onSecondary = FcWhite,
    secondaryContainer = FcGreenLight,
    onSecondaryContainer = Color(0xFF1B5E20),
    background = FcSlate50,
    surface = FcWhite,
    onSurface = FcTextPrimary,
    onSurfaceVariant = FcTextSecondary,
    error = FcRed,
    onError = FcWhite,
    errorContainer = FcRedLight,
    onErrorContainer = Color(0xFF7F1D1D),
    surfaceVariant = FcSlate100,
    outline = FcSlate200,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF062E6F),
    onPrimaryContainer = FcBlueLight,
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF003300),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF9E9E9E),
    error = Color(0xFFEF5350),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
    surfaceVariant = Color(0xFF2C2C2C),
    outline = Color(0xFF444444),
)

val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
)

@Composable
fun JobAlertsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
