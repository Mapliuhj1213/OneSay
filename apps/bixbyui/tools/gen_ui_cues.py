#!/usr/bin/env python3
"""tools/gen_ui_cues.py -- synthesized (no TTS, no samples) UI cues for the open-source build.
24 kHz / 16-bit / mono, plain 44-byte RIFF header, RMS ~4000, peak <= 29000 (same loudness convention as the voice templates).
ack_done.wav: soft rising two-note chime (E5 -> A5), ~0.42 s.  confirm_prompt.wav: two equal attention pulses (A5, A5) + low tail, ~0.75 s.
Original work generated from scratch for this repository (no third-party audio); covered by the repository LICENSE.
Usage (from apps/bixbyui): python3 tools/gen_ui_cues.py [out_dir]   -> byte-identical output every run."""
import math, struct, sys
SR = 24000
def note(f, dur, start, buf, amp=1.0, decay=7.0):
    n0 = int(start * SR); n = int(dur * SR)
    for i in range(n):
        t = i / SR
        env = (1 - math.exp(-t / 0.004)) * math.exp(-decay * t)          # 4 ms attack, exponential decay
        s = math.sin(2*math.pi*f*t) + 0.35*math.sin(2*math.pi*2*f*t) + 0.12*math.sin(2*math.pi*3*f*t)
        if n0 + i < len(buf): buf[n0 + i] += amp * env * s
def finish(buf, path, rms_target=4000.0, peak_max=29000):
    m = len(buf); fade = int(0.015 * SR)
    for i in range(fade): buf[m - 1 - i] *= i / fade                    # 15 ms fade-out, no click
    sq = [x*x for x in buf]; active = [x for x in sq if x > 1e-6]
    rms = math.sqrt(sum(active) / len(active)); g = rms_target / rms
    peak = max(abs(x) for x in buf) * g
    if peak > peak_max: g *= peak_max / peak
    pcm = b''.join(struct.pack('<h', max(-32768, min(32767, int(round(x * g))))) for x in buf)
    hdr = b'RIFF' + struct.pack('<I', 36 + len(pcm)) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 1, SR, SR*2, 2, 16) + b'data' + struct.pack('<I', len(pcm))
    open(path, 'wb').write(hdr + pcm)
    out = [int(round(x*g)) for x in buf]
    print(path, 'ms=%d' % (1000*m//SR), 'peak=%d' % max(abs(x) for x in out), 'rms_active=%.0f' % math.sqrt(sum(x*x for x in out if x)/max(1,sum(1 for x in out if x))))
d = sys.argv[1] if len(sys.argv) > 1 else 'app/assets'   # run from apps/bixbyui
b = [0.0] * int(0.42 * SR); note(659.25, 0.20, 0.00, b, 0.8, 9.0); note(880.0, 0.30, 0.10, b, 1.0, 8.0); finish(b, d + '/ack_done.wav')
b = [0.0] * int(0.75 * SR); note(880.0, 0.16, 0.00, b, 1.0, 10.0); note(880.0, 0.16, 0.18, b, 1.0, 10.0); note(587.33, 0.40, 0.36, b, 0.7, 6.0); finish(b, d + '/confirm_prompt.wav')
