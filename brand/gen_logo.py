#!/usr/bin/env python3
"""Generate the 8-bit lowercase 'alt' logo, terminal/hacker styling.
Pure stdlib - no PIL."""
import zlib, struct, sys
from collections import deque

# --- glyph bitmaps -----------------------------------------------------
# 12 rows, baseline at row 11. Squared terminal letterforms, uniform 2px
# strokes, no curves or tapers - that's what reads as a monospace
# terminal face rather than a game font.
GLYPH_H = 12

A = [
    ".......",
    ".......",
    ".......",
    ".......",
    ".......",
    ".#####.",
    ".....##",
    ".######",
    ".##..##",
    ".##..##",
    ".##..##",
    ".######",
]

L = [
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    ".##.",
    "####",
]

T = [
    ".......",
    "..##...",
    "..##...",
    "#######",
    "..##...",
    "..##...",
    "..##...",
    "..##...",
    "..##...",
    "..##...",
    "..##...",
    "...####",
]

# Terminal cursor block on the baseline - reads instantly as a command
# prompt, which is the whole point of the hacker treatment.
CURSOR = [
    "......",
    "......",
    "......",
    "......",
    "......",
    "######",
    "######",
    "######",
    "######",
    "######",
    "######",
    "######",
]

GAP = 3
CURSOR_GAP = 3


def compose(parts):
    rows = []
    for r in range(GLYPH_H):
        line = ""
        for i, (g, gap) in enumerate(parts):
            if i:
                line += "." * gap
            line += g[r]
        rows.append(line)
    return rows, len(rows[0])


# --- colors ------------------------------------------------------------
# Phosphor green. Deliberately a NARROW ramp: a CRT phosphor is close to
# uniformly lit, and a wide gradient made the bottom of the letters
# darker than the surrounding glow, which looked inverted.
GRADIENT = [
    (140, 255, 175),
    (126, 255, 165),
    (112, 252, 152),
    (96, 248, 140),
    (84, 244, 130),
    (72, 240, 120),
    (62, 234, 112),
    (54, 228, 104),
    (46, 220, 96),
    (40, 212, 90),
    (34, 202, 84),
    (30, 192, 78),
]
# Glow must stay clearly dimmer than every ink value above, or it reads
# as a pale outline instead of light bleeding off the glyph.
GLOW = [(30, 200, 90, 70), (26, 170, 76, 34), (20, 140, 62, 14)]
TRANSPARENT = (0, 0, 0, 0)


