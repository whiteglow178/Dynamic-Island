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
// Renkler Pal kaydından gelir (varsayılanlar ColorEditor.kt'de); Renk düzenleyici açıksa kullanıcının seçtikleri geçerli olur.
private fun schemeFromPal() = darkColorScheme(
    primary = Pal.c("ui_primary"), onPrimary = Pal.c("ui_on_primary"),
    primaryContainer = Pal.c("ui_primary_container"), onPrimaryContainer = Pal.c("ui_on_primary_container"),
    secondary = Pal.c("ui_secondary"), onSecondary = Pal.c("ui_on_secondary"),
    secondaryContainer = Pal.c("ui_secondary_container"), onSecondaryContainer = Pal.c("ui_on_secondary_container"),
    background = Pal.c("ui_background"), onBackground = Pal.c("ui_on_background"),
    surface = Pal.c("ui_surface"), onSurface = Pal.c("ui_on_surface"),
    surfaceVariant = Pal.c("ui_surface_variant"), onSurfaceVariant = Pal.c("ui_on_surface_variant"),
    outline = Pal.c("ui_outline"), outlineVariant = Pal.c("ui_outline_variant"),
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
    MaterialTheme(colorScheme = schemeFromPal(), typography = AppTypography, shapes = AppShapes, content = content)
}
