FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

# Runtime stage: JRE only, non-root user, no build tooling.
# curl is here on purpose: it backs the HEALTHCHECK and the CI smoke check, and the temurin JRE image
# does not ship wget.
FROM eclipse-temurin:25-jre-jammy AS runtime
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