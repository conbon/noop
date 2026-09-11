package com.noop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - Palette — "Night Console" v2
//
// Dark-only, performance-dashboard grade. Neutral blue-charcoal surfaces, white
// primary text, and one saturated signal color per metric family:
//   sleep = slate blue · recovery = traffic-light tiers · strain = electric blue.
// Chrome (selection, links, focus) uses a restrained cyan so metric colors stay
// reserved for data.

object Palette {

    // Surfaces — measured from the reference: a cool graphite that lifts slightly toward
    // the top of the screen. Cards sit one step lighter than the base.
    val surfaceBase = Color(0xFF151A1E)    // app background
    val surfaceBaseTop = Color(0xFF1F262C) // the lighter head of the background gradient
    val surfaceRaised = Color(0xFF23282D)  // cards
    val surfaceOverlay = Color(0xFF2C3136) // sheets / popovers / nav bar
    val surfaceInset = Color(0xFF1A1F23)   // wells / chart insets
    val hairline = Color(0xFF2B3136)       // subtle 1px separators
    val hairlineStrong = Color(0xFF3B4248) // emphasis separators

    // Text — pure white numerals, a light grey for supporting values.
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFB9BDC2)
    val textTertiary = Color(0xFF858688)

    // Ambient glow behind hero readouts.
    val glowAmbient = Color(0xFF1C2230)

    // Accent — chrome, not data. The same blue the strain family owns.
    val accent = Color(0xFF0093E9)
    val accentHover = Color(0xFF3FAFF2)
    val accentMuted = Color(0xFF16303F)  // selected-row tint
    val focusRing = Color(0xFF0093E9)
    const val disabledOpacity = 0.45f

    // Ring track — the unfilled remainder of every gauge (opaque, reads on base and cards).
    val ringTrack = Color(0xFF2F373A)

    // Metric identities.
    val sleepBlue = Color(0xFF7AA2BB)   // slate blue — everything sleep
    val strainBlue = Color(0xFF0093E9)  // electric blue — everything strain

    // Recovery — traffic-light tiers (discrete, like the score chip).
    val recoveryLow = Color(0xFFF5052A)   // 0–33
    val recoveryMedium = Color(0xFFFFDE00) // 34–66
    val recoveryHigh = Color(0xFF1EEA0D)  // 67–100

    // Recovery gradient stops (for chart fills; the score itself uses tiers).
    val recovery000 = Color(0xFFF5052A)
    val recovery030 = Color(0xFFFF8A1A)
    val recovery055 = Color(0xFFFFDE00)
    val recovery078 = Color(0xFF8EE800)
    val recovery100 = Color(0xFF1EEA0D)

    /** Ordered gradient stops (position 0..1 → color) for the recovery scale. */
    val recoveryStops: List<Pair<Float, Color>> = listOf(
        0.00f to recovery000,
        0.30f to recovery030,
        0.55f to recovery055,
        0.78f to recovery078,
        1.00f to recovery100,
    )

    // Strain ramp — deep to bright electric blue.
    val strain000 = Color(0xFF1D6FB5)
    val strain033 = Color(0xFF0093E9)
    val strain066 = Color(0xFF0093E9)
    val strain100 = Color(0xFF4FB6F5)

    val strainStops: List<Pair<Float, Color>> = listOf(
        0.00f to strain000,
        0.33f to strain033,
        0.66f to strain066,
        1.00f to strain100,
    )

    // Sleep stages.
    val sleepAwake = Color(0xFFC9C9CB) // pale grey
    val sleepLight = Color(0xFFA4A3F3) // periwinkle
    val sleepDeep = Color(0xFFFB97FA)  // pink (SWS)
    val sleepREM = Color(0xFFAC5AED)   // violet

    // HR zones 1..5 — cool → hot (the stress low/medium/high family, then warning/critical).
    val zone1 = Color(0xFF67ADE8)
    val zone2 = Color(0xFF00F2A0)
    val zone3 = Color(0xFFFFDE00)
    val zone4 = Color(0xFFFFA721)
    val zone5 = Color(0xFFF5052A)

    /** HR zones indexed 1..5; index 0 mirrors zone1 for convenience. */
    val hrZones: List<Color> = listOf(zone1, zone1, zone2, zone3, zone4, zone5)

    // Status — teal-green "within range", orange caution, red critical.
    val statusPositive = Color(0xFF00F2A0)
    val statusWarning = Color(0xFFFFA721)
    val statusCritical = Color(0xFFF5052A)

    // Per-metric accents for secondary dashboards.
    val metricCyan = Color(0xFF67ADE8)
    val metricPurple = Color(0xFFAC5AED)
    val metricAmber = Color(0xFFFFA721)
    val metricRose = Color(0xFFF5556A)

    // MARK: - Sampling helpers

    /** Linear-interpolate two colors in sRGB space. */
    private fun lerp(a: Color, b: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * tt,
            green = a.green + (b.green - a.green) * tt,
            blue = a.blue + (b.blue - a.blue) * tt,
            alpha = a.alpha + (b.alpha - a.alpha) * tt,
        )
    }

    /** Sample a set of (location, color) stops at a normalized position 0..1. */
    fun sample(stops: List<Pair<Float, Color>>, position: Float): Color {
        if (stops.isEmpty()) return Color.Transparent
        if (stops.size == 1) return stops.first().second
        val t = position.coerceIn(0f, 1f)
        var lower = stops.first()
        var upper = stops.last()
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (t >= a.first && t <= b.first) {
                lower = a; upper = b; break
            }
        }
        val span = upper.first - lower.first
        val localT = if (span > 0f) (t - lower.first) / span else 0f
        return lerp(lower.second, upper.second, localT)
    }

    /**
     * Recovery color for a 0..100 score — DISCRETE traffic-light tiers, so the
     * ring, chip and chart markers always land on exactly one of three colors.
     */
    fun recoveryColor(score: Double): Color = when {
        score < 34 -> recoveryLow
        score < 67 -> recoveryMedium
        else -> recoveryHigh
    }

    /** Strain color: the electric-blue identity (uniform, not score-graded). */
    fun strainColor(strain: Double): Color = strainBlue

    /** The state word for a recovery score. */
    fun recoveryState(score: Double): String = when {
        score < 34 -> "LOW"
        score < 67 -> "MODERATE"
        else -> "HIGH"
    }

    /** HR-zone color for a 1..5 zone index (clamped). */
    fun hrZoneColor(zone: Int): Color = hrZones[zone.coerceIn(1, 5)]

    /** The recovery gradient as a horizontal sweep brush (for chart fills). */
    fun recoveryBrush(): Brush =
        Brush.horizontalGradient(*recoveryStops.toTypedArray())

    /** The strain ramp as a horizontal sweep brush. */
    fun strainBrush(): Brush =
        Brush.horizontalGradient(*strainStops.toTypedArray())

    /** Screen background: a gentle lift at the top settling into the base by ~600px. */
    fun screenBackground(): Brush =
        Brush.verticalGradient(0f to surfaceBaseTop, 1f to surfaceBase, endY = 1400f)
}

