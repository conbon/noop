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
import org.junit.Before
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
 *
 * Page captures use a very tall viewport so nothing scrolls and every card renders;
 * only [ScreenScreenshotTest.shell] uses real phone dimensions. Motion is switched off
 * for the run: an infinite transition left running by one test keeps the Compose
 * runtime busy and makes every later capture wait out its idle timeout.
 */
private const val TALL_PAGE = "w411dp-h3200dp-420dpi"

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TALL_PAGE)
class ScreenScreenshotTest {

    private val days: List<DailyMetric> = fixtureDays()

    @Before
    fun stillFrames() { Motion.animationsEnabled = false }

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
    }

    /** The whole Sleep page with a strap-scored latest night (timed stages + HR trace). */
    @Test
    fun sleepFullPage() {
        val sessions = fixtureSessions(days)
        captureRoboImage("build/outputs/roborazzi/sleep_full.png") {
            NoopTheme {
                SleepContent(
                    days = days, sessions = sessions, hr = fixtureHr(sessions.last()),
                    series = mapOf("sleepNeedMin" to days.associate { it.day to 470.0 + 20 * cos(it.day.hashCode() % 7 / 2.0) }),
                )
            }
        }
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
    fun ecg() {
        captureRoboImage("build/outputs/roborazzi/ecg.png") {
            NoopTheme {
                EcgContent(
                    live = LiveState(connected = true, bonded = true, heartRate = 62, batteryPct = 71.0),
                    family = com.noop.protocol.DeviceFamily.WHOOP5,
                    variant = com.noop.protocol.Whoop5Variant.MG,
                    snapshot = fixtureEcg(),
                    onStart = {}, onStop = {}, onClear = {}, onExport = {},
                )
            }
        }
    }

    @Test
    fun ecgIdle() {
        captureRoboImage("build/outputs/roborazzi/ecg_idle.png") {
            NoopTheme {
                EcgContent(
                    live = LiveState(connected = true, bonded = true),
                    family = com.noop.protocol.DeviceFamily.WHOOP5,
                    variant = com.noop.protocol.Whoop5Variant.UNKNOWN,
                    snapshot = null,
                    onStart = {}, onStop = {}, onClear = {}, onExport = {},
                )
            }
        }
    }

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

/**
 * A synthetic in-progress ECG recording: 4 s of a PQRST-ish waveform at 128 Hz fed through the real
 * session/decoder path, so the screen renders exactly what a live run would produce.
 */
internal fun fixtureEcg(): com.noop.protocol.EcgSession.Snapshot {
    val session = com.noop.protocol.EcgSession(startedAtMs = 1_700_000_000_000L)
    session.commandSent(com.noop.protocol.Whoop5Ecg.TOGGLE_REALTIME_FILTERED_ECG_CMD, 1)
    session.commandSent(com.noop.protocol.Whoop5Ecg.MAIN_CONTROL_ECG_DATA_GENERATION_CMD, 2)
    session.commandAnswered(com.noop.protocol.Whoop5Ecg.TOGGLE_REALTIME_FILTERED_ECG_CMD, com.noop.protocol.Whoop5EcgProbe.CommandOutcome.Success)
    session.commandAnswered(com.noop.protocol.Whoop5Ecg.MAIN_CONTROL_ECG_DATA_GENERATION_CMD, com.noop.protocol.Whoop5EcgProbe.CommandOutcome.Success)
    val hz = 128
    val beatEvery = (hz * 60 / 62.0).toInt()
    fun sample(i: Int): Int {
        val t = i % beatEvery
        val qrs = when (t) {
            in 20..23 -> -180.0
            in 24..29 -> 1800.0 * (1 - kotlin.math.abs(t - 26.5) / 3.5)
            in 30..33 -> -320.0
            else -> 0.0
        }
        val tWave = if (t in 55..85) 260.0 * kotlin.math.sin(Math.PI * (t - 55) / 30.0) else 0.0
        val pWave = if (t in 2..14) 90.0 * kotlin.math.sin(Math.PI * (t - 2) / 12.0) else 0.0
        val noise = 12.0 * cos(i * 0.7)
        return (qrs + tWave + pWave + noise).toInt()
    }
    var seq = 1
    var idx = 0
    val perPacket = 32
    val packets = 4 * hz / perPacket
    for (p in 0 until packets) {
        val samples = List(perPacket) { sample(idx + it) }
        idx += perPacket
        val header = listOf(
            3, 0x05, 1, 1, 0, 1,
            0, 1, (p * 100 / (30 * hz / perPacket)).coerceAtMost(99), 0, 62, 63,
            48, 0, 21,
            perPacket and 0xFF, (perPacket shr 8) and 0xFF,
        )
        val payload = (header + samples.flatMap { listOf(it and 0xFF, (it shr 8) and 0xFF) })
            .map { it.toByte() }.toByteArray()
        val frame = com.noop.protocol.Framing.puffinCommandFrame(cmd = 0, seq = seq++ and 0xFF, payload = payload, type = 43)
        session.feed(frame, nowMs = 1_700_000_000_000L + 400L + p * (perPacket * 1000L / hz))
    }
    return session.snapshot()
}
