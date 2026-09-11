package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
// (rememberTextMeasurer is used by the week charts below)
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.data.HrSample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// MARK: - Shared shapes for the Sleep screen charts

/** One contiguous stage span in wall-clock unix seconds; stage ∈ awake|light|deep|rem. */
internal data class StageSpan(val start: Long, val end: Long, val stage: String)

/** A column of a week chart: two-line label ("Sat" / "5"). */
internal data class DayLabel(val weekday: String, val dayOfMonth: String)

internal val DASH = PathEffect.dashPathEffect(floatArrayOf(6f, 7f))

internal fun sleepStageColor(stage: String): Color = when (stage) {
    "awake" -> Palette.sleepAwake
    "light" -> Palette.sleepLight
    "deep" -> Palette.sleepDeep
    "rem" -> Palette.sleepREM
    else -> Palette.textSecondary
}

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun clock(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    HHMM.format(Instant.ofEpochSecond(ts).atZone(zone))

// MARK: - Night chart: overnight HR with the selected stage highlighted

/**
 * The hero of the Sleep screen. A fixed 30–130 bpm axis, the night's heart-rate trace
 * in muted grey, dashed bounds at sleep onset and wake, and — when timed stage spans
 * are known — the selected stage drawn over the trace in its colour with soft columns
 * beneath. Without HR samples the columns still show where the stage fell.
 */
@Composable
internal fun NightChart(
    hr: List<HrSample>,
    onset: Long,
    wake: Long,
    spans: List<StageSpan>?,
    selectedStage: String,
    modifier: Modifier = Modifier,
) {
    val ticks = listOf(130, 110, 90, 70, 50, 30)
    val color = sleepStageColor(selectedStage)
    // With neither a trace nor timed stages there is nothing to plot: keep just the
    // bounds in a short strip rather than an empty 230dp box.
    val compact = hr.size < 2 && spans == null
    val chartHeight = if (compact) 72.dp else 230.dp

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            // Y-axis tick labels.
            Column(
                modifier = Modifier.fillMaxWidth(0.11f).height(chartHeight),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                if (!compact) ticks.forEach { t ->
                    Text("$t", style = NoopType.captionNumber, color = Palette.textTertiary)
                }
            }
            Canvas(modifier = Modifier.weight(1f).height(chartHeight)) {
                val span = (wake - onset).coerceAtLeast(60L).toDouble()
                val pad = span * 0.06 // HR keeps running a little past both bounds
                val x0 = onset - pad
                val x1 = wake + pad
                fun xOf(ts: Long) = (((ts - x0) / (x1 - x0)) * size.width).toFloat()
                fun yOf(bpm: Double) = ((130.0 - bpm.coerceIn(30.0, 130.0)) / 100.0 * size.height).toFloat()

                // Selected-stage columns hang from the trace (its highest point inside the
                // span) and fade toward the baseline, so they sit under the line, not over it.
                spans?.filter { it.stage == selectedStage }?.forEach { s ->
                    val l = xOf(s.start); val r = xOf(s.end)
                    val inSpan = hr.filter { it.ts in s.start..s.end }
                    val top = if (inSpan.isNotEmpty()) yOf(inSpan.maxOf { it.bpm }.toDouble()) - 6f
                    else size.height * 0.45f
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.0f)),
                            startY = top, endY = size.height,
                        ),
                        topLeft = Offset(l, top.coerceAtLeast(0f)),
                        size = Size((r - l).coerceAtLeast(2f), (size.height - top).coerceAtLeast(0f)),
                    )
                }

                // Trace.
                if (hr.size >= 2) {
                    val base = Path()
                    hr.forEachIndexed { i, s ->
                        val p = Offset(xOf(s.ts), yOf(s.bpm.toDouble()))
                        if (i == 0) base.moveTo(p.x, p.y) else base.lineTo(p.x, p.y)
                    }
                    drawPath(
                        base, color = Palette.textSecondary.copy(alpha = 0.55f),
                        style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    // Re-draw the trace in the stage colour inside each selected span.
                    spans?.filter { it.stage == selectedStage }?.forEach { s ->
                        clipRect(left = xOf(s.start), right = xOf(s.end)) {
                            drawPath(
                                base, color = color,
                                style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }

                // Onset / wake bounds.
                listOf(onset, wake).forEach { ts ->
                    val x = xOf(ts)
                    drawLine(
                        Palette.textPrimary.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height),
                        strokeWidth = 1.5f, pathEffect = DASH,
                    )
                    drawCircle(Palette.textPrimary, radius = 5f, center = Offset(x, size.height - 5f))
                }
            }
        }
        // Bound times, aligned under their markers.
        Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 6.dp)) {
            Text(clock(onset), style = NoopType.captionNumber, color = Palette.textPrimary, modifier = Modifier.weight(1f))
            Text(clock(wake), style = NoopType.captionNumber, color = Palette.textPrimary, textAlign = TextAlign.End)
        }
        if (compact) {
            Text(
                "No heart-rate trace for this night — nights recorded from the strap draw one here.",
                style = NoopType.footnote, color = Palette.textTertiary,
                modifier = Modifier.padding(start = 40.dp, top = 8.dp),
            )
        }
    }
}

