package com.mahasamuha.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mahasamuha.assistant.R

/** The Mahasamuha design system's palette (its tokens.json; oklch values converted to sRGB). */
object Brand {
    val ink = Color(0xFF201914)
    val paper = Color(0xFFF8F5EF)
    val nodeCenter = Color(0xFF232933)
    val indigo = Color(0xFF355BBD)
    val sage = Color(0xFF489A68)
    val saffron = Color(0xFFEB881F)
    val fieldLight = Color(0xFFEAE7E4)
    val fieldDark = Color(0xFFE0DEDA)
}

/**
 * Semantic colors for paper (light) or the flat-ink dark variant. Text tones are black or white at
 * fixed alphas, as the design system specifies.
 */
@Immutable
data class AppColors(
    val dark: Boolean,
    val surface: Color,
    /** Floating panels: paper with a shadow, or white at 4% on ink with [panelEdge] (shadows don't read on ink). */
    val panel: Color,
    val panelEdge: Color?,
    val text: Color,
    val textBody: Color,
    val textMuted: Color,
    val divider: Color,
    val inset: Color,
    val bubbleAssistant: Color,
    val bubbleUser: Color,
    val onBubbleUser: Color,
    val actionBackground: Color,
    val actionContent: Color,
    val status: Color,
)

private val PaperColors =
    AppColors(
        dark = false,
        surface = Brand.paper,
        panel = Brand.paper,
        panelEdge = null,
        text = Brand.ink,
        textBody = Color.Black.copy(alpha = 0.70f),
        textMuted = Color.Black.copy(alpha = 0.55f),
        divider = Color.Black.copy(alpha = 0.08f),
        inset = Brand.fieldLight,
        bubbleAssistant = Color.Black.copy(alpha = 0.04f),
        bubbleUser = Brand.indigo,
        onBubbleUser = Brand.paper,
        actionBackground = Brand.ink,
        actionContent = Brand.paper,
        status = Brand.sage,
    )

private val InkColors =
    AppColors(
        dark = true,
        surface = Brand.ink,
        panel = Color.White.copy(alpha = 0.04f).compositeOver(Brand.ink),
        panelEdge = Color.White.copy(alpha = 0.08f),
        text = Brand.paper,
        textBody = Brand.paper,
        textMuted = Color.White.copy(alpha = 0.55f),
        divider = Color.White.copy(alpha = 0.10f),
        inset = Color.White.copy(alpha = 0.06f),
        bubbleAssistant = Color.White.copy(alpha = 0.06f),
        bubbleUser = Brand.indigo,
        onBubbleUser = Brand.paper,
        actionBackground = Brand.paper,
        actionContent = Brand.ink,
        status = Brand.sage,
    )

private val LocalAppColors = staticCompositionLocalOf { PaperColors }

private fun geist(weight: Int) =
    Font(R.font.geist, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(geist(400), geist(500))
val GeistMono =
    FontFamily(
        Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    )

/** The design system's type scale, the sizes a phone screen uses. */
object AppType {
    val h3 = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp)
    val bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val ui = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.005.em)
    val caption = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val eyebrow = TextStyle(fontFamily = GeistMono, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.08.em)
}

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable
        get() = LocalAppColors.current
}

/** Paper by default; the flat-ink variant when the system is in dark mode. */
@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) InkColors else PaperColors
    // Material components (ripples, text selection, dialogs) pick up the same palette.
    val scheme =
        if (colors.dark) {
            darkColorScheme(
                primary = colors.actionBackground,
                onPrimary = colors.actionContent,
                background = colors.surface,
                onBackground = colors.text,
                surface = colors.surface,
                onSurface = colors.text,
                surfaceContainer = colors.surface,
                surfaceContainerLow = colors.surface,
                surfaceContainerHigh = colors.surface,
                onSurfaceVariant = colors.textMuted,
                outlineVariant = colors.divider,
            )
        } else {
            lightColorScheme(
                primary = colors.actionBackground,
                onPrimary = colors.actionContent,
                background = colors.surface,
                onBackground = colors.text,
                surface = colors.surface,
                onSurface = colors.text,
                surfaceContainer = colors.surface,
                surfaceContainerLow = colors.surface,
                surfaceContainerHigh = colors.surface,
                onSurfaceVariant = colors.textMuted,
                outlineVariant = colors.divider,
            )
        }
    val typography =
        Typography(
            headlineSmall = AppType.h3,
            bodyLarge = AppType.body,
            bodyMedium = AppType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = AppType.ui,
        )
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
