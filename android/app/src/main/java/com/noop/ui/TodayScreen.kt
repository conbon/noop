package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home — the daily console. Trio of metric rings (Sleep / Recovery / Strain),
 * a Health-Monitor + Last-Night card pair, a 7-day Strain & Recovery overlay,
 * and full-width dashboard rows with day-over-day deltas.
 *
 * The screen splits into a thin state collector ([TodayScreen]) and a stateless
 * renderer ([TodayContent]) so JVM screenshot tests can drive it with synthetic
 * data.
 */
@Composable
fun TodayScreen(viewModel: AppViewModel, onSupport: () -> Unit = {}) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val alert by viewModel.healthAlert.collectAsStateWithLifecycle()
    val days by viewModel.recentDays.collectAsStateWithLifecycle()
    TodayContent(today = today, alert = alert, days = days, onSupport = onSupport)
}

@Composable
internal fun TodayContent(
    today: DailyMetric?,
    alert: String?,
    days: List<DailyMetric>,
    onSupport: () -> Unit = {},
) {
    ScreenScaffold(title = "Today", subtitle = todayDateLine()) {

        if (today?.recovery == null) {
            DataPendingNote(
                title = "Live now. Your scores are building.",
                body = "Your live heart rate is working from the strap, and recovery, strain " +
                    "and sleep build from it over your next few nights of wear, sharpening as it " +
                    "learns your baseline. Want your full history instantly? Import your WHOOP " +
                    "export in Data Sources and it backfills in about a minute.",
            )
        }

        if (alert != null) IllnessBanner(alert)

        // TRIO — the three scores of the day, each a full-circle gauge.
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.weight(1f)) {
                TrioRow(today)
            }
            IconButton(onClick = onSupport, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Support NOOP",
                    tint = Palette.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // MONITORS — health-watch summary + last night, side by side.
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            HealthMonitorCard(today = today, days = days, alert = alert, modifier = Modifier.weight(1f))
            LastNightCard(today = today, modifier = Modifier.weight(1f))
        }

        // WEEK — strain vs recovery, overlaid on one chart.
        WeekOverlayCard(days)

        // DASHBOARD — full-width metric rows with day-over-day deltas.
        SectionHeader("My Dashboard", trailing = "vs yesterday")
        DashboardRows(days)
    }
}

// MARK: - Trio ring row

@Composable
private fun TrioRow(d: DailyMetric?) {
    val sleepPct = d?.totalSleepMin?.let { ((it / SLEEP_TARGET_MIN) * 100).coerceAtMost(100.0) }
    val recovery = d?.recovery
    val strain = d?.strain

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        RingWithLabel(
            label = "Sleep",
            value = sleepPct?.roundToInt()?.toString() ?: "–",
            unit = if (sleepPct != null) "%" else null,
            fraction = ((sleepPct ?: 0.0) / 100.0).toFloat(),
            color = Palette.sleepBlue,
        )
        RingWithLabel(
            label = "Recovery",
            value = recovery?.roundToInt()?.toString() ?: "–",
            unit = if (recovery != null) "%" else null,
            fraction = ((recovery ?: 0.0) / 100.0).toFloat(),
            color = recovery?.let { Palette.recoveryColor(it) } ?: Palette.textTertiary,
        )
        RingWithLabel(
            label = "Strain",
            value = strain?.let { String.format(Locale.US, "%.1f", it) } ?: "–",
            unit = null,
            fraction = ((strain ?: 0.0) / 21.0).toFloat(),
            color = Palette.strainBlue,
        )
    }
}

@Composable
private fun RingWithLabel(
    label: String,
    value: String,
    unit: String?,
    fraction: Float,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MetricRing(
            value = value,
            unit = unit,
            fraction = fraction,
            color = color,
            diameter = 100.dp,
            lineWidth = 9.dp,
        )
        Spacer(Modifier.height(10.dp))
        Overline(label, color = Palette.textPrimary)
    }
}

// MARK: - Health Monitor card

/** One vital line: label, formatted value, and whether it sits inside its baseline band. */
private data class Vital(val ok: Boolean)

/**
 * Baseline check per vital: value vs the mean of up to the prior 30 days (excluding
 * today), with a per-metric tolerance. SpO2 and skin temp use absolute bands.
 */
