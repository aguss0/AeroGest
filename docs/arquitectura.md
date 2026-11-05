# AeroGest: arquitectura y diseño

Este documento describe el dominio, el alcance del MVP, la arquitectura, los procesos principales,
el modelo de datos, los contratos de las APIs y las reglas de negocio. Las decisiones y sus
alternativas están en [`docs/adr/`](adr/).

## 1. Dominio y alcance del MVP

AeroGest es el backend de una aerolínea para administrar vuelos comerciales y reservas de pasajeros.
El problema central es **no confirmar reservas inválidas**: sin plazas, en vuelos que no las admiten,
duplicadas o sin haber podido validar el vuelo. Esto se complica porque las plazas y las reservas
viven en dos servicios distintos, con bases de datos separadas, bajo concurrencia y con fallos de red.

**Dentro del alcance:** gestión de vuelos y sus estados, disponibilidad de plazas, gestión básica de
pasajeros, creación, consulta y cancelación de reservas, integración entre servicios, seguridad,
persistencia, pruebas y documentación de las APIs.

**Fuera del alcance:** pagos, reembolsos, facturación, check-in, embarque, equipaje, tripulación,
control de tráfico aéreo y asientos individuales (solo se gestiona la cantidad de plazas, ADR 0001).

**Integración externa:** si un vuelo **ya despegó** no lo decide la aerolínea, ocurre en el mundo
real. Flight Service lo consulta en **OpenSky Network** (API pública de tráfico aéreo) y cierra las
ventas del vuelo cuando su aeronave aparece en el aire (RN-16, ADR 0006).

## 2. Actores y permisos

| Actor | Rol en Keycloak | Puede |
|---|---|---|
| Administrador | `ADMIN` | Crear, modificar y cambiar el estado de vuelos. Ver y cancelar pasajeros y reservas de cualquier usuario |
| Usuario | `USER` | Consultar vuelos. Registrar sus pasajeros y reservar, consultar y cancelar **sus** reservas |
| Reservation Service | `SERVICE` | Usar solo la API interna de Flight Service (`/internal/**`). No tiene acceso a la API pública |
| Sin autenticar | — | Solo la documentación (Swagger) y `/actuator/health` |

El rol habilita el endpoint; qué datos puede tocar cada usuario lo deciden los servicios según el
claim `sub` del token. Un recurso ajeno responde **403** (RN-09).

## 3. Arquitectura

```mermaid
flowchart LR
    client["Cliente<br/>(Postman, Swagger)"]
    kc["Keycloak 26<br/>realm aerogest<br/>:8180"]
    gw["API Gateway :8080<br/>ruteo + Security Filter<br/>(JWT y rol por ruta)"]
    subgraph fs["Flight Service :8081 (interno)"]
        fapi["API pública<br/>/api/v1/flights"]
        fint["API interna<br/>/internal/v1/flights"]
        fjob["Job de salida real<br/>(cada 5 min)"]
    end
    opensky["OpenSky Network<br/>API pública externa<br/>/api/states/all"]
    subgraph rs["Reservation Service :8082 (interno)"]
        rapi["API pública<br/>/api/v1/passengers<br/>/api/v1/reservations"]
        job["Job de reconciliación<br/>(cada 1 min)"]
    end
    fdb[("flights_db<br/>PostgreSQL :5433")]
    rdb[("reservations_db<br/>PostgreSQL :5434")]

    common[["aerogest-common (JAR Maven)<br/>roles Keycloak, correlation id,<br/>formato de error, paginación"]]

    client -- "1. login OIDC<br/>(Swagger: authorization code + PKCE;<br/>Postman: password grant)" --> kc
    client -- "2. JWT USER/ADMIN" --> gw
    gw -- "/api/v1/flights/**" --> fapi
    gw -- "/api/v1/passengers/**<br/>/api/v1/reservations/**" --> rapi
    gw -. "/internal/** denegado" .-x fint
    rapi -- "3. client_credentials" --> kc
    rapi -- "4. JWT SERVICE<br/>asignar / liberar plaza" --> fint
    job -- "liberar plaza" --> fint
    fjob -- "¿la aeronave está en el aire?<br/>HTTPS, anónimo" --> opensky
    fapi -- "departure-check (ADMIN)" --> opensky
    fs --> fdb
    rs --> rdb
    gw -. "valida firma (JWKS)" .-> kc
    fs -. "valida firma (JWKS)" .-> kc
    rs -. "valida firma (JWKS)" .-> kc
    common -. "dependencia Maven" .- gw
    common -. "dependencia Maven" .- fs
    common -. "dependencia Maven" .- rs
```

