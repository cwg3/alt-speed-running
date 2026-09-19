CUBIOMES := ../tools/cubiomes

.PHONY: all clean cubiomes

all: smoketest

cubiomes:
	$(MAKE) -C $(CUBIOMES) release

smoketest: smoketest.c cubiomes
	$(CC) -O3 -o smoketest smoketest.c $(CUBIOMES)/libcubiomes.a -lm -lpthread

clean:
	rm -f smoketest
