// Production seed filter (MVP scope).
//
// Overworld and nether seeds are searched INDEPENDENTLY and paired
// together afterward, rather than using one seed for both dimensions.
// This deliberately breaks the correlation vanilla Minecraft has
// between a seed's overworld and nether generation, which is what
// blocks "Divine Travel"-style strategies where a skilled player infers
// nether structure locations from overworld terrain. This is the same
// tradeoff the incumbent documents using independent seeds for.
//
// Criteria:
//   overworld: a Village within MAX_VILLAGE_DIST of the actual world
//              spawn point - see the note on getSpawn below
//   nether:    a Bastion within MAX_BASTION_DIST of nether origin,
//              AND a Fortress within MAX_FORTRESS_DIST of that Bastion
//
// The distances are deliberately tight. An earlier pass allowed a
// village up to 2000 blocks from spawn, which is far enough away to be
// useless and produced starts a runner would just reset. 112 blocks is
// 7 chunks, comparable to what the incumbent filters for.
//
// Village distance is measured from getSpawn(), NOT from the origin.
// Minecraft does not spawn the player at (0,0) - it searches outward
// for a valid spawn biome, which routinely lands 200-350 blocks away.
// Measuring from the origin produced seeds that passed the filter with
// a village "16 blocks from spawn" that was actually 328 blocks from
// where the player stood, in an unknown direction. Every seed in the
// first pool had this; players saw no structures at all. The check is
// therefore spawn-relative, and the search window is derived from the
// spawn point rather than from region (-1,-1)..(1,1) around origin.
//
// NOT filtered: whether the village contains a blacksmith. Since 1.14
// villages are generated with jigsaw assembly, which cubiomes does not
// model - its getHouseList is documented as mc < MC_1_14 only. So a
// village is guaranteed to be close, but not to be a good one. Fixing
// that needs village piece generation cubiomes doesn't have.
//
// The scan starts from a random offset rather than from seed 0. An
// earlier version always started at 0, so every run produced the same
// twenty seeds - a pool a competitor could simply memorise, which on a
// ladder is an advantage that has nothing to do with skill.
// Pass a start seed as the second argument to reproduce a pool.
//
// Output: JSON files under output/ listing accepted seeds, plus a
// combined match_seeds.json pairing them 1:1 for actual match use.

#include "../tools/cubiomes/finders.h"
#include "../tools/cubiomes/generator.h"
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define MAX_VILLAGE_DIST   112.0
#define MAX_BASTION_DIST   300.0
#define MAX_FORTRESS_DIST  300.0

typedef struct {
    uint64_t seed;
    int x, z;       // village
    int sx, sz;     // world spawn the distance is measured from
    double dist;
} Hit;

static double dist2d(int x, int z)
{
    return sqrt((double)x * x + (double)z * z);
}

// Village structure regions are 32 chunks square in 1.16.
#define VILLAGE_REGION_BLOCKS 512

// Floor division, so negative coordinates map to the correct region
// rather than truncating toward zero and skipping a region band.
static int regionOf(int coord)
{
    return (int)floor((double)coord / VILLAGE_REGION_BLOCKS);
}

// Scan for overworld seeds with a Village near the actual spawn point.
static int scanOverworld(uint64_t startSeed, uint64_t seedCount, int wanted, Hit *out)
{
    Generator g;
    setupGenerator(&g, MC_1_16_1, 0);

    int found = 0;
    for (uint64_t i = 0; i < seedCount && found < wanted; i++)
    {
        uint64_t seed = startSeed + i;

        applySeed(&g, DIM_OVERWORLD, seed);
        Pos spawn = getSpawn(&g);

        int rxLo = regionOf(spawn.x - (int)MAX_VILLAGE_DIST);
        int rxHi = regionOf(spawn.x + (int)MAX_VILLAGE_DIST);
        int rzLo = regionOf(spawn.z - (int)MAX_VILLAGE_DIST);
        int rzHi = regionOf(spawn.z + (int)MAX_VILLAGE_DIST);

        for (int rx = rxLo; rx <= rxHi && found < wanted; rx++)
        {
            for (int rz = rzLo; rz <= rzHi && found < wanted; rz++)
            {
                Pos pos;
                if (!getStructurePos(Village, MC_1_16_1, seed, rx, rz, &pos))
                    continue;

                double d = dist2d(pos.x - spawn.x, pos.z - spawn.z);
                if (d >= MAX_VILLAGE_DIST)
                    continue;

                if (!isViableStructurePos(Village, &g, pos.x, pos.z, 0))
                    continue;

                out[found].seed = seed;
                out[found].x = pos.x;
                out[found].z = pos.z;
                out[found].sx = spawn.x;
                out[found].sz = spawn.z;
                out[found].dist = d;
                found++;
                goto nextSeed;
            }
        }
        nextSeed:;
    }
    return found;
}

typedef struct {
    uint64_t seed;
    int bx, bz;
    double bdist;
    int fx, fz;
    double fdist;
} NetherHit;

