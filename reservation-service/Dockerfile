# Etapa 1: compilar con Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Etapa 2: ejecutar solo el JAR, sin Maven ni código fuente
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 appuser
COPY --from=build /build/target/*.jar app.jar
USER appuser
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
