# Pattern Shop image: built and tested by CI, published to ghcr.io, run by framework CI jobs and by Render.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# The tests already ran in CI's own job; the image only needs the jar
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:25-jre
# Links the ghcr.io package to this repository (its page, README and permissions)
LABEL org.opencontainers.image.source="https://github.com/niranjankagri/pattern-shop-demo-app" \
      org.opencontainers.image.description="Pattern Shop: a demo shop for test automation frameworks" \
      org.opencontainers.image.licenses="MIT"
RUN useradd --uid 10001 --no-create-home shop
COPY --from=build /src/target/pattern-shop.jar /app/pattern-shop.jar
USER shop
ENV PORT=8080
EXPOSE 8080
# The Java 25 image has no curl, so the check speaks HTTP through bash's /dev/tcp: it sends GET /health and passes
# only when a line of the answer is exactly "ok" (the body; the status line and headers never are)
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/$PORT && printf "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -qx ok <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/pattern-shop.jar"]
