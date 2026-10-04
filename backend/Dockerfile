# The image ECS runs (FZ-064).
#
# Multi-stage so the result carries a JRE and a jar, not a JDK, Maven and the whole
# dependency cache — and so `docker build backend/` works on a laptop exactly as it does
# in CI, with no "build the jar first" step to forget.
#
# **Both stages are pinned by digest** (`FZ-139`, `OI-30`). A tag moves: `21-jre` changed
# from Ubuntu 24.04 to 26.04 under this project with no commit and no review, and the way
# anyone found out was a local scan finding nothing while the same scan in CI found eight
# HIGH. A digest means the base is in the repository, an upgrade is a commit somebody
# approved, and a scan is a verdict on the artifact that actually ships.
#
# The cost is stated rather than hidden: a pinned digest does not pick up the base's own
# security patches. `verify.yml` scans exactly these digests on every run, so the build
# fails when one of them acquires a finding — which is what turns the pin from a freeze
# into a decision somebody has to make.
#
# To bump either, resolve the new digest and paste it:
#   docker buildx imagetools inspect eclipse-temurin:21-jre-noble --format '{{.Manifest.Digest}}'

# `-noble` (Ubuntu 24.04) and not the bare tag: 26.04 ships /usr/bin/pebble, a Go binary
# whose standard library carries the eight findings `FZ-127` had to baseline. Choosing the
# variant removes them instead of suppressing them. This stage never ships — only the jar
# is copied out — but it builds what does, so it is pinned too.
FROM eclipse-temurin:21-jdk-noble@sha256:70898f0f893a6b772a0f29834d8b022e3ac20b6a0c33a922973cf66342ef56be AS build

WORKDIR /build

# The wrapper and the POM first, on their own: dependencies change far less often than
# source, so this layer survives most builds and the download is paid once.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src/ src/

# Tests are skipped here on purpose: CI has already run the full suite against a real
# PostgreSQL (verify.yml), and Testcontainers cannot start a database inside this build.
# Running them here would either fail or quietly skip, and both are worse than not trying.
RUN ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests


# The base that actually ships. Same distribution as the build stage on purpose: one glibc,
# one set of CA certificates, one thing to reason about when something behaves differently
# in the image than it did in the build.
FROM eclipse-temurin:21-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766 AS runtime

# Not root. A container that does not need to write anywhere should not be able to.
RUN groupadd --system freezehub && useradd --system --gid freezehub freezehub

WORKDIR /app
COPY --from=build --chown=freezehub:freezehub /build/target/*.jar app.jar

USER freezehub
EXPOSE 8080

# Container-aware by default in modern JVMs: the heap is sized from the Fargate task's
# memory limit rather than the host's, so there is no -Xmx to keep in step with the
# task definition.
#
# `exec` form, so the JVM is PID 1 and receives the SIGTERM ECS sends on a deployment —
# without it a shell would swallow the signal and every deploy would end in a kill.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
