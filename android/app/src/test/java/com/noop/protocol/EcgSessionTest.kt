package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EcgSession] turns type-43 frames + Labrador acks into a recording. Synthetic frames only (the
 * same shape contract as [Whoop5EcgTest]); nothing here claims to know what a real MG sends.
 */
class EcgSessionTest {

    private fun header(
        samples: Int,
        status: Int = 1,
        stoppedAndComplete: Int = 0,
        progress: Int = 40,
        hr: Int = 63,
    ): List<Int> = listOf(
        2, 0x05, 1, 1, stoppedAndComplete, 1,
        0, status, progress, 0, 61, hr,
        812 and 0xFF, (812 shr 8) and 0xFF, 17,
        samples and 0xFF, (samples shr 8) and 0xFF,
    )

    private fun i16le(values: List<Int>): List<Int> =
        values.flatMap { listOf(it and 0xFF, (it shr 8) and 0xFF) }

    private fun ecgFrame(samples: List<Int>, status: Int = 1, complete: Int = 0, seq: Int = 1): ByteArray =
        Framing.puffinCommandFrame(
            cmd = 0x00, seq = seq,
            payload = (header(samples.size, status = status, stoppedAndComplete = complete) + i16le(samples))
                .map { it.toByte() }.toByteArray(),
            type = 43,
        )

    /** A 5/MG COMMAND_RESPONSE: inner [36, seq, cmd, 0x01, result, …] at offset 8 ⇒ result at frame[12]. */
    private fun ack(cmd: Int, result: Int): ByteArray =
        Framing.puffinCommandFrame(cmd = cmd, seq = 7, payload = byteArrayOf(0x01, result.toByte(), 0), type = 36)

    @Test
    fun appendsSamplesAndMeasuresRate() {
        val s = EcgSession(startedAtMs = 1_000L)
        assertTrue(s.feed(ecgFrame(listOf(1, 2, 3, 4, 5)), nowMs = 1_100L))
        assertTrue(s.feed(ecgFrame(listOf(6, 7, 8, 9, 10)), nowMs = 1_200L))
        val snap = s.snapshot()
        assertEquals(2, snap.packets)
        assertEquals((1..10).toList(), snap.samples)
        // 10 samples over 100 ms of arrival span ⇒ 100 Hz measured, never assumed.
        assertEquals(100.0, snap.measuredHz!!, 1e-9)
        assertEquals(2, snap.candidateFrames.size)
        assertFalse(snap.finished)
    }

    @Test
    fun countsUndecodableType43WithoutGuessing() {
        val s = EcgSession(startedAtMs = 0L)
        val junk = Framing.puffinCommandFrame(cmd = 0, seq = 1, payload = byteArrayOf(1, 2, 3), type = 43)
        assertFalse(s.feed(junk, nowMs = 10L))
        val snap = s.snapshot()
        assertEquals(0, snap.packets)
        assertEquals(1, snap.undecodedType43)
        assertNull(snap.measuredHz)
        assertTrue(snap.samples.isEmpty())
    }

    @Test
    fun strapCheckCompleteFinishesTheRun() {
        val s = EcgSession(startedAtMs = 0L)
        s.feed(ecgFrame(listOf(1, 2)), nowMs = 10L)
        s.feed(ecgFrame(listOf(3, 4), status = EcgArrhythmiaCheckStatus.CHECK_COMPLETE.raw), nowMs = 20L)
        assertTrue(s.isFinished)
        assertEquals("strap reported the check complete", s.snapshot().finishReason)
        // Late frames are ignored once finished.
        assertFalse(s.feed(ecgFrame(listOf(5, 6)), nowMs = 30L))
        assertEquals(listOf(1, 2, 3, 4), s.snapshot().samples)
    }

    @Test
    fun wallClockCapEndsASilentRun() {
        val s = EcgSession(startedAtMs = 0L, capMs = 5_000L)
        assertFalse(s.tick(4_999L))
        assertTrue(s.tick(5_000L))
        assertEquals("no ECG packets within 5s", s.snapshot().finishReason)
    }

