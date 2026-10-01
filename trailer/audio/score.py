"""Original score for the trailer, played entirely on Minecraft note block samples.

Every instrument is a vanilla note block sound, pitched the way note blocks pitch
them (playback rate 2^((note - base) / 12) from the sample's own note). The music is
written at 120 BPM so every bar is exactly two seconds and every cut in the edit
lands on a beat.

    python3 score.py out.wav
"""
import os
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import fftconvolve

SR = 48000
BPM = 120.0
BEAT = 60.0 / BPM
WORK = os.environ.get("TRAILER_WORK", "/home/user/work")
SND = os.path.join(WORK, "sounds", "note")
DURATION = 84.0

# Sample note for each instrument (MIDI), from the note block ranges in the wiki:
# harp F#3-F#5 is centred on F#4, bass on F#2, bell / chime / xylophone on F#6, etc.
BASE = {"harp": 66, "pling": 66, "bit": 66, "banjo": 66, "iron_xylophone": 66, "bass": 42, "didgeridoo": 42,
        "guitar": 54, "flute": 78, "cow_bell": 78, "bell": 90, "icechime": 90, "xylobone": 90}
PAN = {"harp": -0.15, "pling": 0.25, "bit": 0.3, "banjo": -0.3, "iron_xylophone": 0.2, "bass": 0.0, "didgeridoo": 0.0,
       "guitar": -0.35, "flute": 0.2, "cow_bell": 0.35, "bell": 0.4, "icechime": -0.4, "xylobone": 0.15,
       "bd": 0.0, "snare": 0.05, "hat": 0.25}

_cache = {}


def sample(name):
    if name not in _cache:
        sr, d = wavfile.read(os.path.join(SND, name + ".wav"))
        d = d.astype(np.float32) / 32768.0
        if d.ndim > 1:
            d = d.mean(axis=1)
        _cache[name] = d
    return _cache[name]


def note_name(n):
    names = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6, "G": 7,
             "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11}
    if isinstance(n, (int, float)):
        return n
    p = n[:-1]
    o = int(n[-1])
    return 12 * (o + 1) + names[p]


class Track:
    def __init__(self):
        self.buf = np.zeros((int(DURATION * SR) + SR, 2), np.float32)

    def play(self, t, inst, note=None, vel=1.0, length=None, vibrato=0.0):
        """Places one note block hit at time t (seconds)."""
        s = sample(inst)
        if note is not None and inst in BASE:
            rate = 2.0 ** ((note_name(note) - BASE[inst]) / 12.0)
        elif note is not None:
            rate = 2.0 ** (note_name(note) / 12.0)
        else:
            rate = 1.0
        n_out = int(len(s) / rate)
        if length:
            n_out = min(n_out, int(length * SR))
        idx = np.arange(n_out) * rate
        if vibrato:
            tt = np.arange(n_out) / SR
            idx = idx + vibrato * SR * 0.004 * np.sin(2 * np.pi * 5.5 * tt) * np.clip(tt / 0.25, 0, 1)
            idx = np.clip(idx, 0, len(s) - 1)
        out = np.interp(idx, np.arange(len(s)), s).astype(np.float32)
        if length:
            fade = min(int(0.04 * SR), len(out))
            out[-fade:] *= np.linspace(1, 0, fade)
        out *= vel
        p = PAN.get(inst, 0.0)
        l, r = np.cos((p + 1) * np.pi / 4), np.sin((p + 1) * np.pi / 4)
        i0 = int(t * SR)
        i1 = min(i0 + len(out), len(self.buf))
        self.buf[i0:i1, 0] += out[: i1 - i0] * l * 1.414
        self.buf[i0:i1, 1] += out[: i1 - i0] * r * 1.414


# -- harmony ----------------------------------------------------------------------------------

