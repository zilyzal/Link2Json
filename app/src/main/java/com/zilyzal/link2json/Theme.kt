package com.zilyzal.link2json

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF080B16)
val Indigo = Color(0xFF15103A)
val Violet = Color(0xFF7C5CFF)
val Blue = Color(0xFF4F7CFF)
val Cyan = Color(0xFF22D3EE)
val Pink = Color(0xFFFF6BCB)
val Mint = Color(0xFF6EE7B7)
val Amber = Color(0xFFFBBF24)
val TextHi = Color(0xFFF4F6FF)
val TextLo = Color(0xFFA6ADC8)

private val Scheme = darkColorScheme(
    primary = Violet,
    secondary = Cyan,
    tertiary = Pink,
    background = Ink,
    surface = Ink,
    onPrimary = Color.White,
    onBackground = TextHi,
    onSurface = TextHi,
)

@Composable
fun Link2JsonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