// Scan for nether seeds with a Bastion near origin AND a Fortress near that Bastion.
static int scanNether(uint64_t startSeed, uint64_t seedCount, int wanted, NetherHit *out)
{
    Generator g;
    setupGenerator(&g, MC_1_16_1, 0);

    int found = 0;
    for (uint64_t i = 0; i < seedCount && found < wanted; i++)
    {
        uint64_t seed = startSeed + i;

        for (int rx = -1; rx <= 1 && found < wanted; rx++)
        {
            for (int rz = -1; rz <= 1 && found < wanted; rz++)
            {
                Pos bpos;
                if (!getStructurePos(Bastion, MC_1_16_1, seed, rx, rz, &bpos))
                    continue;

                double bd = dist2d(bpos.x, bpos.z);
                if (bd >= MAX_BASTION_DIST)
                    continue;

                applySeed(&g, DIM_NETHER, seed);
                if (!isViableStructurePos(Bastion, &g, bpos.x, bpos.z, 0))
                    continue;

                // Look for a nearby fortress in the surrounding regions.
                for (int frx = rx - 1; frx <= rx + 1; frx++)
                {
                    for (int frz = rz - 1; frz <= rz + 1; frz++)
                    {
                        Pos fpos;
                        if (!getStructurePos(Fortress, MC_1_16_1, seed, frx, frz, &fpos))
                            continue;

                        double fd = dist2d(fpos.x - bpos.x, fpos.z - bpos.z);
                        if (fd >= MAX_FORTRESS_DIST)
                            continue;

                        if (!isViableStructurePos(Fortress, &g, fpos.x, fpos.z, 0))
                            continue;

                        out[found].seed = seed;
                        out[found].bx = bpos.x;
                        out[found].bz = bpos.z;
                        out[found].bdist = bd;
                        out[found].fx = fpos.x;
                        out[found].fz = fpos.z;
                        out[found].fdist = fd;
                        found++;
                        goto nextSeed;
                    }
                }
            }
        }
        nextSeed:;
    }
    return found;
}

int main(int argc, char **argv)
{
    int wanted = 20;
    if (argc > 1)
        wanted = atoi(argv[1]);

    // Explicit start seed reproduces a pool; otherwise pick one.
    uint64_t startSeed;
    if (argc > 2)
    {
        startSeed = strtoull(argv[2], NULL, 10);
    }
    else
    {
        // time(NULL) alone is one-second resolution, so two runs in the
        // same second produced an identical pool - exactly the problem
        // the random start is meant to avoid. Mix in the pid and a
        // higher-resolution clock.
        uint64_t entropy = (uint64_t)time(NULL);
        entropy ^= (uint64_t)getpid() << 32;
        entropy ^= (uint64_t)clock() << 16;
        srand((unsigned)entropy);
        startSeed = ((uint64_t)rand() << 32) ^ ((uint64_t)rand() << 16) ^ (uint64_t)rand();
        // Seeds travel to the client as JSON numbers through a Node
        // Lambda, and JSON numbers lose precision above 2^53. Bounding
        // the start to 2^48 keeps every emitted seed exactly
        // representable end to end; it still leaves 2.8e14 possible
        // starting points, so the pool stays unpredictable.
        startSeed &= (1ULL << 48) - 1;
    }
    fprintf(stderr, "Start seed: %llu\n", (unsigned long long)startSeed);

    mkdir("output", 0755);

    fprintf(stderr, "Scanning for %d overworld seeds (village within %.0f blocks of spawn)...\n",
            wanted, MAX_VILLAGE_DIST);
    Hit *owHits = malloc(sizeof(Hit) * wanted);
    int owFound = scanOverworld(startSeed, 2000000, wanted, owHits);
    fprintf(stderr, "  found %d\n", owFound);

    fprintf(stderr, "Scanning for %d nether seeds (bastion within %.0f, fortress within %.0f of it)...\n",
            wanted, MAX_BASTION_DIST, MAX_FORTRESS_DIST);
    NetherHit *netherHits = malloc(sizeof(NetherHit) * wanted);
    int netherFound = scanNether(startSeed, 2000000, wanted, netherHits);
    fprintf(stderr, "  found %d\n", netherFound);

    FILE *fow = fopen("output/overworld_seeds.json", "w");
    fprintf(fow, "[\n");
    for (int i = 0; i < owFound; i++)
    {
        fprintf(fow, "  {\"seed\": %llu, \"spawn\": {\"x\": %d, \"z\": %d}, "
                     "\"village\": {\"x\": %d, \"z\": %d, \"distFromSpawn\": %.0f}}%s\n",
                (unsigned long long)owHits[i].seed,
                owHits[i].sx, owHits[i].sz,
                owHits[i].x, owHits[i].z, owHits[i].dist,
                (i == owFound - 1) ? "" : ",");
    }
    fprintf(fow, "]\n");
    fclose(fow);

    FILE *fn = fopen("output/nether_seeds.json", "w");
    fprintf(fn, "[\n");
    for (int i = 0; i < netherFound; i++)
    {
        fprintf(fn, "  {\"seed\": %llu, \"bastion\": {\"x\": %d, \"z\": %d, \"dist\": %.0f}, "
                     "\"fortress\": {\"x\": %d, \"z\": %d, \"distFromBastion\": %.0f}}%s\n",
                (unsigned long long)netherHits[i].seed,
                netherHits[i].bx, netherHits[i].bz, netherHits[i].bdist,
                netherHits[i].fx, netherHits[i].fz, netherHits[i].fdist,
                (i == netherFound - 1) ? "" : ",");
    }
    fprintf(fn, "]\n");
    fclose(fn);

    int pairs = owFound < netherFound ? owFound : netherFound;
    FILE *fm = fopen("output/match_seeds.json", "w");
    fprintf(fm, "[\n");
    for (int i = 0; i < pairs; i++)
    {
        fprintf(fm, "  {\"overworldSeed\": %llu, \"netherSeed\": %llu}%s\n",
                (unsigned long long)owHits[i].seed,
                (unsigned long long)netherHits[i].seed,
                (i == pairs - 1) ? "" : ",");
    }
    fprintf(fm, "]\n");
    fclose(fm);

    fprintf(stderr, "Wrote %d overworld, %d nether, %d paired match seeds to output/\n",
            owFound, netherFound, pairs);

    free(owHits);
    free(netherHits);
    return 0;
}