F_MAJ = ["F", "C", "Dm", "Bb"]
CHORDS = {"F": ["F", "A", "C"], "C": ["C", "E", "G"], "Dm": ["D", "F", "A"], "Bb": ["Bb", "D", "F"],
          "Gm": ["G", "Bb", "D"], "Am": ["A", "C", "E"], "C7": ["C", "E", "Bb"]}
ROOT = {"F": "F", "C": "C", "Dm": "D", "Bb": "Bb", "Gm": "G", "Am": "A", "C7": "C"}


def n(pitch, octave):
    return note_name("%s%d" % (pitch, octave))


# The morning theme: eight bars, answered phrase in the second half.
THEME = [
    # (beat in bar, note, length in beats)
    [(0, "A4", 1), (1, "C5", .5), (1.5, "A4", .5), (2, "F4", 1), (3, "G4", .5), (3.5, "A4", .5)],
    [(0, "G4", 1), (1, "E4", .5), (1.5, "G4", .5), (2, "C5", 1.5), (3.5, "Bb4", .5)],
    [(0, "A4", 1), (1, "F4", .5), (1.5, "A4", .5), (2, "D5", 1), (3, "C5", .5), (3.5, "Bb4", .5)],
    [(0, "A4", .5), (0.5, "G4", .5), (1, "F4", 1), (2, "G4", 2)],
    [(0, "A4", 1), (1, "C5", .5), (1.5, "A4", .5), (2, "F5", 1), (3, "E5", .5), (3.5, "D5", .5)],
    [(0, "C5", 1), (1, "E5", .5), (1.5, "C5", .5), (2, "G4", 1.5), (3.5, "A4", .5)],
    [(0, "Bb4", 1), (1, "D5", .5), (1.5, "F5", .5), (2, "E5", .5), (2.5, "D5", .5), (3, "C5", .5), (3.5, "Bb4", .5)],
    [(0, "A4", 1.5), (1.5, "G4", .5), (2, "F4", 2)],
]
PROG = ["F", "C", "Dm", "Bb", "F", "C", "Bb", "C"]


def bar_time(t0, bar, beat=0.0):
    return t0 + (bar * 4 + beat) * BEAT


def morning(tr, t0, bars, cut_at):
    """Act one: gentle harp theme, oom-pah bass, bell sparkle, soft hats."""
    for b in range(bars):
        chord = PROG[b % 8]
        tones = CHORDS[chord]
        root = ROOT[chord]
        # Bass: root on 1, fifth on 3, with a walk on 4& every other bar.
        for beat, pitch in ((0, n(root, 2)), (2, n(tones[2], 2) if tones[2] not in ("C", "D") else n(tones[2], 3))):
            tt = bar_time(t0, b, beat)
            if tt < cut_at:
                tr.play(tt, "bass", pitch, 0.9)
        # Guitar stabs on 2 and 4 (the "pah").
        if b >= 1:
            for beat in (1, 3):
                tt = bar_time(t0, b, beat)
                if tt < cut_at:
                    for k, tone in enumerate(tones):
                        tr.play(tt, "guitar", n(tone, 3) + (12 if tone in ("C", "D", "E") else 0), 0.18)
        # Melody from bar 2.
        if b >= 2:
            for beat, note, ln in THEME[(b - 2) % 8]:
                tt = bar_time(t0, b, beat)
                if tt < cut_at:
                    tr.play(tt, "harp", note, 0.85)
                    if b >= 6:
                        tr.play(tt, "flute", note_name(note) + 12, 0.12)
        else:
            # Intro: a rising harp arpeggio.
            for k, beat in enumerate((0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5)):
                tt = bar_time(t0, b, beat)
                tone = tones[k % 3]
                tr.play(tt, "harp", n(tone, 4) + (12 if k >= 3 else 0), 0.55)
        # Bell sparkle on the downbeat, soft hats on the off beats.
        tt = bar_time(t0, b, 0)
        if tt < cut_at and b >= 2:
            tr.play(tt, "bell", n(tones[1], 5), 0.22)
        for beat in (0.5, 1.5, 2.5, 3.5):
            tt = bar_time(t0, b, beat)
            if tt < cut_at and b >= 2:
                tr.play(tt, "hat", 0, 0.22)


