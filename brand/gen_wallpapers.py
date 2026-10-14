#!/usr/bin/env python3
"""Render the five seed-type wallpapers shown behind the countdown.

The look is a still from Tron (1982): each structure a lit grey model
traced in glowing strips - a white-hot core in an azure halo, only where
two surfaces meet - standing on a dark slab of its own terrain, on a
glossy floor grid under a low sunset, softened like film and grained.

The lines are not drawn edge by edge. Every attempt at that left nicks,
beads or overshoots where one block's stroke met the next; instead each
pixel is tagged with the flat surface covering it and a line goes
wherever two surfaces meet. See draw_model.

Slabs are trimmed to whole floor-grid squares and the grid is laid from
the slab's corner, so every slab edge runs along a grid line.

The geometry is real. Each structure is dumped from a generated world
twice, once with structures on and once with them off, and the model is
the difference: exactly the blocks the structure placed, with none of
the hand-picking a block-type filter needs (a desert temple is sandstone
in a desert made of sandstone). The terrain slab under it is that same
world with the structure taken away, so a buried chest sits in real sand.

Dumps are `wall-<type>.txt` (structures on) and `wallB-<type>.txt`
(off) - lines of `P x0 y0 z0 x1 y1 z1` for each structure piece and
`B x y z block` for every non-air block. They live in brand/walldata/,
which is gitignored: they come from a seed, and seeds stay out of this
repository. Its README says how to re-dump them.

Requires Pillow, numpy and pycairo:  venv/bin/pip install Pillow numpy pycairo
(pycairo builds against Homebrew's cairo: PKG_CONFIG_PATH=/opt/homebrew/lib/pkgconfig)
Usage:  venv/bin/python gen_wallpapers.py <dump_dir> <out_dir> [--width N] [type ...]

The mod ships them at --width 1920, into
mod/src/main/resources/assets/speedrunmcalt/textures/gui/seed/ (and the
lobby into textures/gui/). The soft lines have no hard edge for the
game's stretching to show, and grain makes a 3840 frame several times
the size. Ui.java's WALL_W/WALL_H must match.
"""
import gzip
import math
import os
import struct
import sys

import cairo
import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080
SS = 2  # supersampling factor


class Canvas:
    """An opaque RGB picture that lines and polygons are drawn onto as
    vector paths, through cairo, anti-aliased.

    Pillow's ImageDraw has no anti-aliasing: every neon edge was a
    staircase, and supersampling only made the steps smaller. Cairo
    covers each pixel by how much of the path is in it, so a line at
    any angle is smooth at any size.
    """

    def __init__(self, img_or_size):
        img = (img_or_size if isinstance(img_or_size, Image.Image)
               else Image.new("RGB", img_or_size))
        w, h = img.size
        rgb = np.asarray(img.convert("RGB"))
        # ARGB32 is BGRA in memory on a little-endian machine. Opaque
        # throughout, so premultiplying changes nothing.
        self.buf = np.empty((h, w, 4), np.uint8)
        self.buf[..., 0] = rgb[..., 2]
        self.buf[..., 1] = rgb[..., 1]
        self.buf[..., 2] = rgb[..., 0]
        self.buf[..., 3] = 255
        self.surface = cairo.ImageSurface.create_for_data(
            memoryview(self.buf), cairo.FORMAT_ARGB32, w, h, w * 4)
        self.ctx = cairo.Context(self.surface)
        self.ctx.set_antialias(cairo.ANTIALIAS_BEST)
        self.ctx.set_line_join(cairo.LINE_JOIN_ROUND)

    def _colour(self, rgba):
        r, g, b, a = rgba
        self.ctx.set_source_rgba(r / 255, g / 255, b / 255, a / 255)

    def polygon(self, pts, fill):
        self.ctx.move_to(*pts[0])
        for p in pts[1:]:
            self.ctx.line_to(*p)
        self.ctx.close_path()
        self._colour(fill)
        # Not anti-aliased. Two smoothed faces sharing an edge each only
        # half-cover it, and the lines behind leaked through every such
        # crack as dashes. Every visible face border is stroked over.
        self.ctx.set_antialias(cairo.ANTIALIAS_NONE)
        self.ctx.fill()
        self.ctx.set_antialias(cairo.ANTIALIAS_BEST)

    def line(self, pts, fill, width, round_caps=True):
        # Round caps close the corners where a face's edges meet; the
        # grid's lines are many short segments of changing alpha, and
        # round caps there doubled up at every joint into beads.
        self.ctx.set_line_cap(cairo.LINE_CAP_ROUND if round_caps else cairo.LINE_CAP_BUTT)
        self.ctx.set_line_width(width)
        self.ctx.move_to(*pts[0])
        for p in pts[1:]:
            self.ctx.line_to(*p)
        self._colour(fill)
        self.ctx.stroke()

    def faded_line(self, pts, alphas, rgb, width):
        """One stroke along a straight run of points whose alpha
        changes along it. Drawn as many short strokes, every joint
        between two of them was a visible nick in the line."""
        (x0, y0), (x1, y1) = pts[0], pts[-1]
        span = math.hypot(x1 - x0, y1 - y0)
        if span < 1e-6:
            return
        g = cairo.LinearGradient(x0, y0, x1, y1)
        r, gr, b = (c / 255 for c in rgb)
        for (x, y), a in zip(pts, alphas):
            t = min(1.0, max(0.0, math.hypot(x - x0, y - y0) / span))
            g.add_color_stop_rgba(t, r, gr, b, a / 255)
        self.ctx.set_line_cap(cairo.LINE_CAP_BUTT)
        self.ctx.set_line_width(width)
        self.ctx.move_to(x0, y0)
        self.ctx.line_to(x1, y1)
        self.ctx.set_source(g)
        self.ctx.stroke()

    def image(self):
        self.surface.flush()
        return Image.fromarray(self.buf[..., 2::-1].copy())

# The mod's palette (Palette.java), so the renders and the screens they
# sit behind are one set of colours.
CYAN = (90, 185, 255)  # Tron azure
PURPLE = (169, 123, 255)
MAGENTA = (236, 72, 153)
ORANGE = (255, 106, 0)
YELLOW = (255, 212, 0)
# The terrain slab's lines: a deep blue under the cyan, so the slab
# reads as part of the floor's world and the structure stands off it.
SLAB = (60, 110, 220)

