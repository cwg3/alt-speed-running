# A seed check with no Gradle, no Loom, and no copy of the mod project.
#
# The local harness runs checks through `gradlew runServer`, which forces
# two workarounds that this image removes outright:
#
#   - every worker gets an rsync'd COPY of mod/, because Loom takes a
#     per-project Gradle lock and parallel runServer calls queue behind
#     each other. Parallel width ends up bounded by disk, not cores.
#   - every worker needs a unique server-port, because they share a
#     network namespace. Getting that wrong cost a whole run: 388 of 539
#     ravine checks failed to bind 25565 and were recorded as seeds
#     without ravines.
#
# A container has its own network namespace, so port 25565 cannot
# collide however many run at once, and a standalone Fabric server is
# just `java -jar` over two mod jars.
ARG MC_VERSION=1.16.1
ARG LOADER_VERSION=0.19.5
ARG INSTALLER_VERSION=1.0.1

# The cubiomes stage. seedtypes is the candidate generator - pure C, no
# JVM and no Minecraft - and it was the last thing still running on the
# dev machine after the Java checks moved here. Ocean is always the
# limiting type, so a big shipwreck batch is over half an hour of local
# CPU, which is exactly what this image exists to stop.
FROM debian:bookworm-slim AS cfilter
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential \
 && rm -rf /var/lib/apt/lists/*
# The repo's own layout, mirrored. seedtypes.c includes
# "../tools/cubiomes/finders.h" - a path relative to where it lives in
# the tree - so the build directory is shaped the same way instead of
# patching the include and having the two copies drift.
WORKDIR /build
COPY cubiomes/ tools/cubiomes/
COPY seedtypes.c seed-filter/
WORKDIR /build/seed-filter
RUN make -C ../tools/cubiomes release \
 && cc -O3 -o seedtypes seedtypes.c ../tools/cubiomes/libcubiomes.a -lm -lpthread \
 && test -x seedtypes

FROM eclipse-temurin:17-jdk-jammy AS build
ARG MC_VERSION
ARG LOADER_VERSION
ARG INSTALLER_VERSION

# Pinned to the exact build the mod compiles against. gradle.properties
# explains why it is not something newer: 0.42.0+1.16 needs a biome
# registry change that does not exist until MC 1.16.2.
#
# This URL is Modrinth's and not Maven's ON PURPOSE. The maven.fabricmc.net
# coordinate for fabric-api serves a 2101-byte BOM, not a runnable
# bundle - Loom never notices because it resolves each module
# separately in dev. An image built from the Maven coordinate boots
# fine and then dies at mod init with ClassNotFoundException on
# ServerLifecycleEvents, which reads like a mod bug rather than a
# packaging one. The size assertion below is what makes that failure
# loud and immediate instead.
ARG FABRIC_API_URL=https://cdn.modrinth.com/data/P7dR8mSH/versions/0.18.0%2Bbuild.387-1.16.1/fabric-api-0.18.0%2Bbuild.387-1.16.1.jar
ARG FABRIC_API_MIN_BYTES=600000

WORKDIR /srv
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN curl -sSL -o installer.jar \
      "https://maven.fabricmc.net/net/fabricmc/fabric-installer/${INSTALLER_VERSION}/fabric-installer-${INSTALLER_VERSION}.jar" \
 && java -jar installer.jar server \
      -mcversion "${MC_VERSION}" -loader "${LOADER_VERSION}" -downloadMinecraft \
 && rm installer.jar

RUN mkdir -p mods \
 && curl -sSL -o mods/fabric-api.jar "${FABRIC_API_URL}" \
 && actual=$(stat -c%s mods/fabric-api.jar) \
 && if [ "$actual" -lt "${FABRIC_API_MIN_BYTES}" ]; then \
        echo "fabric-api.jar is ${actual} bytes - that is the Maven BOM stub, not the bundle" >&2; \
        exit 1; \
    fi \
 && echo "fabric-api.jar ok: ${actual} bytes, $(unzip -l mods/fabric-api.jar | grep -c 'META-INF/jars') nested modules" 2>/dev/null || true

# Built by build.sh from mod/build/libs - the REMAPPED production jar,
# which is what players run. Dev runs yarn-mapped classes under Loom.
COPY speedrunmcalt.jar mods/

RUN printf 'eula=true\n' > eula.txt

FROM eclipse-temurin:17-jre-jammy
WORKDIR /srv
COPY --from=build /srv /srv
COPY --from=cfilter /build/seed-filter/seedtypes /usr/local/bin/seedtypes
COPY run-checks.sh /usr/local/bin/run-checks
RUN chmod +x /usr/local/bin/run-checks
ENTRYPOINT ["/usr/local/bin/run-checks"]
