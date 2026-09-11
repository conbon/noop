package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the parser over a REAL export on the developer's machine. Skipped unless
 * `WHOOP_EXPORT_DIR` points at a folder holding the four WHOOP CSVs (any filename that
 * ends with the canonical name is accepted). Nothing personal is committed: the
 * assertions below check invariants and print a summary for eyeballing.
 */
class RealWhoopExportSanityTest {

    private val dir: File? = System.getenv("WHOOP_EXPORT_DIR")?.let(::File)?.takeIf { it.isDirectory }

    private fun load(): Map<String, ByteArray> {
        val wanted = listOf("physiological_cycles.csv", "sleeps.csv", "workouts.csv", "journal_entries.csv")
        val out = LinkedHashMap<String, ByteArray>()
        for (f in dir!!.listFiles().orEmpty()) {
            val name = wanted.firstOrNull { f.name.lowercase().endsWith(it) } ?: continue
            out[name] = f.readBytes()
        }
        return out
    }

    @Test
    fun realExportParsesCoherently() {
        assumeTrue("set WHOOP_EXPORT_DIR to run against a real export", dir != null)
        val csv = load()
        assumeTrue(csv.size == 4)

        val p = WhoopCsvImporter.parse(csv)

        // Every day is unique and ISO-shaped; every row has at least one score or sleep field.
        val days = p.daily.map { it.day }
        assertEquals(days.size, days.toSet().size)
        assertTrue(days.all { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) })

        // Skin temperature has been converted to a deviation: no absolute ~33 °C values remain.
        val skin = p.daily.mapNotNull { it.skinTempDevC }
        assertTrue(skin.isNotEmpty())
        assertTrue("max |dev| = ${skin.maxOf { kotlin.math.abs(it) }}", skin.all { kotlin.math.abs(it) < 3.0 })
        assertTrue("mean dev should hover near 0", kotlin.math.abs(skin.average()) < 0.15)

        // Recovery stays inside 0..100, strain inside 0..21.
        assertTrue(p.daily.mapNotNull { it.recovery }.all { it in 0.0..100.0 })
        assertTrue(p.daily.mapNotNull { it.strain }.all { it in 0.0..21.0 })

        // Journal answers are not all false (the Answered-yes column is being read).
        val yes = p.journal.count { it.answeredYes }
        assertTrue("expected some yes answers, got $yes of ${p.journal.size}", yes > 0)

        // Sleep sessions: each session's day-of-wake exists among the daily rows.
        assertTrue(p.sleepSessions.isNotEmpty())

        // The most recent cycle in the file is filed under the most recent wake date.
        val latest = p.daily.maxByOrNull { it.day }!!
        println("=== REAL EXPORT SUMMARY ===")
        println("daily=${p.daily.size} sleeps=${p.sleepSessions.size} workouts=${p.workouts.size} journal=${p.journal.size} series=${p.series.size}")
        println("span ${p.firstDay} → ${p.lastDay}")
        println("latest day ${latest.day}: recovery=${latest.recovery} rhr=${latest.restingHr} hrv=${latest.avgHrv} " +
            "sleepMin=${latest.totalSleepMin} spo2=${latest.spo2Pct} resp=${latest.respRateBpm} skinDev=${latest.skinTempDevC} strain=${latest.strain}")
        val prev = p.daily.filter { it.day < latest.day }.maxByOrNull { it.day }!!
        println("previous day ${prev.day}: recovery=${prev.recovery} strain=${prev.strain} sleepMin=${prev.totalSleepMin}")
        println("skin dev: min=${skin.minOrNull()} max=${skin.maxOrNull()} mean=${"%.3f".format(skin.average())}")
        println("journal yes=$yes / ${p.journal.size}")
        println("series keys=${p.series.map { it.key }.toSet()}")
    }
}