# Blocks that are terrain wherever they turn up in the diff. The
# structure-on world differs from the structure-off world in more than
# the structure: trees grow around houses rather than through them, and
# villages reshape the ground they sit on. None of that is the structure.
NATURAL = {
    "stone", "dirt", "grass_block", "coarse_dirt", "podzol", "sand", "red_sand",
    "gravel", "granite", "diorite", "andesite", "clay", "water", "snow",
    "snow_block", "ice", "bedrock", "sandstone_natural",
    "coal_ore", "iron_ore", "gold_ore", "lapis_ore", "redstone_ore",
    "diamond_ore", "emerald_ore",
    "grass", "tall_grass", "fern", "large_fern", "dead_bush", "seagrass",
    "tall_seagrass", "kelp", "kelp_plant", "sugar_cane", "cactus", "vine",
    "lily_pad", "sweet_berry_bush", "dandelion", "poppy", "blue_orchid",
    "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
    "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
    "sunflower", "lilac", "rose_bush", "peony", "brown_mushroom",
    "red_mushroom", "brown_mushroom_block", "red_mushroom_block",
    "mushroom_stem", "pumpkin", "melon", "bamboo", "cocoa",
}
# Natural blocks that are not GROUND - the slab is ground, so these are
# left out of it rather than modelled.
NOT_GROUND = {
    "water", "grass", "tall_grass", "fern", "large_fern", "dead_bush",
    "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sugar_cane",
    "cactus", "vine", "lily_pad", "sweet_berry_bush", "brown_mushroom",
    "red_mushroom", "brown_mushroom_block", "red_mushroom_block",
    "mushroom_stem", "pumpkin", "melon", "bamboo", "cocoa", "snow",
}


def is_tree(name):
    return name.endswith("_leaves") or (name.endswith("_log") and not name.startswith("stripped"))


def accent(name):
    """Neon colour for a structure block. Cyan unless it is one of the
    few things a speedrunner is actually looking for in it."""
    if name in ("lava", "magma_block", "tnt", "fire", "campfire"):
        return ORANGE
    if name in ("obsidian", "crying_obsidian"):
        return PURPLE
    if name in ("chest", "trapped_chest", "barrel", "gold_block", "bell"):
        return YELLOW
    if name in ("netherrack",):
        return MAGENTA
    return CYAN


def split_state(name):
    """"oak_stairs|facing=west|half=top" -> ("oak_stairs", {...}).

    Only template blocks carry a state, and only the ones whose shape
    depends on it; a dump has bare names, and gets the defaults.
    """
    base, *props = name.split("|")
    return base, dict(p.split("=", 1) for p in props)


# Quarter-block halves, by the side of the cube they sit against.
SIDE = {
    "north": (0, 0, 0, 1, 1, 0.5), "south": (0, 0, 0.5, 1, 1, 1),
    "west": (0, 0, 0, 0.5, 1, 1), "east": (0.5, 0, 0, 1, 1, 1),
}


def _cut(box, y0, y1):
    return (box[0], y0, box[2], box[3], y1, box[5])


def shape(name):
    """Boxes (x0, y0, z0, x1, y1, z1) inside the unit cube, and whether
    the block is a full cube for culling purposes.

    A dump's stairs and doors have no state - which way they face is
    unknown - and stay cubes, as they always were."""
    base, st = split_state(name)
    if base.endswith("_stairs") and st:
        # A slab and a step. Upside-down stairs are most of a
        # shipwreck's hull; as cubes it was a heap of blocks.
        top = st.get("half") == "top"
        slab = (0, 0.5, 0, 1, 1, 1) if top else (0, 0, 0, 1, 0.5, 1)
        step = _cut(SIDE[st.get("facing", "north")], *((0, 0.5) if top else (0.5, 1)))
        return [slab, step], False
    if base.endswith("_slab"):
        kind = st.get("type", "bottom")
        if kind == "double":
            return [(0, 0, 0, 1, 1, 1)], True
        return [(0, 0.5, 0, 1, 1, 1) if kind == "top" else (0, 0, 0, 1, 0.5, 1)], False
    if base.endswith(("_fence", "_wall", "_bars", "_pane")) or base in ("iron_bars",):
        # The post, and a rail toward each neighbour it joins.
        boxes = [(0.375, 0, 0.375, 0.625, 1, 0.625)]
        rails = {"north": (0.4375, 0.375, 0, 0.5625, 0.9375, 0.5),
                 "south": (0.4375, 0.375, 0.5, 0.5625, 0.9375, 1),
                 "west": (0, 0.375, 0.4375, 0.5, 0.9375, 0.5625),
                 "east": (0.5, 0.375, 0.4375, 1, 0.9375, 0.5625)}
        boxes += [r for d, r in rails.items() if st.get(d) == "true"]
        return boxes, False
    if base.endswith("_trapdoor"):
        if st.get("open") == "true":
            opposite = {"north": "south", "south": "north", "east": "west", "west": "east"}
            thin = {"north": (0, 0, 0, 1, 1, 0.1875), "south": (0, 0, 0.8125, 1, 1, 1),
                    "west": (0, 0, 0, 0.1875, 1, 1), "east": (0.8125, 0, 0, 1, 1, 1)}
            return [thin[opposite[st.get("facing", "north")]]], False
        return [(0, 0.8125, 0, 1, 1, 1) if st.get("half") == "top" else (0, 0, 0, 1, 0.1875, 1)], False
    if base.endswith("_door") and st:
        thin = {"east": (0, 0, 0, 0.1875, 1, 1), "west": (0.8125, 0, 0, 1, 1, 1),
                "south": (0, 0, 0, 1, 1, 0.1875), "north": (0, 0, 0.8125, 1, 1, 1)}
        return [thin[st.get("facing", "north")]], False
    if base.endswith(("_carpet", "_pressure_plate")) or base in ("rail", "snow"):
        return [(0, 0, 0, 1, 0.1875, 1)], False
    if base.endswith(("torch", "_lantern")) or base in ("lantern", "bell", "flower_pot"):
        return [(0.375, 0, 0.375, 0.625, 0.625, 0.625)], False
    if base in ("chest", "trapped_chest"):
        return [(0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375)], False
    return [(0, 0, 0, 1, 1, 1)], True


