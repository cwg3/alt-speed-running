#!/usr/bin/env python3
"""Generate the 'alt' wordmark: Andale Mono at 33px, rendered with
antialiasing disabled so the glyphs land on a hard pixel grid, then
given CRT phosphor styling.

Rendering a real terminal face at a small size with AA off is what
produces authentic 8-bit letterforms - the pixel grid comes from the
font's own hinting rather than from hand-drawn approximations of it.

Requires Pillow:  python3 -m venv venv && venv/bin/pip install Pillow
Usage:            venv/bin/python gen_logo.py [out_dir]
"""
import sys
from collections import deque

from PIL import Image, ImageDraw, ImageFont

FONT_PATH = "/System/Library/Fonts/Supplemental/Andale Mono.ttf"
FONT_SIZE = 33
TEXT = "alt"
# Columns of blank space between glyphs. Andale Mono is monospace, so
# drawing the string in one call leaves a wide fixed advance between
# letters; each glyph is rendered separately and re-packed to this gap
# instead. Lower is tighter.
LETTER_GAP = 2

# Phosphor green ramp, brightest at the top. Deliberately narrow: a CRT
# phosphor is close to uniformly lit, and a wide gradient makes the
# bottom of the letters darker than the surrounding glow, which reads
# as inverted.
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
# Glow stays clearly dimmer than every ink value above, or it reads as a
# pale sticker outline instead of light bleeding off the glyph.
GLOW = [(30, 200, 90, 70), (26, 170, 76, 34), (20, 140, 62, 14)]
BG = (8, 12, 10, 255)


def text_mask():
    """Rasterize TEXT with antialiasing off and re-pack the glyphs to
    LETTER_GAP columns apart.

    Each glyph is drawn on its own canvas at the same origin, then
    trimmed horizontally only - trimming vertically per glyph would
    discard each one's offset from the baseline and leave 'a', 'l' and
    't' all sitting at the same height.
    """
    font = ImageFont.truetype(FONT_PATH, FONT_SIZE)
    canvas_h = FONT_SIZE * 3
    origin = (FONT_SIZE, FONT_SIZE)

    glyphs = []
    for ch in TEXT:
        img = Image.new("L", (FONT_SIZE * 3, canvas_h), 0)
        draw = ImageDraw.Draw(img)
        draw.fontmode = "1"  # disables antialiasing - hard pixel edges only
        draw.text(origin, ch, font=font, fill=255)
        bbox = img.getbbox()
        if not bbox:
            raise RuntimeError(f"font rendered nothing for {ch!r} - check FONT_PATH")
        left, _, right, _ = bbox
        px = img.load()
        glyphs.append([[px[x, y] > 127 for x in range(left, right)]
                       for y in range(canvas_h)])

    width = sum(len(g[0]) for g in glyphs) + LETTER_GAP * (len(glyphs) - 1)
    rows = []
    for y in range(canvas_h):
        row = []
        for i, g in enumerate(glyphs):
            if i:
                row += [False] * LETTER_GAP
            row += g[y]
        rows.append(row)

    # now trim the blank rows above and below the whole word
    used = [y for y, row in enumerate(rows) if any(row)]
    rows = rows[used[0]:used[-1] + 1]
    return rows, width, len(rows)


def build(ink, width, height, pad):
    cw, ch = width + pad * 2, height + pad * 2
    grid = [[False] * cw for _ in range(ch)]
    for y in range(height):
        for x in range(width):
            if ink[y][x]:
                grid[y + pad][x + pad] = True

    # Flood fill the outside from the border. Without this the glow
    # radiates into enclosed counters (the bowl of 'a'), filling them
    # and making the word illegible.
    outside = [[False] * cw for _ in range(ch)]
    q = deque()
    for x in range(cw):
        for y in (0, ch - 1):
            if not grid[y][x] and not outside[y][x]:
                outside[y][x] = True
                q.append((x, y))
    for y in range(ch):
        for x in (0, cw - 1):
            if not grid[y][x] and not outside[y][x]:
                outside[y][x] = True
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < cw and 0 <= ny < ch and not grid[ny][nx] and not outside[ny][nx]:
                outside[ny][nx] = True
                q.append((nx, ny))

    glow = [[0] * cw for _ in range(ch)]
    for y in range(ch):
        for x in range(cw):
            if grid[y][x] or not outside[y][x]:
                continue
            best = 0
            for dy in range(-3, 4):
                for dx in range(-3, 4):
                    oy, ox = y + dy, x + dx
                    if 0 <= oy < ch and 0 <= ox < cw and grid[oy][ox]:
                        lvl = 4 - max(abs(dy), abs(dx))
                        best = max(best, lvl)
            glow[y][x] = best
    return grid, glow, cw, ch


def colorize(grid, glow, cw, ch, pad, height, bg):
    img = Image.new("RGBA", (cw, ch))
    px = img.load()
    for y in range(ch):
        for x in range(cw):
            if grid[y][x]:
                gy = min(max(y - pad, 0), height - 1)
                idx = gy * len(GRADIENT) // max(height, 1)
                r, g, b = GRADIENT[min(idx, len(GRADIENT) - 1)]
                px[x, y] = (r, g, b, 255)
            elif glow[y][x]:
                gr, gg, gb, ga = GLOW[3 - glow[y][x]]
                if bg:
                    # Composite over the known background here. Leaving
                    # these semi-transparent means the viewer composites
                    # them over whatever it likes (white, usually), which
                    # turns a dim glow into a thick pale outline.
                    br, bgc, bb, _ = bg
                    px[x, y] = (
                        (gr * ga + br * (255 - ga)) // 255,
                        (gg * ga + bgc * (255 - ga)) // 255,
                        (gb * ga + bb * (255 - ga)) // 255,
                        255,
                    )
                else:
                    px[x, y] = (gr, gg, gb, ga)
            else:
                px[x, y] = bg if bg else (0, 0, 0, 0)
    return img


def scanline(img, every=4, factor=0.70):
    px = img.load()
    w, h = img.size
    for y in range(h):
        if y % every != every - 1:
            continue
        for x in range(w):
            r, g, b, a = px[x, y]
            px[x, y] = (int(r * factor), int(g * factor), int(b * factor), a)
    return img


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    ink, w, h = text_mask()
    print(f"Andale Mono {FONT_SIZE}px -> {w}x{h} pixel grid")

    # wide transparent wordmark
    grid, glow, cw, ch = build(ink, w, h, pad=3)
    img = colorize(grid, glow, cw, ch, 3, h, bg=None)
    img = img.resize((cw * 6, ch * 6), Image.NEAREST)
    img.save(f"{out_dir}/logo_alt.png")

    # dark preview with scanlines
    grid, glow, cw, ch = build(ink, w, h, pad=4)
    img = colorize(grid, glow, cw, ch, 4, h, bg=BG)
    img = img.resize((cw * 8, ch * 8), Image.NEAREST)
    img = scanline(img)
    img.save(f"{out_dir}/logo_alt_preview.png")

    # square 128x128 mod icon
    grid, glow, cw, ch = build(ink, w, h, pad=2)
    img = colorize(grid, glow, cw, ch, 2, h, bg=BG)
    scale = max(1, min(128 // cw, 128 // ch))
    img = img.resize((cw * scale, ch * scale), Image.NEAREST)
    icon = Image.new("RGBA", (128, 128), BG)
    icon.paste(img, ((128 - img.width) // 2, (128 - img.height) // 2))
    icon.save(f"{out_dir}/icon.png")

    print("wrote logo_alt.png, logo_alt_preview.png, icon.png")


if __name__ == "__main__":
    main()
