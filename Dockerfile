# Pattern Shop image: built and tested by CI, published to ghcr.io, run by framework CI jobs and by Render.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# The tests already ran in CI's own job; the image only needs the jar
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
# Links the ghcr.io package to this repository (its page, README and permissions)
LABEL org.opencontainers.image.source="https://github.com/niranjankagri/pattern-shop-demo-app" \
      org.opencontainers.image.description="Pattern Shop: a demo shop for test automation frameworks" \
      org.opencontainers.image.licenses="MIT"
RUN useradd --uid 10001 --no-create-home shop
COPY --from=build /src/target/pattern-shop.jar /app/pattern-shop.jar
USER shop
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -fsS "http://localhost:${PORT}/health" || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/pattern-shop.jar"]
