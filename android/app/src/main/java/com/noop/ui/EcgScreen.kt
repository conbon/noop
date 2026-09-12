package com.noop.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ble.LiveState
import com.noop.protocol.DeviceFamily
import com.noop.protocol.EcgArrhythmiaCheckResult
import com.noop.protocol.EcgArrhythmiaCheckStatus
import com.noop.protocol.EcgSession
import com.noop.protocol.Whoop5EcgProbe
import com.noop.protocol.Whoop5Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/**
 * ECG — the WHOOP MG "Labrador" hardware test bed.
 *
 * This screen exists to CLOSE THE LOOP on real hardware: it arms a recording, sends the attested
 * turn-on sequence (139=1 then 124=2), draws whatever type-43 samples come back, and shows the
 * probe verdict built from the strap's own result codes. Nothing here is a medical reading: the
 * sample unit/scale is unattested, the classifier verdict is the strap's, and the app only relays it.
 */
@Composable
fun EcgScreen(viewModel: AppViewModel) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val family by viewModel.linkFamily.collectAsStateWithLifecycle()
    val variant by viewModel.strapVariant.collectAsStateWithLifecycle()
    val ecg by viewModel.ecg.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val csv = pendingCsv
        pendingCsv = null
        if (uri == null || csv == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                        ?: error("could not open $uri")
                }
            }
            Toast.makeText(
                context,
                r.fold({ "ECG CSV exported" }, { "Export failed: ${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    EcgContent(
        live = live,
        family = family,
        variant = variant,
        snapshot = ecg,
        onStart = {
            if (!viewModel.startEcg()) {
                Toast.makeText(context, "ECG needs a bonded 5/MG link — see the log", Toast.LENGTH_SHORT).show()
            }
        },
        onStop = { viewModel.stopEcg() },
        onClear = { viewModel.clearEcg() },
        onExport = { snap ->
            pendingCsv = snap.csv()
            exportLauncher.launch("noop-ecg-${snap.startedAtMs}.csv")
        },
    )
}

@Composable
internal fun EcgContent(
    live: LiveState,
    family: DeviceFamily,
    variant: Whoop5Variant,
    snapshot: EcgSession.Snapshot?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onExport: (EcgSession.Snapshot) -> Unit,
) {
    var override by remember { mutableStateOf(false) }
    val recording = snapshot != null && !snapshot.finished
    val linkIs5 = live.bonded && family == DeviceFamily.WHOOP5
    val canStart = linkIs5 && (variant.isMG || override) && !recording

    ScreenScaffold(title = "ECG", subtitle = "WHOOP MG hardware test bed") {

        // Link status pills.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (label, tone) = when {
                live.bonded -> "Bonded" to StrandTone.Positive
                live.connected -> "Connected" to StrandTone.Warning
                else -> "Disconnected" to StrandTone.Critical
            }
            StatePill(label, tone = tone, pulsing = live.bonded)
            StatePill(
                if (live.connected) (if (family == DeviceFamily.WHOOP5) "5/MG link" else "4.0 link") else "No link",
                tone = if (live.connected && family == DeviceFamily.WHOOP5) StrandTone.Accent else StrandTone.Neutral,
                showsDot = false,
            )
            StatePill(
                when (variant) {
                    Whoop5Variant.MG -> "WHOOP MG"
                    Whoop5Variant.FIVE_ZERO -> "WHOOP 5.0"
                    Whoop5Variant.UNKNOWN -> "Variant ?"
                },
                tone = when (variant) {
                    Whoop5Variant.MG -> StrandTone.Positive
                    Whoop5Variant.FIVE_ZERO -> StrandTone.Warning
                    Whoop5Variant.UNKNOWN -> StrandTone.Neutral
                },
                showsDot = false,
            )
        }

        // Gate messaging.
        when {
            !live.bonded -> DataPendingNote(
                "Connect first",
                "Pair the strap from Live. ECG uses the 5/MG link (fd4b service, CLIENT_HELLO bond); a 4.0 strap has no ECG electrodes.",
            )
            family != DeviceFamily.WHOOP5 -> DataPendingNote(
                "4.0 link",
                "This strap exposes the WHOOP 4.0 service. The Labrador commands are puffin frames on the 5/MG service, so ECG stays off here.",
            )
            !variant.isMG -> NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Not identified as an MG", style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        if (variant == Whoop5Variant.FIVE_ZERO)
                            "The strap reports 5.0 hardware, which has no ECG clasp. Sending the commands anyway is harmless and records what the firmware answers."
                        else
                            "The Device Information Service did not attest an MG (model \"MG\", serial 5AM…). Override to run the probe regardless — it only records what the strap answers.",
                        style = NoopType.subhead, color = Palette.textSecondary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Try anyway (test bed)", style = NoopType.body, color = Palette.textPrimary)
                        Switch(checked = override, onCheckedChange = { override = it })
                    }
                }
            }
        }

        // Controls.
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart,
                enabled = canStart,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Color.White),
                modifier = Modifier.weight(1f),
            ) { Text(if (recording) "Recording" else "Start check") }
            OutlinedButton(onClick = onStop, enabled = recording, modifier = Modifier.weight(1f)) { Text("Stop") }
            OutlinedButton(onClick = onClear, enabled = snapshot != null && !recording) { Text("Clear") }
        }

        if (snapshot == null) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What a run does", style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        "1. TOGGLE_LABRADOR_FILTERED (139) = 1 opens the realtime ECG stream.\n" +
                            "2. TOGGLE_LABRADOR_DATA_GENERATION (124) = 2 starts the front end (MAX86176).\n" +
                            "3. Type-43 frames are decoded as 17-byte status header + int16 samples and drawn here.\n" +
                            "4. The strap's on-board classifier reports progress and completion; the run ends on its checkComplete or at 45 s.\n" +
                            "5. Stop sends 124 = 1 then 139 = 0. Everything is written to the diagnostic log (Settings → Export log).",
                        style = NoopType.subhead, color = Palette.textSecondary,
                    )
                }
            }
            return@ScreenScaffold
        }

        // Live status pills from the last packet header.
        val h = snapshot.header
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatePill(
                if (h == null) "No packets" else if (h.heartKeyLeadsAreOn) "Leads on" else "Leads off",
                tone = when {
                    h == null -> StrandTone.Neutral
                    h.heartKeyLeadsAreOn -> StrandTone.Positive
                    else -> StrandTone.Warning
                },
            )
            StatePill("Signal ${h?.signalQuality?.label ?: "—"}", tone = StrandTone.Neutral, showsDot = false)
            StatePill(
                h?.heartKeyProgress?.percentValue?.let { "$it%" } ?: (h?.heartKeyArrhythmiaCheckStatus?.token ?: "—"),
                tone = if (h?.heartKeyArrhythmiaCheckStatus == EcgArrhythmiaCheckStatus.CHECK_COMPLETE) StrandTone.Positive else StrandTone.Accent,
                showsDot = false,
            )
        }

        // Waveform.
        NoopCard(padding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Overline("Filtered ECG · raw i16")
                    Overline(
                        snapshot.measuredHz?.let { "%.0f Hz measured".format(it) } ?: "rate: measuring",
                        color = Palette.textTertiary,
                    )
                }
                EcgWaveform(samples = snapshot.samples, modifier = Modifier.fillMaxWidth().height(160.dp))
            }
        }

        // Numbers — HR/HRV/stress come from the strap's classifier header, not from the samples.
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            StatTile("HR", h?.heartKeyHR?.takeIf { it > 0 }?.toString() ?: "—", modifier = Modifier.weight(1f), caption = "classifier", accent = Palette.metricRose)
            StatTile("HRV", h?.heartKeyHRV?.takeIf { it > 0 }?.toString() ?: "—", modifier = Modifier.weight(1f), caption = "raw field", accent = Palette.metricCyan)
            StatTile("Stress", h?.heartKeyStressScore?.takeIf { it > 0 }?.toString() ?: "—", modifier = Modifier.weight(1f), caption = "raw field", accent = Palette.metricPurple)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            StatTile("Packets", snapshot.packets.toString(), modifier = Modifier.weight(1f), caption = "${snapshot.undecodedType43} undecoded")
            StatTile("Samples", snapshot.samples.size.toString(), modifier = Modifier.weight(1f), caption = "${snapshot.windowSeconds}s")
            StatTile(
                "Result",
                shortResult(h?.heartKeyArrhythmiaCheckResult, h?.heartKeyArrhythmiaCheckResultRaw),
                modifier = Modifier.weight(1f),
                caption = if (snapshot.finished) (snapshot.finishReason ?: "finished") else "running",
                accent = Palette.textPrimary,
            )
        }

        // Verdict from the strap's own result codes.
        val verdict = snapshot.verdict
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Overline("Probe verdict", color = verdictColor(verdict))
                Text(verdict.headline, style = NoopType.subhead, color = Palette.textPrimary)
                if (snapshot.steps.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (st in snapshot.steps) {
                            Text("${st.label}  ${st.outcome.token}", style = NoopType.mono, color = Palette.textSecondary)
                        }
                    }
                }
            }
        }

        // Export + full report.
        var showReport by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onExport(snapshot) }, enabled = snapshot.samples.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Export CSV") }
            OutlinedButton(onClick = { showReport = !showReport }, modifier = Modifier.weight(1f)) { Text(if (showReport) "Hide report" else "Show report") }
        }
        if (showReport) {
            NoopCard(padding = 12.dp) {
                Text(snapshot.report(), style = NoopType.mono, color = Palette.textSecondary)
            }
        }

        DataPendingNote(
            "Unattested",
            "Sample unit and scale are unknown (raw int16, drawn autoscaled). Sample rate is measured from arrival, not assumed. " +
                "The 5/MG session itself (hello bond, fd4b notifies, puffin commands) is unverified in this fork until a real MG answers.",
        )
    }
}

