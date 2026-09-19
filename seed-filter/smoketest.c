// Phase 1 smoke test: confirm cubiomes can find a Village near spawn on
// Minecraft 1.16.1 for a range of seeds, and report how close it is.
// This is NOT the production seed filter - just proof the toolchain works.
#include "../tools/cubiomes/finders.h"
#include "../tools/cubiomes/generator.h"
#include <stdio.h>
#include <math.h>

int main(void)
{
    Generator g;
    setupGenerator(&g, MC_1_16_1, 0);

    int found = 0;
    uint64_t seed;
    for (seed = 0; seed < 200000 && found < 5; seed++)
    {
        // Villages are placed on a per-region grid; check the region
        // containing spawn (region 0,0) and its neighbors.
        for (int rx = -1; rx <= 1 && found < 5; rx++)
        {
            for (int rz = -1; rz <= 1 && found < 5; rz++)
            {
                Pos pos;
                if (!getStructurePos(Village, MC_1_16_1, seed, rx, rz, &pos))
                    continue;

                applySeed(&g, DIM_OVERWORLD, seed);
                if (!isViableStructurePos(Village, &g, pos.x, pos.z, 0))
                    continue;

                double dist = sqrt((double)pos.x * pos.x + (double)pos.z * pos.z);
                if (dist < 1500)
                {
                    printf("seed=%llu village at (%d, %d), dist=%.0f blocks\n",
                           (unsigned long long)seed, pos.x, pos.z, dist);
                    found++;
                    break;
                }
            }
        }
    }

    if (found == 0)
        printf("no matches found in range - widen the search\n");

    return 0;
}
