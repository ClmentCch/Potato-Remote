package com.potatoremote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potatoremote.R

val Cream = Color(0xFFFFF7EA)
val CreamDeep = Color(0xFFF8ECD8)
val CreamSoft = Color(0xFFFFFCF7)
val Accent = Color(0xFFE0763F)
val AccentDark = Color(0xFFC85F2A)
val AccentSoft = Color(0xFFF9E2D1)
val Brown = Color(0xFF2B1B11)
val BrownSoft = Color(0xFF7A5A43)
val Sage = Color(0xFF7E9E6A)
val Danger = Color(0xFFB84A32)
val Success = Color(0xFF4F8A5F)

val PotatoFont = FontFamily(
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.Medium),
    Font(R.font.outfit, FontWeight.SemiBold),
    Font(R.font.outfit, FontWeight.Bold),
)

private val PotatoColors = lightColorScheme(
    primary = Accent,
    onPrimary = CreamSoft,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Brown,
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7F0E0),
    onSecondaryContainer = Color(0xFF2C4323),
    background = Cream,
    onBackground = Brown,
    surface = CreamSoft,
    onSurface = Brown,
    surfaceVariant = CreamDeep,
    onSurfaceVariant = BrownSoft,
    outline = Color(0xFFD9C3A4),
    outlineVariant = Color(0xFFEADBC5),
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFF9E1DA),
    onErrorContainer = Color(0xFF6E2414),
)

private val PotatoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

private val PotatoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PotatoFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun PotatoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PotatoColors,
        typography = PotatoTypography,
        shapes = PotatoShapes,
        content = content,
    )
}
