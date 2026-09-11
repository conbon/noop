package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.DailyMetric
import com.noop.data.HrSample
import com.noop.data.SleepSession
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sleep — last night in depth, then the week.
 *
 *  1. HOURS OF SLEEP: the night's heart-rate trace with sleep onset/wake bounds and four
 *     selectable stage rows (Awake / Light / SWS / REM). Selecting a stage paints where it
 *     fell on the trace and in its strip. Timed stages come from the on-device stager;
 *     WHOOP-imported nights only carry totals, so their strips show shares instead.
 *  2. WEEKLY TRENDS: performance, hours vs need (hours and %), restorative sleep,
 *     consistency (bed → wake bars), efficiency, respiratory rate — one card each.
 *
 * Split into a collector ([SleepScreen]) and a stateless renderer ([SleepContent]) so
 * screenshot tests can drive it with synthetic nights.
 */
@Composable
fun SleepScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()

    var sessions by remember { mutableStateOf<List<SleepSession>>(emptyList()) }
    var hr by remember { mutableStateOf<List<HrSample>>(emptyList()) }
    var series by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }

    LaunchedEffect(days.lastOrNull()?.day) {
        val now = System.currentTimeMillis() / 1000L
        val from = now - 21L * 24L * 60L * 60L
        val loaded = runCatching { vm.repo.sleepSessionsMerged("my-whoop", from, now) }.getOrDefault(emptyList())
        sessions = loaded
        // HR for the latest main sleep, with a little slack either side for the trace to run past the bounds.
        latestMainSession(loaded)?.let { s ->
            val slack = ((s.endTs - s.startTs) * 0.06).toLong()
            hr = runCatching {
                vm.repo.hrSamples("my-whoop", s.startTs - slack, s.endTs + slack, limit = 20_000)
            }.getOrDefault(emptyList())
        }
        series = SERIES_KEYS.associateWith { key ->
            runCatching { vm.repo.metricSeries("my-whoop", key, "0000-01-01", "9999-12-31") }
                .getOrDefault(emptyList())
                .associate { it.day to it.value }
        }
    }

    SleepContent(days = days, sessions = sessions, hr = hr, series = series)
}

private val SERIES_KEYS = listOf("sleepNeedMin", "sleepPerformancePct")

/** Stateless body — screenshot tests drive this directly with synthetic data. */
@Composable
internal fun SleepContent(
    days: List<DailyMetric>,
    sessions: List<SleepSession>,
    hr: List<HrSample>,
    series: Map<String, Map<String, Double>> = emptyMap(),
) {
    val night = remember(days, sessions) { buildNight(days, sessions) }
    val week = remember(days, sessions, series) { buildWeek(days, sessions, series) }

    ScreenScaffold(title = "Sleep", subtitle = "Today vs. prior 30 days") {
        if (night == null) {
            DataPendingNote(
                title = "No nights here yet",
                body = "Wear the strap overnight, or import your WHOOP export in Settings, and " +
                    "last night's stages and your weekly sleep trends appear here.",
            )
        } else {
            HoursOfSleepCard(night, hr)
        }

        if (week.labels.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Weekly Trends")

            WeekCard("Sleep performance") {
                WeekBarChart(week.performancePct, Palette.sleepBlue, { "${it.roundToInt()}%" }, max = 100.0)
                WeekLabels(week.labels)
            }

            WeekCard("Hours vs. needed (hours)") {
                Legend(listOf(Palette.sleepBlue to "Hours of sleep", Palette.recoveryHigh to "Sleep needed"))
                WeekDualLineChart(week.hours, week.needHours, Palette.sleepBlue, Palette.recoveryHigh, ::hm)
                WeekLabels(week.labels)
            }

            WeekCard("Hours vs. needed (%)") {
                WeekBarChart(week.hoursVsNeedPct, Palette.sleepBlue, { "${it.roundToInt()}%" }, max = 100.0)
                WeekLabels(week.labels)
            }

            WeekCard("Restorative sleep (hours)") {
                Legend(listOf(Palette.sleepDeep to "Deep sleep", Palette.sleepREM to "REM sleep"))
                WeekStackedBarChart(
                    parts = listOf(week.deepHours, week.remHours),
                    colors = listOf(Palette.sleepDeep, Palette.sleepREM),
                    format = ::hm,
                )
                WeekLabels(week.labels)
            }

            WeekCard("Sleep consistency", trailing = "dashed = your usual bed & wake") {
                ConsistencyChart(week.nights)
                WeekLabels(week.labels, modifier = Modifier.padding(start = 44.dp))
            }

            WeekCard("Sleep efficiency") {
                WeekEfficiencyRow(week.efficiencyPct)
                WeekLabels(week.labels)
            }

            WeekCard("Respiratory rate (rpm)") {
                WeekDualLineChart(
                    a = week.respRpm, b = List(week.respRpm.size) { null },
                    aColor = Palette.metricPurple, bColor = Palette.metricPurple,
                    format = { String.format(Locale.US, "%.1f", it) }, height = 120.dp,
                )
                WeekLabels(week.labels)
            }
        }
    }
}