    @Test
    fun stepsCarryResultCodesAndDataRole() {
        val s = EcgSession(startedAtMs = 0L)
        s.commandSent(Whoop5Ecg.TOGGLE_REALTIME_FILTERED_ECG_CMD, 1)
        s.commandSent(Whoop5Ecg.MAIN_CONTROL_ECG_DATA_GENERATION_CMD, Whoop5Ecg.ControlSignal.START.raw)

        val a = ack(Whoop5Ecg.TOGGLE_REALTIME_FILTERED_ECG_CMD, 1)
        assertEquals(Whoop5EcgProbe.CommandOutcome.Success, Whoop5EcgProbe.outcome(a))
        s.commandAnswered(Whoop5Ecg.TOGGLE_REALTIME_FILTERED_ECG_CMD, Whoop5EcgProbe.outcome(a)!!)
        s.commandAnswered(Whoop5Ecg.MAIN_CONTROL_ECG_DATA_GENERATION_CMD, Whoop5EcgProbe.CommandOutcome.Failure)

        val steps = s.snapshot().steps
        assertEquals(listOf("TOGGLE_LABRADOR_FILTERED(1)", "TOGGLE_LABRADOR_DATA_GENERATION(2)"), steps.map { it.label })
        assertEquals(Whoop5EcgProbe.CommandOutcome.Success, steps[0].outcome)
        assertEquals(Whoop5EcgProbe.CommandOutcome.Failure, steps[1].outcome)
        assertTrue(steps.all { it.requestsRealtimeData })
        assertTrue(s.snapshot().verdict is Whoop5EcgProbe.Verdict.DataRequestRefused)
    }

    @Test
    fun stopArgumentsRequestNoData() {
        val s = EcgSession(startedAtMs = 0L)
        s.commandSent(Whoop5Ecg.MAIN_CONTROL_ECG_DATA_GENERATION_CMD, Whoop5Ecg.ControlSignal.STOP.raw)
        s.commandSent(Whoop5Ecg.TOGGLE_REALTIME_FILTERED_ECG_CMD, 0)
        assertTrue(s.snapshot().steps.none { it.requestsRealtimeData })
    }

    @Test
    fun csvCarriesProvenanceAndRawSamples() {
        val s = EcgSession(startedAtMs = 123L)
        s.feed(ecgFrame(listOf(-5, 0, 7)), nowMs = 200L)
        s.finish("test")
        val csv = s.snapshot().csv()
        val lines = csv.trim().lines()
        assertTrue(lines.first().startsWith("# noop-conal ECG recording"))
        assertTrue(csv.contains("# started_unix_ms=123"))
        assertTrue(csv.contains("UNATTESTED"))
        assertEquals("index,sample", lines.first { !it.startsWith("#") })
        assertEquals(listOf("0,-5", "1,0", "2,7"), lines.takeLast(3))
    }

    @Test
    fun reportMentionsCommandsAndPackets() {
        val s = EcgSession(startedAtMs = 0L)
        s.commandSent(Whoop5Ecg.TOGGLE_REALTIME_FILTERED_ECG_CMD, 1)
        s.feed(ecgFrame(listOf(1, 2)), nowMs = 1_500L)
        val r = s.snapshot().report()
        assertTrue(r.contains("TOGGLE_LABRADOR_FILTERED(1)"))
        assertTrue(r.contains("ECG-shaped packets seen in 1s: 1"))
    }

    @Test
    fun reassemblerHandlesWhoop5LengthsAndFragments() {
        val frame = ecgFrame(List(40) { it })
        val r = Reassembler(DeviceFamily.WHOOP5)
        val cut = frame.size / 2
        assertTrue(r.feed(frame.copyOfRange(0, cut)).isEmpty())
        val out = r.feed(frame.copyOfRange(cut, frame.size))
        assertEquals(1, out.size)
        assertTrue(frame.contentEquals(out[0]))
        assertNotNull(Whoop5Ecg.decodeFilteredFrame(out[0]))
    }
}