// MARK: - Motion — physiological: breathe / pulse / flow, no cartoon bounce.

object Motion {
    /**
     * Master switch for motion. Screenshot tests turn it off: an infinite "breathing"
     * transition never lets the Compose runtime go idle, so every capture after it
     * would wait out the idle timeout, and draw-in animations would capture mid-flight.
     */
    @Volatile var animationsEnabled: Boolean = true

    // Durations (ms)
    const val durationFast = 180       // hover/press feedback
    const val durationStandard = 300   // card appear, fades
    const val durationSlow = 900       // ring arc, waveform ignite
    const val breathPeriodMs = 3200    // one breath cycle for ambient pulsing

    // Easings
    val easeOut: Easing = LinearOutSlowInEasing
    val easeInOut: Easing = FastOutSlowInEasing
    val drawIn: Easing = LinearOutSlowInEasing
    val interactive: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}

// MARK: - Metrics — one spacing scale for every screen.

object Metrics {
    val cardRadius = 18.dp
    val cardPadding = 16.dp
    val gap = 12.dp           // gap between cards
    val sectionGap = 28.dp    // gap between sections
    val screenPadding = 20.dp
    val tileHeight = 104.dp   // every metric tile is this tall
    val chartHeight = 220.dp
}

// MARK: - Typography — heavy numerals, tracked uppercase labels.
//
// Numbers are the interface: system sans at ExtraBold with slight negative
// tracking for the big readouts. Labels are small, bold, ALL-CAPS with wide
// tracking. Monospace survives only for raw/log views.