// MARK: - 1. Hours of Sleep card

private val STAGES = listOf("awake" to "Awake", "light" to "Light", "deep" to "SWS (Deep)", "rem" to "REM")

@Composable
private fun HoursOfSleepCard(n: Night, hr: List<HrSample>) {
    var selected by rememberSaveable { mutableStateOf("light") }

    NoopCard(padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Overline("Hours of sleep", color = Palette.textPrimary)

            // Headline duration + delta vs the prior 30 days.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hm(n.asleepMin / 60.0), style = NoopType.number(40f), color = Palette.textPrimary)
                    n.typicalAsleepMin?.let { t ->
                        if (abs(n.asleepMin - t) >= 1.0) DeltaMarker(up = n.asleepMin > t, good = n.asleepMin >= t)
                    }
                }
                n.typicalAsleepMin?.let {
                    Text(hm(it / 60.0), style = NoopType.captionNumber, color = Palette.textSecondary)
                }
            }

            NightChart(
                hr = hr, onset = n.onset, wake = n.wake,
                spans = n.spans, selectedStage = selected,
            )

            HairlineDivider()

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 18.dp)
                        .border(1.5.dp, Palette.textPrimary, RoundedCornerShape(2.dp))
                        .background(Palette.surfaceOverlay, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Overline("Typical range", color = Palette.textPrimary, modifier = Modifier.weight(1f))
                Overline("Duration", color = Palette.textSecondary)
                Spacer(Modifier.width(8.dp))
                Text(hm(n.inBedMin / 60.0), style = NoopType.number(20f), color = Palette.textPrimary)
            }

            STAGES.forEach { (key, label) ->
                val minutes = n.minutesOf(key)
                StageRow(
                    stage = key, label = label, minutes = minutes, night = n,
                    selected = selected == key, onSelect = { selected = key },
                )
            }

            if (n.spans == null) {
                Text(
                    "Stage timing isn't in WHOOP exports — each bar shows that stage's share of the night. " +
                        "Nights scored from the strap show exactly when each stage happened.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun StageRow(
    stage: String,
    label: String,
    minutes: Double,
    night: Night,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val color = stageColor(stage)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onSelect),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioDot(selected)
            Overline(label, color = Palette.textPrimary)
            Text(
                "${pct(minutes, night.inBedMin)}%",
                style = NoopType.captionNumber,
                color = if (selected) color else Palette.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(hm(minutes / 60.0), style = NoopType.number(20f), color = Palette.textPrimary)
        }
        StageStrip(
            stage = stage, spans = night.spans, onset = night.onset, wake = night.wake,
            stageMinutes = minutes, totalMinutes = night.inBedMin,
            typicalRange = night.typicalRange[stage], selected = selected,
            modifier = Modifier.padding(start = 36.dp),
        )
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .border(2.dp, Palette.textPrimary, CircleShape)
            .background(if (selected) Palette.textPrimary else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).background(Palette.surfaceBase, CircleShape))
    }
}

@Composable
private fun DeltaMarker(up: Boolean, good: Boolean) {
    val color = if (good) Palette.statusPositive else Palette.statusWarning
    Box(
        Modifier.size(10.dp).drawBehind {
            val p = Path().apply {
                if (up) { moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height) }
                else { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height) }
                close()
            }
            drawPath(p, color, style = Fill)
        },
    )
}

