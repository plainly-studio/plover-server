# Plover sync server. Build from the repository root:
#   docker build -t plover-server .
#
# The build stage runs on the build machine's own architecture (JVM bytecode is portable), so
# multi-arch images don't compile under emulation.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
ENV PLOVER_SERVER_ONLY=1
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY sync sync
COPY server server
RUN ./gradlew --no-daemon --quiet :server:installDist -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /data plover && mkdir -p /data && chown plover /data
COPY --from=build /src/server/build/install/plover-server /opt/plover
COPY server/docker-entrypoint.sh /usr/local/bin/plover-entrypoint
# A small, fixed heap: the server holds almost nothing in memory. If it ever runs out anyway, exit so
# Docker's restart policy brings it back instead of leaving a hung process. (The data folder, /data, and
# the port, 8443, are the server's defaults; PLOVER_PORT, or SUBTRACK_PORT from before the rename, changes it.)
ENV JAVA_OPTS="-Xmx64m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
USER plover
VOLUME /data
EXPOSE 8443
HEALTHCHECK --interval=1m --timeout=5s --start-period=30s \
    CMD curl --silent --fail --insecure "https://localhost:${PLOVER_PORT:-${SUBTRACK_PORT:-8443}}/v1/info" > /dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/plover-entrypoint"]
