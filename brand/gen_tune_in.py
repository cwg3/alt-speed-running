#!/usr/bin/env python3
"""Synthesise the tune-in sound played over TuneInScreen.

A dead channel coming up on an old set wired straight into the mains:
an arc strike, transformer buzz, static hiss that clears as the picture
locks, a few more arcs while it fights for sync, and a power-down thump
as the tube collapses into the loading screen.

Nothing is sampled - every layer is generated here, so there is no
licence to track and the sound can be re-cut to a new timeline by
editing the constants below.

THE TIMELINE IS SHARED WITH TuneInScreen.java. ARCS_MS, LOCK_MS, OFF_MS
and TOTAL_MS are the moments the picture glitches, steadies and
collapses; the screen draws its glitch spikes at the same instants this
puts its crackles. Change one, change the other.

Requires numpy (venv/bin/pip install numpy) and ffmpeg on PATH.
Usage:  venv/bin/python gen_tune_in.py <out.ogg>

The mod ships it at
mod/src/main/resources/assets/speedrunmcalt/sounds/tune_in.ogg
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 44100

# --- shared with TuneInScreen.java -----------------------------------
TOTAL_MS = 2600
LOCK_MS = 1200
OFF_MS = 2150
ARCS_MS = [30, 340, 620, 910, 1650]
# ---------------------------------------------------------------------

MAINS = 60.0
TARGET_RMS_DB = -16.5
SEED = 0x417  # fixed, so a re-render is byte-for-byte the same sound


def t_axis():
    return np.arange(int(RATE * TOTAL_MS / 1000)) / RATE


def ms(x):
    return int(RATE * x / 1000)


def fft_band(x, lo, hi):
    """Brick-wall band-pass. Crude, but this is static - nobody minds."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    spec[(f < lo) | (f > hi)] = 0
    return np.fft.irfft(spec, len(x))


def env_segments(t, points):
    """Piecewise-linear envelope through (ms, level) points."""
    xs = [p[0] / 1000 for p in points]
    ys = [p[1] for p in points]
    return np.interp(t, xs, ys)


def hum(t):
    """Transformer buzz: rectified mains, heavy in odd harmonics.

    The pitch sags through the power-down so the thump reads as the
    supply dying rather than a separate noise laid on top.
    """
    sag = np.where(t * 1000 < OFF_MS, 1.0,
                   np.clip(1 - (t * 1000 - OFF_MS) / (TOTAL_MS - OFF_MS), 0.25, 1))
    phase = 2 * np.pi * np.cumsum(MAINS * sag) / RATE
    out = np.zeros_like(t)
    for n, amp in [(1, 0.5), (2, 1.0), (3, 0.55), (4, 0.6), (5, 0.35),
                   (6, 0.3), (7, 0.22), (9, 0.15), (11, 0.1), (13, 0.07)]:
        out += amp * np.sin(n * phase)
    # Magnetostriction - the core physically rattling - is what makes a
    # transformer sound angry rather than like a sine. Soft-clip it.
    out = np.tanh(out * 1.6)
    level = env_segments(t, [(0, 0), (12, 1.0), (LOCK_MS, 0.75), (OFF_MS, 0.7),
                             (TOTAL_MS - 80, 0.0), (TOTAL_MS, 0)])
    return out * level * 0.2


def hiss(t, rng):
    """Dead-channel static, clearing as the picture locks."""
    n = fft_band(rng.standard_normal(len(t)), 300, 9000)
    n /= np.abs(n).max()
    # A slow wobble, like a fine-tuning knob being worked.
    wobble = 0.75 + 0.25 * np.sin(2 * np.pi * 3.1 * t + 1.3 * np.sin(2 * np.pi * 0.7 * t))
    level = env_segments(t, [(0, 0.9), (150, 0.8), (LOCK_MS - 200, 0.25),
                             (LOCK_MS, 0.06), (OFF_MS, 0.05),
                             (OFF_MS + 60, 0.35), (TOTAL_MS - 120, 0.0), (TOTAL_MS, 0)])
    return n * wobble * level * 0.5


