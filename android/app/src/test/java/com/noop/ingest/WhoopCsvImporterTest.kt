package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Parser-level tests against synthetic rows shaped exactly like a real WHOOP export
 * (headers verbatim; values plausible). Pure JVM — no Android, no database.
 */
class WhoopCsvImporterTest {

    // Headers copied from a real 2026 export.
    private val cyclesHeader =
        "Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Resting heart rate (bpm)," +
            "Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,Energy burned (cal)," +
            "Max HR (bpm),Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm)," +
            "Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min)," +
            "REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %"

    private val sleepsHeader =
        "Cycle start time,Cycle end time,Cycle timezone,Sleep onset,Wake onset,Sleep performance %," +
            "Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min)," +
            "Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min)," +
            "Sleep efficiency %,Sleep consistency %,Nap"

    private val workoutsHeader =
        "Cycle start time,Cycle end time,Cycle timezone,Workout start time,Workout end time,Duration (min)," +
            "Activity name,Activity Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),HR Zone 1 %," +
            "HR Zone 2 %,HR Zone 3 %,HR Zone 4 %,HR Zone 5 %,GPS enabled"

    private val journalHeader = "Cycle start time,Cycle end time,Cycle timezone,Question text,Answered yes,Notes"

    // Rows: the open current cycle, a closed cycle, a cycle that ends after midnight in
    // UTC+2, and the very first (sleepless) cycle in a "UTCZ" zone.
    private val cyclesCsv = listOf(
        cyclesHeader,
        "2026-09-10 22:49:18,,UTC+01:00,51,61,60,33.13,97.75,,,,,2026-09-10 22:49:18,2026-09-11 07:30:43,74,16.6,376,521,147,167,62,145,481,0,72,92",
        "2026-09-09 22:53:21,2026-09-10 22:49:18,UTC+01:00,72,59,68,32.98,95.79,13.7,2270,179,76,2026-09-09 22:53:21,2026-09-10 07:17:23,100,16.7,479,504,206,151,122,25,456,0,95,91",
        "2026-05-23 23:06:53,2026-05-25 00:12:52,UTC+02:00,53,65,53,34.08,96.05,8.3,2195,144,81,2026-05-23 23:06:53,2026-05-24 06:47:35,84,17.5,444,460,151,147,146,16,508,37,96,76",
        "2023-09-16 00:00:00,2023-09-16 23:33:50,UTCZ,,,,,,4.8,827,117,78,,,,,,,,,,,,,,",
    ).joinToString("\n")

    private val sleepsCsv = listOf(
        sleepsHeader,
        "2026-09-10 22:49:18,,UTC+01:00,2026-09-10 22:49:18,2026-09-11 07:30:43,74,16.6,376,521,147,167,62,145,481,0,72,92,false",
        "2026-09-09 22:53:21,2026-09-10 22:49:18,UTC+01:00,2026-09-10 14:02:00,2026-09-10 14:41:00,,16.9,35,39,30,5,0,4,456,0,90,91,true",
        "2026-09-09 22:53:21,2026-09-10 22:49:18,UTC+01:00,2026-09-09 22:53:21,2026-09-10 07:17:23,100,16.7,479,504,206,151,122,25,456,0,95,91,false",
    ).joinToString("\n")

    private val workoutsCsv = listOf(
        workoutsHeader,
        "2026-09-09 22:53:21,2026-09-10 22:49:18,UTC+01:00,2026-09-10 12:50:00,2026-09-10 13:33:59,43,Functional Fitness,13.1,370.0,179,131,51,17,12,11,1,false",
    ).joinToString("\n")

    private val journalCsv = listOf(
        journalHeader,
        ",,,Consumed caffeine?,false,",
        "2026-09-09 22:53:21,2026-09-10 22:49:18,UTC+01:00,Consumed caffeine?,true,",
        "2026-09-09 22:53:21,2026-09-10 22:49:18,UTC+01:00,Have any alcoholic drinks?,false,",
    ).joinToString("\n")

    private fun parseAll() = WhoopCsvImporter.parse(
        mapOf(
            "physiological_cycles.csv" to cyclesCsv.toByteArray(),
            "sleeps.csv" to sleepsCsv.toByteArray(),
            "workouts.csv" to workoutsCsv.toByteArray(),
            "journal_entries.csv" to journalCsv.toByteArray(),
        ),
    )