# A quarter turn of the lobby's (x, y, z) -> (z, y, -x), for the
# directions a block's state names.
TURN = {"east": "north", "north": "west", "west": "south", "south": "east"}


def turn_state(name):
    base, st = split_state(name)
    if not st:
        return name
    out = {}
    for k, v in st.items():
        if k in TURN:            # a fence's rails: east=true and so on
            out[TURN[k]] = v
        elif k == "facing" and v in TURN:
            out[k] = TURN[v]
        else:
            out[k] = v
    return "|".join([base] + [f"{k}={v}" for k, v in sorted(out.items())])


def read_nbt(path):
    """Just enough of the NBT format to read a structure template."""
    data = gzip.open(path).read()
    pos = 0

    def take(n):
        nonlocal pos
        pos += n
        return data[pos - n:pos]

    def name():
        (n,) = struct.unpack(">H", take(2))
        return take(n).decode()

    def payload(tag):
        if tag == 1:
            return struct.unpack(">b", take(1))[0]
        if tag == 2:
            return struct.unpack(">h", take(2))[0]
        if tag == 3:
            return struct.unpack(">i", take(4))[0]
        if tag == 4:
            return struct.unpack(">q", take(8))[0]
        if tag == 5:
            return struct.unpack(">f", take(4))[0]
        if tag == 6:
            return struct.unpack(">d", take(8))[0]
        if tag == 7:
            (n,) = struct.unpack(">i", take(4))
            return take(n)
        if tag == 8:
            return name()
        if tag == 9:
            inner = take(1)[0]
            (n,) = struct.unpack(">i", take(4))
            return [payload(inner) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                t = take(1)[0]
                if t == 0:
                    return out
                key = name()  # before the payload: the name comes first
                out[key] = payload(t)
        if tag == 11:
            (n,) = struct.unpack(">i", take(4))
            return list(struct.unpack(f">{n}i", take(4 * n)))
        if tag == 12:
            (n,) = struct.unpack(">i", take(4))
            return list(struct.unpack(f">{n}q", take(8 * n)))
        raise ValueError(f"NBT tag {tag}")

    take(1)
    name()
    return payload(10)


def load_template(path, sea):
    """A structure template from the game jar, as a dump would read,
    standing in a sea `sea` blocks up its own height.

    For when the generated example is a poor likeness. Shipwrecks come
    in twenty variants, half of them broken in half or upside down, and
    the one in the dump world was lying on its side; the template is the
    ship as designed.
    """
    nbt = read_nbt(path)
    # Shipwrecks carry several palettes - one per wood - under
    # "palettes"; anything else has one, under "palette".
    states = nbt["palette"] if "palette" in nbt else nbt["palettes"][0]
    # Shape-bearing state kept, as "name|key=value|...".
    KEEP = ("facing", "half", "type", "open")
    palette = []
    for p in states:
        n = p["Name"].split(":")[-1]
        props = p.get("Properties", {})
        # Not stairs: drawn true, the upside-down stairs of a hull are a
        # step on every block and the hull read as brickwork. As cubes
        # it is a clean wall.
        if n.endswith(("_slab", "_trapdoor", "_door")):
            n = "|".join([n] + [f"{k}={props[k]}" for k in KEEP if k in props])
        palette.append(n)
    blocks = {}
    for b in nbt["blocks"]:
        n = palette[b["state"]]
        # Structure blocks are markers for loot, replaced when the
        # structure is placed; there is nothing there in the world.
        if n not in ("air", "cave_air", "structure_void", "water", "structure_block"):
            blocks[tuple(b["pos"])] = n
    # A template's fences are stored unconnected - the game joins them
    # once placed. Join them here: to another fence, or to a full block.
    for (x, y, z), n in list(blocks.items()):
        if n.endswith("_fence"):
            joins = []
            for d, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)),
                                ("west", (-1, 0)), ("east", (1, 0))):
                m = blocks.get((x + dx, y, z + dz))
                if m and (m.endswith("_fence") or shape(m)[1]):
                    joins.append(f"{d}=true")
            blocks[(x, y, z)] = "|".join([n] + joins)
    sx, sy, sz = nbt["size"]
    for x in range(-8, sx + 8):
        for z in range(-8, sz + 8):
            if (x, sea, z) not in blocks:
                blocks[(x, sea, z)] = "water"
    return blocks, [(0, 0, 0, sx - 1, sy - 1, sz - 1)]


def load(path):
    blocks, pieces = {}, []
    with open(path) as f:
        for line in f:
            p = line.split()
            if p[0] == "P":
                pieces.append(tuple(map(int, p[1:])))
            else:
                blocks[(int(p[1]), int(p[2]), int(p[3]))] = p[4]
    return blocks, pieces


def in_pieces(k, pieces):
    x, y, z = k
    return any(a <= x <= d and b <= y <= e and c <= z <= f for a, b, c, d, e, f in pieces)


