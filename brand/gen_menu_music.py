#!/usr/bin/env python3
"""Synthesise the menu music loop played on the title screen and alt menus.

Dark synthwave to sit under the CRT look: a detuned pad, a pulsing
bass, a plucked arpeggio through a ping-pong echo, a lead line in the
third section, soft drums, and a little tape hiss, truncated to 12 bits at the end for
a sampler's crunch. A minor at 92 BPM,
32 bars - about 83 seconds - over Am, Fmaj7, C, E, built in four
8-bar sections:

    A  pad and bass
    B  + arpeggio, kick and hats
    C  + lead and snare
    D  drums and lead drop out, back down to A

Long on purpose: a queue can run for minutes, and a short loop turns
grating long before then.

THE WHOLE TRACK IS RENDERED CIRCULARLY. A note whose release runs past
the last sample wraps to the first, and the echo and reverb are computed
modulo the loop length, so the tails at the end of the file are already
sounding at its start. Played back to back, the seam is inaudible -
which matters, because MusicTracker restarts the file as soon as it
ends.

Nothing is sampled - every layer is generated here, so there is no
licence to track. SEED is fixed, so a re-render is the same track.

Requires numpy (venv/bin/pip install numpy) and ffmpeg on PATH.
Usage:  venv/bin/python gen_menu_music.py <out.ogg>

The mod ships it at
mod/src/main/resources/assets/speedrunmcalt/sounds/menu_loop.ogg
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 44100
BPM = 92
BARS = 32
BEAT = 60.0 / BPM
BAR = 4 * BEAT
N = int(round(BARS * BAR * RATE))
SEED = 0xA17

TARGET_RMS_DB = -20.0
# The final mix is truncated to this many bits before it is written,
# undithered - the crunch of a 12-bit sampler. The file itself is still
# 16-bit PCM going into the encoder; only 4096 levels are used.
BITS = 12

rng = np.random.default_rng(SEED)

# Two bars per chord, the 8-bar cycle repeated four times.
CHORDS = [
    ("A", [57, 60, 64, 69]),        # Am     A3 C4 E4 A4
    ("F", [53, 57, 60, 64]),        # Fmaj7  F3 A3 C4 E4
    ("C", [55, 60, 64, 67]),        # C      G3 C4 E4 G4
    ("E", [56, 59, 64, 68]),        # E      G#3 B3 E4 G#4
]
BASS_ROOT = {"A": 45, "F": 41, "C": 48, "E": 40}

# Lead, section C (bars 16-23): (bar, beat, beats long, midi note).
LEAD = [
    (16, 0, 2, 76), (16, 2, 1, 74), (16, 3, 1, 72),
    (17, 0, 4, 69),
    (18, 0, 2, 72), (18, 2, 1, 69), (18, 3, 1, 72),
    (19, 0, 3, 77), (19, 3, 1, 76),
    (20, 0, 2, 79), (20, 2, 2, 76),
    (21, 0, 2, 74), (21, 2, 2, 72),
    (22, 0, 2, 71), (22, 2, 1, 68), (22, 3, 1, 71),
    (23, 0, 4, 76),
]


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def at(bar, beat=0.0):
    return int(round((bar * BAR + beat * BEAT) * RATE))


def section(bar):
    return "ABCD"[bar // 8]


def place(track, sig, start):
    """Add sig into track at start, wrapping past the end to the top."""
    n = len(sig)
    start %= N
    first = min(n, N - start)
    track[..., start:start + first] += sig[..., :first]
    rest = n - first
    while rest > 0:
        k = min(rest, N)
        track[..., :k] += sig[..., first:first + k]
        first += k
        rest -= k


def additive(freq, n, cutoff, odd_only=False, vibrato=0.0):
    """Band-limited saw (or square) with a smooth low-pass built in.

    Summing the harmonics directly means no aliasing and no filter to
    run sample by sample - the roll-off is each harmonic's weight.
    """
    t = np.arange(n) / RATE
    phase = 2 * np.pi * freq * t
    if vibrato:
        phase += vibrato * np.sin(2 * np.pi * 5.2 * t) * np.minimum(1, t / 0.6)
    out = np.zeros(n)
    k = 1
    while k * freq < min(cutoff * 3, RATE / 2 - 500):
        if not odd_only or k % 2:
            w = (1.0 / k) / (1.0 + (k * freq / cutoff) ** 4)
            out += w * np.sin(k * phase + rng.uniform(0, 2 * np.pi))
        k += 1
    return out


def adsr(n, a, d, s, r_n):
    """Attack/decay/sustain over n samples, then an r_n-sample release."""
    env = np.full(n + r_n, s, dtype=float)
    a_n = max(1, int(a * RATE))
    d_n = max(1, int(d * RATE))
    env[:a_n] = np.linspace(0, 1, a_n)
    end = min(n, a_n + d_n)
    env[a_n:end] = np.linspace(1, s, d_n)[:end - a_n]
    env[n:] = s * np.exp(-np.arange(r_n) / (r_n / 5))
    return env


def spectral(x, response):
    """Circular frequency-domain filter: response(f) -> gain."""
    spec = np.fft.rfft(x, axis=-1)
    f = np.fft.rfftfreq(x.shape[-1], 1 / RATE)
    return np.fft.irfft(spec * response(f), x.shape[-1], axis=-1)


def lowpass(fc, order=2):
    return lambda f: 1 / np.sqrt(1 + (f / fc) ** (2 * order))


def highpass(fc, order=2):
    return lambda f: 1 / np.sqrt(1 + (fc / np.maximum(f, 1e-3)) ** (2 * order))


def stereo(mono, pan=0.0):
    left = np.cos((pan + 1) * np.pi / 4)
    right = np.sin((pan + 1) * np.pi / 4)
    return np.stack([mono * left, mono * right])


# --- instruments ------------------------------------------------------

def pad():
    out = np.zeros((2, N))
    for c in range(BARS // 2):
        name, notes = CHORDS[c % 4]
        n = at(2)
        rel = int(1.6 * RATE)
        env = adsr(n, 0.9, 0.5, 0.8, rel)
        for midi in notes:
            for cents, pan in ((-9, -0.7), (0, 0.0), (9, 0.7)):
                f = hz(midi) * 2 ** (cents / 1200)
                v = additive(f, n + rel, cutoff=1100) * env * 0.11
                place(out, stereo(v, pan), at(2 * c))
    return out


def bass():
    out = np.zeros(N)
    for bar in range(BARS):
        root = hz(BASS_ROOT[CHORDS[(bar // 2) % 4][0]])
        if section(bar) == "A":
            if bar % 2 == 0:
                n = at(2) - int(0.05 * RATE)
                rel = int(0.4 * RATE)
                v = additive(root, n + rel, cutoff=380) * adsr(n, 0.25, 0.3, 0.7, rel)
                place(out, v * 0.5, at(bar))
            continue
        for eighth in range(8):
            n = int(BEAT / 2 * RATE * 0.8)
            rel = int(0.05 * RATE)
            accent = 1.0 if eighth % 2 == 0 else 0.75
            v = additive(root, n + rel, cutoff=520) * adsr(n, 0.004, 0.12, 0.55, rel)
            place(out, v * 0.42 * accent, at(bar, eighth / 2))
    return stereo(out)


def arp():
    dry = np.zeros(N)
    for bar in range(BARS):
        if section(bar) == "A":
            continue
        _, notes = CHORDS[(bar // 2) % 4]
        up = [m + 12 for m in notes] + [notes[1] + 24, notes[2] + 24]
        pattern = up + up[-2:0:-1]
        for s in range(16):
            midi = pattern[(bar * 16 + s) % len(pattern)]
            n = int(0.32 * RATE)
            t = np.arange(n) / RATE
            v = additive(hz(midi), n, cutoff=2400, odd_only=True)
            v *= np.exp(-t / 0.09) * np.minimum(1, t / 0.002)
            # Softer in D, where the track is winding back down.
            level = 0.13 if section(bar) != "D" else 0.08
            place(dry, v * level, at(bar, s / 4))
    # Ping-pong echo on a dotted eighth, circular like everything else.
    d = int(0.75 * BEAT * RATE)
    out = stereo(dry)
    echo_l = np.zeros(N)
    echo_r = np.zeros(N)
    fb = 0.42
    for i in range(1, 7):
        tap = np.roll(dry, d * i) * fb ** i
        if i % 2:
            echo_r += tap
        else:
            echo_l += tap
    out += np.stack([echo_l, echo_r]) * 0.8
    return spectral(out, highpass(220))


def lead():
    out = np.zeros(N)
    for bar, beat, beats, midi in LEAD:
        n = int(beats * BEAT * RATE * 0.95)
        rel = int(0.35 * RATE)
        v = additive(hz(midi), n + rel, cutoff=1800, vibrato=0.035)
        v = 0.6 * v + 0.4 * np.sin(np.cumsum(np.full(n + rel, 2 * np.pi * hz(midi) / RATE)))
        place(out, v * adsr(n, 0.03, 0.2, 0.75, rel) * 0.16, at(bar, beat))
    return stereo(out, 0.1)


def drums():
    kick = np.zeros(N)
    snare = np.zeros(N)
    hats = np.zeros(N)
    kicks = []
    for bar in range(BARS):
        s = section(bar)
        if s in "BC":
            for beat in (0, 2):
                n = int(0.45 * RATE)
                t = np.arange(n) / RATE
                f = 46 + 80 * np.exp(-t / 0.035)
                v = np.sin(2 * np.pi * np.cumsum(f) / RATE) * np.exp(-t / 0.16)
                place(kick, v * 0.55, at(bar, beat))
                kicks.append(at(bar, beat))
            for e in range(8):
                n = int(0.05 * RATE)
                v = rng.standard_normal(n) * np.exp(-np.arange(n) / (0.012 * RATE))
                place(hats, v * (0.05 if e % 2 else 0.03), at(bar, e / 2))
        if s == "C":
            for beat in (1, 3):
                n = int(0.3 * RATE)
                t = np.arange(n) / RATE
                v = rng.standard_normal(n) * np.exp(-t / 0.07) * 0.5
                v += np.sin(2 * np.pi * 185 * t) * np.exp(-t / 0.05) * 0.4
                place(snare, v * 0.3, at(bar, beat))
    hats = spectral(hats, highpass(7000, 3))
    snare = spectral(snare, lambda f: highpass(180)(f) * lowpass(5000)(f))
    return stereo(kick), stereo(snare), stereo(hats, 0.25), kicks


def pump(kicks):
    """Sidechain: duck under each kick, the synthwave breathing."""
    g = np.ones(N)
    n = int(0.3 * RATE)
    shape = 1 - 0.38 * np.exp(-np.arange(n) / (0.07 * RATE))
    for k in kicks:
        idx = (k + np.arange(n)) % N
        g[idx] = np.minimum(g[idx], shape)
    return g


def reverb(x, seconds=2.6):
    """Circular convolution with a decaying stereo noise tail."""
    n = int(seconds * RATE * 1.5)
    t = np.arange(n) / RATE
    ir = rng.standard_normal((2, n)) * np.exp(-t * 6.9 / seconds)
    ir[:, : int(0.012 * RATE)] = 0  # a little pre-delay
    ir = spectral(np.pad(ir, ((0, 0), (0, N - n))), lowpass(4500))
    ir /= np.sqrt(np.sum(ir ** 2) / 2)
    return np.fft.irfft(np.fft.rfft(x, axis=-1) * np.fft.rfft(ir, axis=-1), N, axis=-1)


def hiss():
    v = rng.standard_normal((2, N))
    v = spectral(v, lambda f: highpass(2500)(f) * lowpass(9000)(f))
    return v * 0.004


# --- mix --------------------------------------------------------------

def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]

    p = pad()
    b = bass()
    a = arp()
    l = lead()
    k, s, h, kicks = drums()
    g = pump(kicks)

    dry = p * g + b * g + a + l + k + s + h
    send = 0.35 * p + 0.5 * a + 0.6 * l + 0.7 * s
    mix = dry + 0.32 * reverb(send) + hiss()
    mix = spectral(mix, lambda f: highpass(28)(f) * lowpass(11000)(f))

    rms = np.sqrt(np.mean(mix ** 2))
    mix *= 10 ** (TARGET_RMS_DB / 20) / rms
    mix = np.tanh(mix * 1.1) / 1.1  # gentle ceiling on the peaks
    # A few milliseconds of fade at each end. The content already loops;
    # this only keeps the restart from clicking.
    edge = int(0.004 * RATE)
    ramp = np.linspace(0, 1, edge)
    mix[:, :edge] *= ramp
    mix[:, -edge:] *= ramp[::-1]

    steps = 2 ** (BITS - 1) - 1
    mix = np.round(np.clip(mix, -1, 1) * steps) / steps
    pcm = (mix.T * 32767).astype("<i2").tobytes()
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "menu_loop.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm)
        # ffmpeg's built-in vorbis encoder is "experimental", hence -strict.
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav,
                        "-c:a", "vorbis", "-strict", "-2", "-q:a", "4", out],
                       check=True)
    print(f"{out}: {N / RATE:.1f}s, {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    main()
