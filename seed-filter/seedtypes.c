// Multi-type seed filter.
//
// A seed is not "good" in general - it is good for ONE opening route.
// Requiring a village AND a shipwreck AND a desert temple all near
// spawn drives the pass rate to essentially zero, which is why the
// established filters classify each seed as exactly one type and keep
// a separate pool per type. This does the same.
//
// Division of labour with the mod:
//
//   here (cubiomes)  - structures, distances, biomes, bastion variant.
//                      All pure math: no chunk generation, microseconds
//                      per seed.
//   mod at world gen - lava pools, loot minimums, portal completability.
//                      These are PLACED deterministically rather than
//                      searched for. Terrain features like lava pools
//                      are not predictable from cubiomes at all, and
//                      the conjunction of loot minimums with everything
//                      else is far too rare to find by scanning.
//
// That split is a deliberate design choice and has to be published:
// worlds are not pure vanilla for their seed. The alternative - filter
// only - cannot reach comparable guarantees at any sane cost, and a
// ladder meant to rank the best players needs runs that are comparable
// to each other.
//
// Distances are taken from the published MCSR Ranked criteria so our
// seeds are directly comparable to the standard runners already know:
//   village         <= 7 chunks    desert temple <= 5 chunks
//   ruined portal   <= 3 chunks    shipwreck     <= 4 chunks
//   buried treasure <= 5 chunks
//   bastion <= 14 chunks from nether spawn, at least 10 chunks closer
//   than any rival so it is never ambiguous which one to run to, and
//   the fortress <= 16 chunks FROM THAT BASTION.
//
// NOT implemented: the standard also checks that open terrain paths
// exist between spawn, bastion and fortress, so a runner is not walled
// off behind netherrack. That needs the world generated and is not
// done here.

#include "../tools/cubiomes/finders.h"
#include "../tools/cubiomes/generator.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>

#define CH(n) ((n) * 16.0)

#define MAX_VILLAGE_DIST   CH(7)
#define MAX_TEMPLE_DIST    CH(5)
#define MAX_PORTAL_DIST    CH(3)
#define MAX_SHIPWRECK_DIST CH(4)
#define MAX_TREASURE_DIST  CH(5)

#define MAX_RIVER_DIST     CH(6)   // village + desert temple

// How far a player may reasonably go for their first wood.
//
// Reported from play: a desert temple seed with no tree within four or
// five chunks of spawn. Nothing in the filter established that a player
// could make a crafting table at all - the pool checked that the
// STRUCTURE existed and said nothing about whether the ROUTE worked,
// which is the same mistake as iron in a dungeon chest and a ravine
// with no bubble columns.
//
// Five chunks, matching what the report described as already too far.
#define MAX_WOOD_DIST      CH(5)
// The standard's nether distances, restored.
//
// The intended bastion is within 14 chunks of nether spawn, and the
// fortress within 16 chunks OF THAT BASTION - not of spawn.
//
// This file previously measured both from spawn, on the reasoning that
// a bastion-relative fortress could sit 30 chunks from the origin and
// produce a detour "the standard would never produce". That reasoning
// was wrong about the standard: a bastion-relative fortress IS the
// rule, because a runner goes spawn -> bastion -> fortress and the leg
// that matters is the second one, not the distance back to a point
// nobody returns to.
//
// Measuring from spawn was also strictly stricter, so it discarded
// perfectly good seeds - the safe direction to be wrong in, but wrong.
#define MAX_BASTION_DIST   CH(14)
#define MAX_FORTRESS_DIST  CH(16)   // from the BASTION
#define BASTION_ISOLATION  CH(10)  // margin over the next nearest bastion

