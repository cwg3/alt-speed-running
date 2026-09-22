#include "../tools/cubiomes/finders.h"
#include "../tools/cubiomes/generator.h"
#include <stdio.h>
#include <stdlib.h>
int main(int argc, char** argv) {
    uint64_t seed = strtoull(argv[1], NULL, 10);
    int px = argc > 2 ? atoi(argv[2]) : 0;
    int pz = argc > 3 ? atoi(argv[3]) : 0;
    Generator g; setupGenerator(&g, MC_1_16_1, 0);
    applySeed(&g, DIM_NETHER, seed);
    const char* names[] = {"nether_wastes","soul_sand_valley","crimson_forest","warped_forest","basalt_deltas"};
    int ids[] = {nether_wastes, soul_sand_valley, crimson_forest, warped_forest, basalt_deltas};
    printf("nether biomes around arrival (%d,%d):\n", px, pz);
    for (int dz = -48; dz <= 48; dz += 24) {
        for (int dx = -48; dx <= 48; dx += 24) {
            int id = getBiomeAt(&g, 1, px+dx, 64, pz+dz);
            const char* nm = "?";
            for (int i = 0; i < 5; i++) if (ids[i] == id) nm = names[i];
            printf("%-18s", nm);
        }
        printf("\n");
    }
    return 0;
}
