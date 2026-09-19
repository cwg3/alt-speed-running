// Production seed filter (MVP scope).
//
// Overworld and nether seeds are searched INDEPENDENTLY and paired
// together afterward, rather than using one seed for both dimensions.
// This deliberately breaks the correlation vanilla Minecraft has
// between a seed's overworld and nether generation, which is what
// blocks "Divine Travel"-style strategies where a skilled player infers
// nether structure locations from overworld terrain. This is the same
// tradeoff MCSR Ranked documents using independent seeds for.
//
// Criteria (intentionally simpler than the incumbent's full per-tier
// system - this is the MVP pass, not the final tuned filter):
//   overworld: a Village within MAX_VILLAGE_DIST of spawn
//   nether:    a Bastion within MAX_BASTION_DIST of nether origin,
//              AND a Fortress within MAX_FORTRESS_DIST of that Bastion
//
// Output: JSON files under output/ listing accepted seeds, plus a
// combined match_seeds.json pairing them 1:1 for actual match use.

#include "../tools/cubiomes/finders.h"
#include "../tools/cubiomes/generator.h"
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <sys/stat.h>

#define MAX_VILLAGE_DIST   2000.0
#define MAX_BASTION_DIST   2000.0
#define MAX_FORTRESS_DIST  1200.0

typedef struct {
    uint64_t seed;
    int x, z;
    double dist;
} Hit;

static double dist2d(int x, int z)
{
    return sqrt((double)x * x + (double)z * z);
}

// Scan for overworld seeds with a Village near spawn.
static int scanOverworld(uint64_t startSeed, uint64_t seedCount, int wanted, Hit *out)
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
                Pos pos;
                if (!getStructurePos(Village, MC_1_16_1, seed, rx, rz, &pos))
                    continue;

                double d = dist2d(pos.x, pos.z);
                if (d >= MAX_VILLAGE_DIST)
                    continue;

                applySeed(&g, DIM_OVERWORLD, seed);
                if (!isViableStructurePos(Village, &g, pos.x, pos.z, 0))
                    continue;

                out[found].seed = seed;
                out[found].x = pos.x;
                out[found].z = pos.z;
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

    mkdir("output", 0755);

    fprintf(stderr, "Scanning for %d overworld seeds (village within %.0f blocks)...\n",
            wanted, MAX_VILLAGE_DIST);
    Hit *owHits = malloc(sizeof(Hit) * wanted);
    int owFound = scanOverworld(0, 2000000, wanted, owHits);
    fprintf(stderr, "  found %d\n", owFound);

    fprintf(stderr, "Scanning for %d nether seeds (bastion within %.0f, fortress within %.0f of it)...\n",
            wanted, MAX_BASTION_DIST, MAX_FORTRESS_DIST);
    NetherHit *netherHits = malloc(sizeof(NetherHit) * wanted);
    int netherFound = scanNether(0, 2000000, wanted, netherHits);
    fprintf(stderr, "  found %d\n", netherFound);

    FILE *fow = fopen("output/overworld_seeds.json", "w");
    fprintf(fow, "[\n");
    for (int i = 0; i < owFound; i++)
    {
        fprintf(fow, "  {\"seed\": %llu, \"village\": {\"x\": %d, \"z\": %d, \"dist\": %.0f}}%s\n",
                (unsigned long long)owHits[i].seed, owHits[i].x, owHits[i].z, owHits[i].dist,
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
