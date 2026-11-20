# AeroGest — resumen del proyecto y cumplimiento de consignas

Revisión del 2026-11-10 contra el enunciado preliminar del TPI 2026 (versión del 2026-10-05) y la
rúbrica general v05 (propuesta 0.11). El detalle fila por fila, con nombres de pruebas, está en
[trazabilidad.md](trazabilidad.md).

## El proyecto en un párrafo

AeroGest es el backend de una aerolínea para administrar vuelos y reservas. El proceso central es la
**reserva**: Reservation Service la crea `PENDING`, le pide a Flight Service que ocupe una plaza y
recién entonces la confirma o la rechaza. Las plazas y las reservas viven en dos servicios con bases
separadas, así que el diseño se concentra en **no confirmar reservas inválidas**: sin sobreventa
bajo concurrencia (UPDATE atómico), sin duplicados (índice único parcial) y sin confirmar nada si
Flight no responde (compensación idempotente + job de reconciliación). Un API Gateway es el único
punto de entrada, Keycloak emite los tokens y Flight consulta OpenSky Network para cerrar las
ventas de un vuelo cuyo avión ya despegó.

| Componente | Tecnología | Responsabilidad |
|---|---|---|
| api-gateway (:8080) | Spring Cloud Gateway, Boot 4.0.9 | Ruteo, Security Filter (JWT + rol por ruta), correlation id, Swagger unificado |
| flight-service | Spring Boot 4, Java 21, PostgreSQL | Vuelos, estados, inventario de plazas, verificación de salida con OpenSky |
| reservation-service | Spring Boot 4, Java 21, PostgreSQL | Pasajeros, reservas, orquestación, reconciliación |
| aerogest-common | JAR Maven en `.m2` | Roles de Keycloak, correlation id, formato de error, paginación |
| Keycloak 26 | Realm `aerogest` importado | OIDC, JWT, roles ADMIN / USER / SERVICE |
| OpenSky Network | API pública externa | ¿La aeronave del vuelo ya está en el aire? (RN-16) |

## Cumplimiento del enunciado

| Consigna del enunciado | Estado | Dónde se ve |
|---|---|---|
| C4 C1 (contexto) y C2 (contenedores) que representen lo implementado | ✅ | `docs/diagramas/C4_*.png`, fuentes `.puml`; propuesta v2 §05–06 |
| ≥ 2 microservicios Java/Spring Boot con responsabilidad propia (no 2 CRUD) | ✅ | Flight = capacidad; Reservation = reserva y orquestación (ADR 0001) |
| Colaboración real en un proceso de negocio | ✅ | Reserva y cancelación: Reservation → API interna de Flight (ADR 0003) |
| Qué pasa si otro componente no responde | ✅ | 503 / 504 / 502, compensación, `PENDING` + job; probado con `docker stop` y `docker pause` |
| API Gateway como punto de entrada, sin exponer topología | ✅ | Solo el gateway publica puerto en Compose (ADR 0005) |
| Security Filter en el gateway | ✅ | `api-gateway/.../SecurityConfig.java`: JWT, rol por ruta, `/internal/**` denegado |
| API pública externa con uso genuino de negocio + manejo de fallas | ✅ | OpenSky → RN-16; fallas no cambian nada (ADR 0006, evidencia en vivo) |
| Keycloak: OIDC, JWT, Spring Security, roles, autenticación y autorización | ✅ | Realm con 3 roles; Swagger con authorization code + PKCE; client credentials entre servicios |
| Trusted zone justificada | ✅ | Doble validación de JWT, dueño por `sub`, token SERVICE interno (ADR 0002 y 0005) |
| JAR común Maven en `.m2`, usado por los dos servicios | ✅ | `common/` → `aerogest-common:1.0.0`; dependencia en los 3 `pom.xml` (ADR 0004) |
| Persistencia relacional con estrategia justificada | ✅ | Una base PostgreSQL por servicio, Flyway, restricciones que garantizan reglas |
| APIs REST: métodos, códigos, JSON, validación, errores | ✅ | Bean Validation, RFC 7807 con `code` y `correlationId` |
| Documentación OpenAPI / Swagger | ✅ | `http://localhost:8080/swagger-ui.html` (los dos servicios) |
| Contenerización y Docker Compose del sistema completo | ✅ | `docker compose up -d --build`; arranque limpio en `evidencias/arranque-limpio.txt` |

