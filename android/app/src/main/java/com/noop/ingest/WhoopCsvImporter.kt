package com.noop.ingest

import android.content.Context
import android.net.Uri
import com.noop.data.DailyMetric
import com.noop.data.ImportSummary
import com.noop.data.JournalEntry
import com.noop.data.MetricSeriesRow
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * Imports a WHOOP CSV export (the four-CSV bundle, zipped or loose) into the local Room store.
 *
 * This is the Android port of the macOS source of truth
 * `Packages/StrandImport/Sources/StrandImport/WhoopExportImporter.swift`
 * (with `CSVParsing.swift` ported in [CsvParser.kt]). The CSV column mapping is reproduced
 * faithfully: same columns -> same fields, same header normalization + aliases, same
 * timezone-aware date parsing, same "(cal)" == kcal unit handling, same row-skip rules.
 *
 * Differences are only in the SINK: the Swift importer returns normalized model arrays;
 * here we map those same rows onto the verified Room entities (com.noop.data) and upsert
 * through [WhoopRepository]. All WHOOP rows are written under deviceId "my-whoop".
 *
 * Recognised filenames (case-insensitive, matched anywhere in a zip tree, exactly as Swift):
 *   physiological_cycles.csv  -> DailyMetric  (master daily summary)
 *   sleeps.csv                -> SleepSession + folds sleep fields into DailyMetric
 *   workouts.csv              -> WorkoutRow(source = "my-whoop")
 *   journal_entries.csv       -> JournalEntry
 *
 * The whole pipeline is tolerant: missing files / columns / blank cells degrade gracefully.
 */
object WhoopCsvImporter {

    private const val WHOOP_DEVICE = "my-whoop"
    private const val SOURCE_LABEL = "WHOOP"

    private const val CYCLES_NAME = "physiological_cycles.csv"
    private const val SLEEPS_NAME = "sleeps.csv"
    private const val WORKOUTS_NAME = "workouts.csv"
    private const val JOURNAL_NAME = "journal_entries.csv"

    /** Per-CSV uncompressed ceiling (zip-bomb guard). Mirrors Swift maxEntryBytes = 256 MB. */
    private const val MAX_ENTRY_BYTES = 256L shl 20

    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Public entry point the UI calls.
     *
     * Accepts either a `.zip` containing the WHOOP CSVs, or a single `.csv` (routed by
     * its filename, falling back to header sniffing when the name is unrecognised).
     * Reads the SAF [uri] via the content resolver. Upserts everything via [repo] under
     * [deviceId] (defaults to "my-whoop"), then returns an [ImportSummary] keyed by table.
     */
    suspend fun importZip(
        context: Context,
        uri: Uri,
        repo: WhoopRepository,
        deviceId: String = WHOOP_DEVICE,
    ): ImportSummary {
        val csvData: Map<String, ByteArray> = try {
            loadCsvData(context, uri)
        } catch (e: Exception) {
            return ImportSummary.failure(SOURCE_LABEL, "Could not read export: ${e.message ?: "unknown error"}")
        }

        if (csvData.isEmpty()) {
            return ImportSummary.failure(
                SOURCE_LABEL,
                "No WHOOP CSVs found (expected physiological_cycles.csv, sleeps.csv, workouts.csv or journal_entries.csv)."
            )
        }

        val parsed = parse(csvData, deviceId)
        val daily = parsed.daily
        val sleepSessions = parsed.sleepSessions
        val workouts = parsed.workouts
        val journal = parsed.journal
        val series = parsed.series

        if (parsed.isEmpty) {
            return ImportSummary.failure(SOURCE_LABEL, "Export contained no usable WHOOP rows.")
        }

        repo.upsertDevice(deviceId, name = "WHOOP")
        if (daily.isNotEmpty()) repo.upsertDailyMetrics(daily)
        if (sleepSessions.isNotEmpty()) repo.upsertSleepSessions(sleepSessions)
        if (workouts.isNotEmpty()) repo.upsertWorkouts(workouts)
        if (journal.isNotEmpty()) repo.upsertJournal(journal)
        if (series.isNotEmpty()) repo.upsertMetricSeries(series)

        val counts = LinkedHashMap<String, Int>()
        if (daily.isNotEmpty()) counts["dailyMetric"] = daily.size
        if (sleepSessions.isNotEmpty()) counts["sleepSession"] = sleepSessions.size
        if (workouts.isNotEmpty()) counts["workout"] = workouts.size
        if (journal.isNotEmpty()) counts["journal"] = journal.size
        if (series.isNotEmpty()) counts["metricSeries"] = series.size

        val firstDay = parsed.firstDay
        val lastDay = parsed.lastDay

        // Journal + series are per-day detail; the headline count is the rows a user thinks in.
        val total = daily.size + sleepSessions.size + workouts.size + journal.size
        val message = buildString {
            append("Imported ")
            append(total)
            append(" WHOOP rows")
            if (firstDay != null && lastDay != null) append(" ($firstDay → $lastDay)")
            append(".")
        }

        return ImportSummary(
            source = SOURCE_LABEL,
            counts = counts,
            firstDay = firstDay,
            lastDay = lastDay,
            message = message,
        )
    }