@Composable
private fun HairlineDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
}

// MARK: - 2. Week cards

@Composable
private fun WeekCard(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    NoopCard(padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline(title, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, style = NoopType.footnote, color = Palette.textTertiary)
                Text("›", style = NoopType.headline, color = Palette.textSecondary, modifier = Modifier.padding(start = 8.dp))
            }
            content()
        }
    }
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        items.forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                Box(
                    Modifier.size(10.dp).drawBehind {
                        drawCircle(c, style = Stroke(width = 3f))
                    },
                )
                Spacer(Modifier.width(6.dp))
                Overline(label, color = Palette.textPrimary)
            }
        }
    }
}

// MARK: - Model

/** Last night, resolved from the daily row + its session. */
private data class Night(
    val day: String,
    val onset: Long,
    val wake: Long,
    val asleepMin: Double,
    val inBedMin: Double,
    val awakeMin: Double,
    val lightMin: Double,
    val deepMin: Double,
    val remMin: Double,
    /** Timed stage spans when the on-device stager produced them; null for totals-only nights. */
    val spans: List<StageSpan>?,
    val typicalAsleepMin: Double?,
    /** Per stage: mean ± 1 sd of minutes over the prior 30 nights. */
    val typicalRange: Map<String, ClosedFloatingPointRange<Double>>,
) {
    fun minutesOf(stage: String): Double = when (stage) {
        "awake" -> awakeMin; "light" -> lightMin; "deep" -> deepMin; "rem" -> remMin; else -> 0.0
    }
}

private class Week(
    val labels: List<DayLabel>,
    val performancePct: List<Double?>,
    val hours: List<Double?>,
    val needHours: List<Double?>,
    val hoursVsNeedPct: List<Double?>,
    val deepHours: List<Double?>,
    val remHours: List<Double?>,
    val nights: List<Pair<Long, Long>?>,
    val efficiencyPct: List<Double?>,
    val respRpm: List<Double?>,
)

private fun hasStages(d: DailyMetric) = (d.deepMin ?: 0.0) + (d.remMin ?: 0.0) + (d.lightMin ?: 0.0) > 0.0

/** Awake minutes for a day: implied by efficiency when known, else 6 min per disturbance. */
private fun awakeMinutes(d: DailyMetric, asleep: Double): Double {
    val eff = d.efficiency?.let { if (it > 1.0) it / 100.0 else it }
    return when {
        eff != null && eff in 0.01..0.999 -> max(0.0, asleep / eff - asleep)
        d.disturbances != null -> d.disturbances!! * 6.0
        else -> 0.0
    }
}

private fun buildNight(days: List<DailyMetric>, sessions: List<SleepSession>): Night? {
    val latest = days.lastOrNull(::hasStages) ?: return null
    val deep = latest.deepMin ?: 0.0
    val rem = latest.remMin ?: 0.0
    val light = latest.lightMin ?: 0.0
    val asleep = latest.totalSleepMin ?: (deep + rem + light)
    val awake = awakeMinutes(latest, asleep)
    val inBed = asleep + awake

    val session = sessionForDay(sessions, latest.day) ?: latestMainSession(sessions)
    // Without a session clock, lay the night out as ending at 07:00 on its day so the
    // chart still has bounds; the trace will simply be empty.
    val wake = session?.endTs ?: LocalDate.parse(latest.day).atTime(7, 0).atZone(ZoneId.systemDefault()).toEpochSecond()
    val onset = session?.startTs ?: (wake - (inBed * 60).toLong())

    val prior = days.dropLast(1).filter(::hasStages).takeLast(30)
    fun range(pick: (DailyMetric) -> Double?): ClosedFloatingPointRange<Double>? {
        val xs = prior.mapNotNull(pick).filter { it > 0.0 }
        if (xs.size < 3) return null
        val m = xs.average()
        val sd = sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
        return (m - sd).coerceAtLeast(0.0)..(m + sd)
    }

    return Night(
        day = latest.day,
        onset = onset, wake = wake,
        asleepMin = asleep, inBedMin = inBed, awakeMin = awake,
        lightMin = light, deepMin = deep, remMin = rem,
        spans = session?.let { parseSpans(it.stagesJSON) },
        typicalAsleepMin = prior.mapNotNull { it.totalSleepMin }.filter { it > 0.0 }.takeIf { it.isNotEmpty() }?.average(),
        typicalRange = listOfNotNull(
            range { d -> d.totalSleepMin?.let { awakeMinutes(d, it) } }?.let { "awake" to it },
            range { it.lightMin }?.let { "light" to it },
            range { it.deepMin }?.let { "deep" to it },
            range { it.remMin }?.let { "rem" to it },
        ).toMap(),
    )
}