- **API Gateway como único punto de entrada** (ADR 0005). Es el único que publica un puerto: el
  cliente no conoce dónde están los servicios. Su Security Filter valida el JWT y el rol por ruta,
  y no publica `/internal/**`. Los servicios vuelven a validar el token (segunda barrera) y deciden
  sobre los datos por el `sub`. El tráfico Reservation → Flight va directo por la red interna, con
  su propio token `SERVICE`.
- **Componente común `aerogest-common`** (ADR 0004): JAR Maven instalado en el `.m2` que usan el
  gateway y los dos servicios. Contiene lo que es convención del sistema (roles, correlation id,
  formato de error, paginación); los códigos de error y las reglas de cada servicio quedan en el servicio.
- **Dos microservicios con responsabilidades separadas**, cada uno dueño de su base. Reservation
  nunca lee las tablas de Flight: le pregunta por HTTP.
- **Flight Service** es la fuente de verdad de las plazas. Ocupar una plaza es una sentencia atómica
  `UPDATE flights SET seats_taken = seats_taken + 1 WHERE id = ? AND status = 'SCHEDULED' AND
  departure_at > now AND seats_taken < capacity`. Si no afecta filas, no hay plaza: no hace falta
  bloquear ni consultar antes.
- **Reservation Service** orquesta el proceso de reserva y guarda pasajeros y reservas. La referencia
  al vuelo (`flight_id`) es lógica, sin clave foránea entre bases.
- **OpenSky Network** (ADR 0006) es la API pública externa. Flight Service la consulta desde un job y
  desde un endpoint del ADMIN, **nunca durante una reserva**: si OpenSky cae, las reservas siguen
  funcionando con la regla por horario (RN-04).
- **Keycloak** es infraestructura de identidad (ADR 0002), no un microservicio de negocio.
  Los servicios solo validan la firma de los tokens y no comparten secretos entre sí.

Capas de cada servicio: `controller` (HTTP y validación de formato) → `service` (casos de uso y
transacciones) → `domain` (entidades con sus reglas y transiciones) → `repository` (Spring Data JPA).
Los contratos son DTOs (`record`); las entidades JPA no se exponen.

## 4. Proceso de reserva

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant R as Reservation Service
    participant RDB as reservations_db
    participant K as Keycloak
    participant F as Flight Service
    participant FDB as flights_db

    U->>R: POST /api/v1/reservations {passengerId, flightId} (JWT USER)
    R->>R: valida formato, pasajero propio (RN-09)
    rect rgb(235, 245, 255)
    note over R,RDB: Transacción 1 (corta)
    R->>RDB: ¿reserva activa del pasajero en el vuelo? (RN-06)
    R->>RDB: INSERT reserva PENDING (id UUID propio)
    note right of RDB: el índice único parcial frena<br/>duplicados simultáneos
    end
    R->>K: token client_credentials (cacheado)
    R->>F: POST /internal/v1/flights/{id}/seat-allocations {reservationId}<br/>X-Correlation-Id
    F->>FDB: UPDATE atómico de seats_taken (RN-04, RN-05)
    F->>FDB: INSERT seat_allocation (PK reservationId, RN-15)
    alt plaza asignada
        F-->>R: 201 ALLOCATED
        R->>RDB: Transacción 2: PENDING → CONFIRMED
        R-->>U: 201 reserva CONFIRMED
    else Flight niega la plaza (sin plazas, no reservable, inexistente)
        F-->>R: 409 / 404 con code
        R->>RDB: Transacción 2: PENDING → REJECTED
        R-->>U: 409 NO_AVAILABILITY / FLIGHT_NOT_RESERVABLE, 404 FLIGHT_NOT_FOUND
    else resultado incierto (caída, timeout, respuesta inválida)
        F--xR: sin respuesta válida
        R->>F: DELETE seat-allocations/{reservationId} (idempotente)
        alt se pudo liberar
            R->>RDB: PENDING → REJECTED
        else Flight sigue sin responder
            note over R: queda PENDING: la resuelve el job de reconciliación
        end
        R-->>U: 503 / 504 / 502 (nunca CONFIRMED, RN-10)
    end
