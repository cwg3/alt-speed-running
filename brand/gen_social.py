#!/usr/bin/env python3
"""Generate the 1280x640 GitHub social preview card.

GitHub renders this when the repo link is pasted into Discord, Slack
or a tweet. Without one you get a grey default card with the repo name
in it, which for a project whose whole pitch is being the open
alternative is a wasted first impression.

Reuses gen_logo's CRT treatment rather than reimplementing it, so the
card, the loading screen tile and the in-game wordmark are the same
artwork at different sizes. Requires the same venv:

    venv/bin/python gen_social.py
"""
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import gen_logo as G

W, H = 1280, 640
PURPLE = (169, 123, 255)
DIM = (107, 114, 128)


def line_mask(text, size, gap=2):
    """Rasterise a whole line as ONE mask, with each glyph's x-range.

    One mask per LINE, not per word, because a per-word mask has to be
    cropped to its own ink and that throws away where the glyph sat on
    the baseline. Cropping "-" on its own lifts it to the top of its
    box: the hyphen in "speed-running" floated above the letters until
    the line was rasterised together.

    Returns (rows, width, height, spans) where spans[i] is the (x0, x1)
    of glyph i, which is what lets one line carry three colours.
    """
    font = ImageFont.truetype(G.FONT_PATH, size)
    canvas_h = size * 3
    glyphs, spans = [], []
    for ch in text:
        img = Image.new("L", (size * 3, canvas_h), 0)
        d = ImageDraw.Draw(img)
        d.fontmode = "1"                  # no antialiasing: hard pixel grid
        d.text((size, size), ch, font=font, fill=255)
        bbox = img.getbbox()
        if not bbox:                      # a space carries no ink
            glyphs.append([[False] * max(1, size // 2) for _ in range(canvas_h)])
            continue
        left, _, right, _ = bbox
        px = img.load()
        # Trimmed horizontally only. Vertically every glyph keeps the
        # full canvas, so they all share one baseline.
        glyphs.append([[px[x, y] > 127 for x in range(left, right)]
                       for y in range(canvas_h)])

    rows, x = [], 0
    for i, g in enumerate(glyphs):
        w = len(g[0])
        spans.append((x + (gap if i else 0), x + (gap if i else 0) + w))
        x = spans[-1][1]
    width = x
    for y in range(canvas_h):
        row = []
        for i, g in enumerate(glyphs):
            if i:
                row += [False] * gap
            row += g[y]
        rows.append(row)

    used = [y for y, r in enumerate(rows) if any(r)]
    top, bot = used[0], used[-1] + 1
    return rows[top:bot], width, bot - top, spans


def paint(card, rows, width, height, x, y, scale, colours):
    """Draw a line at `scale`, nearest-neighbour, colour per x-range.

    Nearest, never smooth: rendering with AA off exists so the pixel
    grid survives enlargement, and anything smoother undoes it.
    """
    for (x0, x1), colour in colours:
        m = Image.new("L", (width, height), 0)
        px = m.load()
        for yy in range(height):
            for xx in range(x0, min(x1, width)):
                if rows[yy][xx]:
                    px[xx, yy] = 255
        m = m.resize((width * scale, height * scale), Image.NEAREST)
        card.paste(Image.new("RGB", m.size, colour), (x, y), m)
    return width * scale, height * scale


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    card = Image.new("RGB", (W, H), G.SCREEN_BG)

    # "alt" - the mark.
    rows, w, h, spans = line_mask("alt", 33)
    sc = 9
    mw, mh = w * sc, h * sc
    ax, ay = (W - mw) // 2, 118
    paint(card, rows, w, h, ax, ay, sc, [((0, w), G.PHOSPHOR)])

    # "speed-running" - one mask, three colours, so the hyphen sits on
    # the same baseline as the letters either side of it.
    text = "speed-running"
    rows, w, h, spans = line_mask(text, 33)
    sc = 4
    cut = text.index("-")
    colours = [
        ((spans[0][0], spans[cut - 1][1]), PURPLE),
        ((spans[cut][0], spans[cut][1]), G.PHOSPHOR),
        ((spans[cut + 1][0], spans[-1][1]), PURPLE),
    ]
    wx = (W - w * sc) // 2
    wy = ay + mh + 46
    _, wh = paint(card, rows, w, h, wx, wy, sc, colours)

    # The line that says what it is, clear of the descender on "running".
    # Short on purpose. The full sentence at a legible size ran the
    # width of the card edge to edge, and GitHub prints the repository
    # description directly beneath this image anyway - the card does
    # not have to carry the whole pitch, only enough of it to be worth
    # clicking.
    rows, w, h, spans = line_mask("every deviation, published", 33)
    sc = 2
    paint(card, rows, w, h, (W - w * sc) // 2, wy + wh + 60, sc, [((0, w), DIM)])

    glow = card.filter(ImageFilter.GaussianBlur(G.BLOOM_TIGHT_RADIUS))
    card = Image.blend(card, glow, 0.35)
    wide = card.filter(ImageFilter.GaussianBlur(G.BLOOM_WIDE_RADIUS))
    card = Image.blend(card, wide, 0.22)
    card = G.vignette(card, G.VIGNETTE_STRENGTH)

    card.save(f"{out}/social_preview.png")
    print(f"wrote {out}/social_preview.png  {card.size[0]}x{card.size[1]}")


if __name__ == "__main__":
    main()
