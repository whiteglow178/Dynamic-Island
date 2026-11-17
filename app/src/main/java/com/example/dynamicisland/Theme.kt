package com.example.dynamicisland

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iOS tarzı gruplanmış tasarım: saf siyah (OLED) zemin, bir kademe açık gri gruplar, ince ayırıcılar.
// Vurgu rengi beyazdır; renk yalnızca durum bildirmek için kullanılır.
private val Scheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A3A3C), onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFAEAEB2), onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2C2C2E), onSecondaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF48484A), outlineVariant = Color(0xFF38383A),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun IslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
