package com.noop.protocol

/**
 * One ECG ("Labrador") recording attempt, assembled from the strap's realtime stream.
 *
 * Pure and platform-free so the JVM tests can drive it. The BLE client feeds it every
 * CRC-valid type-43 (REALTIME_RAW_DATA) frame while a recording is armed plus every
 * COMMAND_RESPONSE for the four Labrador opcodes; the UI observes the resulting [Snapshot].
 *
 * What it asserts and what it does not:
 *  - Frames that decode as [FilteredLabradorPacket] are appended sample-for-sample; anything
 *    else is counted but never guessed at.
 *  - The strap's own classifier status decides completion (`checkComplete`, or
 *    `heartKeyIsStoppedAndComplete`); a wall-clock cap is the only other exit.
 *  - Sample rate is MEASURED from packet arrival, never assumed: the unit/scale of the samples
 *    is unattested, so they stay raw integers here and in the UI.
 */
class EcgSession(
    private val startedAtMs: Long,
    /** Recording is abandoned past this — WHOOP's own spot check is 30 s. */
    private val capMs: Long = 45_000L,
    /** Retain at most this many samples (well past a 30 s recording at any plausible rate). */
    private val maxSamples: Int = 60_000,
) {
    data class Snapshot(
        val startedAtMs: Long,
        val packets: Int,
        val undecodedType43: Int,
        val samples: List<Int>,
        val header: EcgStatusHeader?,
        val firstPacketMs: Long?,
        val lastPacketMs: Long?,
        val steps: List<Whoop5EcgProbe.Step>,
        /** Hex of the first few decodable ECG frames, for the probe report and the log. */
        val candidateFrames: List<String>,
        val finished: Boolean,
        val finishReason: String?,
    ) {
        /** Samples per second measured over the packets actually received, or null before two packets. */
        val measuredHz: Double?
            get() {
                val f = firstPacketMs ?: return null
                val l = lastPacketMs ?: return null
                if (l <= f || samples.size < 2) return null
                return samples.size * 1000.0 / (l - f)
            }

        /** Seconds the recording has been (or was) live, never below 1 so rates stay finite. */
        val windowSeconds: Int
            get() = (((lastPacketMs ?: startedAtMs) - startedAtMs) / 1000L).toInt().coerceAtLeast(1)

        val verdict: Whoop5EcgProbe.Verdict
            get() = Whoop5EcgProbe.verdict(steps, ecgPacketsSeen = packets, windowSeconds = windowSeconds)

        /** The full probe report text, as the upstream desktop tool prints it. */
        fun report(): String =
            Whoop5EcgProbe.report(steps, ecgPacketsSeen = packets, candidateFrames = candidateFrames, windowSeconds = windowSeconds)

        /**
         * CSV of the recording: `#`-prefixed provenance lines, then `index,sample`. Samples are the raw
         * signed 16-bit integers off the wire — no unit, no scale — because neither is attested yet.
         */
        fun csv(): String {
            val sb = StringBuilder()
            sb.append("# noop-conal ECG recording (WHOOP MG Labrador filtered stream)\n")
            sb.append("# started_unix_ms=$startedAtMs\n")
            sb.append("# packets=$packets undecoded_type43=$undecodedType43 samples=${samples.size}\n")
            sb.append("# measured_hz=${measuredHz?.let { "%.2f".format(it) } ?: "n/a"} window_s=$windowSeconds\n")
            sb.append("# finished=$finished reason=${finishReason ?: ""}\n")
            header?.let { h ->
                sb.append("# last_header: quality=${h.signalQuality.label} leads_on=${h.heartKeyLeadsAreOn} ")
                sb.append("status=${h.heartKeyArrhythmiaCheckStatus?.token ?: h.heartKeyArrhythmiaCheckStatusRaw} ")
                sb.append("result=${h.heartKeyArrhythmiaCheckResult?.token ?: h.heartKeyArrhythmiaCheckResultRaw} ")
                sb.append("progress=${h.heartKeyProgress.raw} hr=${h.heartKeyHR} avg_hr=${h.heartKeyAverageHR} ")
                sb.append("hrv=${h.heartKeyHRV} stress=${h.heartKeyStressScore}\n")
            }
            sb.append("# sample unit/scale: UNATTESTED raw i16\n")
            sb.append("index,sample\n")
            samples.forEachIndexed { i, v -> sb.append(i).append(',').append(v).append('\n') }
            return sb.toString()
        }
    }

    /** Labrador commands written during this attempt, in order, with their reply (if any). */
    private data class Sent(val cmd: Int, val arg: Int, val outcome: Whoop5EcgProbe.CommandOutcome)

    private var packets = 0
    private var undecoded = 0
    private val samples = ArrayList<Int>()
    private var header: EcgStatusHeader? = null
    private var firstPacketMs: Long? = null
    private var lastPacketMs: Long? = null
    private val sent = ArrayList<Sent>()
    private val candidates = ArrayList<String>()
    private var finished = false
    private var finishReason: String? = null

    /** Record a Labrador command that was written, so its reply can be matched by opcode. */
    fun commandSent(cmd: Int, arg: Int) {
        sent.add(Sent(cmd, arg, Whoop5EcgProbe.CommandOutcome.NoReply))
    }

    /** A COMMAND_RESPONSE arrived for [cmd]; attach its result code to the most recent unanswered step. */
    fun commandAnswered(cmd: Int, outcome: Whoop5EcgProbe.CommandOutcome) {
        val idx = sent.indexOfLast { it.cmd == cmd && it.outcome == Whoop5EcgProbe.CommandOutcome.NoReply }
        if (idx >= 0) sent[idx] = sent[idx].copy(outcome = outcome)
    }

    /**
     * Feed one complete, CRC-valid frame received while recording. Returns true when it was a
     * decodable filtered ECG packet. Frames of any other type are ignored (the caller filters
     * on type 43, but a stray type costs nothing here).
     */
    fun feed(frame: ByteArray, nowMs: Long): Boolean {
        if (finished) return false
        val packet = Whoop5Ecg.decodeFilteredFrame(frame)
        if (packet == null) {
            undecoded++
            return false
        }
        packets++
        if (firstPacketMs == null) firstPacketMs = nowMs
        lastPacketMs = nowMs
        header = packet.header
        if (candidates.size < MAX_CANDIDATE_FRAMES) {
            candidates.add(frame.joinToString("") { "%02x".format(it) })
        }
        if (samples.size + packet.filteredECGDataRaw.size <= maxSamples) {
            samples.addAll(packet.filteredECGDataRaw)
        }
        val h = packet.header
        if (h.heartKeyArrhythmiaCheckStatus == EcgArrhythmiaCheckStatus.CHECK_COMPLETE ||
            h.heartKeyIsStoppedAndComplete
        ) {
            finish("strap reported the check complete")
        }
        return true
    }

    /** Wall-clock housekeeping; call from a timer. Returns true if the cap ended the recording. */
    fun tick(nowMs: Long): Boolean {
        if (finished) return false
        if (nowMs - startedAtMs >= capMs) {
            finish(if (packets == 0) "no ECG packets within ${capMs / 1000}s" else "time cap reached")
            return true
        }
        return false
    }

    fun finish(reason: String) {
        if (finished) return
        finished = true
        finishReason = reason
    }

    val isFinished: Boolean get() = finished

    fun snapshot(): Snapshot = Snapshot(
        startedAtMs = startedAtMs,
        packets = packets,
        undecodedType43 = undecoded,
        samples = samples.toList(),
        header = header,
        firstPacketMs = firstPacketMs,
        lastPacketMs = lastPacketMs,
        steps = sent.map {
            Whoop5EcgProbe.Step(
                label = "${Whoop5Ecg.commandName(it.cmd)}(${it.arg})",
                outcome = it.outcome,
                requestsRealtimeData = Whoop5Ecg.requestsRealtimeData(it.cmd, it.arg),
            )
        },
        candidateFrames = candidates.toList(),
        finished = finished,
        finishReason = finishReason,
    )

    private companion object {
        const val MAX_CANDIDATE_FRAMES = 3
    }
}
