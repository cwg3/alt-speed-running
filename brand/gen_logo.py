#!/usr/bin/env python3
"""Generate the 'alt' wordmark: Andale Mono at 33px, rendered with
antialiasing disabled so the glyphs land on a hard pixel grid, then
given a 1984 monochrome-monitor treatment.

Rendering a real terminal face at a small size with AA off is what
produces authentic 8-bit letterforms - the pixel grid comes from the
font's own hinting rather than from hand-drawn approximations of it.

The CRT pass deliberately avoids a brightness gradient across the
glyphs. A monochrome phosphor monitor lights every lit pixel equally;
the depth comes from bloom, scanlines and vignette, not from shading
the letters.

Requires Pillow:  python3 -m venv venv && venv/bin/pip install Pillow
Usage:            venv/bin/python gen_logo.py [out_dir]
"""
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FONT_PATH = "/System/Library/Fonts/Supplemental/Andale Mono.ttf"
FONT_SIZE = 33
TEXT = "alt"
# Columns of blank space between glyphs. Andale Mono is monospace, so
# drawing the string in one call leaves a wide fixed advance between
# letters; each glyph is rendered separately and re-packed to this gap
# instead. Lower is tighter.
LETTER_GAP = 2

# P1/P39-style phosphor: green with a slight yellow lean. Pure #00FF00
# reads as modern RGB green rather than a phosphor.
PHOSPHOR = (86, 255, 66)
# Screen at rest still glows faintly - a CRT face is never pure black.
SCREEN_BG = (7, 17, 10)

# Two bloom passes: a tight bright halation right at the stroke, and a
# wide dim haze from light scattering inside the glass.
BLOOM_TIGHT_RADIUS = 2.2
BLOOM_TIGHT_GAIN = 0.85
BLOOM_WIDE_RADIUS = 9.0
BLOOM_WIDE_GAIN = 0.45

SCANLINE_EVERY = 3
SCANLINE_FACTOR = 0.62
VIGNETTE_STRENGTH = 0.55


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

    used = [y for y, row in enumerate(rows) if any(row)]
    rows = rows[used[0]:used[-1] + 1]
    return rows, width, len(rows)


def upscaled_mask(rows, width, height, scale, pad):
    """Nearest-neighbour mask at output resolution. Nearest keeps the
    pixel edges hard - the softness should come from bloom, not from
    smearing the glyphs themselves."""
    small = Image.new("L", (width + pad * 2, height + pad * 2), 0)
    px = small.load()
    for y in range(height):
        for x in range(width):
            if rows[y][x]:
                px[x + pad, y + pad] = 255
    return small.resize((small.width * scale, small.height * scale), Image.NEAREST)


def bloom_layer(mask, radius, gain):
    glow = Image.new("RGB", mask.size, (0, 0, 0))
    glow.paste(Image.new("RGB", mask.size, PHOSPHOR), mask=mask)
    glow = glow.filter(ImageFilter.GaussianBlur(radius))
    return glow.point(lambda v: int(v * gain))


def vignette(img, strength):
    w, h = img.size
    cx, cy = w / 2, h / 2
    max_d = (cx ** 2 + cy ** 2) ** 0.5
    grad = Image.new("L", (w, h))
    gpx = grad.load()
    for y in range(h):
        dy2 = (y - cy) ** 2
        for x in range(w):
            d = ((x - cx) ** 2 + dy2) ** 0.5 / max_d
            gpx[x, y] = int(255 * (1.0 - strength * d * d))
    return ImageChops.multiply(img, Image.merge("RGB", (grad, grad, grad)))


def scanlines(img, every, factor):
    px = img.load()
    w, h = img.size
    for y in range(every - 1, h, every):
        for x in range(w):
            r, g, b = px[x, y][:3]
            px[x, y] = (int(r * factor), int(g * factor), int(b * factor))
    return img


def crt(mask, transparent):
    """Compose the monitor look: flat phosphor text, bloom, scanlines,
    vignette."""
    size = mask.size
    core = Image.new("RGB", size, (0, 0, 0))
    core.paste(Image.new("RGB", size, PHOSPHOR), mask=mask)

    lit = ImageChops.add(core, bloom_layer(mask, BLOOM_TIGHT_RADIUS, BLOOM_TIGHT_GAIN))
    lit = ImageChops.add(lit, bloom_layer(mask, BLOOM_WIDE_RADIUS, BLOOM_WIDE_GAIN))

    if transparent:
        # Alpha follows the light itself, so the bloom fades out over
        # whatever the logo is placed on instead of carrying a screen
        # background with it.
        alpha = lit.convert("L").point(lambda v: min(255, int(v * 1.6)))
        out = lit.copy()
        out.putalpha(alpha)
        return out

    screen = ImageChops.add(Image.new("RGB", size, SCREEN_BG), lit)
    screen = scanlines(screen, SCANLINE_EVERY, SCANLINE_FACTOR)
    screen = vignette(screen, VIGNETTE_STRENGTH)
    return screen.convert("RGBA")


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    rows, w, h = text_mask()
    print(f"Andale Mono {FONT_SIZE}px, gap {LETTER_GAP} -> {w}x{h} pixel grid")

    crt(upscaled_mask(rows, w, h, scale=6, pad=4), transparent=True) \
        .save(f"{out_dir}/logo_alt.png")
    crt(upscaled_mask(rows, w, h, scale=8, pad=6), transparent=False) \
        .save(f"{out_dir}/logo_alt_preview.png")

    # Square 128x128 mod icon. The mask is centred on the full canvas
    # and the CRT pass runs over the whole thing - treating a smaller
    # image and pasting it left a visible rectangular seam where the
    # scanlines and vignette stopped.
    art = upscaled_mask(rows, w, h, scale=2, pad=0)
    icon_mask = Image.new("L", (128, 128), 0)
    icon_mask.paste(art, ((128 - art.width) // 2, (128 - art.height) // 2))
    crt(icon_mask, transparent=False).save(f"{out_dir}/icon.png")

    print("wrote logo_alt.png, logo_alt_preview.png, icon.png")


if __name__ == "__main__":
    main()