```

Por qué así (detalle en [ADR 0003](adr/0003-flujo-de-reserva-y-consistencia.md)):
- La llamada HTTP queda **fuera de las transacciones**: mientras se espera a Flight no se retiene
  una conexión ni bloqueos de la base.
- El id de la reserva se genera **antes** de llamar a Flight y viaja como **clave de idempotencia**:
  un reintento o una liberación posterior se refieren siempre a la misma asignación.
- Liberar una reserva que nunca ocupó plaza deja una ficha `RELEASED`; si la asignación original
  llega tarde, Flight la rechaza. Así la compensación es segura aunque no se sepa qué pasó.

**Cancelación (RN-07):** se verifica que la reserva esté `CONFIRMED`, se libera la plaza en Flight
(idempotente) y recién entonces se marca `CANCELLED`. Si Flight no responde, la reserva sigue
`CONFIRMED` y se puede reintentar. Dos cancelaciones simultáneas: Flight devuelve la plaza una sola
vez y `@Version` hace que solo una de las dos marque la reserva.

**Reconciliación:** cada minuto un job toma las reservas `PENDING` de más de 5 minutos, libera su
plaza y las marca `REJECTED`. Si Flight sigue caído, lo intenta en la próxima ejecución.

### Verificación de salida real (RN-16)

```mermaid
sequenceDiagram
    autonumber
    participant J as Job de Flight (cada 5 min)<br/>o POST departure-check (ADMIN)
    participant FDB as flights_db
    participant O as OpenSky Network

    J->>FDB: vuelos SCHEDULED con aeronave y salida entre ahora-6h y ahora+2h
    J->>O: GET /states/all?icao24=a&icao24=b (1 crédito, sin transacción abierta)
    alt respuesta válida
        O-->>J: states [icao24, callsign, ..., on_ground, ...]
        loop cada vuelo, transacción corta que lo relee
            alt en el aire con el callsign del vuelo
                J->>FDB: SCHEDULED → CLOSED, departure_detected_at
            else en tierra / otro callsign / no aparece
                J->>FDB: solo departure_checked_at
            end
        end
    else caída, timeout, 429 o respuesta ininteligible
        O--xJ: falla
        note over J: no cambia ningún vuelo (rige RN-04 por horario)<br/>429: espera X-Rate-Limit-Retry-After-Seconds
    end
```

Después del cierre, la asignación atómica de plazas rechaza las reservas con `FLIGHT_NOT_RESERVABLE`
aunque el horario programado sea futuro. Detalle y alternativas en [ADR 0006](adr/0006-integracion-opensky-salida-real.md).

## 5. Estados y transiciones (RN-08)

```mermaid
stateDiagram-v2
    direction LR
    state "Vuelo" as V {
        [*] --> SCHEDULED
        SCHEDULED --> CLOSED
        SCHEDULED --> CANCELLED
        CLOSED --> SCHEDULED
        CLOSED --> CANCELLED
        CLOSED --> COMPLETED
        CANCELLED --> [*]
        COMPLETED --> [*]
    }
```

Un vuelo admite reservas solo en `SCHEDULED` y antes de su salida (RN-04). `CANCELLED` y
`COMPLETED` son terminales: el vuelo ya no se puede modificar. `SCHEDULED → CLOSED` lo hace el
ADMIN o, automáticamente, la verificación con OpenSky al detectar el despegue (RN-16).

```mermaid
stateDiagram-v2
    direction LR
    state "Reserva" as R {
        [*] --> PENDING
        PENDING --> CONFIRMED: Flight asignó la plaza
        PENDING --> REJECTED: Flight la negó o falla de integración
        CONFIRMED --> CANCELLED: cancelación (libera la plaza)
        CANCELLED --> [*]
        REJECTED --> [*]
    }