// MARK: - Stage strip: where in the night a stage fell (or how much of it, untimed)

/**
 * A hatched track the width of the night. With timed spans, blocks land where the stage
 * occurred; without, one block from the left shows the stage's share of time in bed.
 * A dashed bracket marks the wearer's typical range for that stage (as a share of the
 * night). The selected stage fills in its colour; others in neutral grey.
 */
@Composable
internal fun StageStrip(
    stage: String,
    spans: List<StageSpan>?,
    onset: Long,
    wake: Long,
    stageMinutes: Double,
    totalMinutes: Double,
    typicalRange: ClosedFloatingPointRange<Double>?,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val fill = if (selected) sleepStageColor(stage) else Palette.sleepAwake.copy(alpha = 0.85f)
    Canvas(modifier = modifier.fillMaxWidth().height(22.dp)) {
        val r = CornerRadius(5f, 5f)
        // Track + diagonal hatching.
        drawRoundRect(Palette.surfaceInset, cornerRadius = r)
        clipRect {
            var x = -size.height
            while (x < size.width + size.height) {
                drawLine(
                    Palette.hairlineStrong.copy(alpha = 0.55f),
                    Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.2f,
                )
                x += 9f
            }
        }
        val night = (wake - onset).coerceAtLeast(60L).toDouble()
        if (spans != null) {
            spans.filter { it.stage == stage }.forEach { s ->
                val l = ((s.start - onset) / night * size.width).toFloat().coerceIn(0f, size.width)
                val rr = ((s.end - onset) / night * size.width).toFloat().coerceIn(0f, size.width)
                drawRoundRect(fill, topLeft = Offset(l, 0f), size = Size((rr - l).coerceAtLeast(3f), size.height), cornerRadius = r)
            }
        } else if (totalMinutes > 0.0) {
            val w = (stageMinutes / totalMinutes * size.width).toFloat().coerceIn(0f, size.width)
            if (w > 0f) drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
        }
        // Typical-range bracket.
        if (typicalRange != null && totalMinutes > 0.0) {
            val lo = (typicalRange.start / totalMinutes * size.width).toFloat().coerceIn(0f, size.width)
            val hi = (typicalRange.endInclusive / totalMinutes * size.width).toFloat().coerceIn(0f, size.width)
            drawRect(
                Palette.textPrimary.copy(alpha = 0.9f),
                topLeft = Offset(lo, 1f), size = Size((hi - lo).coerceAtLeast(4f), size.height - 2f),
                style = Stroke(width = 1.5f, pathEffect = DASH),
            )
        }
    }
}

// MARK: - Week charts

/** Two-line day labels under a 7-column chart, with the latest column emphasised. */
@Composable
internal fun WeekLabels(labels: List<DayLabel>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            val last = i == labels.lastIndex
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.weekday, style = NoopType.captionNumber, color = if (last) Palette.textPrimary else Palette.textSecondary)
                Text(l.dayOfMonth, style = NoopType.captionNumber, color = if (last) Palette.textPrimary else Palette.textSecondary)
            }
        }
    }
}

