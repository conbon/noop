package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.noop.ble.LiveState
import com.noop.data.DailyMetric
import com.noop.data.HrSample
import com.noop.data.SleepSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos

/**
 * JVM screenshot renders of the main screens with deterministic synthetic data.
 * Robolectric native graphics rasterizes real pixels; PNGs land in
 * app/build/outputs/roborazzi/.
 */
/**
 * Full-page captures use a very tall viewport so nothing scrolls and every card renders.
 * (Robolectric also spends ~50 s per test waiting for a scrolling page to settle; the
 * tall viewport sidesteps that.) Only [shell] uses real phone dimensions.
 */
private const val TALL_PAGE = "w411dp-h3200dp-420dpi"

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TALL_PAGE)
class ScreenScreenshotTest {

    private val days: List<DailyMetric> = fixtureDays()

    @Test
    fun today() {
        captureRoboImage("build/outputs/roborazzi/today.png") {
            NoopTheme {
                TodayContent(
                    today = days.last(),
                    alert = null,
                    days = days,
                )
            }
        }
    }

    @Test
    fun todayEmpty() {
        captureRoboImage("build/outputs/roborazzi/today_empty.png") {
            NoopTheme {
                TodayContent(today = null, alert = null, days = emptyList())
            }
        }
    }

    @Test
    fun sleep() {
        val sessions = fixtureSessions(days)
        System.err.println("TIMING sleep: before capture ${System.currentTimeMillis()}")
        captureRoboImage("build/outputs/roborazzi/sleep.png") {
            NoopTheme {
                SleepContent(
                    days = days,
                    sessions = sessions,
                    hr = fixtureHr(sessions.last()),
                    series = mapOf("sleepNeedMin" to days.associate { it.day to 470.0 + 20 * cos(it.day.hashCode() % 7 / 2.0) }),
                )
            }
        }
        System.err.println("TIMING sleep: after capture ${System.currentTimeMillis()}")
    }

    /** The whole Sleep page with a strap-scored latest night (timed stages + HR trace). */
    @Test
    fun sleepFullPage() {
        val sessions = fixtureSessions(days)
        System.err.println("TIMING sleepFullPage: before capture ${System.currentTimeMillis()}")
        captureRoboImage("build/outputs/roborazzi/sleep_full.png") {
            NoopTheme {
                SleepContent(
                    days = days, sessions = sessions, hr = fixtureHr(sessions.last()),
                    series = mapOf("sleepNeedMin" to days.associate { it.day to 470.0 + 20 * cos(it.day.hashCode() % 7 / 2.0) }),
                )
            }
        }
        System.err.println("TIMING sleepFullPage: after capture ${System.currentTimeMillis()}")
    }

    /** Totals-only night (the WHOOP-import shape) — exercises the durations fallback. */
    @Test
    fun sleepImportedNight() {
        val sessions = fixtureSessions(days, timedLatest = false)
        captureRoboImage("build/outputs/roborazzi/sleep_imported.png") {
            NoopTheme { SleepContent(days = days, sessions = sessions, hr = emptyList()) }
        }
    }

    @Test
    fun trends() {
        captureRoboImage("build/outputs/roborazzi/trends.png") {
            NoopTheme { TrendsContent(days = fixtureDays(120)) }
        }
    }

    @Test
    fun health() {
        captureRoboImage("build/outputs/roborazzi/health.png") {
            NoopTheme {
                HealthContent(
                    live = LiveState(
                        connected = true,
                        bonded = true,
                        heartRate = 64,
                        rr = listOf(920, 950, 910, 980, 940, 900, 960, 930, 970, 915),
                        batteryPct = 76.0,
                    ),
                    today = days.last(),
                )
            }
        }
    }

    /** Phone-sized frame: Today behind the floating bottom nav, as a user sees it. */
    @Test
    @Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun shell() {
        captureRoboImage("build/outputs/roborazzi/shell.png") {
            NoopTheme {
                Column(Modifier.fillMaxSize().background(Palette.surfaceBase)) {
                    Box(Modifier.weight(1f)) {
                        TodayContent(today = days.last(), alert = null, days = days)
                    }
                    NoopBottomBar(
                        current = Destination.Today,
                        onNavigate = {},
                        onMore = {},
                    )
                }
            }
        }
    }
}

/**
 * One sleep session per fixture day, waking on that day: bed ~23:00 ± 40 min, wake
 * ~07:15 ± 30 min. The latest night carries TIMED stage spans (the on-device stager's
 * `{start,end,stage}` shape) unless [timedLatest] is false; earlier nights carry the
 * WHOOP-import totals shape `{stage,min}`.
 */