    // MARK: - Pure parse step (no Android, no IO — unit-testable)

    /** Everything a WHOOP export yields, before it touches the database. */
    internal data class ParsedExport(
        val daily: List<DailyMetric>,
        val sleepSessions: List<SleepSession>,
        val workouts: List<WorkoutRow>,
        val journal: List<JournalEntry>,
        /** Long-format extras the DailyMetric schema has no column for (sleep need, calories, …). */
        val series: List<MetricSeriesRow>,
    ) {
        val isEmpty: Boolean
            get() = daily.isEmpty() && sleepSessions.isEmpty() && workouts.isEmpty() && journal.isEmpty()

        private val allDays: List<String>
            get() = daily.map { it.day } + journal.map { it.day } +
                sleepSessions.map { epochSecondsToDay(it.startTs) } +
                workouts.map { epochSecondsToDay(it.startTs) }

        val firstDay: String? get() = allDays.minOrNull()
        val lastDay: String? get() = allDays.maxOrNull()
    }

    /**
     * Parse `[lowercasedFilename -> rawBytes]` into model rows. Cycle-derived and
     * sleep-derived daily rows are merged on (deviceId, day): cycle fields (recovery /
     * strain / RHR / HRV / SpO2 / skin-temp / resp) win where present, sleep fields fill
     * the architecture columns. One DailyMetric per day, matching the PK.
     */
    internal fun parse(csvData: Map<String, ByteArray>, deviceId: String = WHOOP_DEVICE): ParsedExport {
        val cycleParse = csvData[CYCLES_NAME]?.let { parseCycles(CsvTable.fromData(it), deviceId) }
        val cycles = cycleParse?.daily ?: emptyList()
        val sleepParse = csvData[SLEEPS_NAME]?.let { parseSleeps(CsvTable.fromData(it), deviceId) }
        val sleepSessions = sleepParse?.sessions ?: emptyList()
        val sleepDaily = sleepParse?.daily ?: emptyList()
        val workouts = csvData[WORKOUTS_NAME]?.let { parseWorkouts(CsvTable.fromData(it), deviceId) } ?: emptyList()
        val journal = csvData[JOURNAL_NAME]?.let { parseJournal(CsvTable.fromData(it), deviceId) } ?: emptyList()

        return ParsedExport(
            daily = mergeDaily(cycles, sleepDaily),
            sleepSessions = sleepSessions,
            workouts = workouts,
            journal = journal,
            series = cycleParse?.series ?: emptyList(),
        )
    }

