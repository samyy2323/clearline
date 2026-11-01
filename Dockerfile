# Single-image build: dashboard + Spring Boot app. Used by `docker compose` and by free hosts (Render, Koyeb, Fly).
FROM node:22-alpine AS ui
WORKDIR /w/dashboard
COPY dashboard/package*.json ./
RUN npm ci --no-audit --no-fund
COPY dashboard/ ./
# vite emits into ../services/auction-engine/src/main/resources/static
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /w
COPY pom.xml .
COPY libs libs
COPY services services
COPY simulator simulator
COPY --from=ui /w/services/auction-engine/src/main/resources/static services/auction-engine/src/main/resources/static
RUN mvn -B -q -DskipTests package -pl services/auction-engine -am

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/data && chown app:app /app/data
WORKDIR /app
COPY --from=build /w/services/auction-engine/target/auction-engine-1.0.0-SNAPSHOT.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC" PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s CMD wget -qO- http://localhost:${PORT}/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