/** Highlight pill behind the latest column, shared by every week chart. */
private fun DrawScope.drawTodayPill(columns: Int) {
    if (columns <= 0) return
    val slot = size.width / columns
    val w = slot * 0.62f
    drawRoundRect(
        Palette.surfaceOverlay,
        topLeft = Offset(size.width - slot / 2f - w / 2f, 0f),
        size = Size(w, size.height),
        cornerRadius = CornerRadius(8f, 8f),
    )
}

private fun DrawScope.drawGrid(rows: Int = 4) {
    for (i in 0..rows) {
        val y = size.height * i / rows
        drawLine(Palette.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

private fun DrawScope.label(
    m: TextMeasurer, text: String, x: Float, y: Float, color: Color, fontSp: Float = 12f, above: Boolean = true,
) {
    val style = TextStyle(fontSize = fontSp.sp, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    val r = m.measure(text, style)
    val top = if (above) y - r.size.height - 4f else y + 4f
    drawText(r, topLeft = Offset(x - r.size.width / 2f, top.coerceIn(0f, size.height - r.size.height)))
}

/**
 * 7 vertical bars with the value printed above each. `max` fixes the scale (100 for %),
 * else the tallest bar sets it. Nulls leave a gap.
 */
@Composable
internal fun WeekBarChart(
    values: List<Double?>,
    color: Color,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    max: Double? = null,
    height: androidx.compose.ui.unit.Dp = 170.dp,
) {
    val m = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val n = values.size
        if (n == 0) return@Canvas
        drawTodayPill(n)
        val top = 22f
        val plotH = size.height - top
        val scale = (max ?: values.filterNotNull().maxOrNull() ?: 1.0).coerceAtLeast(1e-6)
        val slot = size.width / n
        val bw = slot * 0.36f
        drawLine(Palette.hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val h = ((v / scale).coerceIn(0.0, 1.0) * plotH).toFloat()
            val cx = slot * i + slot / 2f
            drawRoundRect(
                color,
                topLeft = Offset(cx - bw / 2f, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(3f, 3f),
            )
            label(m, format(v), cx, size.height - h, color)
        }
    }
}

/**
 * Two series on a shared vertical scale, each with hollow markers and printed values:
 * `a` labels sit above their points, `b` labels below, so the two never collide.
 */
@Composable
internal fun WeekDualLineChart(
    a: List<Double?>,
    b: List<Double?>,
    aColor: Color,
    bColor: Color,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 150.dp,
) {
    val m = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val n = maxOf(a.size, b.size)
        if (n == 0) return@Canvas
        drawTodayPill(n)
        val all = (a + b).filterNotNull()
        if (all.isEmpty()) return@Canvas
        val lo = all.min(); val hi = all.max()
        val padV = ((hi - lo).takeIf { it > 0 } ?: 1.0) * 0.35
        val yMin = lo - padV; val yMax = hi + padV
        val slot = size.width / n
        fun x(i: Int) = slot * i + slot / 2f
        fun y(v: Double) = ((yMax - v) / (yMax - yMin) * size.height).toFloat()

        fun series(vals: List<Double?>, c: Color, above: Boolean) {
            var prev: Offset? = null
            vals.forEachIndexed { i, v ->
                if (v == null) { prev = null; return@forEachIndexed }
                val p = Offset(x(i), y(v))
                prev?.let { drawLine(c.copy(alpha = 0.7f), it, p, strokeWidth = 3f, cap = StrokeCap.Round) }
                prev = p
            }
            vals.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val p = Offset(x(i), y(v))
                drawCircle(Palette.surfaceRaised, radius = 8f, center = p)
                drawCircle(c, radius = 8f, center = p, style = Stroke(width = 4f))
                label(m, format(v), p.x, if (above) p.y - 10f else p.y + 10f, c, above = above)
            }
        }
        series(a, aColor, above = true)
        series(b, bColor, above = false)
    }
}

/** Stacked bars (e.g. deep + REM hours) with the total printed above each column. */
@Composable
internal fun WeekStackedBarChart(
    parts: List<List<Double?>>,
    colors: List<Color>,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 160.dp,
) {
    val m = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val n = parts.firstOrNull()?.size ?: 0
        if (n == 0) return@Canvas
        drawTodayPill(n)
        val totals = (0 until n).map { i -> parts.mapNotNull { it[i] }.takeIf { it.isNotEmpty() }?.sum() }
        val scale = (totals.filterNotNull().maxOrNull() ?: 1.0).coerceAtLeast(1e-6)
        val top = 22f
        val plotH = size.height - top
        val slot = size.width / n
        val bw = slot * 0.36f
        drawLine(Palette.hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        for (i in 0 until n) {
            val total = totals[i] ?: continue
            var yCursor = size.height
            parts.forEachIndexed { pi, series ->
                val v = series[i] ?: return@forEachIndexed
                val h = ((v / scale) * plotH).toFloat()
                drawRect(colors[pi], topLeft = Offset(slot * i + slot / 2f - bw / 2f, yCursor - h), size = Size(bw, h))
                yCursor -= h
            }
            label(m, format(total), slot * i + slot / 2f, yCursor, Palette.textPrimary)
        }
    }
}

/**
 * Bed-to-wake bars against a 19:00 → 11:00 clock axis. Each column is one night; the
 * latest is in sleep blue and prints its onset/wake times; dashed lines trace the
 * wearer's mean bed and wake times across the window.
 */
@Composable
internal fun ConsistencyChart(
    nights: List<Pair<Long, Long>?>,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    height: androidx.compose.ui.unit.Dp = 200.dp,
) {
    val m = rememberTextMeasurer()
    val axisLabels = listOf("19:00", "23:00", "03:00", "07:00", "11:00")
    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(0.13f).height(height),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            axisLabels.forEach { Text(it, style = NoopType.captionNumber, color = Palette.textTertiary) }
        }
        Canvas(modifier = Modifier.weight(1f).height(height)) {
            val n = nights.size
            if (n == 0) return@Canvas
            drawTodayPill(n)
            drawGrid(rows = 4)
            // Minutes since 19:00 the previous evening (0 .. 960).
            fun minutesOnAxis(ts: Long): Double {
                val t = Instant.ofEpochSecond(ts).atZone(zone)
                val mins = t.hour * 60.0 + t.minute
                return if (mins >= 19 * 60) mins - 19 * 60 else mins + 5 * 60
            }
            fun y(mins: Double) = (mins / 960.0 * size.height).toFloat().coerceIn(0f, size.height)
            val slot = size.width / n
            val bw = slot * 0.36f
            val onsets = ArrayList<Double>(); val wakes = ArrayList<Double>()
            nights.forEachIndexed { i, night ->
                if (night == null) return@forEachIndexed
                val (onset, wake) = night
                val yo = y(minutesOnAxis(onset)); val yw = y(minutesOnAxis(wake))
                onsets.add(minutesOnAxis(onset)); wakes.add(minutesOnAxis(wake))
                val last = i == n - 1
                val c = if (last) Palette.sleepBlue else Palette.textTertiary.copy(alpha = 0.6f)
                drawRoundRect(
                    c, topLeft = Offset(slot * i + slot / 2f - bw / 2f, minOf(yo, yw)),
                    size = Size(bw, (maxOf(yo, yw) - minOf(yo, yw)).coerceAtLeast(4f)), cornerRadius = CornerRadius(4f, 4f),
                )
                if (last) {
                    label(m, clock(onset, zone), slot * i + slot / 2f, yo, Palette.sleepBlue, above = true)
                    label(m, clock(wake, zone), slot * i + slot / 2f, yw, Palette.sleepBlue, above = false)
                }
            }
            if (onsets.size >= 2) {
                listOf(onsets.average(), wakes.average()).forEach { mins ->
                    val yy = y(mins)
                    drawLine(Palette.textSecondary, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.5f, pathEffect = DASH)
                }
            }
        }
    }
}

/** Horizontal efficiency strip: asleep blocks over a hatched in-bed track. */
@Composable
internal fun WeekEfficiencyRow(values: List<Double?>, modifier: Modifier = Modifier) {
    WeekBarChart(values, Palette.sleepBlue, { "${it.toInt()}%" }, modifier, max = 100.0, height = 150.dp)
}