## Frente a la rúbrica (105 puntos)

| Criterio | Pts | Qué lo respalda | Riesgo |
|---|---|---|---|
| ARQ-01 Arquitectura | 12 | C4, 6 ADRs, bases separadas, límites por responsabilidad | Bajo |
| SEG-01 Seguridad | 8 | Matriz de permisos, 401/403 probados, recursos ajenos, secretos en `.env` | Bajo |
| TEC-01 Calidad técnica | 10 | Capas, dominio con reglas, errores uniformes, 248 pruebas | Bajo |
| INF-01 Persistencia y despliegue | 10 | Flyway, Compose completo, README con arranque | Bajo |
| NEG-01 Proceso central | 15 | Reserva PENDING → CONFIRMED/REJECTED, cancelación, de punta a punta | Bajo |
| NEG-02 Reglas de negocio | 10 | RN-01 a RN-16, cada una con su prueba | Bajo |
| INT-01 Integración | 12 | Contratos, timeouts, compensación, correlation id, OpenSky tolerante a fallas | Bajo |
| ALC-01 Alcance | 8 | Matriz de trazabilidad completa | **Medio:** faltan datos del equipo en la propuesta |
| DES-01 Desafío | 5 | Consistencia sin transacción distribuida, concurrencia, resultados inciertos | Bajo |
| PRE-01 Presentación | 5 | [guion-presentacion.md](guion-presentacion.md) + [guion-defensa.md](guion-defensa.md) | **Medio:** depende del ensayo y de que todos dominen todo |
| API-01 Colección | 5 | Postman 9 carpetas, entorno, tests por solicitud, script `run-postman.ps1` | Bajo |
| DOC-01 Apreciación | 5 | Historial por fases, ADRs, propuesta v1 → v2 | — (lo valora el docente) |

## Estado del código (verificación del 2026-11-10)

| Suite | Pruebas | Resultado |
|---|---|---|
| common | 11 | ✅ 11/11 |
| flight-service | 113 | ✅ 113/113 (ver nota) |
| reservation-service | 113 | ✅ 113/113 |
| api-gateway | 11 | ✅ 11/11 |
| `smoke-reservations.ps1` contra `docker compose up --build` | 32 | ✅ 32/32 |

Nota: en una primera corrida con la máquina muy cargada (Keycloak arrancando en paralelo),
`HttpOpenSkyClientTest.cuerpoQueNoEsJsonEsRespuestaInvalida` dio un falso `OPENSKY_TIMEOUT` porque
el cliente de prueba tenía 1 s de timeout contra el servidor falso. Se subió a 5 s solo en ese test
(el caso de timeout real usa su propio cliente de 300 ms) y la suite pasó completa. No hubo cambios
en el código productivo.

Consejo para la demo: levantar el sistema **antes** y hacer una petición de calentamiento; la
primera llamada en frío con la máquina cargada puede superar los 15 s del gateway y dar 504.

## Lo que falta (no es código)

1. **Datos del equipo** en la Sección 01 de `propuesta-tpi-2026-v2.md` (equipo, comisión,
   integrantes, contacto).
2. **Reparto de bloques** en `guion-defensa.md` (los `[COMPLETAR: integrante]`) y nombres en la
   portada del guion de presentación.
3. **Armar las diapositivas** siguiendo `guion-presentacion.md` (15 láminas) y **ensayar la demo**
   con el sistema levantado al menos una vez antes de la clase.
4. Opcional: imágenes de los diagramas Mermaid (proceso, estados, DER); los C4 ya están en PNG.

## Limitaciones aceptadas (decirlas antes de que las pregunten)

- Cancelar un vuelo no cancela sus reservas automáticamente.
- No se impide cancelar una reserva después de la salida del vuelo.
- OpenSky solo cierra ventas al detectar el despegue; no se sigue el aterrizaje.
