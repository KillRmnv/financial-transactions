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


ENV JAVA_TOOL_OPTIONS="-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError -XX:HeapDumpPath=/app/dumps -XX:ErrorFile=/app/dumps/hs_err_pid%p.log"

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
	CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]