def build_scene(dump_dir, kind, crop, slab=True, least=8):
    """Voxels as {pos: (class, name)}: S structure, T terrain, W water.

    crop, when set, keeps only what is within that many blocks of the
    first piece's centre. A village is a hillside of houses and at full
    size every house is a few pixels; the first piece is its meeting
    point, which is the part anyone recognises.
    """
    template = f"{dump_dir}/template-{kind}.nbt"
    if os.path.exists(template):
        on, pieces = load_template(template, sea=4)
        off = {}
    else:
        on, pieces = load(f"{dump_dir}/wall-{kind}.txt")
        off, _ = load(f"{dump_dir}/wallB-{kind}.txt")
    if crop:
        p = pieces[0]
        mx, mz = (p[0] + p[3]) / 2, (p[2] + p[5]) / 2
        keep = lambda k: abs(k[0] - mx) <= crop and abs(k[2] - mz) <= crop
        on = {k: v for k, v in on.items() if keep(k)}

    structure = {}
    for k, name in on.items():
        if off.get(k) == name or name in NATURAL or name.endswith("_leaves"):
            continue
        # Logs are a house's frame or a tree's trunk; only the ones
        # inside a piece are the house.
        if is_tree(name) and not in_pieces(k, pieces):
            continue
        structure[k] = name
    if not structure:
        raise SystemExit(f"{kind}: no structure blocks in the diff")

    xs = [k[0] for k in structure]
    zs = [k[2] for k in structure]
    ys = [k[1] for k in structure]
    # Footprint: the structure plus a margin, and never smaller than a
    # 17-block square - a buried treasure is ONE block, and a slab one
    # block wide is not a place.
    pad = 4
    cx, cz = (min(xs) + max(xs)) / 2, (min(zs) + max(zs)) / 2
    hx = max((max(xs) - min(xs)) / 2 + pad, least)
    hz = max((max(zs) - min(zs)) / 2 + pad, least)
    x0, x1 = int(math.floor(cx - hx)), int(math.ceil(cx + hx))
    z0, z1 = int(math.floor(cz - hz)), int(math.ceil(cz + hz))
    # A whole number of floor-grid squares, so the slab's edges can lie
    # on grid lines: trimmed evenly off both sides.
    def to_grid(a, b):
        extra = (b + 1 - a) % GRID
        return a + extra // 2, b - (extra - extra // 2)
    x0, x1 = to_grid(x0, x1)
    z0, z1 = to_grid(z0, z1)

    # Column tops of the ground, ignoring trees and plants.
    tops = {}
    water_top = {}
    for (x, y, z), name in on.items():
        if not (x0 <= x <= x1 and z0 <= z <= z1) or (x, y, z) in structure:
            continue
        if name == "water":
            water_top[(x, z)] = max(water_top.get((x, z), -1), y)
            continue
        if name in NOT_GROUND or is_tree(name):
            continue
        tops[(x, z)] = max(tops.get((x, z), -1), y)
    if slab and not tops:
        raise SystemExit(f"{kind}: no ground in the footprint")
    # The slab's flat bottom: a few blocks under the structure's lowest
    # block. NOT under the lowest ground - at sea that is the seabed, and
    # a shipwreck stood on a column of ocean floor taller than itself.
    # Columns whose ground is below this are left open.
    floor = min(ys) - 3

    scene = {k: ("S", n) for k, n in structure.items()}
    # No slab: the structure floats on its water alone. A shipwreck's
    # terrain is a beach on one side and seabed on the other, and the
    # beach stood in front of the hull.
    for (x, z), top in (tops.items() if slab else ()):
        for y in range(max(floor, top - 40), top + 1):
            k = (x, y, z)
            if k in scene:
                continue
            name = on.get(k)
            if name is None or name in NOT_GROUND or is_tree(name):
                continue
            scene[k] = ("T", name)
    for (x, z), y in water_top.items():
        if (x, y + 1, z) in on:
            continue
        if slab:
            scene[(x, y, z)] = ("W", "water")
    # Align the slab AS BUILT to the floor grid: columns with no ground
    # leave it narrower than the footprint, and its bottom can sit a
    # block above the planned floor. Trimmed to whole grid squares, and
    # the floor - where the grid is drawn - is its actual bottom.
    ground = [k for k, v in scene.items() if v[0] == "T"]
    if ground:
        def to_grid(a, b):
            extra = (b + 1 - a) % GRID
            return a + extra // 2, b - (extra - extra // 2)
        x0, x1 = to_grid(min(k[0] for k in ground), max(k[0] for k in ground))
        z0, z1 = to_grid(min(k[2] for k in ground), max(k[2] for k in ground))
        scene = {k: v for k, v in scene.items()
                 if v[0] != "T" or (x0 <= k[0] <= x1 and z0 <= k[2] <= z1)}
        floor = min(k[1] for k, v in scene.items() if v[0] == "T")
    else:
        # No slab (the shipwreck): it stands on the floor itself, its
        # corner on grid lines. A 9-wide hull cannot fill whole squares.
        hull = [k for k, v in scene.items() if v[0] == "S"]
        x0, z0 = min(k[0] for k in hull), min(k[2] for k in hull)
        floor = min(k[1] for k in hull)
    return scene, floor, (x0, x1, z0, z1)


FACES = [  # normal, then the four corners as offsets in the unit cube
    ((1, 0, 0), [(1, 0, 0), (1, 1, 0), (1, 1, 1), (1, 0, 1)]),
    ((-1, 0, 0), [(0, 0, 1), (0, 1, 1), (0, 1, 0), (0, 0, 0)]),
    ((0, 1, 0), [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)]),
    ((0, -1, 0), [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)]),
    ((0, 0, 1), [(1, 0, 1), (1, 1, 1), (0, 1, 1), (0, 0, 1)]),
    ((0, 0, -1), [(0, 0, 0), (0, 1, 0), (1, 1, 0), (1, 0, 0)]),
]


def faces_of(scene):
    """Every exposed face, with its edges marked bright (a silhouette
    or crease) or faint (a seam to the next block in the same plane).

    The bright/faint split is what makes this read as Tron rather than
    as Minecraft with the textures off. Every block outlined equally is
    a grid; outlining only where the surface turns is a shape.
    """
    def full(k, cls):
        v = scene.get(k)
        return v is not None and v[0] == cls and shape(v[1])[1]

    out = []
    for (x, y, z), (cls, name) in scene.items():
        boxes, cube = shape(name)
        for box in boxes:
            for normal, corners in FACES:
                n = (x + normal[0], y + normal[1], z + normal[2])
                if cls == "W" and normal != (0, 1, 0):
                    continue
                # Hidden only behind the SAME kind of thing: the model and
                # its slab are see-through to each other, which is how a
                # buried chest shows through the sand.
                if cube and full(n, cls):
                    continue
                pts = []
                for c in corners:
                    px = box[0] if c[0] == 0 else box[3]
                    py = box[1] if c[1] == 0 else box[4]
                    pz = box[2] if c[2] == 0 else box[5]
                    pts.append((x + px, y + py, z + pz))
                edges = []
                for i in range(4):
                    a, b = corners[i], corners[(i + 1) % 4]
                    # Which way is "across" this edge, in the face's plane:
                    # from the face's centre toward the edge's midpoint.
                    mid = [(a[j] + b[j]) / 2 - 0.5 for j in range(3)]
                    step = [0, 0, 0]
                    for j in range(3):
                        if normal[j] == 0 and abs(mid[j]) == 0.5:
                            step[j] = int(mid[j] * 2)
                    bright = True
                    if cube:
                        m = (x + step[0], y + step[1], z + step[2])
                        beyond = (m[0] + normal[0], m[1] + normal[1], m[2] + normal[2])
                        if full(m, cls) and not full(beyond, cls):
                            bright = False
                    edges.append((i, bright))
                out.append((cls, name, normal, pts, edges))
    return out