    /**
     * The WHOOP "day" a cycle belongs to. WHOOP files a cycle under the morning you woke
     * up: the cycle starting 22:49 on the 10th with wake at 07:30 on the 11th is the 11th.
     * Cycles routinely END just after midnight, so cycle-end date is NOT a safe fallback;
     * for sleepless cycles we use the cycle's midpoint instead, then the start.
     */
    internal fun cycleDay(wakeOnset: Long?, cycleStart: Long?, cycleEnd: Long?, tzOffsetMin: Int): String? {
        if (wakeOnset != null) return epochSecondsToDay(wakeOnset, tzOffsetMin)
        if (cycleStart != null && cycleEnd != null && cycleEnd >= cycleStart) {
            return epochSecondsToDay(cycleStart + (cycleEnd - cycleStart) / 2, tzOffsetMin)
        }
        return (cycleStart ?: cycleEnd)?.let { epochSecondsToDay(it, tzOffsetMin) }
    }

    // MARK: - Locate + load CSVs

    /**
     * Return `[lowercasedFilename -> rawBytes]` for every recognised WHOOP CSV in the input.
     * Accepts a `.zip` (iterated with [ZipInputStream], routed by base filename) or a single
     * `.csv`. Mirrors Swift `loadCSVData` filename routing.
     */
    private fun loadCsvData(context: Context, uri: Uri): Map<String, ByteArray> {
        val wanted = setOf(CYCLES_NAME, SLEEPS_NAME, WORKOUTS_NAME, JOURNAL_NAME)
        val result = LinkedHashMap<String, ByteArray>()

        // First attempt: treat as a zip. WHOOP exports are zips; this also covers a .zip Uri
        // whose displayName we cannot read. If the stream is not a valid zip, ZipInputStream
        // yields no entries and we fall through to single-CSV handling.
        val firstBytes: ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readAllCapped(MAX_ENTRY_BYTES) }
            ?: throw IllegalStateException("Could not open input stream for $uri")