def arc(t, rng, at_ms, strength, length_ms):
    """One high-voltage discharge: a snap, a buzzing body, a crackle tail."""
    out = np.zeros_like(t)
    start = ms(at_ms)
    n = min(ms(length_ms), len(t) - start)
    if n <= 0:
        return out
    lt = np.arange(n) / RATE
    decay = np.exp(-lt / (length_ms / 1000 / 3.5))

    # The snap: a few ms of broadband. Kept low and given a 2 ms rise -
    # at full level with an instant attack it was the one part of the
    # sound playtest called scary rather than atmospheric.
    snap_n = min(ms(6), n)
    rise = np.minimum(1, np.arange(snap_n) / ms(2))
    snap = rng.standard_normal(snap_n) * rise * np.linspace(1, 0, snap_n)

    # The body: hot noise chopped at twice mains, which is the "zzzt".
    # Top end stops at 7 kHz: the sizzle above it read as harsh, not as
    # electricity.
    body = fft_band(rng.standard_normal(n), 800, 7000)
    body /= np.abs(body).max()
    chop = 0.5 + 0.5 * np.sign(np.sin(2 * np.pi * MAINS * 2 * lt))
    body *= chop * decay

    # Crackle: sparse sharp impulses, thinning out.
    crackle = np.zeros(n)
    density = 0.012 * decay
    hits = rng.random(n) < density
    crackle[hits] = rng.choice([-1.0, 1.0], hits.sum()) * rng.uniform(0.4, 1.0, hits.sum())
    crackle = np.convolve(crackle, np.exp(-np.arange(40) / 6), mode="same")

    seg = 0.55 * body + 0.5 * crackle
    seg[:snap_n] += 0.25 * snap
    out[start:start + n] = seg * strength
    return out


def thump(t):
    """The tube going out: a low knock with the supply dropping away."""
    out = np.zeros_like(t)
    start = ms(OFF_MS)
    n = len(t) - start
    lt = np.arange(n) / RATE
    f = 70 * np.exp(-lt * 3.0) + 28
    phase = 2 * np.pi * np.cumsum(f) / RATE
    out[start:] = np.sin(phase) * np.exp(-lt * 7.0) * 0.6
    return out


def render():
    rng = np.random.default_rng(SEED)
    t = t_axis()
    mix = hum(t) + hiss(t, rng) + thump(t)
    # Arcs sit IN the hum and static, not on top of them. The first cut
    # had them about 2.5x louder and the opening strike was jarring.
    strengths = [0.8, 0.5, 0.6, 0.45, 0.3]
    lengths = [420, 200, 260, 180, 140]
    for at, s, length in zip(ARCS_MS, strengths, lengths):
        mix += arc(t, rng, at, s, length)
    # A last crackle as the picture folds up.
    mix += arc(t, rng, OFF_MS + 30, 0.45, 300)

    # Gentle limiter, then normalise by LOUDNESS, not by peak. Peak
    # normalising means softening the arcs - the peaks - turns the hum
    # and hiss up underneath them. The level was tuned in game (Cues
    # plays this at 0.2) against a file whose RMS was -16.5 dBFS, so
    # hold that, and only pull it down if a peak would clip.
    mix = np.tanh(mix * 1.2)
    rms = np.sqrt(np.mean(mix ** 2))
    mix *= 10 ** (TARGET_RMS_DB / 20) / rms
    peak = np.abs(mix).max()
    if peak > 10 ** (-1 / 20):
        mix *= 10 ** (-1 / 20) / peak
    # 5 ms fades so neither end clicks.
    f = ms(5)
    mix[:f] *= np.linspace(0, 1, f)
    mix[-f:] *= np.linspace(1, 0, f)
    return mix


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    mono = (render() * 32767).astype("<i2")
    # Written as identical stereo channels: ffmpeg's built-in vorbis
    # encoder refuses anything but two, and libvorbis is not in the
    # Homebrew build. Costs a little jar, changes nothing audible.
    pcm = np.repeat(mono, 2)
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "tune_in.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm.tobytes())
        # ffmpeg's built-in vorbis encoder is "experimental", hence -strict.
        # Minecraft decodes it with stb_vorbis like any other .ogg.
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav,
                        "-c:a", "vorbis", "-strict", "-2", "-q:a", "6", out],
                       check=True)
    print(out)


if __name__ == "__main__":
    main()