// How far around the nether origin to check the biome.
//
// A runner arrives at overworldX/8, overworldZ/8 - not at the nether
// origin - and the overworld portal sits near the qualifying
// structure. Working the distance backwards: world spawn is usually
// within ~350 blocks of the origin, the structure within 112 of spawn,
// and the lava pool another 40 out, so a portal can be ~500 blocks from
// the overworld origin and the arrival ~62 blocks from the nether one.
//
// The first version used 64 blocks, which covered that by a margin too
// thin to rely on - a seed tested this session arrived at 20,-30 and
// only just fell inside it. Six chunks covers the same reasoning with
// room to spare, and costs nothing: this is pure biome lookup.
//
// The exact answer would check the real arrival point, but overworld
// and nether seeds are searched independently and only paired later,
// so at this stage there is no structure position to work from. That
// independence is deliberate - it is what breaks Divine Travel - so
// the approximation stays.
//
// Only Basalt Deltas is excluded. Soul Sand Valley looks hostile and
// runs fine.
#define NETHER_SPAWN_RADIUS  CH(6)

typedef enum {
    TYPE_VILLAGE = 0,
    TYPE_DESERT_TEMPLE,
    TYPE_RUINED_PORTAL,
    TYPE_SHIPWRECK,
    TYPE_BURIED_TREASURE,
    TYPE_COUNT
} SeedType;

static const char *TYPE_NAMES[TYPE_COUNT] = {
    "village", "desert_temple", "ruined_portal", "shipwreck", "buried_treasure"
};

static const int TYPE_STRUCT[TYPE_COUNT] = {
    Village, Desert_Pyramid, Ruined_Portal, Shipwreck, Treasure
};

static const double TYPE_MAX_DIST[TYPE_COUNT] = {
    MAX_VILLAGE_DIST, MAX_TEMPLE_DIST, MAX_PORTAL_DIST,
    MAX_SHIPWRECK_DIST, MAX_TREASURE_DIST
};

// Only these two need a river nearby (water source for the lava-pool
// portal). The ocean types have water by definition; the ruined portal
// route does not need one.
static const int TYPE_NEEDS_RIVER[TYPE_COUNT] = { 1, 1, 0, 0, 0 };

// A ruined portal has to be usable where it stands: at least partly
// above ground, and not in the sea. An ocean portal is unreachable
// early and an underground one is not a route at all.
//
// Only the ocean half can be decided here. cubiomes has no surface
// height, so "above ground" needs the world generated and is checked by
// the verification pass instead - which that type needs anyway, since
// cubiomes documents that ruined portal presence itself cannot be
// predicted (the biome check happens after the height is chosen, so a
// portal can silently fail to generate).
static const int TYPE_EXCLUDES_OCEAN[TYPE_COUNT] = { 0, 0, 1, 0, 0 };

// Shipwreck and buried treasure both need a magma ravine to reach the
// nether, and magma ravines do not occur in frozen or cold oceans -
// the same reason those waters have no kelp. Excluding them here is
// free; discovering it after generating a world is not.
//
// This only rules out the biomes that CANNOT have one. Whether a given
// warm-ocean seed actually has a ravine within range still needs the
// world generated, because ravines are carvers and cubiomes cannot see
// them any more than it can see a lava lake.
static const int TYPE_NEEDS_WARM_OCEAN[TYPE_COUNT] = { 0, 0, 0, 1, 1 };

// Village biome is NOT a filter criterion. All five vanilla types -
// plains, desert, savanna, taiga and snowy - are eligible.
//
// This used to exclude taiga and snowy on the reasoning that they are
// the low-resource variants. That was the wrong mechanism. The standard
// filters villages on what they actually CONTAIN - a blacksmith with
// enough to progress, an iron threshold, a river and lava nearby - not
// on which biome they sit in. A cold village that meets those is a
// perfectly good seed, and excluding it by type both shrinks the pool
// and deviates from the standard for no gain.
//
// It was also quietly unreliable. The exclusion sampled the biome at
// block resolution at the village position, while the game picks the
// village VARIANT from the quarter-scale noise grid at the chunk
// centre. Near a border the two disagree, and a taiga village reached
// a live match anyway. Keeping a rule that neither belongs here nor
// works is the worst of both.
//
// Left at zero rather than deleted so the intent is on the record.
static const int TYPE_EXCLUDES_COLD_VILLAGE[TYPE_COUNT] = { 0, 0, 0, 0, 0 };

