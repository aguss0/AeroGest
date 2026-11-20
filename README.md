# AeroGest

Backend de gestión de vuelos y reservas: Trabajo Práctico Integrador de Backend de Aplicaciones, UTN FRC.

Son dos microservicios Spring Boot independientes, cada uno con su propia base PostgreSQL, detrás de
un API Gateway que es el único punto de entrada, y Keycloak como proveedor de identidad. Los tres
componentes Java usan un JAR común (`aerogest-common`) instalado en el repositorio Maven local. El proceso central es la **reserva**: Reservation Service la confirma
solo después de que Flight Service ocupa la plaza, sin sobreventa aunque lleguen solicitudes
simultáneas y sin confirmar nada si Flight no responde. Flight Service consulta además la API
pública de **OpenSky Network** para saber si un vuelo ya despegó de verdad y cerrar sus ventas (RN-16).

| Componente | Puerto | Responsabilidad |
|---|---|---|
| api-gateway | **8080** | Único punto de entrada: ruteo, Security Filter (JWT y roles), correlation id, Swagger unificado |
| flight-service | 8081 (interno) | Vuelos, estados y plazas disponibles (fuente de verdad de la capacidad) |
| reservation-service | 8082 (interno) | Pasajeros y reservas; orquesta la reserva contra Flight |
| Keycloak | 8180 | Usuarios, roles (ADMIN, USER, SERVICE) y emisión de tokens JWT |
| PostgreSQL de vuelos | 5433 | Base exclusiva de flight-service |
| PostgreSQL de reservas | 5434 | Base exclusiva de reservation-service |
| aerogest-common | — | JAR común: roles de Keycloak, correlation id, formato de errores y paginación |
| OpenSky Network (externo) | — | API pública de tráfico aéreo, acceso anónimo: ¿la aeronave del vuelo ya está en el aire? (ADR 0006) |

Los microservicios no publican puertos en Docker: solo el gateway (ADR 0005).

Estado: implementación y verificación completas (Fases 1 a 4). Documentación de entrega en `docs/`.

## Documentación

| Documento | Contenido |
|---|---|
| [docs/arquitectura.md](docs/arquitectura.md) | Dominio, MVP, actores, diagrama de arquitectura, secuencia de la reserva, estados, modelo de datos, contratos REST y reglas de negocio numeradas |
| [docs/adr/](docs/adr/) | Decisiones de arquitectura: diseño inicial (0001), Keycloak (0002), flujo de reserva y consistencia (0003), componente común (0004), API Gateway y trusted zone (0005), integración con OpenSky (0006) |
| [docs/propuesta-tpi-2026-v2.md](docs/propuesta-tpi-2026-v2.md) | Respuestas al formulario de propuesta, con los diagramas C4 (C1 y C2) |
| [docs/trazabilidad.md](docs/trazabilidad.md) | Matriz que vincula requisitos y reglas con su implementación, sus pruebas y los criterios de evaluación |
| [docs/evidencias/](docs/evidencias/) | Salidas de las pruebas automáticas, de los smoke tests y de la colección Postman |
| [docs/guion-defensa.md](docs/guion-defensa.md) | Guion de la demostración y preguntas probables |
| [postman/](postman/) | Colección Postman y entorno local |
| Swagger | http://localhost:8080/swagger-ui.html (los dos servicios, elegí cuál arriba a la derecha) |

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
docker compose up -d --build
```

Keycloak tarda entre 1 y 3 minutos en arrancar. El sistema está listo cuando responden Keycloak y el gateway:

```powershell
curl.exe http://localhost:8180/realms/aerogest
curl.exe http://localhost:8080/actuator/health
```

Para apagar todo: `docker compose down`. Para empezar con bases vacías, agregá `-v`.

**Alternativa sin contenedores para los servicios** (útil para depurar): instalá el componente común,
levantá solo la infraestructura y, en una terminal por componente:

```powershell
.\scripts\build-common.ps1                                # aerogest-common -> ~/.m2
docker compose up -d flights-db reservations-db keycloak
. .\scripts\load-env.ps1
cd flight-service        # o reservation-service, o api-gateway
.\mvnw.cmd spring-boot:run
```

**Diagnóstico:** `docker compose -f docker-compose.yml -f docker-compose.debug.yml up -d` publica además
8081 y 8082 solo en `127.0.0.1`, para ver la API interna de Flight (la usa `smoke-flights.ps1`).

### Usuarios de demostración

El realm `aerogest` (`keycloak/aerogest-realm.json`) se importa en cada arranque limpio de Keycloak.

| Usuario | Rol | Contraseña |
|---|---|---|
| `admin` | ADMIN | `DEMO_ADMIN_PASSWORD` del `.env` |
| `user1`, `user2` | USER | `DEMO_USER_PASSWORD` del `.env` |
| cliente `reservation-service` | SERVICE | `RESERVATION_CLIENT_SECRET` del `.env` (client credentials) |

En Swagger (`http://localhost:8080/swagger-ui.html`) lo más directo es **Authorize → keycloak**
(client_id `aerogest-api`, sin secreto): abre la página de login de Keycloak (OIDC, authorization
code + PKCE) y vuelve con el token. Como alternativa, este script pide el token y lo copia al
portapapeles (funciona desde cualquier carpeta) para pegarlo en **bearerAuth**:

