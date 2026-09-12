# WHOOP MG ECG over BLE — research notes and test-bed plan

Status: **researched, implemented as a test bed, not yet exercised on an MG.** Everything in the
"Attested" sections below comes from community reverse-engineering of the WHOOP 5.0 / MG wire
protocol (the upstream NOOP project and its issue threads, one owner's on-device runs) and from
public regulatory filings. Nothing here was copied from WHOOP software. Where a fact rests on a
single device or a single capture, it says so.

This fork (`noop-conal`, application id `com.noop.conal`) installs beside other NOOP builds and is
the instrument for confirming or correcting these notes on real hardware.

---

## 1. What the MG does

The WHOOP MG has a conductive clasp; pressing it with the opposite hand closes a single-lead ECG
circuit through the body. WHOOP's "Heart Screener" is a 30 s spot check. The FDA 510(k) clearance
(K243236) describes the arrhythmia classifier ("HeartKey") running on the strap firmware, not in the
phone app: the phone receives a stream of filtered samples plus a status header carrying the
classifier's progress and result. That matches the packet layout observed on the wire (§4).

The plain WHOOP 5.0 shares the wire protocol byte-for-byte but has no electrodes; the Labrador
commands can still be sent to it, which is useful for recording what the firmware answers.

## 2. Two wire protocols, one app

| | WHOOP 4.0 | WHOOP 5.0 / MG |
|---|---|---|
| Custom service | `61080001-8d6d-82b8-614a-1c8cb0f8dcc6` | `fd4b0001-cce1-4033-93ce-002d5875f58a` |
| Command write | `…0002` | `fd4b0002…` |
| Notify chars | `…0003` (cmd), `…0004` (event), `…0005` (data) | `fd4b0003`, `fd4b0004`, `fd4b0005`, `fd4b0007` |
| Frame header | `AA len16 crc8(len)` | `AA 01 len16 00 00 crc16-modbus(first 6)` |
| Inner record offset | 4 | 8 |
| Trailer | CRC32 LE over inner | CRC32 LE over inner |
| Bond | one confirmed write (GET_BATTERY_LEVEL) | one confirmed write of the static 16-byte `CLIENT_HELLO` `AA 01 08 00 00 01 E6 71 23 01 91 01 36 3E 5C 8D` |
| Packet types | 35 COMMAND, 36 COMMAND_RESPONSE, 40 REALTIME_DATA, 43 REALTIME_RAW_DATA, 47/48/49/50 offload | same, plus "puffin" 37/38/53/54/56 mirrors |

The 4.0 path is what this fork's BLE client was written for. §6 describes what was added for 5/MG.

## 3. Labrador commands (attested on one MG, firmware 50.39.1.0, hardware WS50_r00)

All four share the payload shape `[0x01 revision, arg]` inside a COMMAND (35) puffin frame, padded
to a 4-byte boundary.

| Opcode | Name | Arguments | Notes |
|---|---|---|---|
| 123 (0x7B) | SELECT_WRIST | 0 right / 1 left (inferred order) | persistent strap config; not needed to stream |
| 124 (0x7C) | TOGGLE_LABRADOR_DATA_GENERATION | **1 = stop, 2 = start**; 0 is refused (FAILURE) | drives the MAX86176 front end ("MAX86176: Set ECG ON" in the strap console log) |
| 125 (0x7D) | TOGGLE_LABRADOR_RAW_SAVE | 0 / 1 | names flash storage, not a live channel |
| 139 (0x8B) | TOGGLE_LABRADOR_FILTERED | 0 / 1 | gates the realtime filtered stream |

**Working turn-on order: `139 = 1` then `124 = 2`.** With 139 closed, `124 = 2` still turns the front
end on (console line appears) but no packets reach the phone. Stop is `124 = 1`, then `139 = 0`.

COMMAND_RESPONSE (type 36 or 38) carries the echoed opcode at frame offset 10 and the result code at
offset 12: `0 FAILURE, 1 SUCCESS, 2 PENDING, 3 UNSUPPORTED`.

## 4. ECG packet layout (type 43, REALTIME_RAW_DATA)

The inner record is `[43, seq, cmd, payload…]`; the payload (frame offset 11) is:

```
offset  size  field
0       1     signalQuality      0 unknown, 1 low, 2 medium, 3 high
1       1     statusFlags
2       1     heartKeyStarted
3       1     heartKeyIsRunning
4       1     heartKeyIsStoppedAndComplete
5       1     heartKeyLeadsAreOn
6       1     arrhythmiaCheckResult   0 notComplete 1 normalSinusRhythm 2 signalUnreadable
                                       3 bradycardia 4 afibDetected 5 tachycardia 6 inconclusive
7       1     arrhythmiaCheckStatus   0 notStarted 1 inProgress 2 checkComplete
8       1     progress (0..100)
9       1     unreadableReason
10      1     averageHR
11      1     HR
12      2     HRV (u16 LE)
14      1     stressScore
15      2     numberOfECGSamples (u16 LE)
17      2*n   samples, int16 LE
        0..3  padding
```

The raw (unfiltered) variant adds leads-off I/Q arrays after the samples; the app decodes it but
does not request it.

**Not attested:** the sample unit and scale (the app keeps them as raw integers and autoscales the
plot), the nominal sample rate (the app measures it from arrival), and whether firmware 50.40.x
behaves identically.

## 5. Telling an MG from a 5.0

Read from the standard Device Information Service, unbonded:

- `0x2A24` Model Number String = `"MG"` on a real MG (strongest signal)
- `0x2A25` Serial Number String prefix `5AM` ⇒ MG, `5AG` ⇒ 5.0 (one field capture showed `MGB…`)
- `0x2A27` Hardware Revision String containing `WG50` ⇒ 5.0 (`WS50_rNN` seen on MGs)

Contradictory evidence resolves to UNKNOWN rather than a guess (`protocol/Whoop5Variant.kt`).

## 6. What this fork now does

- **Rename** to `noop-conal` / `com.noop.conal` so it installs beside another NOOP fork.
- **BLE client** (`ble/WhoopBleClient.kt`): detects the family from the discovered service. On
  5/MG it writes `CLIENT_HELLO` as the confirmed bond write, waits for the ack, then subscribes to
  the four `fd4b` notify characteristics plus standard HR/battery, reads the three DIS strings,
  publishes the variant, and sends one `GET_BATTERY_LEVEL` as a minimal handshake. Commands are
  built as puffin frames; the reassembler and parser run family-aware. 4.0 live/historical
  persistence is skipped on this link (its decoders are 4.0-only). Every connection logs a
  frame-type histogram and the first three frames of each type in hex, and OS bond-state changes.
- **ECG session** (`protocol/EcgSession.kt`): pure Kotlin. Feeds type-43 frames through the
  Labrador decoder, tracks the four commands and their result codes, finishes on the strap's
  `checkComplete` / `stoppedAndComplete` or at 45 s, measures Hz, and renders the probe report
  and a CSV with provenance lines.
- **ECG screen** (Live → ECG): link/variant pills, MG gate with a "try anyway" override, Start /
  Stop / Clear, live autoscaled waveform, classifier HR/HRV/stress, packets/samples/result tiles,
  the probe verdict with per-command result codes, full report toggle, CSV export via the system
  file picker, and an "unattested" note.
- **Log**: everything above goes to the on-device log exported from Settings → Diagnostics.
- **Tooling**: `Tools/whoop-capture/ecg_triage.py` (stdlib) turns an Android HCI snoop log into a
  handle→UUID map, DIS reads, per-family/type frame histogram, written commands, response result
  codes, and a structural triage of type-43 frames, optionally dumping samples to CSV.
  `python3 Tools/whoop-capture/ecg_triage.py --selftest` checks the pipeline on a synthetic log.

## 7. Test plan on the MG

1. Install `noop-conal` (full flavor). Enable **Developer options → Enable Bluetooth HCI snoop log**
   before connecting so the session is captured independently of the app.
2. Live → Connect. Watch for, in order: `5/MG service found`, `Bonding (5/MG): confirmed write
   CLIENT_HELLO`, an OS bond-state line, `BONDED`, `subscribing N characteristics`, three `DIS`
   lines, `5/MG variant: MG`, then frames flowing. Any deviation is the first finding.
3. ECG → Start check while holding the clasp. Expect `TOGGLE_LABRADOR_FILTERED → SUCCESS(1)` and
   `TOGGLE_LABRADOR_DATA_GENERATION → SUCCESS(1)` in the log, then a growing packet count and a
   waveform. Let it run to the strap's `checkComplete` or 45 s.
4. Export the CSV from the ECG screen and the log from Settings → Diagnostics.
5. `adb bugreport` → `FS/data/misc/bluetooth/logs/btsnoop_hci.log` →
   `python3 Tools/whoop-capture/ecg_triage.py btsnoop_hci.log --csv ecg.csv`.
6. For comparison, do the same capture with the official WHOOP app running a Heart Screener; the
   triage output shows any command, argument or ordering the official app uses that this fork does
   not.

Things most likely to need correcting after step 2: whether the hello ack arrives before or after
Android pairing completes, whether `fd4b0007` accepts a CCCD write, and whether `GET_BATTERY_LEVEL`
is a valid 5/MG opcode (a `FAILURE`/`UNSUPPORTED` there is harmless and informative).

## 8. Open questions

- Sample unit/scale and nominal rate of the filtered stream.
- Meaning of `statusFlags` bits and `unreadableReason` codes.
- Whether the raw stream (125 / raw-save) ever reaches BLE or only flash.
- Whether a 30 s check must be timed by the phone or the strap always stops itself.
- What the 5/MG connect handshake of the official app looks like (clock set, data range) — needed
  before any 5/MG history offload, which is out of scope for the ECG work.

## 9. Provenance and licence

The protocol facts were cross-checked against the upstream NOOP project (PolyForm Noncommercial
1.0.0; see `LICENSE` and `ATTRIBUTION.md`), whose Python capture helpers are kept unmodified under
`Tools/whoop-capture/upstream/` for reference. `ecg_triage.py` is an independent implementation.
This fork is for personal use and is not a medical device; the classifier verdicts it shows are the
strap's own and are relayed without interpretation.
