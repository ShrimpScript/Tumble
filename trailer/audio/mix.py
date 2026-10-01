"""Mixes the trailer soundtrack: the score, every timed cue the renderer exported
(game sounds, pitched and varied like the game does), synthesized trailer effects and
ambience beds.

    python3 mix.py cues.json music.wav out.wav
"""
import glob
import json
import os
import re
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

SR = 48000
WORK = os.environ.get("TRAILER_WORK", "/home/user/work")
SND = os.path.join(WORK, "sounds")
DURATION = 84.0
rng = np.random.default_rng(1234)


def load(path):
    sr, d = wavfile.read(path)
    d = d.astype(np.float32) / 32768.0
    if d.ndim == 1:
        d = np.stack([d, d], 1)
    return d


def variants(name):
    exact = os.path.join(SND, name + ".wav")
    if os.path.exists(exact):
        return [exact]
    files = sorted(glob.glob(os.path.join(SND, name + "*.wav")), key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", p)])
    return [f for f in files if re.fullmatch(re.escape(os.path.join(SND, name)) + r"\d*\.wav", f)]


def repitch(x, rate):
    if abs(rate - 1.0) < 1e-3:
        return x
    n = int(len(x) / rate)
    idx = np.arange(n) * rate
    return np.stack([np.interp(idx, np.arange(len(x)), x[:, c]) for c in range(2)], 1).astype(np.float32)


# -- synthesized effects --------------------------------------------------------------------

def bandpass(x, lo, hi):
    sos = butter(2, [lo / (SR / 2), hi / (SR / 2)], btype="band", output="sos")
    return sosfilt(sos, x)


def lowpass(x, f):
    sos = butter(2, f / (SR / 2), output="sos")
    return sosfilt(sos, x)


def stereo(m, width=0.0):
    if width <= 0:
        return np.stack([m, m], 1).astype(np.float32)
    d = np.roll(m, int(width * SR / 1000))
    return np.stack([m, d], 1).astype(np.float32)


def sfx_wind(dur=3.6):
    n = int(dur * SR)
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    # Sweep a band slowly for the howl.
    seg = SR // 20
    for i in range(0, n, seg):
        f = 300 + 250 * np.sin(i / SR * 1.7) + 150 * np.sin(i / SR * 0.6)
        out[i:i + seg] = bandpass(noise[i:i + seg + 2000], f * 0.6, f * 1.6)[:len(out[i:i + seg])]
    env = np.clip(np.arange(n) / (0.6 * SR), 0, 1) * np.clip((n - np.arange(n)) / (0.4 * SR), 0, 1)
    return stereo(out * env * 0.6, 7)


def sfx_scratch():
    n = int(0.42 * SR)
    t = np.arange(n) / SR
    # Two sweeps of a resonant band over noise: forward then back, like a hand on vinyl.
    f = np.where(t < 0.18, 600 + 3200 * (t / 0.18), 3800 - 3300 * ((t - 0.18) / 0.24))
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    seg = 256
    for i in range(0, n, seg):
        fc = f[i]
        out[i:i + seg] = bandpass(noise[max(0, i - 2048):i + seg], fc * 0.8, fc * 1.25)[-len(out[i:i + seg]):]
    ph = np.cumsum(f * 0.35) / SR * 2 * np.pi
    out = out * 0.9 + 0.35 * np.sin(ph) * np.exp(-t * 3)
    env = np.minimum(1, t / 0.01) * np.exp(-((t - 0.2) ** 2) / 0.03)
    return stereo(out * env * 1.2)


def sfx_rewind(dur=1.5):
    n = int(dur * SR)
    t = np.arange(n) / SR
    out = np.zeros(n)
    # Chirpy reversed tape: rapid upward chirps that speed up.
    rate = 8 + 22 * (t / dur) ** 1.5
    phase = np.cumsum(rate) / SR
    chirp_pos = phase % 1.0
    f = 1200 + 2800 * chirp_pos
    out += 0.25 * np.sin(np.cumsum(f) / SR * 2 * np.pi) * (chirp_pos < 0.6)
    out += 0.25 * lowpass(rng.standard_normal(n), 3000) * (0.5 + 0.5 * np.sin(2 * np.pi * 30 * t))
    env = np.clip(t / 0.08, 0, 1) * np.clip((dur - t) / 0.15, 0, 1)
    return stereo(out * env, 4)


def sfx_whoosh(dur=1.2):
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    seg = 512
    for i in range(0, n, seg):
        fc = 2200 * (1 - t[i] / dur) + 250
        out[i:i + seg] = bandpass(noise[max(0, i - 2048):i + seg], fc * 0.6, fc * 1.5)[-len(out[i:i + seg]):]
    env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
    return stereo(out * env * 0.9, 9)


def sfx_thud(dur=0.6, f0=70, gain=1.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = f0 * (1 + 1.5 * np.exp(-t * 30))
    body = np.sin(np.cumsum(f) / SR * 2 * np.pi) * np.exp(-t * 9)
    click = lowpass(rng.standard_normal(n), 900) * np.exp(-t * 40) * 0.6
    return stereo((body + click) * gain)


def sfx_letter():
    n = int(0.08 * SR)
    t = np.arange(n) / SR
    return stereo(np.sin(2 * np.pi * 1100 * t) * np.exp(-t * 60) * 0.3)


SYNTH = {"sfx/wind": sfx_wind, "sfx/scratch": sfx_scratch, "sfx/rewind": sfx_rewind, "sfx/whoosh": sfx_whoosh,
         "sfx/thud": lambda: sfx_thud(0.6, 62, 1.1), "sfx/flop": lambda: sfx_thud(0.5, 85, 0.6), "sfx/letter": sfx_letter}


def loop_bed(names, start, end, gain, fade=0.5):
    """Concatenates game sounds into a bed from start to end."""
    parts = [load(p) for nm in names for p in variants(nm)]
    if not parts:
        return None
    seq = np.concatenate(parts, 0)
    n = int((end - start) * SR)
    reps = int(np.ceil(n / len(seq)))
    bed = np.tile(seq, (reps, 1))[:n]
    env = np.clip(np.arange(n) / (fade * SR), 0, 1) * np.clip((n - np.arange(n)) / (fade * SR), 0, 1)
    return bed * env[:, None] * gain


def limit(x, ceiling=0.89, lookahead=0.005, release=0.12):
    """Look-ahead peak limiter: gain follows the peak envelope, fast attack, slow release."""
    peak = np.abs(x).max(axis=1)
    la = int(lookahead * SR)
    # Peak over the look-ahead window.
    from scipy.ndimage import maximum_filter1d
    env = maximum_filter1d(peak, size=2 * la + 1)
    want = np.minimum(1.0, ceiling / np.maximum(env, 1e-9))
    g = np.empty_like(want)
    cur = 1.0
    rel = np.exp(-1.0 / (release * SR))
    for i in range(len(want)):
        w = want[i]
        cur = w if w < cur else w + (cur - w) * rel
        g[i] = cur
    return (x * g[:, None]).astype(np.float32)


def main(cues_path, music_path, out_path):
    total = int(DURATION * SR)
    sfx = np.zeros((total + SR * 4, 2), np.float32)
    beds = np.zeros_like(sfx)

    def put(buf, x, t, gain):
        i0 = int(t * SR)
        i1 = min(i0 + len(x), len(buf))
        if i1 > i0:
            buf[i0:i1] += x[: i1 - i0] * gain

    cues = json.load(open(cues_path))
    for c in sorted(cues, key=lambda c: c["t"]):
        name = c["name"]
        if name in SYNTH:
            x = SYNTH[name]()
        else:
            files = variants(name)
            if not files:
                print("missing sound", name)
                continue
            pick = files[int(c.get("random", 0)) % len(files)]
            x = load(pick)
        # The game varies pitch a little on steps and hits; cues may pin it.
        x = repitch(x, c.get("pitch", 1.0))
        put(sfx, x, c["t"], c.get("vol", 1.0))

    # Ambience beds.
    def bed(names, a, b, gain, fade=0.5):
        x = loop_bed(names, a, b, gain, fade)
        if x is not None:
            put(beds, x, a, 1.0)

    bed(["ambient/weather/rain"], 44.0, 47.05, 0.55, 0.15)
    bed(["liquid/water"], 28.2, 35.3, 0.18, 0.4)
    bed(["liquid/water"], 36.0, 44.0, 0.12, 0.6)
    bed(["liquid/water"], 59.0, 72.0, 0.2, 0.6)
    cave = variants("ambient/cave/cave")
    if cave:
        put(beds, load(cave[12 % len(cave)]), 35.75, 0.7)
        put(beds, load(cave[3 % len(cave)]), 59.6, 0.25)

    music = load(music_path)[:total]
    # Music dips under the loudest effects so they punch through.
    duck = np.ones(len(music), np.float32)
    for c in cues:
        if c["name"] in ("random/explode", "ambient/weather/thunder"):
            i0 = int(c["t"] * SR)
            i1 = min(len(duck), i0 + int(1.2 * SR))
            duck[i0:i1] = np.minimum(duck[i0:i1], np.linspace(0.45, 1.0, i1 - i0))
    music = music * duck[:, None]

    # Gain staging by loudness, not by peak: music sits at about -20 dBFS RMS where it
    # plays, effects ride above it, and a limiter catches the transients.
    def rms(x):
        active = np.abs(x).max(axis=1) > 1e-3
        return np.sqrt((x[active] ** 2).mean()) if active.any() else 1.0
    music_gain = 0.10 / rms(music)
    mix = np.zeros((total, 2), np.float32)
    mix[: len(music)] += music * music_gain
    mix += sfx[:total] * 0.9
    mix += beds[:total] * 0.9
    mix = limit(mix, ceiling=0.89)
    wavfile.write(out_path, SR, (mix * 32767).astype(np.int16))
    print("wrote", out_path, "%d cues" % len(cues))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