static int slowVillageBiome(int id)
{
    return id == taiga || id == taiga_hills || id == taiga_mountains
        || id == snowy_taiga || id == snowy_taiga_hills || id == snowy_taiga_mountains
        || id == giant_tree_taiga || id == giant_tree_taiga_hills
        || id == giant_spruce_taiga || id == giant_spruce_taiga_hills
        || id == snowy_tundra || id == ice_spikes;
}

static int coldWater(int id)
{
    return id == frozen_ocean || id == deep_frozen_ocean
        || id == cold_ocean || id == deep_cold_ocean
        || id == frozen_river;
}

typedef struct {
    uint64_t seed;
    int type;
    int sx, sz;          // world spawn
    int x, z;            // the qualifying structure
    double dist;         // from spawn
} OverworldHit;

typedef struct {
    uint64_t seed;
    int bx, bz;
    int bastionType;     // 0 units, 1 hoglin_stable, 2 treasure, 3 bridge
    double bdist;
    int fx, fz;
    double fdist;
} NetherHit;

static double dist2d(int dx, int dz)
{
    return sqrt((double)dx * dx + (double)dz * dz);
}

// Is there a river within MAX_RIVER_DIST of the structure? Sampled on a
// grid rather than exhaustively - a river reaching within 6 chunks is
// wide enough that a 16-block step cannot step over it.
static int riverNearby(Generator *g, int cx, int cz)
{
    for (int dx = -(int)MAX_RIVER_DIST; dx <= (int)MAX_RIVER_DIST; dx += 16)
    {
        for (int dz = -(int)MAX_RIVER_DIST; dz <= (int)MAX_RIVER_DIST; dz += 16)
        {
            if (dist2d(dx, dz) > MAX_RIVER_DIST)
                continue;
            int id = getBiomeAt(g, 1, cx + dx, 63, cz + dz);
            if (id == river || id == frozen_river)
                return 1;
        }
    }
    return 0;
}

/**
 * Does any biome within reach of spawn grow trees?
 *
 * This is the CHEAP check: it asks what biome a column sits in, not
 * whether a tree is actually standing there. A forest with a bare patch
 * at the sampled point still passes, and a lone oak in a savanna is
 * still missed. What it reliably catches is the case that was
 * reported - spawning in desert or badlands with nothing wooded in
 * range at all - which is the difference between an awkward start and
 * an impossible one.
 *
 * The honest version counts real logs in the generated world. That
 * costs a world generation per candidate and needs the chunk-local
 * treatment ContainerScan got, because the naive block scan is what
 * killed the launcher's x86 JVM under Rosetta. Worth doing; not worth
 * blocking this on.
 */
static int woodBiome(int id)
{
    return id == forest || id == wooded_hills || id == flower_forest
        || id == birch_forest || id == birch_forest_hills
        || id == tall_birch_forest || id == tall_birch_hills
        || id == dark_forest || id == dark_forest_hills
        || id == jungle || id == jungle_hills || id == jungle_edge
        || id == modified_jungle || id == modified_jungle_edge
        || id == bamboo_jungle || id == bamboo_jungle_hills
        || id == taiga || id == taiga_hills || id == taiga_mountains
        || id == snowy_taiga || id == snowy_taiga_hills
        || id == giant_tree_taiga || id == giant_tree_taiga_hills
        || id == giant_spruce_taiga || id == giant_spruce_taiga_hills
        || id == swamp || id == swamp_hills
        || id == plains || id == sunflower_plains
        || id == wooded_mountains || id == mountain_edge
        || id == savanna || id == savanna_plateau
        || id == shattered_savanna || id == shattered_savanna_plateau
        || id == wooded_badlands_plateau || id == modified_wooded_badlands_plateau;
}

static int woodNearby(Generator *g, int cx, int cz)
{
    for (int dx = -(int)MAX_WOOD_DIST; dx <= (int)MAX_WOOD_DIST; dx += 16)
    {
        for (int dz = -(int)MAX_WOOD_DIST; dz <= (int)MAX_WOOD_DIST; dz += 16)
        {
            if (dist2d(dx, dz) > MAX_WOOD_DIST)
                continue;
            int id = getBiomeAt(g, 1, cx + dx, 63, cz + dz);
            if (id >= 0 && woodBiome(id))
                return 1;
        }
    }
    return 0;
}