    // MARK: header + time primitives

    @Test
    fun headersNormalizeLikeTheExport() {
        assertEquals("deep_sws_duration_min", HeaderNorm.normalize("Deep (SWS) duration (min)"))
        assertEquals("blood_oxygen_pct", HeaderNorm.normalize("Blood oxygen %"))
        assertEquals("answered_yes", HeaderNorm.normalize("Answered yes"))
        assertEquals("heart_rate_variability_ms", HeaderNorm.normalize("Heart rate variability (ms)"))
    }

    @Test
    fun timezoneTokensParse() {
        assertEquals(0, WhoopTime.tzOffsetMinutes("UTCZ"))
        assertEquals(60, WhoopTime.tzOffsetMinutes("UTC+01:00"))
        assertEquals(120, WhoopTime.tzOffsetMinutes("UTC+02:00"))
        assertEquals(-300, WhoopTime.tzOffsetMinutes("UTC-05:00"))
    }

    @Test
    fun timestampsHonourTheCycleOffset() {
        val bst = WhoopTime.parseEpochSeconds("2026-09-10 22:49:18", 60)!!
        val utc = WhoopTime.parseEpochSeconds("2026-09-10 22:49:18", 0)!!
        assertEquals(3600L, utc - bst)
    }

    // MARK: day attribution

    @Test
    fun cyclesAreFiledUnderTheWakeDay() {
        val byDay = parseAll().daily.associateBy { it.day }
        // Open current cycle: starts Sep 10 evening, wakes Sep 11 → Sep 11.
        assertEquals(51.0, byDay["2026-09-11"]?.recovery)
        assertEquals(61, byDay["2026-09-11"]?.restingHr)
        assertEquals(60.0, byDay["2026-09-11"]?.avgHrv)
        assertEquals(376.0, byDay["2026-09-11"]?.totalSleepMin)
        assertNull("strain is not scored yet on the open cycle", byDay["2026-09-11"]?.strain)
        // Closed cycle → Sep 10 with its strain.
        assertEquals(72.0, byDay["2026-09-10"]?.recovery)
        assertEquals(13.7, byDay["2026-09-10"]?.strain)
        // Nothing should have landed on the cycle START dates.
        assertNull(byDay["2026-09-09"])
    }

    @Test
    fun cycleEndingAfterMidnightStaysOnTheWakeDay() {
        val byDay = parseAll().daily.associateBy { it.day }
        assertEquals(53.0, byDay["2026-05-24"]?.recovery)
        assertNull(byDay["2026-05-25"])
    }

    @Test
    fun sleeplessCycleUsesItsMidpointDay() {
        val byDay = parseAll().daily.associateBy { it.day }
        val first = byDay["2023-09-16"]
        assertNotNull(first)
        assertEquals(4.8, first!!.strain)
        assertNull(first.recovery)
    }

    @Test
    fun cycleDayFallbacks() {
        val z = ZoneOffset.UTC
        fun ts(d: String, t: String) = java.time.LocalDateTime.parse("${d}T$t").toEpochSecond(z)
        // Wake wins over everything.
        assertEquals("2026-01-02", WhoopCsvImporter.cycleDay(ts("2026-01-02", "07:00:00"), ts("2026-01-01", "22:00:00"), ts("2026-01-03", "00:30:00"), 0))
        // No wake: midpoint of a 22:00 → 00:30(+2d) cycle lands on the middle day.
        assertEquals("2026-01-02", WhoopCsvImporter.cycleDay(null, ts("2026-01-01", "22:00:00"), ts("2026-01-03", "00:30:00"), 0))
        // Only a start.
        assertEquals("2026-01-01", WhoopCsvImporter.cycleDay(null, ts("2026-01-01", "22:00:00"), null, 0))
        // Nothing.
        assertNull(WhoopCsvImporter.cycleDay(null, null, null, 0))
    }

    // MARK: sleeps

