#!/usr/bin/env python3
"""ecg_triage.py — triage a phone Bluetooth HCI snoop log for WHOOP MG ECG ("Labrador") traffic.

Purpose: close the loop between the noop-conal test bed (or the official WHOOP app) and the strap.
Feed it Android's `btsnoop_hci.log` (Developer options → Enable Bluetooth HCI snoop log, then
`adb bugreport` → FS/data/misc/bluetooth/logs/btsnoop_hci.log) and it prints, without needing any
other file:

  * GATT handle → characteristic UUID map recovered from the discovery chatter (so fd4b0002…0007
    and the DIS strings 2A24/2A25/2A27 are named, not just numbered)
  * Device Information Service reads (model / serial / hardware revision → MG vs 5.0)
  * every CRC-valid WHOOP frame reassembled per handle and direction, with a packet-type histogram
    for each family (4.0 CRC8 header vs 5/MG CRC16-Modbus header — detected, never assumed)
  * the COMMAND opcodes the phone wrote, and for each 5/MG COMMAND_RESPONSE the result code
    (0 FAILURE / 1 SUCCESS / 2 PENDING / 3 UNSUPPORTED) — Labrador opcodes 123/124/125/139 called out
  * a structural triage of every type-43 (REALTIME_RAW_DATA) frame against the Labrador filtered
    layout (17-byte status header + int16 samples, sample count agreeing with the length) with the
    header fields of the first few candidates, and an optional CSV dump of the samples

Everything is stdlib. Written for this fork from the protocol facts documented in
docs/ECG_RESEARCH.md; it shares no code with the upstream extractor kept under upstream/ for
reference.

Usage:
    python3 ecg_triage.py btsnoop_hci.log
    python3 ecg_triage.py btsnoop_hci.log --csv ecg_samples.csv --hex 5
    python3 ecg_triage.py --selftest
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib
from collections import Counter, defaultdict
from dataclasses import dataclass, field

# --------------------------------------------------------------------------------------------------
# Protocol constants (see docs/ECG_RESEARCH.md for provenance)
# --------------------------------------------------------------------------------------------------

SOF = 0xAA

PACKET_TYPES = {
    35: "COMMAND", 36: "COMMAND_RESPONSE", 37: "PUFFIN_COMMAND", 38: "PUFFIN_COMMAND_RESPONSE",
    40: "REALTIME_DATA", 43: "REALTIME_RAW_DATA", 47: "HISTORICAL_DATA", 48: "EVENT",
    49: "METADATA", 50: "CONSOLE_LOGS", 53: "PUFFIN_HISTORICAL", 54: "PUFFIN_EVENT", 56: "PUFFIN_METADATA",
}

COMMANDS = {
    10: "SET_CLOCK", 11: "GET_CLOCK", 16: "GET_BATTERY_LEVEL", 22: "GET_DATA_RANGE",
    23: "HISTORICAL_DATA_RESULT", 30: "SEND_R10_R11_REALTIME", 79: "RUN_HAPTICS_PATTERN",
    123: "SELECT_WRIST", 124: "TOGGLE_LABRADOR_DATA_GENERATION", 125: "TOGGLE_LABRADOR_RAW_SAVE",
    139: "TOGGLE_LABRADOR_FILTERED",
}
LABRADOR = {123, 124, 125, 139}
RESULT_CODES = {0: "FAILURE", 1: "SUCCESS", 2: "PENDING", 3: "UNSUPPORTED"}

WHOOP_UUID_NAMES = {
    "61080001-8d6d-82b8-614a-1c8cb0f8dcc6": "WHOOP4 service",
    "61080002-8d6d-82b8-614a-1c8cb0f8dcc6": "WHOOP4 cmd write",
    "61080003-8d6d-82b8-614a-1c8cb0f8dcc6": "WHOOP4 cmd notify",
    "61080004-8d6d-82b8-614a-1c8cb0f8dcc6": "WHOOP4 event notify",
    "61080005-8d6d-82b8-614a-1c8cb0f8dcc6": "WHOOP4 data notify",
    "fd4b0001-cce1-4033-93ce-002d5875f58a": "WHOOP5/MG service",
    "fd4b0002-cce1-4033-93ce-002d5875f58a": "WHOOP5/MG cmd write",
    "fd4b0003-cce1-4033-93ce-002d5875f58a": "WHOOP5/MG cmd notify",
    "fd4b0004-cce1-4033-93ce-002d5875f58a": "WHOOP5/MG event notify",
    "fd4b0005-cce1-4033-93ce-002d5875f58a": "WHOOP5/MG data notify",
    "fd4b0007-cce1-4033-93ce-002d5875f58a": "WHOOP5/MG aux notify",
    "00002a24-0000-1000-8000-00805f9b34fb": "DIS model number",
    "00002a25-0000-1000-8000-00805f9b34fb": "DIS serial number",
    "00002a26-0000-1000-8000-00805f9b34fb": "DIS firmware revision",
    "00002a27-0000-1000-8000-00805f9b34fb": "DIS hardware revision",
    "00002a37-0000-1000-8000-00805f9b34fb": "Heart Rate Measurement",
    "00002a19-0000-1000-8000-00805f9b34fb": "Battery Level",
}

ECG_HEADER_LEN = 17

# --------------------------------------------------------------------------------------------------
# CRCs
# --------------------------------------------------------------------------------------------------


def crc8(data: bytes) -> int:
    c = 0
    for b in data:
        c ^= b
        for _ in range(8):
            c = ((c << 1) ^ 0x07) & 0xFF if c & 0x80 else (c << 1) & 0xFF
    return c


def crc16_modbus(data: bytes) -> int:
    c = 0xFFFF
    for b in data:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def crc32(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


# --------------------------------------------------------------------------------------------------
# WHOOP frame reassembly (family detected from the header CRC, per handle+direction stream)
# --------------------------------------------------------------------------------------------------


@dataclass
class Frame:
    family: str          # "whoop4" | "whoop5"
    raw: bytes
    inner: bytes         # [type, seq, cmd/…]
    crc_ok: bool
    ts_us: int
    handle: int
    direction: str       # "phone→strap" | "strap→phone"

    @property
    def ptype(self) -> int:
        return self.inner[0]


class Reassembler:
    """Concatenate ATT values for one (handle, direction), cut CRC-valid WHOOP frames out."""

    def __init__(self) -> None:
        self.buf = bytearray()

    def feed(self, chunk: bytes, ts_us: int, handle: int, direction: str) -> list[Frame]:
        self.buf += chunk
        out: list[Frame] = []
        while True:
            i = self.buf.find(bytes([SOF]))
            if i < 0:
                self.buf.clear()
                return out
            if i > 0:
                del self.buf[:i]
            if len(self.buf) < 8:
                return out
            fam, total = self._probe_header(bytes(self.buf[:8]))
            if fam is None:
                del self.buf[0]  # not a header at this SOF; resync on the next 0xAA
                continue
            if len(self.buf) < total:
                return out
            raw = bytes(self.buf[:total])
            del self.buf[:total]
            if fam == "whoop4":
                inner = raw[4:-4]
                ok = crc32(inner) == struct.unpack_from("<I", raw, total - 4)[0]
            else:
                inner = raw[8:-4]
                ok = crc32(inner) == struct.unpack_from("<I", raw, total - 4)[0]
            out.append(Frame(fam, raw, inner, ok, ts_us, handle, direction))

    @staticmethod
    def _probe_header(h: bytes) -> tuple[str | None, int]:
        # 4.0: AA len_lo len_hi crc8(len) ... total = len + 4
        if crc8(h[1:3]) == h[3]:
            length = h[1] | (h[2] << 8)
            if 5 <= length <= 4096:
                return "whoop4", length + 4
        # 5/MG: AA hdr len_lo len_hi xx xx crc16_lo crc16_hi ... total = len + 8
        if crc16_modbus(h[0:6]) == (h[6] | (h[7] << 8)):
            length = h[2] | (h[3] << 8)
            if 5 <= length <= 4096:
                return "whoop5", length + 8
        return None, 0


# --------------------------------------------------------------------------------------------------
# btsnoop → HCI ACL → L2CAP → ATT
# --------------------------------------------------------------------------------------------------

BTSNOOP_MAGIC = b"btsnoop\x00"


@dataclass
class AttPdu:
    ts_us: int
    direction: str
    opcode: int
    body: bytes


def read_btsnoop(data: bytes) -> list[tuple[int, bool, bytes]]:
    """Return (timestamp_us, is_received, hci_packet_with_h4_type) records."""
    if data[:8] != BTSNOOP_MAGIC:
        raise SystemExit("not a btsnoop file (magic mismatch)")
    _version, datalink = struct.unpack_from(">II", data, 8)
    off = 16
    out = []
    while off + 24 <= len(data):
        orig_len, incl_len, flags, _drops, ts = struct.unpack_from(">IIIIq", data, off)
        off += 24
        pkt = data[off:off + incl_len]
        off += incl_len
        received = bool(flags & 1)
        if datalink == 1002:  # H4 with type byte
            out.append((ts, received, pkt))
        elif datalink == 1001:  # unencapsulated: flags bit1 says command/event vs data
            is_cmd_evt = bool(flags & 2)
            if is_cmd_evt:
                continue  # commands/events carry no ATT
            out.append((ts, received, bytes([0x02]) + pkt))
        else:
            raise SystemExit(f"unsupported btsnoop datalink {datalink}")
    return out


def att_pdus(records: list[tuple[int, bool, bytes]]) -> list[AttPdu]:
    """Walk ACL packets, reassemble L2CAP over the PB flag, keep CID 0x0004 (ATT)."""
    pending: dict[tuple[int, bool], bytearray] = defaultdict(bytearray)
    expected: dict[tuple[int, bool], int] = {}
    out: list[AttPdu] = []
    for ts, received, pkt in records:
        if not pkt or pkt[0] != 0x02 or len(pkt) < 5:
            continue
        hf, dlen = struct.unpack_from("<HH", pkt, 1)
        handle = hf & 0x0FFF
        pb = (hf >> 12) & 0x3
        payload = pkt[5:5 + dlen]
        key = (handle, received)
        if pb in (0b00, 0b10):  # first (non-flushable / flushable)
            if len(payload) < 4:
                continue
            l2len, cid = struct.unpack_from("<HH", payload, 0)
            pending[key] = bytearray(payload)
            expected[key] = l2len + 4
        else:  # continuation
            if key not in pending:
                continue
            pending[key] += payload
        if key in expected and len(pending[key]) >= expected[key]:
            sdu = bytes(pending.pop(key)[:expected.pop(key)])
            cid = struct.unpack_from("<H", sdu, 2)[0]
            if cid == 0x0004 and len(sdu) > 4:
                body = sdu[4:]
                out.append(AttPdu(ts, "strap→phone" if received else "phone→strap", body[0], body[1:]))
    return out


def uuid_str(b: bytes) -> str:
    if len(b) == 2:
        return "0000%04x-0000-1000-8000-00805f9b34fb" % struct.unpack("<H", b)[0]
    if len(b) == 16:
        u = b[::-1].hex()
        return f"{u[0:8]}-{u[8:12]}-{u[12:16]}-{u[16:20]}-{u[20:32]}"
    return b.hex()


@dataclass
class Triage:
    handle_uuid: dict[int, str] = field(default_factory=dict)
    dis_reads: list[tuple[int, str, str]] = field(default_factory=list)   # handle, uuid, text
    frames: list[Frame] = field(default_factory=list)
    att_opcodes: Counter = field(default_factory=Counter)
    values_by_handle: Counter = field(default_factory=Counter)


def triage(pdus: list[AttPdu]) -> Triage:
    t = Triage()
    reasm: dict[tuple[int, str], Reassembler] = defaultdict(Reassembler)
    last_read_by_type: bytes | None = None
    last_read_handle: int | None = None
    for p in pdus:
        t.att_opcodes[p.opcode] += 1
        op, b = p.opcode, p.body
        if op == 0x08 and len(b) >= 6:                       # Read By Type Request
            last_read_by_type = b[4:]
        elif op == 0x09 and len(b) >= 1 and last_read_by_type == b"\x03\x28":  # char declarations
            n = b[0]
            for i in range(1, len(b) - n + 1, n):
                ent = b[i:i + n]
                if len(ent) < 5:
                    continue
                value_handle = struct.unpack_from("<H", ent, 3)[0]
                t.handle_uuid[value_handle] = uuid_str(ent[5:])
        elif op == 0x0A and len(b) >= 2:                     # Read Request
            last_read_handle = struct.unpack_from("<H", b, 0)[0]
        elif op == 0x0B and last_read_handle is not None:    # Read Response
            u = t.handle_uuid.get(last_read_handle, "?")
            if u.startswith("00002a2") or all(32 <= c < 127 for c in b):
                t.dis_reads.append((last_read_handle, u, b.decode("ascii", "replace").rstrip("\x00")))
            last_read_handle = None
        elif op in (0x12, 0x52, 0x1B, 0x1D) and len(b) >= 2:  # Write Req / Write Cmd / Notify / Indicate
            handle = struct.unpack_from("<H", b, 0)[0]
            value = b[2:]
            t.values_by_handle[(handle, p.direction)] += 1
            for f in reasm[(handle, p.direction)].feed(value, p.ts_us, handle, p.direction):
                t.frames.append(f)
    return t


# --------------------------------------------------------------------------------------------------
# Labrador structural triage
# --------------------------------------------------------------------------------------------------


@dataclass
class EcgCandidate:
    frame: Frame
    header: dict
    samples: list[int]


def labrador_filtered(frame: Frame) -> EcgCandidate | None:
    """Type-43 inner [43, seq, cmd, payload…]; payload = 17-byte header + int16 LE samples (+ pad<4)."""
    if frame.family != "whoop5" or frame.ptype != 43 or not frame.crc_ok:
        return None
    payload = frame.inner[3:]
    if len(payload) < ECG_HEADER_LEN + 2:
        return None
    h = payload[:ECG_HEADER_LEN]
    n = h[15] | (h[16] << 8)
    if n == 0:
        return None
    rest = payload[ECG_HEADER_LEN:]
    if len(rest) < 2 * n or len(rest) - 2 * n >= 4:
        return None
    samples = list(struct.unpack_from("<%dh" % n, rest, 0))
    header = {
        "signalQuality": h[0], "statusFlags": h[1], "started": h[2], "running": h[3],
        "stoppedAndComplete": h[4], "leadsOn": h[5], "checkResult": h[6], "checkStatus": h[7],
        "progress": h[8], "unreadableReason": h[9], "avgHR": h[10], "hr": h[11],
        "hrv": h[12] | (h[13] << 8), "stress": h[14], "numSamples": n,
    }
    return EcgCandidate(frame, header, samples)


# --------------------------------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------------------------------


def name_handle(t: Triage, handle: int) -> str:
    u = t.handle_uuid.get(handle)
    if not u:
        return f"0x{handle:04x}"
    return f"0x{handle:04x} {WHOOP_UUID_NAMES.get(u, u)}"


def report(t: Triage, hex_count: int, csv_path: str | None, out=sys.stdout) -> dict:
    w = out.write
    w("WHOOP ECG TRIAGE\n================\n")
    w(f"ATT PDUs by opcode: {dict(sorted(t.att_opcodes.items()))}\n\n")

    w("Characteristic handles (from discovery):\n")
    for h, u in sorted(t.handle_uuid.items()):
        tag = WHOOP_UUID_NAMES.get(u)
        if tag or u.startswith("fd4b") or u.startswith("6108"):
            w(f"  0x{h:04x}  {u}  {tag or ''}\n")
    if not t.handle_uuid:
        w("  (none — capture started after discovery; handles below are numeric only)\n")

    w("\nDevice Information Service reads:\n")
    if not t.dis_reads:
        w("  (none)\n")
    for h, u, text in t.dis_reads:
        w(f"  {name_handle(t, h)} = {text!r}\n")
    variant = "UNKNOWN"
    for _h, u, text in t.dis_reads:
        if u.endswith("2a24-0000-1000-8000-00805f9b34fb") and text.strip() == "MG":
            variant = "MG (model number)"
        elif u.endswith("2a25-0000-1000-8000-00805f9b34fb") and text.startswith("5AM"):
            variant = variant if variant.startswith("MG") else "MG (serial prefix)"
        elif u.endswith("2a27-0000-1000-8000-00805f9b34fb") and "WG50" in text and variant == "UNKNOWN":
            variant = "5.0 (hardware revision WG50)"
    w(f"  → variant: {variant}\n")

    w("\nWHOOP frames by family / direction / type:\n")
    hist: Counter = Counter()
    bad = 0
    for f in t.frames:
        if not f.crc_ok:
            bad += 1
            continue
        hist[(f.family, f.direction, f.ptype)] += 1
    for (fam, d, pt), n in sorted(hist.items()):
        w(f"  {fam:6} {d:12} type {pt:3} {PACKET_TYPES.get(pt, '?'):26} ×{n}\n")
    w(f"  CRC32 failures: {bad}\n")

    w("\nCommands written by the phone:\n")
    cmd_hist: Counter = Counter()
    for f in t.frames:
        if f.crc_ok and f.direction == "phone→strap" and f.ptype in (35, 37) and len(f.inner) >= 3:
            cmd_hist[f.inner[2]] += 1
    for c, n in sorted(cmd_hist.items()):
        mark = "  ← LABRADOR" if c in LABRADOR else ""
        w(f"  {c:3} {COMMANDS.get(c, '?'):32} ×{n}{mark}\n")
    for f in t.frames:
        if f.crc_ok and f.direction == "phone→strap" and f.ptype in (35, 37) and len(f.inner) >= 3 and f.inner[2] in LABRADOR:
            w(f"    {COMMANDS[f.inner[2]]} payload={f.inner[3:].hex()} frame={f.raw.hex()}\n")

    w("\n5/MG command responses (result at frame[12]):\n")
    resp_hist: Counter = Counter()
    for f in t.frames:
        if f.family == "whoop5" and f.crc_ok and f.ptype in (36, 38) and len(f.raw) > 12:
            cmd, res = f.inner[2], f.raw[12]
            resp_hist[(cmd, res)] += 1
    if not resp_hist:
        w("  (none)\n")
    for (cmd, res), n in sorted(resp_hist.items()):
        mark = "  ← LABRADOR" if cmd in LABRADOR else ""
        w(f"  {cmd:3} {COMMANDS.get(cmd, '?'):32} → {res} {RESULT_CODES.get(res, '?'):11} ×{n}{mark}\n")

    w("\nLabrador filtered-ECG structural triage (type 43, 5/MG):\n")
    cands = [c for c in (labrador_filtered(f) for f in t.frames) if c]
    type43 = sum(1 for f in t.frames if f.family == "whoop5" and f.ptype == 43 and f.crc_ok)
    w(f"  type-43 frames: {type43}; ECG-shaped: {len(cands)}; samples: {sum(len(c.samples) for c in cands)}\n")
    if len(cands) >= 2:
        span = (cands[-1].frame.ts_us - cands[0].frame.ts_us) / 1e6
        if span > 0:
            w(f"  measured sample rate ≈ {sum(len(c.samples) for c in cands) / span:.1f} Hz over {span:.1f}s\n")
    for c in cands[:hex_count]:
        w(f"  hdr {c.header}\n    raw {c.frame.raw.hex()}\n")
    if csv_path and cands:
        with open(csv_path, "w", encoding="utf-8") as fh:
            fh.write("# raw i16 samples from type-43 Labrador frames; unit/scale unattested\n")
            fh.write("packet,ts_us,index,sample\n")
            for pi, c in enumerate(cands):
                for i, s in enumerate(c.samples):
                    fh.write(f"{pi},{c.frame.ts_us},{i},{s}\n")
        w(f"  samples written to {csv_path}\n")

    if hex_count:
        w(f"\nFirst {hex_count} frames of each 5/MG type (strap→phone):\n")
        seen: Counter = Counter()
        for f in t.frames:
            if f.family == "whoop5" and f.crc_ok and f.direction == "strap→phone" and seen[f.ptype] < hex_count:
                seen[f.ptype] += 1
                w(f"  type {f.ptype:3} len {len(f.raw):4} {f.raw.hex()}\n")

    return {"variant": variant, "frames": len(t.frames), "ecg_candidates": len(cands), "commands": dict(cmd_hist),
            "responses": {f"{c}:{r}": n for (c, r), n in resp_hist.items()}}


# --------------------------------------------------------------------------------------------------
# Self-test: build a synthetic btsnoop in memory and check every stage
# --------------------------------------------------------------------------------------------------


def puffin_frame(ptype: int, seq: int, cmd: int, payload: bytes) -> bytes:
    inner = bytes([ptype, seq, cmd]) + payload
    inner += b"\x00" * ((4 - len(inner) % 4) % 4)
    length = len(inner) + 4
    hdr = bytes([SOF, 0x01, length & 0xFF, length >> 8, 0x00, 0x00])
    c16 = crc16_modbus(hdr)
    return hdr + struct.pack("<H", c16) + inner + struct.pack("<I", crc32(inner))


def _btsnoop(records: list[tuple[bool, bytes]]) -> bytes:
    out = bytearray(BTSNOOP_MAGIC + struct.pack(">II", 1, 1002))
    ts = 0x00E03AB44A676000
    for received, h4 in records:
        ts += 20_000
        out += struct.pack(">IIIIq", len(h4), len(h4), 1 if received else 0, 0, ts) + h4
    return bytes(out)


def _acl(handle: int, pb: int, payload: bytes) -> bytes:
    return bytes([0x02]) + struct.pack("<HH", handle | (pb << 12), len(payload)) + payload


def _att(pdu: bytes) -> bytes:
    return struct.pack("<HH", len(pdu), 0x0004) + pdu


def selftest() -> None:
    conn = 0x0040
    recs: list[tuple[bool, bytes]] = []
    # Discovery: Read By Type Request for characteristic declarations, response naming fd4b0002/0003 + 2A24.
    recs.append((False, _acl(conn, 0b10, _att(b"\x08" + struct.pack("<HH", 1, 0xFFFF) + b"\x03\x28"))))
    ent = b""
    for decl_handle, value_handle, uuid in ((0x20, 0x21, "fd4b0002-cce1-4033-93ce-002d5875f58a"),
                                            (0x22, 0x23, "fd4b0003-cce1-4033-93ce-002d5875f58a")):
        u = bytes.fromhex(uuid.replace("-", ""))[::-1]
        ent += struct.pack("<HBH", decl_handle, 0x10, value_handle) + u
    recs.append((True, _acl(conn, 0b10, _att(b"\x09" + bytes([21]) + ent))))
    recs.append((False, _acl(conn, 0b10, _att(b"\x08" + struct.pack("<HH", 1, 0xFFFF) + b"\x03\x28"))))
    recs.append((True, _acl(conn, 0b10, _att(b"\x09" + bytes([7]) + struct.pack("<HBH", 0x30, 0x02, 0x31) + b"\x24\x2a"))))
    # DIS model number read.
    recs.append((False, _acl(conn, 0b10, _att(b"\x0a" + struct.pack("<H", 0x31)))))
    recs.append((True, _acl(conn, 0b10, _att(b"\x0b" + b"MG"))))
    # Phone writes TOGGLE_LABRADOR_FILTERED(139)=1 to fd4b0002.
    recs.append((False, _acl(conn, 0b10, _att(b"\x12" + struct.pack("<H", 0x21) + puffin_frame(35, 1, 139, b"\x01\x01")))))
    # Strap answers SUCCESS on fd4b0003 (inner [36, seq, cmd, 0x01, result]).
    recs.append((True, _acl(conn, 0b10, _att(b"\x1b" + struct.pack("<H", 0x23) + puffin_frame(36, 1, 139, b"\x01\x01")))))
    # Two type-43 ECG frames, the second split across two ACL fragments AND two notifications.
    samples = list(range(-8, 8))
    hdr = bytes([3, 5, 1, 1, 0, 1, 0, 1, 42, 0, 61, 63, 0x2C, 0x03, 17, len(samples), 0])
    payload = hdr + struct.pack("<%dh" % len(samples), *samples)
    f1 = puffin_frame(43, 2, 0, payload)
    f2 = puffin_frame(43, 3, 0, payload)
    recs.append((True, _acl(conn, 0b10, _att(b"\x1b" + struct.pack("<H", 0x23) + f1))))
    n1 = b"\x1b" + struct.pack("<H", 0x23) + f2[:20]
    n2 = b"\x1b" + struct.pack("<H", 0x23) + f2[20:]
    l2 = _att(n1)
    recs.append((True, _acl(conn, 0b10, l2[:9])))
    recs.append((True, _acl(conn, 0b01, l2[9:])))
    recs.append((True, _acl(conn, 0b10, _att(n2))))
    # Junk that must not decode.
    recs.append((True, _acl(conn, 0b10, _att(b"\x1b" + struct.pack("<H", 0x23) + b"\xaa\x01\x02\x03"))))

    data = _btsnoop(recs)
    t = triage(att_pdus(read_btsnoop(data)))
    import io
    buf = io.StringIO()
    summary = report(t, hex_count=1, csv_path=None, out=buf)
    text = buf.getvalue()
    assert t.handle_uuid[0x21].startswith("fd4b0002"), t.handle_uuid
    assert t.handle_uuid[0x31].endswith("2a24-0000-1000-8000-00805f9b34fb"), t.handle_uuid
    assert summary["variant"].startswith("MG"), summary
    assert summary["commands"] == {139: 1}, summary
    assert summary["responses"] == {"139:1": 1}, summary
    assert summary["ecg_candidates"] == 2, summary
    assert "TOGGLE_LABRADOR_FILTERED" in text and "SUCCESS" in text
    assert "'numSamples': 16" in text
    print("selftest OK")
    print(text)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("log", nargs="?", help="btsnoop_hci.log from Android (Developer options → HCI snoop)")
    ap.add_argument("--csv", help="write the ECG-shaped samples to this CSV")
    ap.add_argument("--hex", type=int, default=3, help="how many frames per type to hex-dump (default 3)")
    ap.add_argument("--selftest", action="store_true", help="run the built-in synthetic pipeline check")
    a = ap.parse_args(argv)
    if a.selftest:
        selftest()
        return 0
    if not a.log:
        ap.error("a btsnoop log path is required (or --selftest)")
    with open(a.log, "rb") as fh:
        data = fh.read()
    t = triage(att_pdus(read_btsnoop(data)))
    report(t, hex_count=a.hex, csv_path=a.csv)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
