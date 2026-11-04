# El contexto de construcción es la raíz del repositorio (ver docker-compose.yml), para poder
# instalar primero el componente común: igual que en desarrollo, el servicio lo toma del .m2.

# Etapa 1: compilar con Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 1) aerogest-common -> repositorio Maven local de la imagen (mvn install, ADR 0004)
COPY common/pom.xml common/pom.xml
RUN mvn -B -q -f common/pom.xml dependency:go-offline
COPY common/src common/src
RUN mvn -B -q -f common/pom.xml install -DskipTests

# 2) el servicio, que declara aerogest-common como dependencia en su pom.xml
COPY reservation-service/pom.xml reservation-service/pom.xml
RUN mvn -B -q -f reservation-service/pom.xml dependency:go-offline
COPY reservation-service/src reservation-service/src
RUN mvn -B -q -f reservation-service/pom.xml package -DskipTests

# Etapa 2: ejecutar solo el JAR, sin Maven ni código fuente
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 appuser
COPY --from=build /build/reservation-service/target/*.jar app.jar
USER appuser
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
