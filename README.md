# AeroGest

Backend de gestión de vuelos y reservas (TPI Backend de Aplicaciones, UTN FRC).
Dos microservicios, cada uno con su propia base PostgreSQL, y Keycloak como proveedor de identidad.

| Componente | Puerto |
|---|---|
| flight-service | 8081 |
| reservation-service | 8082 |
| Keycloak | 8180 |
| PostgreSQL de vuelos | 5433 |
| PostgreSQL de reservas | 5434 |

> Estado: Fase 2 (preparación). Los servicios todavía no tienen lógica de negocio.

## Requisitos (Windows)
- JDK 21
- Docker Desktop (con WSL2)
- PowerShell y `curl.exe` (vienen con Windows 10/11)

## Puesta en marcha

Todos los comandos se ejecutan desde la raíz del repo (`aerogest/`).

```powershell
# 1. Variables de entorno
Copy-Item .env.example .env        # editá las claves de .env

# 2. Infraestructura: bases de datos + Keycloak
#    (si ya habías levantado la versión anterior: docker compose down -v)
docker compose up -d

# 3. Generar los esqueletos Maven (una sola vez)
.\scripts\init-projects.ps1

# 4. Agregar springdoc a AMBOS pom.xml (ver sección siguiente)

# 5. Cargar variables y arrancar cada servicio (una terminal por servicio)
. .\scripts\load-env.ps1
cd flight-service
.\mvnw.cmd spring-boot:run
```

En otra terminal repetí `. .\scripts\load-env.ps1` y arrancá `reservation-service` con el mismo comando.

**Sistema completo en contenedores** (después del paso 3 y 4):

```powershell
docker compose --profile apps up -d --build
```

## Dependencia springdoc (agregar a ambos `pom.xml`, dentro de `<dependencies>`)

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.0.3</version>
</dependency>
```

Para Spring Boot 4 hay que usar la serie 3.x de springdoc. Si existe una 3.0.x más nueva, usala.

## Verificación de la Fase 2

**1. Bases y servicios**
- Cada servicio imprime `Started ...Application` en el log.
- Flyway aplica la migración: busca `Successfully applied 1 migration`.
- Las tablas existen (usá los usuarios y bases de tu `.env`):
  ```powershell
  docker exec -it aerogest-flights-db psql -U flights_user -d flights_db -c "\dt"
  docker exec -it aerogest-reservations-db psql -U reservations_user -d reservations_db -c "\dt"
  ```

**2. Keycloak**
- Abrí http://localhost:8180 e ingresá a la consola con `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`. Tiene que existir el realm `aerogest` con los roles ADMIN, USER y SERVICE.
- Pedí un token de usuario (reemplazá la contraseña por `DEMO_ADMIN_PASSWORD`):
  ```powershell
  $body = @{ grant_type = "password"; client_id = "aerogest-api"; username = "admin"; password = "TU_DEMO_ADMIN_PASSWORD" }
  $t = Invoke-RestMethod -Method Post -Uri "http://localhost:8180/realms/aerogest/protocol/openid-connect/token" -Body $body
  ```
- Verificá que el token trae el rol:
  ```powershell
  $p = $t.access_token.Split('.')[1].Replace('-','+').Replace('_','/')
  switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
  [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json | Select-Object -ExpandProperty realm_access
  ```
  Debe mostrar `roles` con `ADMIN`.
- Pedí el token de servicio:
  ```powershell
  $b = @{ grant_type = "client_credentials"; client_id = "reservation-service"; client_secret = "TU_RESERVATION_CLIENT_SECRET" }
  Invoke-RestMethod -Method Post -Uri "http://localhost:8180/realms/aerogest/protocol/openid-connect/token" -Body $b
  ```

Si el login falla con "Invalid user credentials" y la contraseña es correcta, el reemplazo de variables del realm no funcionó: avisame.

## Estructura

| Ruta | Contenido |
|---|---|
| `docker-compose.yml` | Dos PostgreSQL, Keycloak y (perfil `apps`) los dos servicios |
| `.env.example` | Variables de entorno de ejemplo, sin secretos reales |
| `keycloak/aerogest-realm.json` | Realm con roles, clientes y usuarios de demostración |
| `scripts/` | `init-projects.ps1` y `load-env.ps1` |
| `flight-service/` | Microservicio de vuelos (incluye `Dockerfile`) |
| `reservation-service/` | Microservicio de reservas (incluye `Dockerfile`) |
| `docs/adr/` | Decisiones de arquitectura |
| `postman/` | Colección de pruebas (Fase 4) |
