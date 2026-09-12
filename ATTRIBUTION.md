# Attribution

Strand is an independent, unofficial, local-first macOS app. It is not affiliated
with, endorsed by, or connected to WHOOP, Inc. "WHOOP" is used nominatively only to
identify the hardware the app interoperates with.

Strand builds on prior open-source reverse-engineering and interoperability work:

## WHOOP 4.0 protocol + Swift packages
- **`johnmiddleton12/my-whoop`** — the `WhoopProtocol` and `WhoopStore` Swift packages
  (vendored under `Packages/`), the WHOOP 4.0 BLE framing/command/decode work, and the
  iOS collection logic that Strand's `WhoopBLE`/`Collect` layers are adapted from.
  See `DISCLAIMER.md` (carried over from that project).

## WHOOP 5.0 / MG protocol
- **`b-nnett/goose`** — the WHOOP 5.0 BLE reverse-engineering (service UUID family
  `fd4b0001-…`, CRC16-Modbus header, CLIENT_HELLO, and the "puffin" packet types)
  that Strand's `DeviceFamily` Whoop-5 path and `whoop5_protocol.json` are ported from.

## NOOP upstream (WHOOP 5.0 / MG protocol research, ECG "Labrador")
- **NoopApp / `ryanbr/noop`** — this fork descends from the NOOP project (PolyForm
  Noncommercial 1.0.0, see `LICENSE`). The WHOOP 5.0/MG session work (bond-first,
  `CLIENT_HELLO`, ack-driven offload, DIS-based MG detection) and the MG ECG
  ("Labrador") protocol research — command family 123/124/125/139, the 17-byte
  status header and filtered/raw packet layouts, and the on-hardware findings in
  their issue #891 — are documented in that project's `docs/PROTOCOL.md` §9. The
  `Whoop5Variant`, `Whoop5Ecg` and `Whoop5EcgProbe` sources and their tests under
  `android/app/src/main/java/com/noop/protocol/` are taken from it; the stdlib
  capture tools under `Tools/whoop-capture/upstream/` likewise.

## Other
- **GRDB.swift** (`groue/GRDB.swift`) — SQLite persistence (via Swift Package Manager).

Strand contains no WHOOP proprietary code, binaries, firmware, logos, or assets, and
performs no DRM circumvention. It operates only with the user's own device and data.
Strand is **not a medical device**; all metrics (HR, HRV, recovery, strain, sleep,
SpO₂, temperature) are approximations and not clinically validated.
