package com.noop.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.noop.data.DailyMetric
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.cos

/**
 * JVM screenshot renders of the main screens with deterministic synthetic data.
 * Robolectric native graphics rasterizes real pixels; PNGs land in
 * app/build/outputs/roborazzi/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
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
