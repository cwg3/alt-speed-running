CUBIOMES := ../tools/cubiomes

.PHONY: all clean cubiomes

all: seedfilter

cubiomes:
	$(MAKE) -C $(CUBIOMES) release

smoketest: smoketest.c cubiomes
	$(CC) -O3 -o smoketest smoketest.c $(CUBIOMES)/libcubiomes.a -lm -lpthread

seedfilter: seedfilter.c cubiomes
	$(CC) -O3 -o seedfilter seedfilter.c $(CUBIOMES)/libcubiomes.a -lm -lpthread

clean:
	rm -f smoketest seedfilter query

query: query.c cubiomes
	$(CC) -O3 -o query query.c $(CUBIOMES)/libcubiomes.a -lm -lpthread

seedtypes: seedtypes.c cubiomes
	$(CC) -O3 -o seedtypes seedtypes.c $(CUBIOMES)/libcubiomes.a -lm -lpthread