private fun vitals(today: DailyMetric?, days: List<DailyMetric>): List<Vital> {
    if (today == null) return emptyList()
    val prior = days.dropLast(1).takeLast(30)
    fun baseline(pick: (DailyMetric) -> Double?): Double? {
        val xs = prior.mapNotNull(pick)
        return if (xs.size >= 3) xs.average() else null
    }
    fun within(value: Double?, base: Double?, tolFrac: Double): Vital? {
        if (value == null) return null
        if (base == null) return Vital(ok = true) // no baseline yet — don't alarm
        return Vital(ok = abs(value - base) <= base * tolFrac)
    }

    return listOfNotNull(
        within(today.avgHrv, baseline { it.avgHrv }, 0.25),
        within(today.restingHr?.toDouble(), baseline { it.restingHr?.toDouble() }, 0.08),
        today.spo2Pct?.let { Vital(ok = it >= 94.0) },
        within(today.respRateBpm, baseline { it.respRateBpm }, 0.10),
        today.skinTempDevC?.let { Vital(ok = abs(it) <= 0.6) },
    )
}

@Composable
private fun HealthMonitorCard(
    today: DailyMetric?,
    days: List<DailyMetric>,
    alert: String?,
    modifier: Modifier = Modifier,
) {
    val vs = vitals(today, days)
    val okCount = vs.count { it.ok }
    val total = vs.size
    val allOk = total > 0 && okCount == total && alert == null

    NoopCard(modifier = modifier.height(120.dp)) {
        Column {
            Overline("Health\nMonitor", color = Palette.textPrimary)
            Spacer(Modifier.weight(1f))
            if (total == 0) {
                Text("No vitals yet", style = NoopType.subhead, color = Palette.textTertiary)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val tone = if (allOk) Palette.statusPositive else Palette.statusWarning
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(tone.copy(alpha = 0.18f), RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (allOk) Icons.Filled.Check else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = tone,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Column {
                        Overline(
                            if (allOk) "Within range" else "Check vitals",
                            color = tone,
                        )
                        Text(
                            "$okCount/$total metrics",
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Last Night card

@Composable
private fun LastNightCard(today: DailyMetric?, modifier: Modifier = Modifier) {
    val mins = today?.totalSleepMin
    NoopCard(modifier = modifier.height(120.dp)) {
        Column {
            Overline("Last\nNight", color = Palette.textPrimary)
            Spacer(Modifier.weight(1f))
            if (mins == null) {
                Text("No sleep yet", style = NoopType.subhead, color = Palette.textTertiary)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Palette.sleepBlue.copy(alpha = 0.18f), RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Bedtime,
                            contentDescription = null,
                            tint = Palette.sleepBlue,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Column {
                        Text(
                            formatHm(mins),
                            style = NoopType.number(20f),
                            color = Palette.textPrimary,
                        )
                        Text(
                            today.efficiency?.let {
                                String.format(Locale.US, "%.0f%% efficient", it)
                            } ?: "asleep",
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Strain & Recovery week overlay

@Composable
private fun WeekOverlayCard(days: List<DailyMetric>) {
    val week = days.takeLast(7)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Overline("Strain & Recovery", color = Palette.textPrimary, modifier = Modifier.weight(1f))
                LegendDot(Palette.strainBlue, "Strain")
                Spacer(Modifier.width(10.dp))
                LegendDot(Palette.recoveryHigh, "Recovery")
            }
            if (week.size < 2) {
                Text(
                    "A week of wear draws this chart.",
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            } else {
                DualLineChart(
                    a = week.map { it.strain },
                    aMax = 21.0,
                    aColor = Palette.strainBlue,
                    b = week.map { it.recovery },
                    bMax = 100.0,
                    bColorFor = { Palette.recoveryColor(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { d ->
                        Text(
                            dayInitial(d.day),
                            style = NoopType.captionNumber,
                            color = Palette.textSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
        Text(label.uppercase(), style = NoopType.overline.copy(fontSize = 9.sp), color = Palette.textSecondary)
    }
}

/**
 * Two series on one canvas, each normalized to its own fixed scale (aMax/bMax) so
 * strain (0..21) and recovery (0..100) share the plot honestly. Series A draws as a
 * solid line with hollow point markers; series B draws as a lighter line whose
 * markers take the per-value color (recovery tiers). Null values break the line.
 */
@Composable
private fun DualLineChart(
    a: List<Double?>,
    aMax: Double,
    aColor: Color,
    b: List<Double?>,
    bMax: Double,
    bColorFor: (Double) -> Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val n = maxOf(a.size, b.size)
        if (n < 2) return@Canvas
        val slot = size.width / n
        val topPad = 10f
        val bottomPad = 10f
        val usable = size.height - topPad - bottomPad

        fun x(i: Int) = slot * i + slot / 2f
        fun y(v: Double, vMax: Double) =
            topPad + (1f - (v / vMax).toFloat().coerceIn(0f, 1f)) * usable

        // Faint horizontal gridlines at 0 / 50 / 100%.
        listOf(0f, 0.5f, 1f).forEach { f ->
            val gy = topPad + (1f - f) * usable
            drawLine(
                color = Palette.hairline,
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
            )
        }

        fun drawSeries(
            values: List<Double?>,
            vMax: Double,
            lineColor: Color,
            markerColor: ((Double) -> Color)?,
        ) {
            // Line segments between consecutive non-null points.
            var prev: Offset? = null
            values.forEachIndexed { i, v ->
                if (v == null) { prev = null; return@forEachIndexed }
                val p = Offset(x(i), y(v, vMax))
                prev?.let {
                    drawLine(
                        color = lineColor.copy(alpha = 0.85f),
                        start = it,
                        end = p,
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                }
                prev = p
            }
            // Markers on top.
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val p = Offset(x(i), y(v, vMax))
                val mc = markerColor?.invoke(v) ?: lineColor
                drawCircle(color = Palette.surfaceRaised, radius = 8f, center = p)
                drawCircle(color = mc, radius = 8f, center = p, style = Stroke(width = 4f))
            }
        }

        drawSeries(a, aMax, aColor, null)
        drawSeries(b, bMax, Palette.textSecondary.copy(alpha = 0.5f)) { v -> bColorFor(v) }
    }
}

// MARK: - Dashboard rows

private data class RowSpec(
    val label: String,
    val value: (DailyMetric) -> Double?,
    val format: (Double) -> String,
    val accent: Color,
    /** True when a lower value is the good direction (RHR, resp rate). */
    val lowerIsBetter: Boolean = false,
)

private val rowSpecs = listOf(
    RowSpec("HRV", { it.avgHrv }, { "${it.roundToInt()}" }, Palette.metricPurple),
    RowSpec("Resting heart rate", { it.restingHr?.toDouble() }, { "${it.roundToInt()}" }, Palette.metricRose, lowerIsBetter = true),
    RowSpec("Sleep", { it.totalSleepMin }, { formatHm(it) }, Palette.sleepBlue),
    RowSpec("Blood oxygen", { it.spo2Pct }, { String.format(Locale.US, "%.0f%%", it) }, Palette.metricCyan),
    RowSpec("Respiratory rate", { it.respRateBpm }, { String.format(Locale.US, "%.1f", it) }, Palette.metricAmber, lowerIsBetter = true),
)

@Composable
private fun DashboardRows(days: List<DailyMetric>) {
    val today = days.lastOrNull()
    val yesterday = days.dropLast(1).lastOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        rowSpecs.forEach { spec ->
            DashboardRow(
                label = spec.label,
                value = today?.let(spec.value),
                prior = yesterday?.let(spec.value),
                format = spec.format,
                lowerIsBetter = spec.lowerIsBetter,
            )
        }
    }
}

@Composable
private fun DashboardRow(
    label: String,
    value: Double?,
    prior: Double?,
    format: (Double) -> String,
    lowerIsBetter: Boolean,
) {
    NoopCard(padding = 16.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Overline(label, color = Palette.textPrimary, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        value?.let(format) ?: "–",
                        style = NoopType.number(24f),
                        color = Palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (value != null && prior != null && prior != value) {
                        DeltaTriangle(up = value > prior, good = (value > prior) != lowerIsBetter)
                    }
                }
                if (prior != null) {
                    Text(
                        format(prior),
                        style = NoopType.captionNumber,
                        color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

/** Small ▲/▼ delta marker: green when the move is good, amber when not. */
@Composable
private fun DeltaTriangle(up: Boolean, good: Boolean) {
    val color = if (good) Palette.statusPositive else Palette.statusWarning
    Canvas(modifier = Modifier.size(9.dp)) {
        val path = Path().apply {
            if (up) {
                moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height)
            } else {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height)
            }
            close()
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Fill)
    }
}

// MARK: - Illness banner

@Composable
private fun IllnessBanner(message: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.statusWarning.copy(alpha = 0.12f), shape)
            .border(1.dp, Palette.statusWarning.copy(alpha = 0.4f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.statusWarning)
        Text(message, style = NoopType.subhead, color = Palette.textPrimary)
    }
}

// MARK: - Formatting helpers

/** Sleep-performance target: 8h. */
private const val SLEEP_TARGET_MIN = 480.0

private fun formatHm(mins: Double): String {
    val total = mins.roundToInt()
    return "${total / 60}:" + String.format(Locale.US, "%02d", total % 60)
}

private fun todayDateLine(): String {
    val now = LocalDate.now()
    return now.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))
}

/** First letter of the weekday for an ISO `yyyy-MM-dd` day key ("M", "T", …). */
private fun dayInitial(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEEE", Locale.US))
}.getOrDefault("·")