```powershell
.\scripts\get-token.ps1               # user1; también -User user2 o -User admin
```

En Swagger: **Authorize**, pegá el token tal cual (sin escribir `Bearer`, Swagger lo agrega) y **Authorize**.
El token vence a los 30 minutos. Desde el Swagger del gateway el mismo token sirve para los dos servicios.

## Pruebas

### Automáticas (JUnit, Mockito, Testcontainers)
Requieren Docker Desktop abierto: las pruebas de integración levantan su propio PostgreSQL.
Primero hay que instalar el componente común: `.\scripts\build-common.ps1` corre además sus pruebas.

```powershell
cd common;              .\mvnw.cmd install   # 11 pruebas
cd flight-service;      .\mvnw.cmd test      # 113 pruebas
cd reservation-service; .\mvnw.cmd test      # 113 pruebas
cd api-gateway;         .\mvnw.cmd test      # 11 pruebas
```

Incluyen reglas de dominio, servicios con dobles, API de punta a punta contra una base real,
seguridad (401, 403, recursos ajenos), fallos de integración (caída, timeout, respuesta inválida)
y concurrencia (20 solicitudes por la última plaza, reservas y cancelaciones simultáneas). La
integración con OpenSky se prueba con un servidor HTTP falso y un doble del cliente: ninguna prueba
automática sale a Internet.

### OpenSky en vivo
Para ver la regla RN-16 con un avión real: buscá una aeronave en el aire en
`https://opensky-network.org/api/states/all?lamin=-40&lomin=-66&lamax=-30&lomax=-56` (posición 8
`false`, callsign en la posición 1), creá un vuelo con ese `callsign` y `aircraftIcao24` y llamá a
`POST /api/v1/flights/{id}/departure-check` como admin (carpeta 09 de Postman). Ejemplo en
[docs/evidencias/opensky-en-vivo.md](docs/evidencias/opensky-en-vivo.md). Sin cuenta: 400 créditos
por día; el job automático usa 288.

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
.\scripts\smoke-reservations.ps1                     # por el gateway: proceso completo, incluida la última plaza con 10 reservas simultáneas
.\scripts\smoke-reservations.ps1 -IncludeFailures    # además apaga (503) y congela (504) Flight Service
.\scripts\smoke-flights.ps1                          # directo a Flight, con docker-compose.debug.yml: incluye la API interna
```

## Estructura

| Ruta | Contenido |
|---|---|
| `docker-compose.yml` | Sistema completo: dos PostgreSQL, Keycloak, los dos servicios y el gateway |
| `docker-compose.debug.yml` | Solo diagnóstico: publica 8081 y 8082 en `127.0.0.1` |
| `.env.example` | Variables de entorno de ejemplo, sin secretos reales |
| `keycloak/aerogest-realm.json` | Realm con roles, clientes y usuarios de demostración |
| `common/` | Componente común `aerogest-common` (JAR Maven, ADR 0004) |
| `api-gateway/` | API Gateway: rutas, Security Filter, manejo de errores y `Dockerfile` (ADR 0005) |
| `flight-service/`, `reservation-service/` | Cada microservicio: código, migraciones Flyway (`src/main/resources/db/migration`), pruebas y `Dockerfile` |
| `scripts/` | Carga del `.env`, instalación del componente común, smoke tests, ejecución de la colección (`init-projects.ps1` generó los esqueletos en la Fase 2) |
| `postman/` | Colección de pruebas funcionales y entorno |
| `docs/` | Arquitectura, ADRs, trazabilidad, evidencias y guion de defensa |

## Pendiente
- Completar la Sección 01 de la propuesta (equipo, comisión, integrantes, contacto) y el reparto del guion de defensa.
- Opcional: imágenes de los diagramas Mermaid de la propuesta (los C4 ya están en PNG en [docs/diagramas/](docs/diagramas/)).

Resumen de cumplimiento contra el enunciado y la rúbrica: [docs/resumen-cumplimiento.md](docs/resumen-cumplimiento.md).
Guion de la presentación oral (3 integrantes, con diagrama de flujo): [docs/presentacion-3-integrantes.md](docs/presentacion-3-integrantes.md).