private fun buildWeek(
    days: List<DailyMetric>,
    sessions: List<SleepSession>,
    series: Map<String, Map<String, Double>>,
): Week {
    val week = days.filter(::hasStages).takeLast(7)
    val typicalNeed = max(450.0, days.mapNotNull { it.totalSleepMin }.filter { it > 0.0 }.takeIf { it.isNotEmpty() }?.average() ?: 450.0)
    val needFor = { d: DailyMetric -> series["sleepNeedMin"]?.get(d.day) ?: typicalNeed }
    val fmt = DateTimeFormatter.ofPattern("EEE", Locale.US)

    return Week(
        labels = week.map { d ->
            val date = runCatching { LocalDate.parse(d.day) }.getOrNull()
            DayLabel(date?.format(fmt) ?: "·", date?.dayOfMonth?.toString() ?: "")
        },
        performancePct = week.map { d ->
            series["sleepPerformancePct"]?.get(d.day)
                ?: d.totalSleepMin?.let { minOf(100.0, it / needFor(d) * 100.0) }
        },
        hours = week.map { it.totalSleepMin?.let { m -> m / 60.0 } },
        needHours = week.map { needFor(it) / 60.0 },
        hoursVsNeedPct = week.map { d -> d.totalSleepMin?.let { minOf(100.0, it / needFor(d) * 100.0) } },
        deepHours = week.map { it.deepMin?.let { m -> m / 60.0 } },
        remHours = week.map { it.remMin?.let { m -> m / 60.0 } },
        nights = week.map { d -> sessionForDay(sessions, d.day)?.let { it.startTs to it.endTs } },
        efficiencyPct = week.map { it.efficiency?.let { e -> if (e <= 1.0) e * 100.0 else e } },
        respRpm = week.map { it.respRateBpm },
    )
}

/** The main (longest) sleep whose wake falls on [day], in the phone's zone. */
private fun sessionForDay(sessions: List<SleepSession>, day: String): SleepSession? {
    val zone = ZoneId.systemDefault()
    return sessions
        .filter { Instant.ofEpochSecond(it.endTs).atZone(zone).toLocalDate().toString() == day }
        .maxByOrNull { it.endTs - it.startTs }
}

/** Most recent session at least 3 h long (skips naps). */
internal fun latestMainSession(sessions: List<SleepSession>): SleepSession? =
    sessions.filter { it.endTs - it.startTs >= 3 * 3600 }.maxByOrNull { it.endTs }

/**
 * Timed spans from a stagesJSON array of `{start, end, stage}` (the on-device stager's
 * shape). Returns null for the WHOOP-import shape `{stage, min}` or anything unparseable.
 */
internal fun parseSpans(json: String?): List<StageSpan>? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val arr = JSONArray(json)
        val out = ArrayList<StageSpan>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("start") || !o.has("end")) return null
            val stage = when (o.optString("stage").lowercase()) {
                "wake", "awake" -> "awake"; "light" -> "light"; "deep", "sws" -> "deep"; "rem" -> "rem"
                else -> continue
            }
            out.add(StageSpan(o.getLong("start"), o.getLong("end"), stage))
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

// MARK: - Formatting

/** Hours as "h:mm" — 6.27 → "6:16". */
private fun hm(hours: Double): String {
    val total = (hours * 60).roundToInt().coerceAtLeast(0)
    return "${total / 60}:" + String.format(Locale.US, "%02d", total % 60)
}

private fun pct(minutes: Double, total: Double): Int =
    if (total > 0.0) (minutes / total * 100.0).roundToInt() else 0