class Camera:
    def __init__(self, target, yaw, pitch, dist, focal, cx, cy):
        self.t = np.array(target, float)
        cy_, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        # Forward points from the eye to the target, pitched DOWN.
        self.fwd = np.array([-sy * cp, -sp, -cy_ * cp])
        self.eye = self.t - self.fwd * dist
        self.right = np.cross(self.fwd, [0, 1, 0])
        self.right /= np.linalg.norm(self.right)
        self.up = np.cross(self.right, self.fwd)
        self.f, self.cx, self.cy = focal, cx, cy

    def project(self, p):
        d = np.asarray(p, float) - self.eye
        z = d @ self.fwd
        x = d @ self.right
        y = d @ self.up
        return self.cx + self.f * x / z, self.cy - self.f * y / z, z


def sky(img, horizon):
    """Night overhead, a Mead sunset at the horizon, black floor below."""
    w, h = img.size
    px = np.zeros((h, w, 3), float)
    ys = np.arange(h)[:, None]
    above = np.clip((horizon - ys) / max(horizon, 1), 0, 1)  # 0 at horizon, 1 at top
    top = np.array([4, 5, 14])
    mid = np.array([24, 10, 52])
    low = np.array([120, 24, 92])
    band = np.where(above > 0.35, 0, 1)[..., None]
    t1 = np.clip((above - 0.35) / 0.65, 0, 1)[..., None]
    t2 = np.clip(above / 0.35, 0, 1)[..., None]
    upper = mid + (top - mid) * t1
    lower = low + (mid - low) * t2 ** 0.7
    col = np.where(band == 1, lower, upper)
    # Below the horizon: near-black floor, warming toward the horizon
    # where it catches the sunset.
    below = np.clip((ys - horizon) / max(h - horizon, 1), 0, 1)[..., None]
    floor = np.array([26, 70, 96]) * (1 - below) ** 1.6 + np.array([6, 16, 26])
    px[:] = np.where((ys < horizon)[..., None], col, floor)
    # The sun: a wide low glow on the horizon behind the model.
    xs = np.arange(w)[None, :]
    dx = (xs - w * 0.5) / (w * 0.45)
    dy = (ys - horizon) / (h * 0.22)
    glow = np.exp(-(dx ** 2 + dy ** 2) * 1.6)[..., None]
    px += glow * np.array([255, 70, 120]) * 0.55
    hot = np.exp(-((ys - horizon) / (h * 0.006)) ** 2)[..., None] * np.exp(-dx ** 2 * 0.8)[..., None]
    px += hot * np.array([255, 150, 40]) * 0.9
    img.paste(Image.fromarray(np.clip(px, 0, 255).astype(np.uint8)))


GRID = 4   # floor grid spacing, in blocks
# Line softness and glow size. The lobby's models are far and small,
# and at full size the glow swallowed them.
LINE_SCALE = 1.0
# How far the floor grid runs before it has faded out. The lobby's
# models stand 170-300 blocks off, where the default had all but gone.
GRID_REACH = 400


