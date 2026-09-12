package com.noop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

// MARK: - Locked component system (ported from StrandDesign/Components.swift + StrandCard.swift)
//
// Every screen composes ONLY these. Fixed dimensions + one spacing scale guarantee
// the uniform, instrument-grade look from the reference.

// MARK: - NoopCard — the one card surface (surface.raised, 16pt radius, hairline border)

@Composable
fun NoopCard(
    modifier: Modifier = Modifier,
    padding: Dp = Metrics.cardPadding,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.surfaceRaised)
            .padding(padding),
    ) {
        content()
    }
}

// MARK: - DataPendingNote — the shared "what shows now vs what needs an import" banner
//
// A NoopCard with a leading AutoGraph glyph, a bold title and a body line. Every data
// screen drops one of these in its empty/partial state so the user always knows what is
// live now and what an import will backfill. Copy is passed verbatim by the call site.

@Composable
fun DataPendingNote(title: String, body: String, modifier: Modifier = Modifier) {
    NoopCard(modifier = modifier, padding = 18.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.AutoGraph,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
                Text(body, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}

// MARK: - Overline label (ALL-CAPS, semibold, +0.8 tracking, secondary)

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Palette.textSecondary) {
    Text(
        text = text.uppercase(),
        style = NoopType.overline,
        color = color,
        modifier = modifier,
    )
}

// MARK: - Section header

@Composable
fun SectionHeader(
    title: String,
    overline: String? = null,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (overline != null) Overline(overline)
            Text(title, style = NoopType.title2, color = Palette.textPrimary)
        }
        if (trailing != null) {
            Text(trailing, style = NoopType.footnote, color = Palette.textSecondary)
        }
    }
}

// MARK: - StrandTone (ported from StrandDesign/StatePill.swift)

enum class StrandTone(val color: Color) {
    Neutral(Palette.textSecondary),
    Accent(Palette.accent),
    Positive(Palette.statusPositive),
    Warning(Palette.statusWarning),
    Critical(Palette.statusCritical),
}

// MARK: - ConnectionDot — tiny status dot with optional breathing pulse halo

@Composable
fun ConnectionDot(
    tone: StrandTone = StrandTone.Positive,
    pulsing: Boolean = false,
    size: Dp = 9.dp,
    modifier: Modifier = Modifier,
) {
    val animate = pulsing && Motion.animationsEnabled
    var scale = 1.7f
    var haloAlpha = 0.25f
    if (animate) {
        val transition = rememberInfiniteTransition(label = "dot")
        scale = transition.animateFloat(
            initialValue = 1.0f,
            targetValue = 2.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.breathPeriodMs, easing = Motion.easeInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotScale",
        ).value
        haloAlpha = transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.breathPeriodMs, easing = Motion.easeInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotHalo",
        ).value
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (pulsing) {
            Box(
                modifier = Modifier
                    .size(size)
                    .drawBehind {
                        drawCircleScaled(tone.color, scale, haloAlpha)
                    },
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(tone.color),
        )
    }
}

private fun DrawScope.drawCircleScaled(
    color: Color,
    scale: Float,
    alpha: Float,
) {
    drawCircle(color = color, radius = (size.minDimension / 2f) * scale, alpha = alpha)
}

// MARK: - StatePill — rounded pill with optional leading dot + tinted label

@Composable
fun StatePill(
    title: String,
    tone: StrandTone = StrandTone.Neutral,
    showsDot: Boolean = true,
    pulsing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(tone.color.copy(alpha = 0.12f))
            .border(1.dp, tone.color.copy(alpha = 0.28f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showsDot) ConnectionDot(tone = tone, pulsing = pulsing, size = 7.dp)
        Text(title, style = NoopType.overline.copy(letterSpacing = 0.4.sp), color = tone.color)
    }
}

// MARK: - SourceBadge

@Composable
fun SourceBadge(text: String, tint: Color = Palette.accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text.uppercase(),
        style = NoopType.overline.copy(fontSize = 10.sp, letterSpacing = 0.5.sp),
        color = tint,
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.30f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// MARK: - StatTile — uniform fixed-height metric tile

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color = Palette.textPrimary,
    delta: String? = null,
    deltaColor: Color = Palette.textTertiary,
) {
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = 14.dp) {
        Column {
            Overline(label)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                style = NoopType.number(26f),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (caption != null) {
                    Text(
                        caption, style = NoopType.footnote, color = Palette.textTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (delta != null) {
                    Text(delta, style = NoopType.captionNumber, color = deltaColor)
                }
            }
        }
    }
}

// MARK: - InsightCard

@Composable
fun InsightCard(
    category: String,
    status: String,
    detail: String,
    modifier: Modifier = Modifier,
    statusColor: Color = Palette.accent,
) {
    NoopCard(modifier = modifier, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline(category)
            Text(status, style = NoopType.title1, color = statusColor)
            Text(detail, style = NoopType.subhead, color = Palette.textSecondary)
        }
    }
}