internal fun fixtureSessions(days: List<DailyMetric>, timedLatest: Boolean = true): List<SleepSession> {
    val zone = ZoneId.systemDefault()
    return days.mapIndexed { i, d ->
        val date = LocalDate.parse(d.day)
        val wake = date.atTime(7, 15).plusMinutes((30 * cos(i * 0.9)).toLong()).atZone(zone).toEpochSecond()
        val onset = date.minusDays(1).atTime(23, 0).plusMinutes((40 * cos(i * 1.3)).toLong()).atZone(zone).toEpochSecond()
        val timed = timedLatest && i == days.lastIndex
        SleepSession(
            deviceId = "my-whoop",
            startTs = onset,
            endTs = wake,
            efficiency = d.efficiency,
            stagesJSON = if (timed) timedStagesJson(onset, wake) else totalsStagesJson(d),
        )
    }
}

/** A plausible architecture: light → deep cycles early, REM later, brief wakes throughout. */
private fun timedStagesJson(onset: Long, wake: Long): String {
    val pattern = listOf(
        "awake" to 12, "light" to 25, "deep" to 45, "light" to 20, "awake" to 6, "deep" to 40,
        "light" to 30, "rem" to 18, "light" to 25, "deep" to 30, "light" to 20, "awake" to 10,
        "rem" to 25, "light" to 35, "rem" to 30, "awake" to 14, "light" to 30, "rem" to 22,
        "awake" to 20, "light" to 30, "awake" to 25,
    )
    val totalMin = pattern.sumOf { it.second }.toDouble()
    val scale = (wake - onset) / (totalMin * 60.0)
    var t = onset
    val arr = org.json.JSONArray()
    for ((stage, min) in pattern) {
        val end = t + (min * 60 * scale).toLong()
        arr.put(org.json.JSONObject().put("start", t).put("end", end).put("stage", stage))
        t = end
    }
    return arr.toString()
}

private fun totalsStagesJson(d: DailyMetric): String = org.json.JSONArray().apply {
    put(org.json.JSONObject().put("stage", "light").put("min", d.lightMin))
    put(org.json.JSONObject().put("stage", "deep").put("min", d.deepMin))
    put(org.json.JSONObject().put("stage", "rem").put("min", d.remMin))
}.toString()

/** One HR sample a minute across the session: ~52–72 bpm asleep, spiky around wakes. */
internal fun fixtureHr(s: SleepSession): List<HrSample> {
    val slack = ((s.endTs - s.startTs) * 0.06).toLong()
    val out = ArrayList<HrSample>()
    var t = s.startTs - slack
    var i = 0
    while (t <= s.endTs + slack) {
        val frac = (t - s.startTs).toDouble() / (s.endTs - s.startTs)
        val outside = frac < 0.0 || frac > 1.0
        val base = if (outside) 80.0 else 62.0 - 8 * cos(frac * 6.3) - 4 * frac
        val jitter = 3.5 * cos(i * 1.7) + 2.5 * cos(i * 0.37)
        val spike = if (i % 53 == 0 && !outside) 22.0 else 0.0
        out.add(HrSample("my-whoop", t, (base + jitter + spike).toInt().coerceIn(40, 130)))
        t += 60
        i++
    }
    return out
}

/**
 * 30 deterministic days ending today. Values move on slow cosine waves so charts
 * show believable structure (no RNG: reruns produce identical pixels).
 */
internal fun fixtureDays(count: Int = 30): List<DailyMetric> {
    val start = LocalDate.now().minusDays((count - 1).toLong())
    return (0 until count).map { i ->
        val w = i / 6.0
        val recovery = 62 + 28 * cos(w) // 34..90 — crosses all three tiers
        val strain = 10.5 + 6.5 * cos(w + 2.1) // 4..17
        val sleep = 420 + 60 * cos(w + 1.2) // 6h..8h
        DailyMetric(
            deviceId = "my-whoop",
            day = start.plusDays(i.toLong()).toString(),
            totalSleepMin = sleep,
            efficiency = 86.0 + 6 * cos(w + 0.4),
            deepMin = sleep * 0.22,
            remMin = sleep * 0.24,
            lightMin = sleep * 0.40,
            disturbances = 6 + (3 * cos(w)).toInt(),
            restingHr = (58 + 4 * cos(w + 2.6)).toInt(),
            avgHrv = 74.0 + 16 * cos(w - 0.3),
            recovery = recovery,
            strain = strain,
            exerciseCount = if (i % 3 == 0) 1 else 0,
            spo2Pct = 97.0 + cos(w) * 1.2,
            skinTempDevC = 0.15 * cos(w + 1.8),
            respRateBpm = 15.8 + 0.9 * cos(w + 0.9),
        )
    }
}
