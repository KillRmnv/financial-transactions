# Pinned by digest, tag kept for readability: `25-jdk-jammy` and `25-jre-jammy` are mutable, so
# two builds of the same commit could compile against one JDK and run on another, and six months
# later the repository could not say what was in production. Two mutable tags make that worse rather
# than better — the build and the runtime stages update independently.
#
# Dependabot's `docker` ecosystem updates these digests, so pinning is a pull request that CI
# checks rather than a chore: the image rebuilds and the 274 tests and the startup smoke check run
# against the new base before anyone merges it. Refresh one by hand with
# `docker buildx imagetools inspect eclipse-temurin:<tag>` and read its Digest.
FROM eclipse-temurin:25-jdk-jammy@sha256:07a4ee373cd86f337e62cb8149f1e0a9f08aaf3a18c27509a493dda119fb480c AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

# Runtime stage: JRE only, non-root user, no build tooling.
# curl is here on purpose: it backs the HEALTHCHECK and the CI smoke check, and the temurin JRE image
# does not ship wget.
FROM eclipse-temurin:25-jre-jammy@sha256:e0d8c47eccd7ec82d635542dfcd1f2b1293add6bdd7ffbeb650f8ec1b1335425 AS runtime
WORKDIR /app

RUN apt-get update \
	&& apt-get install --no-install-recommends -y curl \
	&& rm -rf /var/lib/apt/lists/*

RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /workspace/target/*.jar app.jar
RUN chown app:app app.jar && mkdir /app/dumps && chown app:app /app/dumps


# The JVM reads these itself, which is how a deployment overrides flags without rebuilding the image.
#
# Sizing is by percentage rather than by -Xmx for one reason: the container's memory limit is what the
# JVM can see (UseContainerSupport is on by default since JDK 10), so a percentage follows the limit
# wherever the image runs, and an absolute number baked into a layer is a number somebody forgets to
# recompute. It needs mem_limit to be set — without one the container sees the host's memory and 70% of
# that is not a decision anyone made.
#
# The three memory regions are separate limits on purpose. MaxRAMPercentage covers the heap only;
# metaspace, the code cache, thread stacks and the mapped jar live outside it, so a heap that fills its
# percentage still leaves room for them — and an unbounded metaspace is how a leak there takes down a
# container whose heap was never touched. MaxMetaspaceSize is the cap that turns that into a
# OutOfMemoryError instead of an eviction.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=35 -XX:MaxMetaspaceSize=128m -XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError -XX:HeapDumpPath=/app/dumps -XX:ErrorFile=/app/dumps/hs_err_pid%p.log"

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
	CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]