def fall_runs(tr, times):
    """Cartoon falls: a descending xylophone glissando into each drop."""
    scale = ["F", "E", "D", "C", "Bb", "A", "G", "F", "E", "D", "C", "Bb", "A", "G", "F"]
    for t, dur in times:
        steps = len(scale)
        for k, p in enumerate(scale):
            octave = 6 - (k // 7)
            tr.play(t + dur * k / steps, "xylobone", n(p, octave), 0.55 - 0.02 * k)


def sad_trombone(tr, t):
    """Wah, wah, wah, waaah: didgeridoo doubled by bass, half steps down."""
    notes = [("Bb", 2, 0.42), ("A", 2, 0.42), ("Ab", 2, 0.42), ("G", 2, 1.6)]
    tt = t
    for i, (p, o, ln) in enumerate(notes):
        tr.play(tt, "didgeridoo", n(p, o), 0.9, length=ln, vibrato=1.0 if i == 3 else 0.0)
        tr.play(tt, "bass", n(p, o), 0.5, length=ln)
        tt += ln + 0.05


def full_band(tr, t0, bars, end_at, lead="pling", drums=True, intensity=1.0):
    """Title and montage: same tune, the whole band, a real kit."""
    for b in range(bars):
        chord = PROG[b % 8]
        tones = CHORDS[chord]
        root = ROOT[chord]
        for beat, off in ((0, 0), (1.5, 12), (2, 7), (3, 12), (3.5, 0)):
            tt = bar_time(t0, b, beat)
            if tt < end_at:
                tr.play(tt, "bass", n(root, 2) + off if off != 7 else n(tones[2], 2), 0.95 * intensity)
        for beat in (0.5, 1, 1.5, 2.5, 3, 3.5):
            tt = bar_time(t0, b, beat)
            if tt < end_at:
                for tone in tones:
                    tr.play(tt, "banjo", n(tone, 4), 0.11 * intensity)
        for beat, note, ln in THEME[b % 8]:
            tt = bar_time(t0, b, beat)
            if tt < end_at:
                tr.play(tt, lead, note, 0.7 * intensity)
                tr.play(tt, "harp", note_name(note) + 12, 0.35 * intensity)
                tr.play(tt, "bell", note_name(note) - 12 + 12, 0.07 * intensity)
        if drums:
            for beat in (0, 1.5, 2.25, 3):
                tt = bar_time(t0, b, beat)
                if tt < end_at:
                    tr.play(tt, "bd", -4, 0.9 * intensity)
            for beat in (1, 3):
                tt = bar_time(t0, b, beat)
                if tt < end_at:
                    tr.play(tt, "snare", 0, 0.55 * intensity)
            for k in range(8):
                tt = bar_time(t0, b, k * 0.5)
                if tt < end_at:
                    tr.play(tt, "hat", 3 if k % 2 else 0, (0.32 if k % 2 else 0.2) * intensity)
        if b % 4 == 0:
            tt = bar_time(t0, b, 0)
            if tt < end_at:
                tr.play(tt, "icechime", n(tones[0], 6), 0.25 * intensity)


def cave_theme(tr, t0, bars, end_at):
    """The return: the tune slowed to half the notes, harp and iron xylophone, no drums."""
    for b in range(bars):
        chord = PROG[(b + 4) % 8]
        tones = CHORDS[chord]
        tt = bar_time(t0, b, 0)
        if tt < end_at:
            tr.play(tt, "bass", n(ROOT[chord], 2), 0.7)
            tr.play(bar_time(t0, b, 2), "bass", n(tones[2], 2), 0.45)
        for k, beat in enumerate((0, 1, 2, 3)):
            tt = bar_time(t0, b, beat)
            if tt < end_at:
                tr.play(tt, "iron_xylophone", n(tones[k % 3], 4), 0.25)
        for beat, note, ln in THEME[(b + 4) % 8][::2]:
            tt = bar_time(t0, b, beat)
            if tt < end_at:
                tr.play(tt, "harp", note, 0.45)


def ending(tr, t0):
    """End card: the last two bars of the theme, then a held F major chord."""
    full_band(tr, t0, 2, t0 + 4 * BEAT * 2, lead="bit", intensity=0.9)
    tt = t0 + 8 * BEAT * 2 / 2 * 0 + 4 * BEAT * 2
    # Final chord, rolled.
    for k, (p, o) in enumerate([("F", 2), ("C", 3), ("F", 3), ("A", 3), ("C", 4), ("F", 4), ("A", 4), ("C", 5), ("F", 5)]):
        inst = "bass" if o <= 2 else ("harp" if o <= 4 else "bell")
        tr.play(tt + k * 0.03, inst, n(p, o), 0.75 if inst != "bell" else 0.35)
    tr.play(tt, "icechime", n("F", 6), 0.4)
    tr.play(tt + 0.6, "icechime", n("C", 6), 0.25)
    tr.play(tt + 1.2, "icechime", n("A", 6), 0.2)


def letters(tr, t0, gap, count=6):
    scale = ["C", "D", "E", "F", "G", "A"]
    for i in range(count):
        tr.play(t0 + i * gap, "xylobone", n(scale[i], 5), 0.6)


def reverb(x, seconds=1.6, mix=0.18):
    """Small synthetic room: exponentially decaying stereo noise impulse."""
    rng = np.random.default_rng(3)
    nlen = int(seconds * SR)
    env = np.exp(-np.arange(nlen) / (0.25 * SR))
    out = np.zeros_like(x)
    for c in range(2):
        ir = rng.standard_normal(nlen).astype(np.float32) * env
        ir[: int(0.012 * SR)] = 0
        ir /= np.sqrt((ir ** 2).sum())
        out[:, c] = fftconvolve(x[:, c], ir)[: len(x)]
    return x + out * mix


def build(cues=None):
    tr = Track()
    # Act one: bars from 5.0 s; the creeper's hiss cuts the band off at 25.0 mid phrase.
    morning(tr, 5.0, 11, cut_at=25.0)
    # The fall: a glissando into the shaft, the slip and the final drop.
    fall_runs(tr, [(28.2, 0.9), (31.75, 0.7), (34.5, 0.75)])
    # The death screen.
    sad_trombone(tr, 37.0)
    # Title: letters land as a rising xylophone run, then the band kicks in.
    letters(tr, 40.25 + 0.32, 0.12)
    # A snare fill into the band.
    for k in range(8):
        tr.play(41.0 + k * BEAT / 4 * 2, "snare", 0, 0.25 + 0.06 * k)
    tr.play(41.0, "bass", n("C", 2), 0.8)
    tr.play(41.5, "bass", n("E", 2), 0.8)
    full_band(tr, 42.0, 8, end_at=58.95)
    # Montage ends on a hit as the return cuts in.
    tr.play(58.95, "bd", -4, 1.0)
    tr.play(58.95, "bell", n("F", 5), 0.4)
    cave_theme(tr, 59.0, 5, end_at=69.7)
    # The flop gets a plunk.
    tr.play(69.85, "bass", n("F", 1) + 12, 0.8)
    tr.play(70.0, "harp", n("C", 4), 0.4)
    # End card.
    letters(tr, 72.25 + 0.32, 0.12)
    ending(tr, 74.0)
    x = tr.buf[: int(DURATION * SR)]
    x = reverb(x)
    peak = np.abs(x).max()
    return x / max(peak, 1e-6) * 0.89


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(WORK, "music.wav")
    x = build()
    wavfile.write(out, SR, (x * 32767).astype(np.int16))
    print("wrote", out, "%.1f s" % (len(x) / SR))