    @Test
    fun mainSleepFoldsIntoTheWakeDayAndNapsStaySessionsOnly() {
        val parsed = parseAll()
        // 3 sessions (two mains + one nap) …
        assertEquals(3, parsed.sleepSessions.size)
        // … but the nap never becomes a daily row, and daily rows key on the wake day.
        val sleepOnlyDays = WhoopCsvImporter.parse(mapOf("sleeps.csv" to sleepsCsv.toByteArray())).daily
        assertEquals(setOf("2026-09-10", "2026-09-11"), sleepOnlyDays.map { it.day }.toSet())
        assertEquals(376.0, sleepOnlyDays.first { it.day == "2026-09-11" }.totalSleepMin)
        // Nap session window is 39 minutes in bed.
        val nap = parsed.sleepSessions.minByOrNull { it.endTs - it.startTs }!!
        assertEquals(39L * 60L, nap.endTs - nap.startTs)
    }

    @Test
    fun sleepStagesRoundTripAsJson() {
        val main = parseAll().sleepSessions.maxByOrNull { it.startTs }!!
        val json = main.stagesJSON!!
        assertTrue(json.contains("\"stage\":\"deep\""))
        assertTrue(json.contains("167"))
    }

    // MARK: skin temperature → baseline deviation

    @Test
    fun skinTempBecomesADeviationNotAnAbsolute() {
        for (d in parseAll().daily) {
            d.skinTempDevC?.let { assertTrue("dev $it should be small", kotlin.math.abs(it) < 2.0) }
        }
    }

    @Test
    fun skinTempBaselineIsRollingAfterAWeek() {
        val days = (1..40).associate { i ->
            val day = LocalDate.of(2026, 1, 1).plusDays((i - 1).toLong()).toString()
            day to if (i == 40) 33.6 else 33.0
        }
        val dev = WhoopCsvImporter.skinTempDeviations(days)
        assertEquals(0.0, dev["2026-01-01"]!!, 1e-9)       // early days: vs first-month mean (all 33.0)
        assertEquals(0.6, dev["2026-02-09"]!!, 1e-9)       // the spike vs a 30-day baseline of 33.0
    }

    // MARK: workouts

    @Test
    fun workoutsCarryZonesAndKcal() {
        val w = parseAll().workouts.single()
        assertEquals("Functional Fitness", w.sport)
        assertEquals("my-whoop", w.source)
        assertEquals(370.0, w.energyKcal)
        assertEquals(179, w.maxHr)
        assertEquals(43L * 60L + 59L, w.endTs - w.startTs)
        assertTrue(w.zonesJSON!!.contains("\"zone1\":51"))
        // Started 12:50 BST == 11:50 UTC.
        assertEquals("2026-09-10T11:50:00Z", java.time.Instant.ofEpochSecond(w.startTs).toString())
    }

    // MARK: journal

    @Test
    fun journalReadsTheAnsweredYesColumnAndSkipsUnfiledRows() {
        val j = parseAll().journal
        assertEquals(2, j.size) // the cycle-less prompt is dropped
        val caffeine = j.first { it.question == "Consumed caffeine?" }
        assertTrue(caffeine.answeredYes)
        assertEquals("2026-09-10", caffeine.day) // same day the cycle's recovery is filed under
        assertFalse(j.first { it.question == "Have any alcoholic drinks?" }.answeredYes)
    }

    // MARK: extra series

    @Test
    fun cycleExtrasAreKeptAsSeries() {
        val s = parseAll().series
        fun v(day: String, key: String) = s.firstOrNull { it.day == day && it.key == key }?.value
        assertEquals(2270.0, v("2026-09-10", "energyBurnedKcal"))
        assertEquals(456.0, v("2026-09-10", "sleepNeedMin"))
        assertEquals(74.0, v("2026-09-11", "sleepPerformancePct"))
        assertEquals(33.13, v("2026-09-11", "skinTempC"))
        assertNull(v("2026-09-11", "energyBurnedKcal")) // not scored yet on the open cycle
    }

    @Test
    fun summarySpanCoversEverything() {
        val p = parseAll()
        assertEquals("2023-09-16", p.firstDay)
        assertEquals("2026-09-11", p.lastDay)
        assertFalse(p.isEmpty)
    }
}