// Structure matches discarded for having no wood within reach of
// spawn. Counted rather than silently dropped: if this is a large
// fraction, the radius or the biome list is wrong, and a filter that
// quietly discards most of its candidates should say so.
//
// This counts (seed, type) rejections, not distinct seeds - a seed is
// tested against each type in turn, so one woodless spawn near several
// structures increments it more than once. Fine for spotting a filter
// that has gone wrong; not a seed count.
static uint64_t rejectedNoWood = 0;

// Classify one seed. Returns the type, or -1 if it qualifies as none.
// Types are tested in a fixed order and the first match wins, so a seed
// is only ever in one pool.
static int classifyOverworld(Generator *g, uint64_t seed, OverworldHit *out)
{
    applySeed(g, DIM_OVERWORLD, seed);
    Pos spawn = getSpawn(g);

    for (int type = 0; type < TYPE_COUNT; type++)
    {
        double maxDist = TYPE_MAX_DIST[type];
        int structType = TYPE_STRUCT[type];

        // Structure regions differ per type, so sweep every region that
        // could overlap the search circle around spawn.
        int regionBlocks = (structType == Treasure) ? 16 : 512;
        int rxLo = (int)floor((spawn.x - maxDist) / regionBlocks);
        int rxHi = (int)floor((spawn.x + maxDist) / regionBlocks);
        int rzLo = (int)floor((spawn.z - maxDist) / regionBlocks);
        int rzHi = (int)floor((spawn.z + maxDist) / regionBlocks);

        for (int rx = rxLo; rx <= rxHi; rx++)
        {
            for (int rz = rzLo; rz <= rzHi; rz++)
            {
                Pos pos;
                if (!getStructurePos(structType, MC_1_16_1, seed, rx, rz, &pos))
                    continue;

                double d = dist2d(pos.x - spawn.x, pos.z - spawn.z);
                if (d >= maxDist)
                    continue;

                if (!isViableStructurePos(structType, g, pos.x, pos.z, 0))
                    continue;

                if (TYPE_NEEDS_RIVER[type] && !riverNearby(g, pos.x, pos.z))
                    continue;

                if (TYPE_EXCLUDES_OCEAN[type])
                {
                    int id = getBiomeAt(g, 1, pos.x, 63, pos.z);
                    if (id < 0 || getCategory(MC_1_16_1, id) == ocean)
                        continue;
                }

                if (TYPE_NEEDS_WARM_OCEAN[type])
                {
                    int id = getBiomeAt(g, 1, pos.x, 63, pos.z);
                    if (id < 0 || coldWater(id))
                        continue;
                }

                if (TYPE_EXCLUDES_COLD_VILLAGE[type])
                {
                    int id = getBiomeAt(g, 1, pos.x, 63, pos.z);
                    if (id < 0 || slowVillageBiome(id))
                        continue;
                }

                // Every type needs a first crafting table. Measured
                // from SPAWN, not from the structure: the player starts
                // at spawn and that is where the walk for wood begins.
                if (!woodNearby(g, spawn.x, spawn.z))
                {
                    rejectedNoWood++;
                    continue;
                }

                out->seed = seed;
                out->type = type;
                out->sx = spawn.x;
                out->sz = spawn.z;
                out->x = pos.x;
                out->z = pos.z;
                out->dist = d;
                return type;
            }
        }
    }
    return -1;
}

// The intended bastion must be unambiguous: close to the origin AND
// clearly closer than any rival, so a runner is never guessing which
// way to go. A fortress must then be reachable from it.
// Is the nether arrival area somewhere a run can start?
//
// Only checks biome, which is all cubiomes can see. Terrain - whether
// there is ground under the portal rather than an open drop to the
// lava sea - needs the world generated and is not covered here.
// Counts how often the spawn check actually rejects, so a filter that
// silently does nothing cannot be mistaken for one that passes
// everything.
static uint64_t netherRejectedBasalt = 0;

