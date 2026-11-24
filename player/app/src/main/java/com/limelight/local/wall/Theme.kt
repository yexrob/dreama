package com.limelight.local.wall

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The tokens of design-opus (src/base.css, ux-spec.md §5). Every size in the
 * wall is in the mockups' 1280×720 canvas units; [DesignCanvas] makes one dp
 * one canvas px whatever the panel's real density turns out to be.
 */
object Wall {
    val Bg = Color(0xFF0E0F12)
    val Surface = Color(0xFF17191D)
    val SurfaceHi = Color(0xFF202328)
    val Sheet = Color(0xFF1A1C20)
    val Fill = Color.White.copy(alpha = 0.10f)
    val FillHi = Color.White.copy(alpha = 0.18f)
    val Sel = Color.White.copy(alpha = 0.20f)
    val Text = Color(0xFFECECEE)
    val Text2 = Color(0xFFA6A9B0)
    // Disabled only: 4.9:1 is too faint for anything that has to be read
    val Text3 = Color(0xFF7D818A)
    // Progress and "on" only
    val Accent = Color(0xFFE3A857)
    val Danger = Color(0xFFEE8577)
    val Scrim = Color(0xA808090B)
    val PosterText = Color(0xFFF4F2EE)
    val PosterTextDark = Color(0xFF1D2024)

    val Margin = 64.dp
    val Radius = 16.dp
    val RadiusXl = 28.dp
    val RadiusPoster = 14.dp

    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

/**
 * The type ramp of ux-spec §1.3. Nothing under 24 (1° in the headset), no
 * weight under 400, and tabular figures so a ticking time does not shimmy.
 */
object WallType {
    private fun style(size: Int, line: Float, weight: Int) = TextStyle(
        fontSize = size.sp, lineHeight = (size * line).sp, fontWeight = FontWeight(weight),
        fontFeatureSettings = "tnum", color = Wall.Text)

    val Display = style(64, 1.1f, 600)
    val Title = style(44, 1.2f, 600)
    val Head = style(32, 1.3f, 600)
    val Body = style(28, 1.5f, 400)
    val Caption = style(24, 1.4f, 500)
    val Label = style(28, 1.3f, 500)
    val Button = style(28, 1.2f, 600)
    val ButtonSmall = style(24, 1.2f, 600)
}

/**
 * The width the mockups are drawn at. The whole wall is scaled so this many
 * units fill the panel; if text reads too small in the headset, lower it and
 * everything grows together (ux-spec §1.2, §8 #1).
 */
const val DESIGN_WIDTH = 1280f

@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Wall.Bg)) {
        val density = constraints.maxWidth / DESIGN_WIDTH
        CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
            WallTheme(content)
        }
    }
}

@Composable
private fun WallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Wall.Text, onPrimary = Wall.Bg,
            background = Wall.Bg, onBackground = Wall.Text,
            surface = Wall.Surface, onSurface = Wall.Text, onSurfaceVariant = Wall.Text2,
            error = Wall.Danger,
        ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Wall.Text,
            LocalTextStyle provides WallType.Body,
            content = content,
        )
    }
}
