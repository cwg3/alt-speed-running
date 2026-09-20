// Prints structure positions for a given seed, so a generated world can
// be checked against what the seed is supposed to produce.
//
// Used to verify the independent overworld/nether seeding actually
// works: generate a world with overworld=A and nether=B, then confirm
// the fortress in the real nether sits where this reports for B and not
// for A.
//
// KNOWN ISSUE: the overworld query is exact - it predicted seed 60's
// village at 624,-464 and the generated world put it precisely there -
// but the NETHER predictions do not match the generated world. The
// seeding itself was confirmed correct by reading the seed each
// dimension's chunk generator actually holds, so this is a bug in the
// prediction here rather than in the game. Likely the nether biome
// source needs more setup than applySeed alone, or 1.16's shared
// fortress/bastion placement is modelled differently. Fix before
// relying on nether predictions - notably for cross-checking replay
// timelines against real structure locations.
#include "../tools/cubiomes/finders.h"
#include "../tools/cubiomes/generator.h"
#include <stdio.h>
#include <stdlib.h>

static void nearest(int type, const char *label, uint64_t seed, int dim)
{
    Generator g;
    setupGenerator(&g, MC_1_16_1, 0);
    applySeed(&g, dim, seed);

    int bestX = 0, bestZ = 0;
    long bestDistSq = -1;

    // Scan the regions around the origin; structures are placed on a
    // per-region grid.
    for (int rx = -4; rx <= 4; rx++)
    {
        for (int rz = -4; rz <= 4; rz++)
        {
            Pos pos;
            if (!getStructurePos(type, MC_1_16_1, seed, rx, rz, &pos))
                continue;
            if (!isViableStructurePos(type, &g, pos.x, pos.z, 0))
                continue;

            long d = (long)pos.x * pos.x + (long)pos.z * pos.z;
            if (bestDistSq < 0 || d < bestDistSq)
            {
                bestDistSq = d;
                bestX = pos.x;
                bestZ = pos.z;
            }
        }
    }

    if (bestDistSq < 0)
        printf("%s: none found\n", label);
    else
        printf("%s: x=%d z=%d\n", label, bestX, bestZ);
}

int main(int argc, char **argv)
{
    if (argc < 2)
    {
        fprintf(stderr, "usage: query <seed>\n");
        return 1;
    }
    uint64_t seed = strtoull(argv[1], NULL, 10);
    printf("seed %llu\n", (unsigned long long)seed);
    nearest(Fortress, "  nether fortress", seed, DIM_NETHER);
    nearest(Bastion, "  bastion", seed, DIM_NETHER);
    nearest(Village, "  overworld village", seed, DIM_OVERWORLD);
    return 0;
}