static int netherSpawnViable(Generator *g)
{
    for (int dx = -(int)NETHER_SPAWN_RADIUS; dx <= (int)NETHER_SPAWN_RADIUS; dx += 16)
    {
        for (int dz = -(int)NETHER_SPAWN_RADIUS; dz <= (int)NETHER_SPAWN_RADIUS; dz += 16)
        {
            int id = getBiomeAt(g, 1, dx, 64, dz);
            if (id < 0)
                return 0;
            if (id == basalt_deltas)
                return 0;
        }
    }
    return 1;
}

static int classifyNether(Generator *g, uint64_t seed, NetherHit *out)
{
    applySeed(g, DIM_NETHER, seed);

    if (!netherSpawnViable(g))
    {
        netherRejectedBasalt++;
        return 0;
    }

    double bestDist = 1e18, secondDist = 1e18;
    Pos best = {0, 0};
    int bestFound = 0;

    for (int rx = -2; rx <= 2; rx++)
    {
        for (int rz = -2; rz <= 2; rz++)
        {
            Pos bpos;
            if (!getStructurePos(Bastion, MC_1_16_1, seed, rx, rz, &bpos))
                continue;
            if (!isViableStructurePos(Bastion, g, bpos.x, bpos.z, 0))
                continue;

            double d = dist2d(bpos.x, bpos.z);
            if (d < bestDist)
            {
                secondDist = bestDist;
                bestDist = d;
                best = bpos;
                bestFound = 1;
            }
            else if (d < secondDist)
            {
                secondDist = d;
            }
        }
    }

    if (!bestFound || bestDist >= MAX_BASTION_DIST)
        return 0;
    if (secondDist - bestDist < BASTION_ISOLATION)
        return 0;   // two plausible bastions: ambiguous, reject

    // All four bastion types are kept. Which one a seed gives is part
    // of what the run has to handle, so it is recorded rather than
    // filtered on.
    StructureVariant sv;
    memset(&sv, 0, sizeof(sv));
    int haveVariant = getVariant(&sv, Bastion, MC_1_16_1, seed, best.x, best.z, -1);

    for (int frx = -2; frx <= 2; frx++)
    {
        for (int frz = -2; frz <= 2; frz++)
        {
            Pos fpos;
            if (!getStructurePos(Fortress, MC_1_16_1, seed, frx, frz, &fpos))
                continue;

            // From the intended BASTION, which is the leg a runner
            // actually walks. Measuring from spawn instead rejected
            // seeds the standard accepts.
            double fd = dist2d(fpos.x - best.x, fpos.z - best.z);
            if (fd >= MAX_FORTRESS_DIST)
                continue;
            if (!isViableStructurePos(Fortress, g, fpos.x, fpos.z, 0))
                continue;

            out->seed = seed;
            out->bx = best.x;
            out->bz = best.z;
            out->bastionType = haveVariant ? sv.start : -1;
            out->bdist = bestDist;
            out->fx = fpos.x;
            out->fz = fpos.z;
            out->fdist = fd;
            return 1;
        }
    }
    return 0;
}

