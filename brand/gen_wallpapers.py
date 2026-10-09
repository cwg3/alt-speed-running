#!/usr/bin/env python3
"""Render the five seed-type wallpapers shown behind the countdown.

Syd Mead by way of 1982 Tron: each structure as a dark, glossy model
traced in neon - silhouette and crease edges bright, the seams between
blocks faint - standing on a slab of its own terrain, on an endless
reflective grid under a low sunset.

The geometry is real. Each structure is dumped from a generated world
twice, once with structures on and once with them off, and the model is
the difference: exactly the blocks the structure placed, with none of
the hand-picking a block-type filter needs (a desert temple is sandstone
in a desert made of sandstone). The terrain slab under it is that same
world with the structure taken away, so a buried chest sits in real sand.

Dumps are `wall-<type>.txt` (structures on) and `wallB-<type>.txt`
(off) - lines of `P x0 y0 z0 x1 y1 z1` for each structure piece and
`B x y z block` for every non-air block. They are not in git: they come
from a seed, and seeds stay out of this repository.

Requires Pillow and numpy:  venv/bin/pip install Pillow numpy
Usage:  venv/bin/python gen_wallpapers.py <dump_dir> <out_dir> [--width N] [type ...]

The mod ships them at --width 1280, into
mod/src/main/resources/assets/speedrunmcalt/textures/gui/seed/ - full
1920 frames are five megabytes of jar for a screen shown ten seconds.
"""
import gzip
import math
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
SS = 2  # supersampling factor

# The mod's palette (Palette.java), so the renders and the screens they
# sit behind are one set of colours.
CYAN = (34, 211, 238)
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


def shape(name):
    """(x0, y0, z0, x1, y1, z1) inside the unit cube, and whether the
    block is a full cube for culling purposes."""
    if name.endswith("_slab"):
        return (0, 0, 0, 1, 0.5, 1), False
    if name.endswith(("_fence", "_wall", "_bars", "_pane")) or name in ("iron_bars",):
        return (0.375, 0, 0.375, 0.625, 1, 0.625), False
    if name.endswith(("_trapdoor", "_carpet", "_pressure_plate")) or name in ("rail", "snow"):
        return (0, 0, 0, 1, 0.1875, 1), False
    if name.endswith(("torch", "_lantern")) or name in ("lantern", "bell", "flower_pot"):
        return (0.375, 0, 0.375, 0.625, 0.625, 0.625), False
    if name in ("chest", "trapped_chest"):
        return (0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375), False
    return (0, 0, 0, 1, 1, 1), True


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
    palette = [p["Name"].split(":")[-1] for p in states]
    blocks = {}
    for b in nbt["blocks"]:
        n = palette[b["state"]]
        if n not in ("air", "cave_air", "structure_void", "water"):
            blocks[tuple(b["pos"])] = n
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
        if (x, y + 1, z) not in on:
            scene[(x, y, z)] = ("W", "water")
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
        box, cube = shape(name)
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
    floor = np.array([28, 8, 36]) * (1 - below) ** 3 + np.array([2, 2, 6])
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