/** Tile-sized label for the strap classifier's result; the full token is in the report. */
private fun shortResult(r: EcgArrhythmiaCheckResult?, raw: Int?): String = when (r) {
    null -> raw?.toString() ?: "—"
    EcgArrhythmiaCheckResult.NOT_COMPLETE -> "…"
    EcgArrhythmiaCheckResult.NORMAL_SINUS_RHYTHM -> "NSR"
    EcgArrhythmiaCheckResult.SIGNAL_UNREADABLE -> "unread"
    EcgArrhythmiaCheckResult.BRADYCARDIA -> "brady"
    else -> r.name.lowercase().replace('_', ' ').split(' ').first().take(7)
}

private fun verdictColor(v: Whoop5EcgProbe.Verdict): Color = when (v) {
    is Whoop5EcgProbe.Verdict.EcgCandidatesArrived -> Palette.statusPositive
    is Whoop5EcgProbe.Verdict.DataRequestRefused,
    is Whoop5EcgProbe.Verdict.OpcodeUnsupported -> Palette.statusCritical
    is Whoop5EcgProbe.Verdict.AcceptedButSilent,
    is Whoop5EcgProbe.Verdict.CommandRefused,
    is Whoop5EcgProbe.Verdict.DataRequestNotAccepted -> Palette.statusWarning
    else -> Palette.textSecondary
}

/**
 * Autoscaled strip-chart of the most recent samples. Raw integers in, no unit out: the y-axis is
 * min..max of the visible window with a small margin, so any signal fills the box regardless of scale.
 */
@Composable
internal fun EcgWaveform(samples: List<Int>, modifier: Modifier = Modifier, window: Int = 1500) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.clip(shape).background(Palette.surfaceInset)) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val w = size.width
            val hgt = size.height
            // Grid: 4 horizontal bands.
            for (i in 1..3) {
                val y = hgt * i / 4f
                drawLine(Palette.ringTrack, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (samples.size < 2) return@Canvas
            val view = if (samples.size > window) samples.subList(samples.size - window, samples.size) else samples
            val mn = view.min()
            val mx = view.max()
            val span = (mx - mn).coerceAtLeast(1)
            val pad = hgt * 0.08f
            val path = Path()
            val dx = w / (view.size - 1).coerceAtLeast(1)
            view.forEachIndexed { i, v ->
                val x = i * dx
                val y = hgt - pad - (v - mn).toFloat() / span * (hgt - 2 * pad)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Palette.metricRose, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
    }
}