int main(int argc, char **argv)
{
    int wantedPerType = (argc > 1) ? atoi(argv[1]) : 20;

    uint64_t startSeed;
    if (argc > 2)
    {
        startSeed = strtoull(argv[2], NULL, 10);
    }
    else
    {
        uint64_t entropy = (uint64_t)time(NULL);
        entropy ^= (uint64_t)getpid() << 32;
        entropy ^= (uint64_t)clock() << 16;
        srand((unsigned)entropy);
        startSeed = ((uint64_t)rand() << 32) ^ ((uint64_t)rand() << 16) ^ (uint64_t)rand();
        // Seeds reach the client as JSON numbers through a Node Lambda,
        // which loses precision above 2^53.
        startSeed &= (1ULL << 48) - 1;
    }
    fprintf(stderr, "Start seed: %llu\n", (unsigned long long)startSeed);

    mkdir("output", 0755);

    OverworldHit *hits[TYPE_COUNT];
    int found[TYPE_COUNT];
    for (int t = 0; t < TYPE_COUNT; t++)
    {
        hits[t] = malloc(sizeof(OverworldHit) * wantedPerType);
        found[t] = 0;
    }

    NetherHit *netherHits = malloc(sizeof(NetherHit) * wantedPerType * TYPE_COUNT);
    int netherFound = 0;
    int netherWanted = wantedPerType * TYPE_COUNT;

    Generator g;
    setupGenerator(&g, MC_1_16_1, 0);

    uint64_t scanned = 0;
    int complete = 0;
    while (!complete && scanned < 20000000ULL)
    {
        uint64_t seed = startSeed + scanned;
        scanned++;

        OverworldHit hit;
        int type = classifyOverworld(&g, seed, &hit);
        if (type >= 0 && found[type] < wantedPerType)
        {
            hits[type][found[type]++] = hit;
        }

        if (netherFound < netherWanted)
        {
            NetherHit nh;
            if (classifyNether(&g, seed, &nh))
                netherHits[netherFound++] = nh;
        }

        complete = (netherFound >= netherWanted);
        for (int t = 0; t < TYPE_COUNT && complete; t++)
            if (found[t] < wantedPerType)
                complete = 0;

        if (scanned % 200000 == 0)
        {
            fprintf(stderr, "  %llu scanned:", (unsigned long long)scanned);
            for (int t = 0; t < TYPE_COUNT; t++)
                fprintf(stderr, " %s=%d", TYPE_NAMES[t], found[t]);
            fprintf(stderr, " nether=%d\n", netherFound);
        }
    }

    fprintf(stderr, "Scanned %llu seeds\n", (unsigned long long)scanned);
    for (int t = 0; t < TYPE_COUNT; t++)
        fprintf(stderr, "  %-16s %d (1 in %.0f)\n", TYPE_NAMES[t], found[t],
                found[t] ? (double)scanned / found[t] : 0.0);
    fprintf(stderr, "  %-16s %d (1 in %.0f)\n", "nether", netherFound,
            netherFound ? (double)scanned / netherFound : 0.0);
    fprintf(stderr, "  %-16s %llu seeds rejected for basalt deltas at nether spawn\n",
            "", (unsigned long long)netherRejectedBasalt);
    fprintf(stderr, "  %-16s %llu structure matches rejected for no wood within %.0f blocks of spawn\n",
            "", (unsigned long long)rejectedNoWood, MAX_WOOD_DIST);

    FILE *f = fopen("output/overworld_by_type.json", "w");
    fprintf(f, "{\n");
    for (int t = 0; t < TYPE_COUNT; t++)
    {
        fprintf(f, "  \"%s\": [\n", TYPE_NAMES[t]);
        for (int i = 0; i < found[t]; i++)
        {
            OverworldHit *h = &hits[t][i];
            fprintf(f, "    {\"seed\": %llu, \"spawn\": {\"x\": %d, \"z\": %d}, "
                       "\"structure\": {\"x\": %d, \"z\": %d, \"distFromSpawn\": %.0f}}%s\n",
                    (unsigned long long)h->seed, h->sx, h->sz, h->x, h->z, h->dist,
                    (i == found[t] - 1) ? "" : ",");
        }
        fprintf(f, "  ]%s\n", (t == TYPE_COUNT - 1) ? "" : ",");
    }
    fprintf(f, "}\n");
    fclose(f);

    static const char *BASTION_NAMES[4] = { "units", "hoglin_stable", "treasure", "bridge" };
    f = fopen("output/nether_seeds.json", "w");
    fprintf(f, "[\n");
    for (int i = 0; i < netherFound; i++)
    {
        NetherHit *n = &netherHits[i];
        fprintf(f, "  {\"seed\": %llu, \"bastion\": {\"x\": %d, \"z\": %d, \"dist\": %.0f, "
                   "\"type\": \"%s\"}, \"fortress\": {\"x\": %d, \"z\": %d, \"distFromBastion\": %.0f}}%s\n",
                (unsigned long long)n->seed, n->bx, n->bz, n->bdist,
                (n->bastionType >= 0 && n->bastionType < 4)
                        ? BASTION_NAMES[n->bastionType] : "unknown",
                n->fx, n->fz, n->fdist,
                (i == netherFound - 1) ? "" : ",");
    }
    fprintf(f, "]\n");
    fclose(f);

    fprintf(stderr, "Wrote output/overworld_by_type.json and output/nether_seeds.json\n");
    return 0;
}