def render(rows, width, scale, pad, bg=None, scanlines=False):
    h = GLYPH_H
    cw = width + pad * 2
    ch = h + pad * 2

    ink = [[False] * cw for _ in range(ch)]
    for y in range(h):
        for x in range(width):
            if rows[y][x] == "#":
                ink[y + pad][x + pad] = True

    # Flood fill the OUTSIDE from the canvas border. Without this the
    # glow radiates into enclosed counters (the hole in 'a', the gaps
    # in 't'), filling them with pale green and making the word
    # illegible - which is exactly what the first attempt did.
    outside = [[False] * cw for _ in range(ch)]
    q = deque()
    for x in range(cw):
        for y in (0, ch - 1):
            if not ink[y][x] and not outside[y][x]:
                outside[y][x] = True
                q.append((x, y))
    for y in range(ch):
        for x in (0, cw - 1):
            if not ink[y][x] and not outside[y][x]:
                outside[y][x] = True
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < cw and 0 <= ny < ch and not ink[ny][nx] and not outside[ny][nx]:
                outside[ny][nx] = True
                q.append((nx, ny))

    # glow level per outside pixel, by chebyshev distance to nearest ink
    glow = [[0] * cw for _ in range(ch)]
    for y in range(ch):
        for x in range(cw):
            if ink[y][x] or not outside[y][x]:
                continue
            best = 0
            for dy in range(-3, 4):
                for dx in range(-3, 4):
                    oy, ox = y + dy, x + dx
                    if 0 <= oy < ch and 0 <= ox < cw and ink[oy][ox]:
                        d = max(abs(dy), abs(dx))
                        lvl = 4 - d  # d=1 -> 3, d=2 -> 2, d=3 -> 1
                        if lvl > best:
                            best = lvl
            glow[y][x] = best

    rgba = []
    for y in range(ch):
        row = bytearray()
        for x in range(cw):
            if ink[y][x]:
                gy = min(max(y - pad, 0), len(GRADIENT) - 1)
                r, g, b = GRADIENT[gy]
                px = (r, g, b, 255)
            elif glow[y][x]:
                gr, gg, gb, ga = GLOW[3 - glow[y][x]]
                if bg:
                    # Composite over the known background ourselves.
                    # Leaving these semi-transparent means the *viewer*
                    # composites them over whatever it likes (white, in
                    # most image viewers), which turned a dim glow into
                    # a thick pale outline.
                    br, bgc, bb, _ = bg
                    px = (
                        (gr * ga + br * (255 - ga)) // 255,
                        (gg * ga + bgc * (255 - ga)) // 255,
                        (gb * ga + bb * (255 - ga)) // 255,
                        255,
                    )
                else:
                    px = (gr, gg, gb, ga)
            else:
                px = bg if bg else TRANSPARENT
            row += bytes(px)
        rgba.append(row)

    out = []
    for row in rgba:
        big = bytearray()
        for x in range(cw):
            big += row[x * 4:(x + 1) * 4] * scale
        for sub in range(scale):
            if scanlines and sub % 4 == 3:
                dark = bytearray()
                for i in range(0, len(big), 4):
                    r, g, b, a = big[i:i + 4]
                    dark += bytes((r * 70 // 100, g * 70 // 100, b * 70 // 100, a))
                out.append(dark)
            else:
                out.append(bytearray(big))
    return out, cw * scale, ch * scale


def write_png(path, width, height, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    comp = zlib.compress(raw, 9)

    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", comp)
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print(f"wrote {path} ({width}x{height})")


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    parts = [(A, 0), (L, GAP), (T, GAP), (CURSOR, CURSOR_GAP)]
    rows, width = compose(parts)

    px, w, h = render(rows, width, scale=12, pad=4)
    write_png(f"{out_dir}/logo_alt.png", w, h, px)

    px, w, h = render(rows, width, scale=16, pad=5,
                      bg=(8, 12, 10, 255), scanlines=True)
    write_png(f"{out_dir}/logo_alt_preview.png", w, h, px)

    # Square 128x128 mod icon. Drops the cursor block - at icon size the
    # wordmark alone is already small, and the extra glyph would shrink
    # the letters below legibility in a launcher's mod list.
    icon_rows, icon_w = compose([(A, 0), (L, GAP), (T, GAP)])
    px, w, h = render(icon_rows, icon_w, scale=4, pad=1,
                      bg=(8, 12, 10, 255))
    px, w, h = pad_to_square(px, w, h, 128, (8, 12, 10, 255))
    write_png(f"{out_dir}/icon.png", w, h, px)


def pad_to_square(rows, width, height, size, bg):
    """Center an image on a square canvas of the given size."""
    if width > size or height > size:
        # Silently clamping here produced rows of the wrong byte length
        # and a corrupt PNG that only failed when something tried to
        # decode it - fail at generation time instead.
        raise ValueError(f"image {width}x{height} does not fit in {size}x{size}")
    bg_px = bytes(bg)
    out = []
    top = (size - height) // 2
    left = (size - width) // 2
    blank = bytearray(bg_px * size)
    for _ in range(top):
        out.append(bytearray(blank))
    for row in rows:
        line = bytearray(bg_px * left)
        line += row[:width * 4]
        line += bg_px * (size - left - width)
        out.append(line)
    while len(out) < size:
        out.append(bytearray(blank))
    return out[:size], size, size


if __name__ == "__main__":
    main()