```

Solo `PENDING` y `CONFIRMED` son **activas**: cuentan para RN-06 y pueden ocupar una plaza.

## 6. Modelo de datos

Cada servicio tiene su propia base y su esquema se crea solo con migraciones Flyway
(`ddl-auto: validate`).

```mermaid
erDiagram
    FLIGHTS ||--o{ SEAT_ALLOCATIONS : "tiene"
    FLIGHTS {
        bigint id PK
        varchar code UK "RN-01"
        varchar origin "IATA, distinto de destination (RN-11)"
        varchar destination "IATA"
        timestamptz departure_at
        timestamptz arrival_at "mayor que departure_at (RN-02)"
        int capacity "mayor que 0 (RN-03)"
        int seats_taken "entre 0 y capacity (RN-05)"
        varchar status "SCHEDULED, CLOSED, CANCELLED, COMPLETED"
        varchar callsign "ICAO, opcional, junto con aircraft_icao24 (RN-16)"
        varchar aircraft_icao24 "transpondedor, 6 hex"
        timestamptz departure_checked_at "última consulta válida a OpenSky"
        timestamptz departure_detected_at "despegue detectado"
        bigint version "bloqueo optimista"
    }
    SEAT_ALLOCATIONS {
        uuid reservation_id PK "una asignación por reserva (RN-15)"
        bigint flight_id FK
        varchar status "ALLOCATED, RELEASED"
        timestamptz allocated_at
        timestamptz released_at
    }
```

```mermaid
erDiagram
    PASSENGERS ||--o{ RESERVATIONS : "tiene"
    PASSENGERS {
        bigint id PK
        varchar owner_user_id "sub de Keycloak (RN-09)"
        varchar first_name
        varchar last_name
        varchar document_type "DNI, PASSPORT"
        varchar document_number "normalizado; UK con document_type (RN-14)"
    }
    RESERVATIONS {
        uuid id PK "generado por la aplicación"
        bigint passenger_id FK
        bigint flight_id "referencia lógica a Flight Service"
        varchar created_by_user_id "sub de Keycloak (RN-09)"
        varchar status "PENDING, CONFIRMED, CANCELLED, REJECTED"
        bigint version "bloqueo optimista"
    }
```

Restricciones que garantizan las reglas aunque haya solicitudes simultáneas:

| Restricción | Base | Garantiza |
|---|---|---|
| `uq_flights_code` | flights | RN-01 |
| `ck_flights_times`, `ck_flights_capacity`, `ck_flights_route` | flights | RN-02, RN-03, RN-11 |
| `ck_flights_seats` (`0 <= seats_taken <= capacity`) | flights | RN-05, última barrera ante cualquier error |
| PK `seat_allocations.reservation_id` | flights | RN-15: una asignación por reserva |
| `ck_flights_aircraft_pair`, `ck_flights_callsign`, `ck_flights_icao24` | flights | RN-16: callsign e icao24 juntos y con formato válido |
| `uq_passengers_document` | reservations | RN-14 |
| índice único parcial `uq_reservations_active_passenger_flight` (`WHERE status IN ('PENDING','CONFIRMED')`) | reservations | RN-06 |

Todas las tablas tienen además `created_at` y `updated_at`.

## 7. Contratos de las APIs

Todas las rutas públicas se consumen por el gateway: `http://localhost:8080`. La documentación
interactiva de los dos servicios está en `http://localhost:8080/swagger-ui.html` (selector arriba a
la derecha, botón *Authorize* con un token Bearer). El gateway publica solo el grupo `public` de
cada servicio; la API interna de Flight se documenta en su propio Swagger (grupo `internal`), que
solo es accesible con `docker-compose.debug.yml`.

### API Gateway (8080)

| Ruta | Destino | Rol exigido por el gateway |
|---|---|---|
| `GET /api/v1/flights/**` | Flight Service | USER, ADMIN |
| `POST/PUT/PATCH /api/v1/flights/**` | Flight Service | ADMIN |
| `/api/v1/passengers/**`, `/api/v1/reservations/**` | Reservation Service | USER, ADMIN |
| `/swagger-ui.html`, `/api-docs/flights`, `/api-docs/reservations`, `/actuator/health` | Gateway / grupo `public` de cada servicio | público |
| `/internal/**` y cualquier otra ruta | — | denegada (401 sin token, 403 con token) |

Errores propios del gateway: 401 `UNAUTHENTICATED`, 403 `ACCESS_DENIED`, 503 `UPSTREAM_UNAVAILABLE`
(servicio caído), 504 `UPSTREAM_TIMEOUT` (no respondió en 15 s). Los errores de los servicios se
reenvían sin cambios.

### Flight Service (8081, interno)

| Método y ruta | Rol | Respuestas |
|---|---|---|
| `POST /api/v1/flights` (con `callsign` y `aircraftIcao24` opcionales) | ADMIN | 201, 400 (`VALIDATION_ERROR`, `INVALID_SCHEDULE`, `INVALID_ROUTE`, `DEPARTURE_IN_PAST`, `INVALID_CAPACITY`, `INVALID_AIRCRAFT_REFERENCE`), 409 `DUPLICATE_FLIGHT_CODE` |
| `GET /api/v1/flights?origin&destination&status&departureFrom&departureTo&page&size` | USER, ADMIN | 200 (página) |
| `GET /api/v1/flights/{id}` | USER, ADMIN | 200, 404 `FLIGHT_NOT_FOUND` |
| `PUT /api/v1/flights/{id}` | ADMIN | 200, 400, 404, 409 `FLIGHT_NOT_MODIFIABLE` |
| `PATCH /api/v1/flights/{id}/status` | ADMIN | 200, 409 `INVALID_STATUS_TRANSITION` |
| `POST /api/v1/flights/{id}/departure-check` | ADMIN | 200 `{result: DEPARTURE_DETECTED \| ON_GROUND \| OTHER_FLIGHT \| NOT_SEEN, status, observedCallsign, observedOnGround, lastContact, ...}`, 404, 409 `FLIGHT_NOT_TRACKABLE`, 502 `OPENSKY_INVALID_RESPONSE`, 503 `OPENSKY_UNAVAILABLE` / `OPENSKY_RATE_LIMITED`, 504 `OPENSKY_TIMEOUT` |
| `GET /internal/v1/flights/{id}/availability` | SERVICE | 200 `{flightId, status, capacity, availableSeats, acceptsReservations}` |
| `POST /internal/v1/flights/{id}/seat-allocations` `{reservationId}` | SERVICE | 201 nueva, 200 reintento ya procesado, 404, 409 `NO_AVAILABILITY`, `FLIGHT_NOT_RESERVABLE`, `ALLOCATION_CONFLICT` |
| `DELETE /internal/v1/flights/{id}/seat-allocations/{reservationId}` | SERVICE | 204 (idempotente), 404 |

### Reservation Service (8082, interno)

| Método y ruta | Rol | Respuestas |
|---|---|---|
| `POST /api/v1/passengers` | USER, ADMIN | 201, 400 (`VALIDATION_ERROR`, `INVALID_PASSENGER_DATA`), 409 `DUPLICATE_PASSENGER_DOCUMENT` |
| `GET /api/v1/passengers` · `GET /api/v1/passengers/{id}` | USER, ADMIN | 200, 403, 404 `PASSENGER_NOT_FOUND` |
| `PUT /api/v1/passengers/{id}` `{firstName, lastName}` | USER, ADMIN | 200, 400, 403, 404 |
| `POST /api/v1/reservations` `{passengerId, flightId}` | USER, ADMIN | 201 `CONFIRMED`; 403; 404 `PASSENGER_NOT_FOUND`, `FLIGHT_NOT_FOUND`; 409 `DUPLICATE_ACTIVE_RESERVATION`, `NO_AVAILABILITY`, `FLIGHT_NOT_RESERVABLE`; 502 `INVALID_FLIGHT_RESPONSE`; 503 `FLIGHT_SERVICE_UNAVAILABLE`; 504 `FLIGHT_SERVICE_TIMEOUT` |
| `GET /api/v1/reservations` · `GET /api/v1/reservations/{id}` | USER, ADMIN | 200, 403, 404 `RESERVATION_NOT_FOUND` |
| `POST /api/v1/reservations/{id}/cancel` | USER, ADMIN | 200 `CANCELLED`, 403, 404, 409 `INVALID_RESERVATION_STATE`, `CONCURRENT_MODIFICATION`, 503 |

### Formato de errores

El gateway y los dos servicios responden con RFC 7807 más un `code` estable, que es lo que
interpretan los clientes (incluido Reservation Service al leer los errores de Flight). El formato
lo arma `ProblemResponses`, del componente común:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "El vuelo no tiene plazas disponibles",
  "code": "NO_AVAILABILITY",
  "correlationId": "9237a49b-b8d6-42d0-8cd9-6cab8b149a72"
}
```

Sin token: 401 `UNAUTHENTICATED`. Rol insuficiente o recurso ajeno: 403 `ACCESS_DENIED`.

### Integración y trazabilidad

- **Timeouts:** conexión 2 s y lectura 3 s (`FLIGHT_SERVICE_CONNECT_TIMEOUT_MS`, `FLIGHT_SERVICE_READ_TIMEOUT_MS`).
- **Token de servicio:** se pide con `client_credentials`, se cachea y se renueva 30 s antes de
  vencer. Ante un 401 de Flight se pide uno nuevo y se reintenta una sola vez.
- **Clasificación de respuestas de Flight:** 404 y 409 con `code` conocido son rechazos definitivos.
  Caída, timeout, 5xx, 401/403 y cuerpos inválidos son resultados inciertos, y nunca se confirma.
- **OpenSky:** conexión 3 s y respuesta 5 s (`OPENSKY_*`), acceso anónimo (400 créditos/día; el job
  usa 288). Ver la tabla de fallas en el ADR 0006.
- **Correlation id:** el gateway fija el `X-Correlation-Id` de cada solicitud (respeta el del
  cliente o genera uno) y lo reenvía. Va al log del gateway y de los dos servicios, Reservation lo
  reenvía a Flight y aparece en las respuestas de error.

## 8. Reglas de negocio

| Regla | Descripción | Dónde se garantiza |
|---|---|---|
| RN-01 | Código de vuelo único | `FlightService` y restricción `uq_flights_code` |
| RN-02 | La llegada es posterior a la salida | `Flight` y `ck_flights_times` |
| RN-03 | Datos obligatorios del vuelo y capacidad mayor que 0 | DTO con Bean Validation, `Flight` y `ck_flights_capacity` |
| RN-04 | No se reserva en vuelos que no estén `SCHEDULED` o que ya salieron | `UPDATE` atómico de Flight (`Flight.isReservable`) |
| RN-05 | Nunca más reservas confirmadas que plazas | `UPDATE ... WHERE seats_taken < capacity` y `ck_flights_seats` |
| RN-06 | Un pasajero no tiene dos reservas activas en el mismo vuelo | Verificación previa e índice único parcial |
| RN-07 | Cancelar libera la plaza una sola vez; no se cancela dos veces | `Reservation.cancel`, liberación idempotente en Flight y `@Version` |
| RN-08 | Transiciones de estado válidas | `FlightStatus` y `ReservationStatus` |
| RN-09 | Solo usuarios autorizados; nadie accede a datos ajenos salvo ADMIN | `SecurityConfig` (roles) y `CurrentUser.canAccess` (`sub`) |
| RN-10 | Sin validar en Flight no se confirma | `ReservationService` y `FlightServiceException` |
| RN-11 | Origen distinto de destino | `Flight` y `ck_flights_route` |
| RN-12 | No se crea un vuelo con salida en el pasado | `Flight.create` |
| RN-13 | No se reduce la capacidad por debajo de las plazas ocupadas | `Flight.update` |
| RN-14 | El documento (tipo y número normalizado) es único | `DocumentType.normalize` y `uq_passengers_document` |
| RN-15 | Asignar y liberar son idempotentes por `reservationId` | PK de `seat_allocations` y `SeatInventoryService` |
| RN-16 | Un vuelo cuya aeronave OpenSky muestra en el aire con su callsign deja de aceptar reservas; sin respuesta válida no cambia nada | `DepartureCheckResult`, `DepartureVerificationService`, `Flight.registerDetectedDeparture` |

La relación de cada regla con sus pruebas está en la [matriz de trazabilidad](trazabilidad.md).