def grid(draw, cam, floor_y, horizon, w, h, origin=(0, 0), spacing=GRID, reach=400):
    """The Tron floor: cyan lines on the plane under the slab, fading
    into the haze at the horizon."""
    c = cam.t
    def line(p0, p1):
        steps = 60
        pts, alphas = [], []
        for i in range(steps + 1):
            p = p0 + (p1 - p0) * i / steps
            sx, sy, z = cam.project(p)
            if z < 1:
                continue
            pts.append((sx, sy))
            alphas.append(70 * max(0.0, 1 - z / (reach * 0.9)) ** 1.5)
        if len(pts) > 1 and max(alphas) > 2:
            draw.faded_line(pts, alphas, CYAN, SS)
    # Lines through the origin given, so they can run along a slab's edges.
    gx0 = origin[0] + int((c[0] - origin[0]) // spacing) * spacing
    gz0 = origin[1] + int((c[2] - origin[1]) // spacing) * spacing
    for k in range(-reach // spacing, reach // spacing + 1):
        x = gx0 + k * spacing
        line(np.array([x, floor_y, c[2] - reach]), np.array([x, floor_y, c[2] + reach]))
        z = gz0 + k * spacing
        line(np.array([c[0] - reach, floor_y, z]), np.array([c[0] + reach, floor_y, z]))


def draw_model(base, glow, cam, faces, mirror_y=None, dim=1.0, slab_alpha=170):
    """Fills by the painter's algorithm, then the lines found from the
    picture rather than drawn edge by edge: a map of which flat surface
    covers each pixel, and a line wherever two surfaces meet. Every line
    is where an edge is actually seen, all of one width, and stops where
    the edge does. Glass (a see-through slab) gets its own map, so what
    is behind it keeps its lines."""
    light = np.array([0.4, 0.85, 0.3])
    light /= np.linalg.norm(light)
    items = []
    for cls, name, normal, pts, edges in faces:
        if mirror_y is not None:
            pts = [(p[0], 2 * mirror_y - p[1], p[2]) for p in pts]
            normal = (normal[0], -normal[1], normal[2])
        proj = [cam.project(p) for p in pts]
        if min(p[2] for p in proj) < 0.5:
            continue
        depth = sum(p[2] for p in proj) / 4
        poly = [(p[0], p[1]) for p in proj]
        items.append((depth, cls, name, normal, poly, pts))
    items.sort(key=lambda it: -it[0])

    w, h = base.surface.get_width(), base.surface.get_height()
    keys = {}
    solid_map, glass_map = Canvas((w, h)), Canvas((w, h))
    colour_map = Canvas((w, h))
    for depth, cls, name, normal, poly, world in items:
        shade = max(0.0, float(np.dot(normal, light)))
        if cls == "S":
            g = 58 + 70 * shade
            rgb, fill_a = [int(g * 0.92), int(g * 0.97), int(g * 1.08)], 255
            line = accent(split_state(name)[0])
        elif cls in ("T", "G"):
            g = 22 + 30 * shade
            rgb = [int(g * 0.8), int(g * 0.95), int(g * 1.25)]
            fill_a = GLASS_ALPHA if cls == "G" else 255
            line = SLAB
        else:
            rgb, fill_a, line = [10, 40, 70], 70, None
        fill_a = int(fill_a * dim)
        base.polygon(poly, fill=tuple(rgb) + (fill_a,))
        glow.polygon(poly, fill=(0, 0, 0, fill_a))
        if line is None:
            continue
        # A flat surface: its class, which way it faces, and where along
        # that axis it lies. Coplanar faces share one, so no seam.
        axis = int(np.argmax(np.abs(normal)))
        key = keys.setdefault((cls, tuple(normal), round(world[0][axis] * 16)), len(keys) + 1)
        enc = (key >> 16, (key >> 8) & 255, key & 255, 255)
        (glass_map if cls == "G" else solid_map).polygon(poly, fill=enc)
        if cls != "G":
            colour_map.polygon(poly, fill=line + (255,))

    def edges_of(canvas):
        canvas.surface.flush()
        b = canvas.buf.astype(np.int64)
        k = (b[..., 2] << 16) | (b[..., 1] << 8) | b[..., 0]
        e = np.zeros(k.shape, bool)
        e[:, :-1] |= k[:, :-1] != k[:, 1:]
        e[:-1, :] |= k[:-1, :] != k[1:, :]
        e[:, 1:] |= k[:, 1:] != k[:, :-1]
        e[1:, :] |= k[1:, :] != k[:-1, :]
        return e
    solid_e, glass_e = edges_of(solid_map), edges_of(glass_map)
    colour_map.surface.flush()
    col = colour_map.buf[..., 2::-1].astype(float)
    # A pixel on the outside of a silhouette has no colour of its own -
    # it takes its neighbour's.
    colour = Image.fromarray(col.astype(np.uint8)).filter(ImageFilter.MaxFilter(5))
    col = np.maximum(col, np.asarray(colour, float))
    is_neon = (np.abs(col - np.array(SLAB)).sum(-1) > 30)[..., None]

    def soft(mask, grow):
        m = Image.fromarray((mask * 255).astype(np.uint8))
        if grow > 1:
            m = m.filter(ImageFilter.MaxFilter(grow))
        # Blurred well past the pixel grid, so a stair-stepped diagonal
        # cannot read as one; lifted back so the core stays bright.
        soft = np.asarray(m.filter(ImageFilter.GaussianBlur(1.5 * SS * LINE_SCALE)), float) / 255
        return np.minimum(1.0, soft * 1.5)[..., None]

    core = soft(solid_e, 3) * dim
    b = np.asarray(base.image(), float)
    white = col + (255 - col) * 0.7
    line_rgb = np.where(is_neon, white, col)
    line_a = np.where(is_neon, 1.0, 140 / 255) * core
    b = b * (1 - line_a) + line_rgb * line_a
    ga = soft(glass_e, 3) * dim * (140 / 255)
    b = b * (1 - ga) + np.array(SLAB) * ga
    g = np.asarray(glow.image(), float)
    halo = soft(solid_e, 7 if LINE_SCALE >= 1 else 3) * dim * is_neon
    g = g * (1 - halo) + col * halo
    for canvas, arr in ((base, b), (glow, g)):
        img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
        rgb = np.asarray(img)
        canvas.buf[..., 0] = rgb[..., 2]
        canvas.buf[..., 1] = rgb[..., 1]
        canvas.buf[..., 2] = rgb[..., 0]
        canvas.surface.mark_dirty()


def beam(base, glow, cam, scene):
    """A shaft of light straight up out of the chest, to the sky.

    The treasure is one block under several of sand; without a marker
    the picture is a slab of beach. X marks the spot, Tron-style.
    """
    chests = [k for k, v in scene.items() if v[0] == "S" and v[1] == "chest"]
    if chests:
        beam_at(base, glow, cam, chests[0])


def beam_at(base, glow, cam, pos, height=60):
    x, y, z = pos
    bot = cam.project((x + 0.5, y + 0.5, z + 0.5))
    top = cam.project((x + 0.5, y + height, z + 0.5))
    for layer, width, alpha in ((glow, 10, 200), (glow, 4, 255), (base, 2, 255)):
        layer.line([(bot[0], bot[1]), (top[0], top[1])], fill=YELLOW + (alpha,), width=width * SS)


def bloom(img):
    tight = img.filter(ImageFilter.GaussianBlur(5 * SS * LINE_SCALE))
    wide = img.filter(ImageFilter.GaussianBlur(26 * SS * LINE_SCALE))
    a = np.asarray(img, float)
    # The halo around the strips. paint() rolls highlights off toward
    # white, so a strong glow brightens rather than clipping flat.
    out = a * 0.6 + np.asarray(tight, float) * 1.0 + np.asarray(wide, float) * 0.8
    return np.clip(out, 0, 255)


VIEWS = {
    # yaw, share of the frame width the model spans, crop radius, and
    # build_scene's options
    "village": (35, 0.78, 26, {}),
    "desert_temple": (32, 0.50, None, {}),
    "ruined_portal": (40, 0.50, None, {}),
    "shipwreck": (130, 0.66, None, {"slab": False}),
    "buried_treasure": (215, 0.40, None, {"least": 4}),
}


# The slab is near-opaque by default. Buried treasure's whole point is
# what is INSIDE the sand, so its slab is glass.
GLASS_ALPHA = 60
SLAB_ALPHA = {"buried_treasure": GLASS_ALPHA}


def render(dump_dir, out_dir, kind, out_w=W):
    yaw, span, crop, opts = VIEWS[kind]
    scene, floor, (x0, x1, z0, z1) = build_scene(dump_dir, kind, crop, **opts)
    if SLAB_ALPHA.get(kind) == GLASS_ALPHA:
        # Glass: the chest under the sand is the point.
        scene = {k: ("G" if c == "T" else c, n) for k, (c, n) in scene.items()}
    faces = faces_of(scene)
    pitch = 20.0

    # Framed on the model and its slab, not the sea: the water plane
    # runs past the ship on one side more than the other, and fitting to
    # it pushed the ship off centre.
    pts = np.array([p for f in faces if f[0] != "W" for p in f[3]])
    lo, hi = pts.min(0), pts.max(0)
    centre = (lo + hi) / 2
    radius = float(np.linalg.norm(hi - lo)) / 2
    w, h = W * SS, H * SS

    # Fit, then solve for distance. The model has to span its share of
    # the frame AND the horizon has to land in the upper third, where the
    # sunset is - and at a given pitch, how high the horizon sits is set
    # by the focal length. A long lens pushes it off the top of the
    # frame (the first renders had no sky at all); a short one, from
    # close in, brings it down and gives the steep Mead perspective.
    sample = pts[:: max(1, len(pts) // 4000)]
    want_horizon = h * 0.27
    dist = radius * 2.6
    for i in range(11):
        cam = Camera(centre, yaw, pitch, dist, 1000.0, 0, 0)
        proj = [cam.project(p) for p in sample]
        sx = [p[0] for p in proj]
        sy = [p[1] for p in proj]
        # Whichever of width and height runs out first sets the size.
        focal = 1000.0 * min((w * span) / (max(sx) - min(sx)),
                             (h * 0.66) / (max(sy) - min(sy)))
        ox = -(max(sx) + min(sx)) / 2 * focal / 1000.0
        oy = -(max(sy) + min(sy)) / 2 * focal / 1000.0
        cy = h * 0.54 + oy
        # The horizon sits focal * tan(pitch) above the image centre, so
        # the pitch that puts it in the upper third follows from the
        # lens - solved for, rather than chosen and then cropped off.
        # The last pass only fits: changing the pitch after the final
        # fit left every model off centre by however far it last moved.
        if i < 10:
            pitch = min(35.0, max(6.0, math.degrees(math.atan((cy - want_horizon) / focal))))
    cam = Camera(centre, yaw, pitch, dist, focal, w / 2 + ox, cy)

    horizon = int(cam.cy - cam.f * math.tan(math.radians(pitch)))
    beams = []
    if kind == "buried_treasure":
        beams = [k for k, v in scene.items() if v[0] == "S" and v[1] == "chest"][:1]
    paint(cam, horizon, faces, floor, SLAB_ALPHA.get(kind, 170), beams, f"{out_dir}/{kind}.png", out_w,
          grid_origin=(x0, z0))
    print(f"{kind}: {len(faces)} faces -> {out_dir}/{kind}.png")


def paint(cam, horizon, faces, floor, slab_alpha, beams, path, out_w, beam_height=60, grid_origin=(0, 0)):
    """Sky, reflection, grid, model, bloom, CRT - shared by the seed
    wallpapers and the lobby."""
    w, h = W * SS, H * SS
    base = Image.new("RGB", (w, h))
    sky(base, horizon)

    # Reflection first, dim and soft, then the grid over it, then the
    # model. The floor is glossy, not a mirror.
    refl = Canvas((w, h))
    # Only the glowing structures reflect. A slab's mirrored outline lay
    # under it as stray lines across the grid, and the slab looked off it.
    draw_model(refl, Canvas((w, h)), cam, [f for f in faces if f[0] == "S"],
               mirror_y=floor, dim=0.8, slab_alpha=slab_alpha)
    refl_a = np.asarray(refl.image().filter(ImageFilter.GaussianBlur(3 * SS)), float)
    rows = np.arange(h)[:, None, None]
    fade = np.clip((rows - horizon) / (h * 0.5), 0, 1)
    b = np.asarray(base, float) + refl_a * 0.28 * (1 - fade)
    b = np.clip(b, 0, 255).astype(np.uint8)

    # The floor grid stops at the models. Their faces are see-through -
    # the slab most of all - and the cyan floor lines showing through
    # them read as lines ON the model, a second grid over the blocks.
    gridded = Canvas(Image.fromarray(b))
    grid(gridded, cam, floor, horizon, w, h, grid_origin, reach=GRID_REACH)
    # The same goes for the models' reflections: the grid lay over the
    # mirrored neon and crossed every line of it. There the mask is
    # blurred and faded like the reflection, so the grid comes back as
    # the reflection dies away rather than stopping at a hard edge.
    def silhouette(mirror):
        mask = Canvas((w, h))
        for cls, _, _, pts, _ in faces:
            # The grid stops at the slabs themselves but runs on under
            # their reflections: cut away there, it never reached the
            # slab's corners, and nothing showed it lined up.
            if cls == "W" or (mirror and cls != "S"):
                continue
            if mirror:
                pts = [(p[0], 2 * floor - p[1], p[2]) for p in pts]
            proj = [cam.project(p) for p in pts]
            if min(p[2] for p in proj) >= 0.5:
                mask.polygon([(p[0], p[1]) for p in proj], (255, 255, 255, 255))
        return mask.image()
    # Not grown: the soft lines have no teeth, and a gap around each
    # slab made the grid look offset from it.
    m = np.asarray(silhouette(False).filter(ImageFilter.GaussianBlur(0.5 * SS)), float)[..., :1] / 255
    mr = np.asarray(silhouette(True).filter(ImageFilter.GaussianBlur(3 * SS)), float)[..., :1] / 255
    m = np.maximum(m, mr * (1 - fade))
    g = np.asarray(gridded.image(), float)
    base = Canvas(Image.fromarray((g * (1 - m) + b * m).astype(np.uint8)))

    glow = Canvas((w, h))
    draw_model(base, glow, cam, faces, slab_alpha=slab_alpha)
    for pos in beams:
        beam_at(base, glow, cam, pos, beam_height)
    base, glow = base.image(), glow.image()
    # Film: a touch soft everywhere.
    base = base.filter(ImageFilter.GaussianBlur(0.7 * SS))

    out = np.asarray(base, float) + bloom(glow) - np.asarray(glow, float) * 0.6
    # Highlights roll off toward white instead of clipping.
    out = 255 * (1 - np.exp(-out / 200))
    # Grain.
    rng = np.random.default_rng(1982)
    # Made at the 1920 frame and enlarged, so it looks the same at any
    # output size rather than finer and harsher at full resolution.
    gh, gw = out.shape[0] // SS, out.shape[1] // SS
    grain = Image.fromarray(np.clip(128 + rng.normal(0, 2.5, (gh, gw)) * 8, 0, 255).astype(np.uint8))
    grain = np.asarray(grain.resize((out.shape[1], out.shape[0]), Image.BILINEAR), float)
    out += ((grain - 128) / 8)[..., None]
    # No scanlines. Every sixth row darkened, crossing a thin sloped gap
    # between two neon lines, cut it into a regular row of dashes - and
    # the game resizing the picture to the window beat against them too.
    # Vignette.
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.sqrt(((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2)
    out *= np.clip(1.15 - 0.45 * r ** 2, 0.35, 1)[..., None]

    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).resize(
        (out_w, out_w * H // W), Image.LANCZOS)
    img.save(path, optimize=True)


# The lobby: four of the five on one grid. Each is placed by WHERE IT LANDS ON
# SCREEN - u, its centre's offset from the middle in units of the
# focal length (left negative) - and how far away it is, rather than by
# world coordinates; perspective made world placement a guessing game.
# Then quarter turns.
#
# The middle third, from the horizon down, is the profile panel and the
# buttons, and a structure behind them is a structure nobody sees. So:
# village and temple near, at the sides; portal and wreck further back
# and standing above them on screen, which needs the camera high enough
# that a far structure's base clears a near one's roof.
#
# Not the buried treasure. The only place left for it was dead centre,
# behind the panel, with its beam the one visible part - and on a 21:9
# screen, cropped top and bottom, the horizon is level with the wordmark
# and the beam ran up through the letters. It has its own wallpaper.
LOBBY = [
    ("village", -0.48, 170, 0),
    ("desert_temple", 0.50, 175, 0),
    ("ruined_portal", -0.36, 290, 0),
    ("shipwreck", 0.35, 300, 1),
]
LOBBY_EYE = 46          # camera height above the floor, in blocks
LOBBY_FOCAL = 3000.0    # pixels, at the supersampled size
# Where the horizon falls, as a share of the picture's height. Below
# the wordmark - "alt" sat ON the horizon at 0.27 - and at the top of
# the profile panel. 0.40 holds that on 16:9 and on 21:9, where the
# picture is cropped top and bottom and everything rides up.
LOBBY_HORIZON = 0.40
LOBBY_CENTRE = 0.66     # image row the camera looks at


def render_lobby(dump_dir, out_dir, out_w=W):
    global LINE_SCALE, GRID_REACH
    LINE_SCALE = 0.45
    GRID_REACH = 700
    merged = {}
    beams = []
    for kind, u, depth, turns in LOBBY:
        _, _, crop, opts = VIEWS[kind]
        scene, floor, _ = build_scene(dump_dir, kind, crop, **opts)
        for _ in range(turns):
            scene = {(z, y, -x): (v[0], turn_state(v[1])) for (x, y, z), v in scene.items()}
        solid = [k for k, v in scene.items() if v[0] != "W"]
        cx = (min(k[0] for k in solid) + max(k[0] for k in solid)) / 2
        cz = (min(k[2] for k in solid) + max(k[2] for k in solid)) / 2
        tx, tz = u * depth, -depth
        dx, dy, dz = int(round(tx - cx)), -floor, int(round(tz - cz))
        # Onto the floor grid: the slab's near corner on a grid line.
        ground = [k for k, v in scene.items() if v[0] == "T"] or solid
        dx -= (min(k[0] for k in ground) + dx) % GRID
        dz -= (min(k[2] for k in ground) + dz) % GRID
        glass = SLAB_ALPHA.get(kind) == GLASS_ALPHA
        for (x, y, z), (cls, name) in scene.items():
            k = (x + dx, y + dy, z + dz)
            merged[k] = ("G" if glass and cls == "T" else cls, name)
            if kind == "buried_treasure" and cls == "S" and name == "chest":
                beams.append(k)
    faces = faces_of(merged)

    w, h = W * SS, H * SS
    # The pitch follows from where the horizon has to be: it sits
    # focal * tan(pitch) above the point the camera looks at.
    cy = h * LOBBY_CENTRE
    horizon = int(h * LOBBY_HORIZON)
    pitch = math.degrees(math.atan((cy - horizon) / LOBBY_FOCAL))
    p = math.radians(pitch)
    fwd = np.array([0.0, -math.sin(p), -math.cos(p)])
    eye = np.array([0.0, LOBBY_EYE, 0.0])
    dist = 100.0
    cam = Camera(eye + fwd * dist, 0, pitch, dist, LOBBY_FOCAL, w / 2, cy)
    paint(cam, horizon, faces, 0, 170, beams, f"{out_dir}/lobby.png", out_w)
    print(f"lobby: {len(faces)} faces -> {out_dir}/lobby.png")


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    dump_dir, out_dir = sys.argv[1], sys.argv[2]
    rest = sys.argv[3:]
    out_w = W
    if "--width" in rest:
        i = rest.index("--width")
        out_w = int(rest[i + 1])
        rest = rest[:i] + rest[i + 2:]
    for kind in rest or list(VIEWS) + ["lobby"]:
        if kind == "lobby":
            render_lobby(dump_dir, out_dir, out_w)
        else:
            render(dump_dir, out_dir, kind, out_w)


if __name__ == "__main__":
    main()