def grid(draw, cam, floor_y, horizon, w, h, spacing=4, reach=400):
    """The Tron floor: cyan lines on the plane under the slab, fading
    into the haze at the horizon."""
    c = cam.t
    def line(p0, p1):
        steps = 60
        prev = None
        for i in range(steps + 1):
            p = p0 + (p1 - p0) * i / steps
            sx, sy, z = cam.project(p)
            if z < 1:
                prev = None
                continue
            if prev:
                fade = max(0.0, 1 - z / (reach * 0.9)) ** 1.5
                a = int(150 * fade)
                if a > 2:
                    draw.line([prev, (sx, sy)], fill=CYAN + (a,), width=SS)
            prev = (sx, sy)
    gx0 = int(c[0] // spacing) * spacing
    gz0 = int(c[2] // spacing) * spacing
    for k in range(-reach // spacing, reach // spacing + 1):
        x = gx0 + k * spacing
        line(np.array([x, floor_y, c[2] - reach]), np.array([x, floor_y, c[2] + reach]))
        z = gz0 + k * spacing
        line(np.array([c[0] - reach, floor_y, z]), np.array([c[0] + reach, floor_y, z]))


def draw_model(base, glow, cam, faces, mirror_y=None, dim=1.0, slab_alpha=170):
    """Painter's algorithm, far to near. Faces go into the base image;
    edges go into both, so the glow layer can be bloomed without
    blooming the dark faces."""
    bd = ImageDraw.Draw(base, "RGBA")
    gd = ImageDraw.Draw(glow, "RGBA")
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
        items.append((depth, cls, name, normal, proj, edges))
    items.sort(key=lambda it: -it[0])

    for depth, cls, name, normal, proj, edges in items:
        poly = [(p[0], p[1]) for p in proj]
        shade = max(0.0, float(np.dot(normal, light)))
        neon = accent(name) if cls == "S" else CYAN
        if cls == "S":
            # Glossy black with a breath of the block's own neon in it -
            # Tron's solids are not black, they are lit from inside.
            base_rgb = [int(6 + c * 0.05 + 22 * shade) for c in neon]
            fill_a, edge_a, seam_a = 235, 255, 70
        elif cls in ("T", "G"):
            # The slab is the stage, not the subject: dim blue, never
            # bloomed, so the only thing glowing is the structure. G is a
            # glass slab - see SLAB_ALPHA - in a scene that mixes both.
            neon = SLAB
            base_rgb = [int(3 + 8 * shade), int(6 + 12 * shade), int(14 + 22 * shade)]
            fill_a, edge_a, seam_a = (GLASS_ALPHA if cls == "G" else slab_alpha), 90, 14
        else:  # water
            base_rgb = [10, 40, 70]
            fill_a, edge_a, seam_a = 70, 0, 0
        fill_a = int(fill_a * dim)
        bd.polygon(poly, fill=tuple(base_rgb) + (fill_a,))
        gd.polygon(poly, fill=(0, 0, 0, fill_a))
        for i, bright in edges:
            a = edge_a if bright else seam_a
            a = int(a * dim)
            if a <= 0:
                continue
            seg = [poly[i], poly[(i + 1) % 4]]
            width = (2 if bright else 1) * SS
            bd.line(seg, fill=neon + (a,), width=width)
            if bright and cls == "S":
                gd.line(seg, fill=neon + (a,), width=width + SS)


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
        d = ImageDraw.Draw(layer, "RGBA")
        d.line([(bot[0], bot[1]), (top[0], top[1])], fill=YELLOW + (alpha,), width=width * SS)


def bloom(img):
    tight = img.filter(ImageFilter.GaussianBlur(4 * SS))
    wide = img.filter(ImageFilter.GaussianBlur(18 * SS))
    a = np.asarray(img, float)
    out = a * 0.6 + np.asarray(tight, float) * 1.1 + np.asarray(wide, float) * 0.9
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
    paint(cam, horizon, faces, floor, SLAB_ALPHA.get(kind, 170), beams, f"{out_dir}/{kind}.png", out_w)
    print(f"{kind}: {len(faces)} faces -> {out_dir}/{kind}.png")


def paint(cam, horizon, faces, floor, slab_alpha, beams, path, out_w, beam_height=60):
    """Sky, reflection, grid, model, bloom, CRT - shared by the seed
    wallpapers and the lobby."""
    w, h = W * SS, H * SS
    base = Image.new("RGB", (w, h))
    sky(base, horizon)

    # Reflection first, dim and soft, then the grid over it, then the
    # model. The floor is glossy, not a mirror.
    refl = Image.new("RGB", (w, h))
    rglow = Image.new("RGB", (w, h))
    draw_model(refl, rglow, cam, faces, mirror_y=floor, dim=0.8, slab_alpha=slab_alpha)
    refl_a = np.asarray(refl.filter(ImageFilter.GaussianBlur(3 * SS)), float)
    rows = np.arange(h)[:, None, None]
    fade = np.clip((rows - horizon) / (h * 0.5), 0, 1)
    b = np.asarray(base, float) + refl_a * 0.28 * (1 - fade)
    base = Image.fromarray(np.clip(b, 0, 255).astype(np.uint8))

    grid(ImageDraw.Draw(base, "RGBA"), cam, floor, horizon, w, h)

    glow = Image.new("RGB", (w, h))
    draw_model(base, glow, cam, faces, slab_alpha=slab_alpha)
    for pos in beams:
        beam_at(base, glow, cam, pos, beam_height)

    out = np.asarray(base, float) + bloom(glow) - np.asarray(glow, float) * 0.6
    # Scanlines, the same CRT the wordmark is on: every third row a touch
    # darker, faint enough to read as texture rather than stripes.
    out[::3 * SS] *= 0.88
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
    merged = {}
    beams = []
    for kind, u, depth, turns in LOBBY:
        _, _, crop, opts = VIEWS[kind]
        scene, floor, _ = build_scene(dump_dir, kind, crop, **opts)
        for _ in range(turns):
            scene = {(z, y, -x): v for (x, y, z), v in scene.items()}
        solid = [k for k, v in scene.items() if v[0] != "W"]
        cx = (min(k[0] for k in solid) + max(k[0] for k in solid)) / 2
        cz = (min(k[2] for k in solid) + max(k[2] for k in solid)) / 2
        tx, tz = u * depth, -depth
        dx, dy, dz = int(round(tx - cx)), -floor, int(round(tz - cz))
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