object NoopType {
    private val sans = FontFamily.Default
    private val monoFamily = FontFamily.Monospace

    /** Display — the hero score numerals. */
    fun display(size: Float = 72f) = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = size.sp,
        letterSpacing = (-0.5).sp,
    )

    val title1 = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
    val title2 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 21.sp)
    val headline = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val subhead = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp)
    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val footnote = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 11.sp)

    /** Overline 11 / Bold, +1.3 tracking, ALL-CAPS at use site. */
    val overline = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        letterSpacing = 1.3.sp,
    )

    /** Mono 13 — raw / log views only. */
    val mono = TextStyle(fontFamily = monoFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp)

    /** A numeric readout at an arbitrary size — heavy sans, slightly tightened. */
    fun number(size: Float, weight: FontWeight = FontWeight.ExtraBold) = TextStyle(
        fontFamily = sans, fontWeight = weight, fontSize = size.sp,
        letterSpacing = (-0.3).sp,
    )

    fun mono(size: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = monoFamily, fontWeight = weight, fontSize = size.sp,
    )

    val bodyNumber = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val captionNumber = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

    const val overlineTracking = 1.3f
}

// MARK: - Material3 bridge

private val NoopColorScheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.surfaceBase,
    primaryContainer = Palette.accentMuted,
    onPrimaryContainer = Palette.accentHover,
    secondary = Palette.metricPurple,
    onSecondary = Palette.surfaceBase,
    background = Palette.surfaceBase,
    onBackground = Palette.textPrimary,
    surface = Palette.surfaceRaised,
    onSurface = Palette.textPrimary,
    surfaceVariant = Palette.surfaceOverlay,
    onSurfaceVariant = Palette.textSecondary,
    outline = Palette.hairline,
    outlineVariant = Palette.hairlineStrong,
    error = Palette.statusCritical,
    onError = Palette.surfaceBase,
)

private val NoopMaterialTypography = Typography(
    displayLarge = NoopType.display(72f),
    titleLarge = NoopType.title1,
    titleMedium = NoopType.title2,
    titleSmall = NoopType.headline,
    bodyLarge = NoopType.body,
    bodyMedium = NoopType.subhead,
    bodySmall = NoopType.caption,
    labelLarge = NoopType.headline,
    labelMedium = NoopType.caption,
    labelSmall = NoopType.overline,
)

private val NoopShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(Metrics.cardRadius),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * NoopTheme — dark, dashboard-grade. Always dark regardless of system setting
 * (the design system is dark-only), but `isSystemInDarkTheme` is read so the
 * status-bar contract is satisfied on devices that key off it.
 */
@Composable
fun NoopTheme(content: @Composable () -> Unit) {
    // The design system is dark-only; we always apply the dark scheme regardless of
    // the system setting. `isSystemInDarkTheme` is referenced so status-bar tooling
    // that keys off it stays satisfied.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = NoopColorScheme,
        typography = NoopMaterialTypography,
        shapes = NoopShapes,
        content = content,
    )
}