        if (looksLikeZip(firstBytes)) {
            firstBytes.inputStream().use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val base = baseName(entry.name).lowercase()
                            if (base in wanted && !result.containsKey(base)) {
                                val declared = entry.size // -1 when unknown
                                if (declared <= MAX_ENTRY_BYTES) {
                                    val bytes = zis.readEntryCapped(MAX_ENTRY_BYTES)
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        result[base] = bytes
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            if (result.isNotEmpty()) return result
        }

        // Not a (useful) zip — treat the input as a single CSV. Route by display name; if the
        // name is unknown, sniff the header row to identify which WHOOP CSV it is.
        val name = displayName(context, uri)?.lowercase()
        val routed = when {
            name != null && baseName(name) in wanted -> baseName(name)
            else -> sniffCsvKind(firstBytes)
        }
        if (routed != null) {
            result[routed] = firstBytes
        }
        return result
    }

    /** Whether the leading bytes are a local-file-header zip signature ("PK"). */
    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** Identify a loose CSV by its header columns when the filename is unhelpful. */
    private fun sniffCsvKind(bytes: ByteArray): String? {
        val table = CsvTable.fromData(bytes)
        val h = table.normalizedHeaders.toHashSet()
        return when {
            "activity_name" in h || "workout_start_time" in h -> WORKOUTS_NAME
            "question_text" in h || "answered_yes_no" in h || "question" in h -> JOURNAL_NAME
            "nap" in h && ("sleep_onset" in h || "wake_onset" in h) -> SLEEPS_NAME
            "cycle_start_time" in h || "recovery_score_pct" in h || "day_strain" in h -> CYCLES_NAME
            "sleep_onset" in h || "asleep_duration_min" in h -> SLEEPS_NAME
            else -> null
        }
    }

    private fun baseName(path: String): String {
        val cleaned = path.replace('\\', '/')
        val slash = cleaned.lastIndexOf('/')
        return if (slash >= 0) cleaned.substring(slash + 1) else cleaned
    }

    private fun displayName(context: Context, uri: Uri): String? {
        // Try the OpenableColumns display name; fall back to the last path segment.
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (_: Exception) {
            // ignore — fall through
        }
        return uri.lastPathSegment
    }

    // MARK: - physiological_cycles.csv -> DailyMetric

    private class CycleParse(
        val daily: List<DailyMetric>,
        val series: List<MetricSeriesRow>,
    )

    /** Extra cycle columns kept as long-format series (no DailyMetric column exists for them). */
    private val CYCLE_SERIES: List<Pair<String, Array<String>>> = listOf(
        "sleepPerformancePct" to arrayOf("sleep_performance_pct"),
        "sleepNeedMin" to arrayOf("sleep_need_min"),
        "sleepDebtMin" to arrayOf("sleep_debt_min"),
        "sleepConsistencyPct" to arrayOf("sleep_consistency_pct"),
        "energyBurnedKcal" to arrayOf("energy_burned_cal"), // CSV "(cal)" == kcal
        "avgHrBpm" to arrayOf("average_hr_bpm", "average_heart_rate_bpm"),
        "maxHrBpm" to arrayOf("max_hr_bpm", "max_heart_rate_bpm"),
        "skinTempC" to arrayOf("skin_temp_celsius"),
    )

    /** Days of trailing history that define the personal skin-temperature baseline. */
    private const val SKIN_BASELINE_DAYS = 30
    /** Below this many prior readings the baseline falls back to the first-month mean. */
    private const val SKIN_BASELINE_MIN = 7

    private fun parseCycles(table: CsvTable, deviceId: String): CycleParse {
        val out = ArrayList<DailyMetric>(table.rows.size)
        val series = ArrayList<MetricSeriesRow>()
        // Absolute skin temperature per day; converted to a baseline deviation below.
        val absSkinByDay = HashMap<String, Double>()

        for (row in table.rows) {
            val tz = WhoopTime.tzOffsetMinutes(row["cycle_timezone"])
            val cycleStart = WhoopTime.parseEpochSeconds(row.cell("cycle_start_time"), tz)
            val cycleEnd = WhoopTime.parseEpochSeconds(row.cell("cycle_end_time"), tz)
            val wakeOnset = WhoopTime.parseEpochSeconds(row.cell("wake_onset"), tz)

            val day = cycleDay(wakeOnset, cycleStart, cycleEnd, tz) ?: continue

            val recovery = row.double("recovery_score_pct")
            val restingHr = row.double("resting_heart_rate_bpm", "resting_heart_rate")
            val avgHrv = row.double("heart_rate_variability_ms", "heart_rate_variability_rmssd_ms")
            val skinTemp = row.double("skin_temp_celsius")
            val spo2 = row.double("blood_oxygen_pct", "blood_oxygen_pct_pct")
            val strain = row.double("day_strain")
            val resp = row.double("respiratory_rate_rpm", "respiratory_rate")

            val asleepMin = row.double("asleep_duration_min")
            val lightMin = row.double("light_sleep_duration_min")
            val deepMin = row.double("deep_sws_duration_min", "deep_sleep_duration_min")
            val remMin = row.double("rem_duration_min")
            val awakeMin = row.double("awake_duration_min")
            val efficiency = row.double("sleep_efficiency_pct")

            // First row per day wins (the export is newest-first, so a duplicate wake date
            // keeps the later cycle); the same rule mergeDaily applies.
            if (skinTemp != null && day !in absSkinByDay) absSkinByDay[day] = skinTemp

            for ((key, columns) in CYCLE_SERIES) {
                row.double(*columns)?.let { series.add(MetricSeriesRow(deviceId, day, key, it)) }
            }

            out.add(
                DailyMetric(
                    deviceId = deviceId,
                    day = day,
                    totalSleepMin = asleepMin,
                    efficiency = efficiency,
                    deepMin = deepMin,
                    remMin = remMin,
                    lightMin = lightMin,
                    // "awake_duration_min" -> disturbances slot (Whoop's disturbance count is
                    // not a separate cycles column; awake minutes are the nearest faithful proxy).
                    disturbances = awakeMin?.roundToInt(),
                    restingHr = restingHr?.roundToInt(),
                    avgHrv = avgHrv,
                    recovery = recovery,
                    strain = strain,
                    exerciseCount = null, // not present in physiological_cycles.csv
                    spo2Pct = spo2,
                    skinTempDevC = null, // filled from the baseline pass below
                    respRateBpm = resp,
                )
            )
        }

        val deviation = skinTempDeviations(absSkinByDay)
        val withSkin = out.map { d -> deviation[d.day]?.let { d.copy(skinTempDevC = it) } ?: d }
        return CycleParse(withSkin, dedupeSeries(series))
    }

    /**
     * Absolute skin temperature → deviation from the wearer's own baseline, per day.
     * Baseline = mean of the previous [SKIN_BASELINE_DAYS] readings; while fewer than
     * [SKIN_BASELINE_MIN] exist, the first month's mean stands in so early days don't
     * read as wild swings. Result rounded to 0.01 °C.
     */
    internal fun skinTempDeviations(absByDay: Map<String, Double>): Map<String, Double> {
        if (absByDay.isEmpty()) return emptyMap()
        val ordered = absByDay.entries.sortedBy { it.key }
        val values = ordered.map { it.value }
        val seedMean = values.take(SKIN_BASELINE_DAYS).average()
        val out = HashMap<String, Double>(ordered.size)
        for (i in ordered.indices) {
            val prior = values.subList(maxOf(0, i - SKIN_BASELINE_DAYS), i)
            val baseline = if (prior.size >= SKIN_BASELINE_MIN) prior.average() else seedMean
            out[ordered[i].key] = Math.round((ordered[i].value - baseline) * 100.0) / 100.0
        }
        return out
    }

    /** One value per (day, key): first occurrence wins, matching the daily dedupe rule. */
    private fun dedupeSeries(rows: List<MetricSeriesRow>): List<MetricSeriesRow> {
        val seen = HashSet<String>()
        return rows.filter { seen.add(it.day + " " + it.key) }
    }

    // MARK: - sleeps.csv -> SleepSession (+ DailyMetric sleep fields)

    private class SleepParse(
        val sessions: List<SleepSession>,
        val daily: List<DailyMetric>,
    )

    private fun parseSleeps(table: CsvTable, deviceId: String): SleepParse {
        val sessions = ArrayList<SleepSession>(table.rows.size)
        val daily = ArrayList<DailyMetric>()
        for (row in table.rows) {
            val tz = WhoopTime.tzOffsetMinutes(row["cycle_timezone"])
            val cycleStart = WhoopTime.parseEpochSeconds(row.cell("cycle_start_time"), tz)
            val sleepOnset = WhoopTime.parseEpochSeconds(row.cell("sleep_onset"), tz)
            val wakeOnset = WhoopTime.parseEpochSeconds(row.cell("wake_onset"), tz)

            // Swift skip rule: cycleStart == nil && sleepOnset == nil && wakeOnset == nil.
            if (cycleStart == null && sleepOnset == null && wakeOnset == null) continue

            val isNap = row.bool("nap") ?: false

            val efficiency = row.double("sleep_efficiency_pct")
            val resp = row.double("respiratory_rate_rpm", "respiratory_rate")
            val asleepMin = row.double("asleep_duration_min")
            val lightMin = row.double("light_sleep_duration_min")
            val deepMin = row.double("deep_sws_duration_min", "deep_sleep_duration_min")
            val remMin = row.double("rem_duration_min")
            val awakeMin = row.double("awake_duration_min")

            // SleepSession PK is (deviceId, startTs). Prefer the actual sleep onset, then cycle
            // start. endTs prefers wake_onset; if absent derive from in-bed/asleep minutes.
            val startTs = sleepOnset ?: cycleStart
            if (startTs != null) {
                val inBedMin = row.double("in_bed_duration_min")
                val derivedEnd = startTs + ((inBedMin ?: asleepMin ?: 0.0) * 60.0).toLong()
                val endTs = wakeOnset ?: derivedEnd
                sessions.add(
                    SleepSession(
                        deviceId = deviceId,
                        startTs = startTs,
                        endTs = if (endTs >= startTs) endTs else startTs,
                        efficiency = efficiency,
                        restingHr = null, // resting HR is a cycles/recovery field, not in sleeps.csv
                        avgHrv = null,    // HRV likewise is a recovery field, not in sleeps.csv
                        stagesJSON = stagesJson(lightMin, deepMin, remMin, awakeMin),
                    )
                )
            }

            // Fold the MAIN sleep (not naps) into a DailyMetric keyed on the WHOOP day — the
            // morning you woke — so the sleep-architecture columns are populated even when
            // physiological_cycles.csv is absent, and line up with the cycle rows.
            if (!isNap) {
                val dayTs = wakeOnset ?: sleepOnset ?: cycleStart
                if (dayTs != null) {
                    daily.add(
                        DailyMetric(
                            deviceId = deviceId,
                            day = epochSecondsToDay(dayTs, tz),
                            totalSleepMin = asleepMin,
                            efficiency = efficiency,
                            deepMin = deepMin,
                            remMin = remMin,
                            lightMin = lightMin,
                            disturbances = awakeMin?.roundToInt(),
                            restingHr = null,
                            avgHrv = null,
                            recovery = null,
                            strain = null,
                            exerciseCount = null,
                            spo2Pct = null,
                            skinTempDevC = null,
                            respRateBpm = resp,
                        )
                    )
                }
            }
        }
        return SleepParse(sessions, daily)
    }

    // MARK: - workouts.csv -> WorkoutRow

    private fun parseWorkouts(table: CsvTable, deviceId: String): List<WorkoutRow> {
        val out = ArrayList<WorkoutRow>(table.rows.size)
        for (row in table.rows) {
            val tz = WhoopTime.tzOffsetMinutes(row["cycle_timezone"])
            val cycleStart = WhoopTime.parseEpochSeconds(row.cell("cycle_start_time"), tz)
            val workoutStart = WhoopTime.parseEpochSeconds(row.cell("workout_start_time"), tz)
            val workoutEnd = WhoopTime.parseEpochSeconds(row.cell("workout_end_time"), tz)

            // Swift skip rule: workoutStart == nil && workoutEnd == nil && cycleStart == nil.
            if (workoutStart == null && workoutEnd == null && cycleStart == null) continue

            val startTs = workoutStart ?: cycleStart ?: workoutEnd!!
            val sport = row.cell("activity_name") ?: "Workout" // PK component; never blank.

            val strain = row.double("activity_strain")
            val energyKcal = row.double("energy_burned_cal") // CSV "(cal)" == kcal
            val avgHr = row.double("average_hr_bpm", "average_heart_rate_bpm")
            val maxHr = row.double("max_hr_bpm", "max_heart_rate_bpm")

            val z1 = row.double("hr_zone_1_pct", "zone_1_pct", "hr_zone_1_pct_pct")
            val z2 = row.double("hr_zone_2_pct", "zone_2_pct", "hr_zone_2_pct_pct")
            val z3 = row.double("hr_zone_3_pct", "zone_3_pct", "hr_zone_3_pct_pct")
            val z4 = row.double("hr_zone_4_pct", "zone_4_pct", "hr_zone_4_pct_pct")
            val z5 = row.double("hr_zone_5_pct", "zone_5_pct", "hr_zone_5_pct_pct")

            val distance = row.double("distance_meters", "distance_meter")

            // durationS: prefer explicit end-start, else null.
            val durationS: Double? = if (workoutEnd != null && workoutStart != null && workoutEnd >= workoutStart) {
                (workoutEnd - workoutStart).toDouble()
            } else null

            // endTs for the row: workoutEnd, else start + duration, else start.
            val endTs = workoutEnd ?: (startTs + (durationS ?: 0.0).toLong())

            out.add(
                WorkoutRow(
                    deviceId = deviceId,
                    startTs = startTs,
                    endTs = if (endTs >= startTs) endTs else startTs,
                    sport = sport,
                    source = WHOOP_DEVICE, // required by the task: source = "my-whoop"
                    durationS = durationS,
                    energyKcal = energyKcal,
                    avgHr = avgHr?.roundToInt(),
                    maxHr = maxHr?.roundToInt(),
                    strain = strain,
                    distanceM = distance,
                    zonesJSON = zonesJson(z1, z2, z3, z4, z5),
                    notes = null,
                )
            )
        }
        return out
    }

    // MARK: - journal_entries.csv -> JournalEntry

    private fun parseJournal(table: CsvTable, deviceId: String): List<JournalEntry> {
        val out = ArrayList<JournalEntry>(table.rows.size)
        for (row in table.rows) {
            val tz = WhoopTime.tzOffsetMinutes(row["cycle_timezone"])
            val cycleStart = WhoopTime.parseEpochSeconds(row.cell("cycle_start_time"), tz)
            val cycleEnd = WhoopTime.parseEpochSeconds(row.cell("cycle_end_time"), tz)
            val question = row.cell("question_text", "question", "question_text_text")
            val answer = row.cell("answered_yes", "answered_yes_no", "answer", "answer_text")
            val notes = row.cell("notes")

            // Our JournalEntry PK is (deviceId, day, question); a question is required to store.
            if (question == null) continue

            // Rows with no cycle at all are the not-yet-filed prompts for the day the export
            // was taken; there is no honest day to store them under, so they are skipped.
            val day = cycleDay(null, cycleStart, cycleEnd, tz) ?: continue

            val answeredYes = parseYesNo(answer)

            out.add(
                JournalEntry(
                    deviceId = deviceId,
                    day = day,
                    question = question,
                    answeredYes = answeredYes,
                    notes = notes,
                )
            )
        }
        return out
    }

    // MARK: - Merge helpers

    /**
     * Merge cycle-derived and sleep-derived daily rows on (deviceId, day). Cycle fields take
     * precedence (they carry recovery/strain/RHR/HRV/SpO2/skin-temp); sleep rows fill any sleep
     * architecture columns the cycle row left null. One row per (deviceId, day) to honour the PK.
     */
    private fun mergeDaily(cycles: List<DailyMetric>, sleepDaily: List<DailyMetric>): List<DailyMetric> {
        if (sleepDaily.isEmpty()) return dedupeByDay(cycles, preferFirst = true)
        if (cycles.isEmpty()) return dedupeByDay(sleepDaily, preferFirst = true)

        val byDay = LinkedHashMap<String, DailyMetric>()
        // Seed with sleep rows first (lower precedence), then overlay cycle rows.
        for (s in sleepDaily) {
            val key = s.day
            byDay[key] = byDay[key]?.let { mergeRow(it, s) } ?: s
        }
        for (c in cycles) {
            val key = c.day
            val existing = byDay[key]
            byDay[key] = if (existing == null) c else mergeRow(existing, c)
        }
        return byDay.values.toList()
    }

    /** Collapse rows that share a day, keeping the first non-null field per column. */
    private fun dedupeByDay(rows: List<DailyMetric>, preferFirst: Boolean): List<DailyMetric> {
        val byDay = LinkedHashMap<String, DailyMetric>()
        for (r in rows) {
            val existing = byDay[r.day]
            byDay[r.day] = if (existing == null) r else if (preferFirst) mergeRow(existing, r) else mergeRow(r, existing)
        }
        return byDay.values.toList()
    }

    /** [base] wins for every non-null field; [fill] supplies values only where [base] is null. */
    private fun mergeRow(base: DailyMetric, fill: DailyMetric): DailyMetric = DailyMetric(
        deviceId = base.deviceId,
        day = base.day,
        totalSleepMin = base.totalSleepMin ?: fill.totalSleepMin,
        efficiency = base.efficiency ?: fill.efficiency,
        deepMin = base.deepMin ?: fill.deepMin,
        remMin = base.remMin ?: fill.remMin,
        lightMin = base.lightMin ?: fill.lightMin,
        disturbances = base.disturbances ?: fill.disturbances,
        restingHr = base.restingHr ?: fill.restingHr,
        avgHrv = base.avgHrv ?: fill.avgHrv,
        recovery = base.recovery ?: fill.recovery,
        strain = base.strain ?: fill.strain,
        exerciseCount = base.exerciseCount ?: fill.exerciseCount,
        spo2Pct = base.spo2Pct ?: fill.spo2Pct,
        skinTempDevC = base.skinTempDevC ?: fill.skinTempDevC,
        respRateBpm = base.respRateBpm ?: fill.respRateBpm,
    )

    // MARK: - JSON encoders (match DemoSeeder shapes)

    /** Stage-segments array `[{stage, min}]` (minutes), null if no stage data present. */
    private fun stagesJson(lightMin: Double?, deepMin: Double?, remMin: Double?, awakeMin: Double?): String? {
        if (lightMin == null && deepMin == null && remMin == null && awakeMin == null) return null
        val arr = JSONArray()
        fun seg(stage: String, min: Double?) {
            if (min != null) arr.put(JSONObject().put("stage", stage).put("min", min))
        }
        seg("light", lightMin)
        seg("deep", deepMin)
        seg("rem", remMin)
        seg("awake", awakeMin)
        return if (arr.length() == 0) null else arr.toString()
    }

    /** HR-zone-percentage object `{zone1..zone5}`, null if no zone data present. */
    private fun zonesJson(z1: Double?, z2: Double?, z3: Double?, z4: Double?, z5: Double?): String? {
        if (z1 == null && z2 == null && z3 == null && z4 == null && z5 == null) return null
        val obj = JSONObject()
        if (z1 != null) obj.put("zone1", z1)
        if (z2 != null) obj.put("zone2", z2)
        if (z3 != null) obj.put("zone3", z3)
        if (z4 != null) obj.put("zone4", z4)
        if (z5 != null) obj.put("zone5", z5)
        return if (obj.length() == 0) null else obj.toString()
    }

    private fun parseYesNo(raw: String?): Boolean {
        val t = raw?.trim()?.lowercase() ?: return false
        return t == "true" || t == "yes" || t == "1" || t == "y"
    }

    // MARK: - Day-string derivation

    /**
     * Convert wall-clock unix SECONDS to a "YYYY-MM-DD" day string at the given UTC offset
     * (minutes). The offset re-applies the source local-day boundary so a cycle that starts
     * at 23:30 local does not roll to the next UTC day.
     */
    private fun epochSecondsToDay(epochSeconds: Long, offsetMinutes: Int = 0): String {
        val offset = try {
            ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
        } catch (_: Exception) {
            ZoneOffset.UTC
        }
        return Instant.ofEpochSecond(epochSeconds).atOffset(offset).toLocalDate().format(DAY_FMT)
    }
}

// MARK: - Stream helpers

/** Read a whole stream, throwing if it exceeds [cap] bytes (memory guard). */
private fun InputStream.readAllCapped(cap: Long): ByteArray {
    val buffer = ByteArrayOutputStream(64 * 1024)
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        total += n
        if (total > cap) throw IllegalStateException("Input exceeds $cap bytes")
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
}

/** Read the current zip entry, capping at [cap] bytes; returns null if it overflows. */
private fun ZipInputStream.readEntryCapped(cap: Long): ByteArray? {
    val buffer = ByteArrayOutputStream(64 * 1024)
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        total += n
        if (total > cap) return null
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
}
