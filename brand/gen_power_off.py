#!/usr/bin/env python3
"""Synthesise the power-off sound played when Quit Game is pressed.

A 1970s set being switched off: the clunk of the knob, a loud "tsch"
of static noise with the clicky crackle of charge discharging off the
glass on top of it, a knock and a crackle of discharge as
it folds to a line, then the last of the transformer hum. No flyback
whine - it grated, and was taken out.

Nothing is sampled - every layer is generated here, so there is no
licence to track. Built the same way as gen_tune_in.py, and mastered to
the same loudness so Cues can play both at the same volume.

THE TIMELINE IS SHARED WITH PowerOff.java. SQUEEZE_MS is where the
picture has closed to a line and LINE_MS where the line has pulled in to
a dot; the knock and crackle land on the first. STOP_MS is when PowerOff stops the game - the sound has
to be over by then, or the shutdown cuts it off. Change one, change the
other.

Requires numpy (venv/bin/pip install numpy) and ffmpeg on PATH.
Usage:  venv/bin/python gen_power_off.py <out.ogg>

The mod ships it at
mod/src/main/resources/assets/speedrunmcalt/sounds/power_off.ogg
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 44100

# --- shared with PowerOff.java ---------------------------------------
SQUEEZE_MS = 260
LINE_MS = 450
STOP_MS = 1100
# ---------------------------------------------------------------------

TOTAL_MS = STOP_MS - 30  # finished just before the game stops
TSCH_MS = 1000
MAINS = 60.0
TARGET_RMS_DB = -16.5  # gen_tune_in.py's level; Cues plays both at 0.2
SEED = 0x0FF  # fixed, so a re-render is byte-for-byte the same sound


def t_axis():
    return np.arange(int(RATE * TOTAL_MS / 1000)) / RATE


def ms(x):
    return int(RATE * x / 1000)


def fft_band(x, lo, hi):
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    spec[(f < lo) | (f > hi)] = 0
    return np.fft.irfft(spec, len(x))


def env_segments(t, points):
    xs = [p[0] / 1000 for p in points]
    ys = [p[1] for p in points]
    return np.interp(t, xs, ys)


def at(t, start_ms, sig):
    out = np.zeros_like(t)
    s = ms(start_ms)
    n = min(len(sig), len(t) - s)
    out[s:s + n] = sig[:n]
    return out


def clunk(t, rng):
    """The knob: a hard plastic click with the chassis knocking under it."""
    n = ms(60)
    lt = np.arange(n) / RATE
    click = fft_band(rng.standard_normal(n), 1500, 6000)
    click /= np.abs(click).max()
    click *= np.exp(-lt / 0.003)
    knock = np.sin(2 * np.pi * 130 * lt) * np.exp(-lt / 0.025)
    return at(t, 0, 0.7 * click + 0.5 * knock)


def static_blip(t, rng):
    """The glass discharging: a dense run of sharp clicks and pops right
    after the knob, thinning out to the odd tick - the crackle you could
    hear (and feel on your arm) off a set just switched off.

    Clicks, not hiss: each one is a single broadband snap a fraction of
    a millisecond long, so it reads as static electricity rather than as
    a dead channel. A trace of hiss sits under the densest part only.
    """
    start = 10
    n = ms(380)
    lt = np.arange(n) / RATE
    density = 0.05 * np.exp(-lt / 0.07) + 0.002 * np.exp(-lt / 0.25)
    hits = rng.random(n) < density / 4
    imp = np.zeros(n)
    k = hits.sum()
    # Mostly small ticks with the occasional fat pop.
    imp[hits] = rng.choice([-1.0, 1.0], k) * np.where(rng.random(k) < 0.12,
                                                      rng.uniform(0.8, 1.0, k),
                                                      rng.uniform(0.15, 0.5, k))
    snap = np.exp(-np.arange(18) / 2.5) * np.cos(np.arange(18) * 1.9)
    clicks = np.convolve(imp, snap, mode="full")[:n]
    clicks = fft_band(clicks, 700, 12000)
    clicks /= np.abs(clicks).max()
    hiss = fft_band(rng.standard_normal(n), 1500, 9000)
    hiss /= np.abs(hiss).max()
    hiss *= np.exp(-lt / 0.03) * 0.12
    return at(t, start, (0.9 * clicks + hiss) * np.minimum(1, lt / 0.001))


def tsch(t, rng):
    """The "tsch": a loud, full burst of static noise as the knob goes,
    the sound of the screen's snow - snapping on and washing out over
    TSCH_MS. The clicks ride on top of it.

    Noise across most of the band rather than a narrow hiss, with the
    lows rolled off below 200 Hz so it stays a "tsch" and not a rumble,
    and the very top tamed so it is not a whistle.
    """
    start = 6
    n = ms(TSCH_MS)
    lt = np.arange(n) / RATE
    noise = fft_band(rng.standard_normal(n), 200, 11000)
    noise /= np.abs(noise).max()
    # Full for the first moment, then a wash down to nothing at
    # TSCH_MS. A steep fall (power 4.5): most of the level is gone in
    # the first fifth, with a quiet tail running out to the end.
    tail = np.clip(1 - lt / lt[-1], 0, 1) ** 4.5
    level = np.minimum(1, lt / 0.0015) * (0.35 + 0.65 * np.exp(-lt / 0.08)) * tail
    return at(t, start, noise * level * 0.95)


def hum(t):
    """Transformer hum, sagging and fading as the supply drains."""
    sag = np.clip(1 - t / (TOTAL_MS / 1000) * 0.7, 0.3, 1)
    phase = 2 * np.pi * np.cumsum(MAINS * sag) / RATE
    out = np.zeros_like(t)
    for n, amp in [(1, 0.5), (2, 1.0), (3, 0.55), (4, 0.6), (5, 0.35), (7, 0.2)]:
        out += amp * np.sin(n * phase)
    out = np.tanh(out * 1.6)
    level = env_segments(t, [(0, 0.5), (SQUEEZE_MS, 0.4), (TOTAL_MS - 40, 0.0), (TOTAL_MS, 0)])
    return out * level * 0.18


def knock(t):
    """The tube going out, on the frame the picture becomes a line."""
    n = ms(TOTAL_MS - SQUEEZE_MS)
    lt = np.arange(n) / RATE
    f = 75 * np.exp(-lt * 3.0) + 30
    phase = 2 * np.pi * np.cumsum(f) / RATE
    return at(t, SQUEEZE_MS, np.sin(phase) * np.exp(-lt * 8.0) * 0.6)


def discharge(t, rng):
    """A second, sparser run of clicks as the picture folds to a line."""
    start = SQUEEZE_MS - 20
    n = ms(TOTAL_MS - start)
    lt = np.arange(n) / RATE
    density = 0.02 * np.exp(-lt / 0.09) + 0.0008
    hits = rng.random(n) < density / 4
    imp = np.zeros(n)
    imp[hits] = rng.choice([-1.0, 1.0], hits.sum()) * rng.uniform(0.15, 0.6, hits.sum())
    snap = np.exp(-np.arange(18) / 2.5) * np.cos(np.arange(18) * 1.9)
    clicks = fft_band(np.convolve(imp, snap, mode="full")[:n], 700, 12000)
    return at(t, start, 0.5 * clicks / max(1e-9, np.abs(clicks).max()))


def render():
    rng = np.random.default_rng(SEED)
    t = t_axis()
    mix = clunk(t, rng) + tsch(t, rng) + static_blip(t, rng) + hum(t) + knock(t) + discharge(t, rng)
    # gen_tune_in.py's mastering: gentle limiter, normalise by loudness,
    # pull down only if a peak would clip.
    mix = np.tanh(mix * 1.2)
    rms = np.sqrt(np.mean(mix ** 2))
    mix *= 10 ** (TARGET_RMS_DB / 20) / rms
    peak = np.abs(mix).max()
    if peak > 10 ** (-1 / 20):
        mix *= 10 ** (-1 / 20) / peak
    f = ms(5)
    mix[:f] *= np.linspace(0, 1, f)
    mix[-f:] *= np.linspace(1, 0, f)
    return mix


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    mono = (render() * 32767).astype("<i2")
    # Identical stereo channels: ffmpeg's built-in vorbis encoder will
    # only take two (see gen_tune_in.py).
    pcm = np.repeat(mono, 2)
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "power_off.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav,
                        "-c:a", "vorbis", "-strict", "-2", "-q:a", "6", out],
                       check=True)
    print(out)


if __name__ == "__main__":
    main()