// MARK: - SegmentedPillControl — the ONE segmented control

@Composable
fun <T> SegmentedPillControl(
    items: List<T>,
    selection: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outerShape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(outerShape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, outerShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val selected = item == selection
            val bg by animateColorAsState(
                if (selected) Palette.surfaceOverlay else Color.Transparent,
                tween(Motion.durationFast), label = "segBg",
            )
            Text(
                text = label(item).uppercase(),
                style = NoopType.overline.copy(letterSpacing = 0.8.sp),
                color = if (selected) Palette.textPrimary else Palette.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickableNoRipple { onSelect(item) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

// MARK: - GaugeRing — THE signature component
//
// A full-circle gauge: thick rounded-cap stroke in ONE solid metric color,
// filled clockwise from 12 o'clock to `fraction` over a faint white track,
// with a draw-in animation. Center holds arbitrary content (usually a heavy
// numeral + a small unit).

@Composable
fun GaugeRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 240.dp,
    lineWidth: Dp = 16.dp,
    content: @Composable () -> Unit = {},
) {
    val target = fraction.coerceIn(0f, 1f)
    val animatedFraction = if (Motion.animationsEnabled) {
        animateFloatAsState(
            targetValue = target,
            animationSpec = tween(Motion.durationSlow, easing = Motion.drawIn),
            label = "ringFill",
        ).value
    } else target
    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(diameter)
                .drawBehind {
                    val stroke = lineWidth.toPx()
                    val radius = (min(size.width, size.height) - stroke) / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val topLeft = Offset(center.x - radius, center.y - radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    val ringStroke = Stroke(width = stroke, cap = StrokeCap.Round)

                    // Full-circle faint track.
                    drawArc(
                        color = Palette.ringTrack,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = ringStroke,
                    )

                    // Solid-color fill, clockwise from 12 o'clock.
                    if (animatedFraction > 0.004f) {
                        drawArc(
                            color = color,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedFraction,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = ringStroke,
                        )
                    }
                },
        )
        content()
    }
}

// MARK: - MetricRing — a labeled gauge with the standard center readout
//
// The trio-row unit: GaugeRing with a heavy number + small trailing unit in the
// center. `value` is pre-formatted by the caller ("74", "4.3"); `unit` renders
// small and top-aligned next to it ("%").

@Composable
fun MetricRing(
    value: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    unit: String? = null,
    diameter: Dp = 104.dp,
    lineWidth: Dp = 9.dp,
) {
    GaugeRing(
        fraction = fraction,
        color = color,
        diameter = diameter,
        lineWidth = lineWidth,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = value,
                style = NoopType.number(diameter.value * 0.27f),
                color = Palette.textPrimary,
                maxLines = 1,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = NoopType.number(diameter.value * 0.13f, FontWeight.Bold),
                    color = Palette.textPrimary,
                    modifier = Modifier.padding(top = diameter.value.dp * 0.045f),
                )
            }
        }
    }
}

// MARK: - RecoveryRing — the recovery-specific gauge (kept API for all call sites)
//
// Full-circle GaugeRing tinted by the discrete recovery tier, with the score
// numeral, the tier word tinted to match, and an optional supporting line.

@Composable
fun RecoveryRing(
    score: Double,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    diameter: Dp = 240.dp,
    lineWidth: Dp = 16.dp,
    showsLabel: Boolean = true,
) {
    val tierColor = Palette.recoveryColor(score)
    val stateWord = Palette.recoveryState(score)

    GaugeRing(
        fraction = (score / 100.0).toFloat(),
        color = tierColor,
        diameter = diameter,
        lineWidth = lineWidth,
        modifier = modifier,
    ) {
        if (showsLabel) {
            val numberSp = diameter.value * 0.26f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = score.toInt().toString(),
                        style = NoopType.display(numberSp),
                        color = Palette.textPrimary,
                    )
                    Text(
                        text = "%",
                        style = NoopType.number(numberSp * 0.45f, FontWeight.Bold),
                        color = Palette.textPrimary,
                        modifier = Modifier.padding(top = (numberSp * 0.10f).dp),
                    )
                }
                Text(
                    text = stateWord,
                    style = NoopType.overline,
                    color = tierColor,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// MARK: - ScreenScaffold (ported from Strand/Screens/ScreenScaffold.swift)
//
// Standard scrollable screen container: a title + optional subtitle header over the
// dark surface, then a left-aligned content column with 28dp screen padding.

@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.screenBackground())
            .verticalScroll(rememberScrollState())
            .padding(Metrics.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NoopType.title1, color = Palette.textPrimary)
            if (subtitle != null) {
                Text(subtitle, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
        content()
    }
}

// MARK: - Small interaction helper (clickable without ripple, for pill segments)

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick,
    )
