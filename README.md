# AeroGest

Backend de gestión de vuelos y reservas: Trabajo Práctico Integrador de Backend de Aplicaciones, UTN FRC.

Son dos microservicios Spring Boot independientes, cada uno con su propia base PostgreSQL, y Keycloak
como proveedor de identidad. El proceso central es la **reserva**: Reservation Service la confirma
solo después de que Flight Service ocupa la plaza, sin sobreventa aunque lleguen solicitudes
simultáneas y sin confirmar nada si Flight no responde.

| Componente | Puerto | Responsabilidad |
|---|---|---|
| flight-service | 8081 | Vuelos, estados y plazas disponibles (fuente de verdad de la capacidad) |
| reservation-service | 8082 | Pasajeros y reservas; orquesta la reserva contra Flight |
| Keycloak | 8180 | Usuarios, roles (ADMIN, USER, SERVICE) y emisión de tokens JWT |
| PostgreSQL de vuelos | 5433 | Base exclusiva de flight-service |
| PostgreSQL de reservas | 5434 | Base exclusiva de reservation-service |

Estado: implementación y verificación completas (Fases 1 a 4). Documentación de entrega en `docs/`.

## Documentación

| Documento | Contenido |
|---|---|
| [docs/arquitectura.md](docs/arquitectura.md) | Dominio, MVP, actores, diagrama de arquitectura, secuencia de la reserva, estados, modelo de datos, contratos REST y reglas de negocio numeradas |
| [docs/adr/](docs/adr/) | Decisiones de arquitectura: diseño inicial (0001), Keycloak (0002), flujo de reserva y consistencia (0003) |
| [docs/trazabilidad.md](docs/trazabilidad.md) | Matriz que vincula requisitos y reglas con su implementación, sus pruebas y los criterios de evaluación |
| [docs/evidencias/](docs/evidencias/) | Salidas de las pruebas automáticas, de los smoke tests y de la colección Postman |
| [docs/guion-defensa.md](docs/guion-defensa.md) | Guion de la demostración y preguntas probables |
| [postman/](postman/) | Colección Postman y entorno local |
| Swagger | http://localhost:8081/swagger-ui.html y http://localhost:8082/swagger-ui.html |

## Requisitos (Windows)
- Docker Desktop (con WSL2)
- PowerShell 5.1 o superior (viene con Windows)
- Para correr los servicios con Maven o los tests: JDK 21
- Para correr la colección por consola: Node.js (opcional; también se puede usar Postman)

## Puesta en marcha

Todos los comandos se ejecutan desde la raíz del repo (`aerogest/`).

```powershell
# 1. Variables de entorno: copiá el ejemplo y cambiá las claves
Copy-Item .env.example .env

# 2. Sistema completo en contenedores (la primera vez tarda varios minutos en construir)
docker compose --profile apps up -d --build
```

Keycloak tarda entre 1 y 3 minutos en arrancar. El sistema está listo cuando los tres responden:

```powershell
curl.exe http://localhost:8180/realms/aerogest
curl.exe http://localhost:8081/actuator/health
curl.exe http://localhost:8082/actuator/health
```

Para apagar todo: `docker compose --profile apps down`. Para empezar con bases vacías, agregá `-v`.

**Alternativa sin contenedores para los servicios** (útil para depurar): levantá solo la
infraestructura con `docker compose up -d` y, en una terminal por servicio:

```powershell
. .\scripts\load-env.ps1
cd flight-service        # o reservation-service
.\mvnw.cmd spring-boot:run
```

### Usuarios de demostración

El realm `aerogest` (`keycloak/aerogest-realm.json`) se importa en cada arranque limpio de Keycloak.

| Usuario | Rol | Contraseña |
|---|---|---|
| `admin` | ADMIN | `DEMO_ADMIN_PASSWORD` del `.env` |
| `user1`, `user2` | USER | `DEMO_USER_PASSWORD` del `.env` |
| cliente `reservation-service` | SERVICE | `RESERVATION_CLIENT_SECRET` del `.env` (client credentials) |

Para probar en Swagger, este script pide el token y lo copia al portapapeles (funciona desde cualquier carpeta):

```powershell
.\scripts\get-token.ps1               # user1; también -User user2 o -User admin
```

En Swagger: **Authorize**, pegá el token tal cual (sin escribir `Bearer`, Swagger lo agrega) y **Authorize**.
El token vence a los 30 minutos y se carga por separado en cada servicio (8081 y 8082).

## Pruebas

### Automáticas (JUnit, Mockito, Testcontainers)
Requieren Docker Desktop abierto: las pruebas de integración levantan su propio PostgreSQL.

```powershell
cd flight-service;      .\mvnw.cmd test   # 72 pruebas
cd reservation-service; .\mvnw.cmd test   # 113 pruebas
```

Incluyen reglas de dominio, servicios con dobles, API de punta a punta contra una base real,
seguridad (401, 403, recursos ajenos), fallos de integración (caída, timeout, respuesta inválida)
y concurrencia (20 solicitudes por la última plaza, reservas y cancelaciones simultáneas).

### Colección Postman
Importá `postman/AeroGest.postman_collection.json` y `postman/aerogest-local.postman_environment.json`,
completá en el entorno las tres claves del `.env` y ejecutá las carpetas **00 a 06** en orden con
*Run collection*. Cada corrida crea vuelos y documentos nuevos, así que se puede repetir.

Por consola, con las claves tomadas del `.env`:

```powershell
. .\scripts\load-env.ps1
.\scripts\run-postman.ps1
```

Las carpetas **07** (Flight apagado) y **08** (Flight sin responder) son escenarios manuales: cada
una indica qué comando de Docker ejecutar antes y después.

### Smoke tests contra el sistema real
```powershell
. .\scripts\load-env.ps1
.\scripts\smoke-flights.ps1                          # Flight Service: vuelos, seguridad, asignación de plazas
.\scripts\smoke-reservations.ps1                     # proceso completo, incluida la última plaza con 10 reservas simultáneas
.\scripts\smoke-reservations.ps1 -IncludeFailures    # además apaga (503) y congela (504) Flight Service
```

## Estructura

| Ruta | Contenido |
|---|---|
| `docker-compose.yml` | Dos PostgreSQL y Keycloak; con el perfil `apps`, también los dos servicios |
| `.env.example` | Variables de entorno de ejemplo, sin secretos reales |
| `keycloak/aerogest-realm.json` | Realm con roles, clientes y usuarios de demostración |
| `flight-service/`, `reservation-service/` | Cada microservicio: código, migraciones Flyway (`src/main/resources/db/migration`), pruebas y `Dockerfile` |
| `scripts/` | Carga del `.env`, smoke tests, ejecución de la colección (`init-projects.ps1` generó los esqueletos en la Fase 2) |
| `postman/` | Colección de pruebas funcionales y entorno |
| `docs/` | Arquitectura, ADRs, trazabilidad, evidencias y guion de defensa |

## Decisiones pendientes
- Confirmar con la cátedra que Keycloak, como infraestructura de identidad, es compatible con el
  requisito de dos microservicios de negocio (ver ADR 0002).
