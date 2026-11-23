package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme =
  darkColorScheme(
    primary = TacticalEmerald,
    onPrimary = ObsidianBg,
    primaryContainer = TacticalEmeraldContainer,
    onPrimaryContainer = TacticalEmerald,
    secondary = ElectricCyan,
    onSecondary = ObsidianBg,
    secondaryContainer = ElectricCyanContainer,
    onSecondaryContainer = ElectricCyan,
    tertiary = AmberGold,
    onTertiary = ObsidianBg,
    tertiaryContainer = AmberGoldContainer,
    onTertiaryContainer = AmberGold,
    error = ThreatCrimson,
    onError = TextPrimaryDark,
    errorContainer = ThreatCrimsonContainer,
    onErrorContainer = ThreatCrimson,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = ObsidianOutline,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = ForestTeal,
    onPrimary = IvorySurface,
    primaryContainer = ForestTealContainer,
    onPrimaryContainer = ForestTeal,
    secondary = ElectricCyan,
    onSecondary = TextPrimaryLight,
    tertiary = AmberGold,
    onTertiary = TextPrimaryLight,
    error = ThreatCrimson,
    onError = IvorySurface,
    background = IvoryBg,
    onBackground = TextPrimaryLight,
    surface = IvorySurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = IvorySurfaceElevated,
    onSurfaceVariant = TextSecondaryLight,
    outline = IvoryOutline,
  )

@Composable
fun KnightSightTheme(
  darkTheme: Boolean = true, // Obsidian & Emerald tactical theme by default
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content,
  )
}
