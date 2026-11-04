# ADR 0006: Integración con OpenSky Network para verificar la salida real

Estado: aprobado e implementado. Fecha: 2026-11-04.

## Contexto
El enunciado exige consumir al menos una API pública externa con información **genuinamente
necesaria** para una funcionalidad o regla de negocio (no decorativa ni algo que se pueda guardar
como configuración), y poder explicar qué pasa cuando no responde o responde algo inesperado.

RN-04 dice que solo se reserva en vuelos `SCHEDULED` cuya salida es futura. Hasta ahora "salida"
era el horario programado, que la aerolínea carga a mano. Pero que un vuelo **ya despegó** no lo
decide la aerolínea: pasa en el mundo real. Un vuelo que sale antes de horario, o cuyo estado nadie
actualizó, seguía aceptando reservas.

## Decisión
- **API:** OpenSky Network, `GET https://opensky-network.org/api/states/all`, acceso **anónimo**
  (sin cuenta ni secretos). Estado de las aeronaves en tiempo real, cada 10 s.
- **Datos del vuelo:** dos campos opcionales que van juntos o ninguno (`INVALID_AIRCRAFT_REFERENCE`):
  `callsign` (indicativo ICAO, por ejemplo `ARG1324`) y `aircraftIcao24` (transpondedor, 6 dígitos
  hexadecimales). Se guardan normalizados y con `CHECK` en la base (migración `V2`). Además
  `departureCheckedAt` (última consulta con respuesta válida) y `departureDetectedAt`.
- **Regla RN-16:** el vuelo despegó si OpenSky devuelve su `icao24` **en el aire** (`on_ground = false`)
  **con el mismo callsign**. El callsign es indispensable: el mismo avión hace varios tramos por día
  con distinto indicativo. Entonces el vuelo pasa `SCHEDULED → CLOSED` (transición válida de RN-08)
  y el `UPDATE` atómico de asignación de plazas rechaza las reservas nuevas con
  `FLIGHT_NOT_RESERVABLE`. Reservation Service no cambia.

  | Lo que informa OpenSky | Resultado | Efecto |
  |---|---|---|
  | La aeronave en el aire con el callsign del vuelo | `DEPARTURE_DETECTED` | Vuelo `CLOSED` |
  | La aeronave en tierra | `ON_GROUND` | Solo registra la consulta |
  | La aeronave en el aire con otro callsign | `OTHER_FLIGHT` | Solo registra la consulta |
  | La aeronave no aparece (apagada, fuera de cobertura) | `NOT_SEEN` | Solo registra la consulta |

- **Job en Flight Service** (`DepartureVerificationJob`), dueño del estado del vuelo. Cada 5 min toma
  los vuelos `SCHEDULED` con aeronave cuya salida programada está entre ahora − 6 h y ahora + 2 h
  (hasta 100) y hace **una sola consulta** con todos los `icao24` (`?icao24=a&icao24=b`), que cuesta
  1 crédito: 288 créditos por día de los 400 del acceso anónimo. Cada corrida lleva su propio
  correlation id en el log (`opensky-xxxxxxxx`).
- **Verificación manual:** `POST /api/v1/flights/{id}/departure-check` (ADMIN). Verifica en el
  momento y devuelve el resultado, lo observado y el estado del vuelo. Sirve para operar y para la demo.
- **Fuera del camino de la reserva:** ninguna reserva consulta OpenSky. Su latencia y su
  disponibilidad no dependen de un servicio externo.
- **Transacciones:** la consulta HTTP no está dentro de ninguna transacción. Se leen los candidatos,
  se consulta y el resultado se aplica a cada vuelo en una transacción corta que **lo vuelve a leer**:
  si cambió de aeronave o de estado mientras tanto, no se toca. Una edición concurrente del admin la
  detecta `@Version` y el vuelo se revisa en la próxima corrida.
- **Cliente** (`HttpOpenSkyClient`, JDK `HttpClient`): timeouts de conexión 3 s y de respuesta 5 s.
  Si la respuesta no respeta el formato (no es JSON, `states` no es lista, una fila incompleta o con
  `on_ground` que no es booleano) se rechaza **entera**. Queda dentro de Flight Service y no en
  `aerogest-common`: solo lo usa este servicio.

### Fallas
Principio: **nunca se cierra un vuelo sin una respuesta válida que muestre la aeronave en el aire con
su callsign.** Ante cualquier falla el vuelo no cambia y sigue rigiendo RN-04 por horario.

| Falla | Job | Verificación manual |
|---|---|---|
| Sin conexión / caído / estado ≠ 200 | Log `WARN`, reintenta en la próxima corrida | 503 `OPENSKY_UNAVAILABLE` |
| Sin respuesta a tiempo | Ídem | 504 `OPENSKY_TIMEOUT` |
| Respuesta ininteligible | Ídem | 502 `OPENSKY_INVALID_RESPONSE` |
| Créditos agotados (429) | No vuelve a consultar hasta `X-Rate-Limit-Retry-After-Seconds` (o un intervalo) | 503 `OPENSKY_RATE_LIMITED`, sin gastar otra consulta |
| `states: null` (nadie transmite) | `NOT_SEEN`: no cambia nada | Ídem |

## Alternativas descartadas
- **`/flights/departure?airport=...`**: OpenSky lo arma en lotes nocturnos; no sirve para saber si un
  vuelo despegó ahora.
- **Recuadro alrededor del aeropuerto + callsign**: necesita una tabla de coordenadas por IATA, cuesta
  1 a 3 créditos por aeropuerto y no ve el avión cuando ya salió del recuadro.
- **Consultar OpenSky al reservar**: haría depender cada reserva de un servicio externo con límite de
  uso; un rechazo por falla de OpenSky sería un error de negocio falso.
- **OAuth2 client credentials** (4000 créditos/día): no hace falta con 288 consultas por día y obliga
  a cada persona que levante el proyecto a registrarse. Queda como mejora.
- **Completar el vuelo al aterrizar** (`CLOSED → COMPLETED`): no protege ninguna regla de reservas.
  Queda como trabajo futuro.

## Consecuencias
- El admin tiene que cargar `callsign` y `aircraftIcao24` para que un vuelo se verifique. Sin ellos,
  el vuelo funciona como antes (RN-04 por horario).
- Un vuelo cerrado por despegue se puede reabrir a mano (`CLOSED → SCHEDULED`); si la aeronave sigue
  en el aire con ese callsign, el job lo vuelve a cerrar.
- Cambiar la aeronave de un vuelo descarta su última verificación; un `PUT` sin los dos campos la quita.
- Requiere salida a Internet desde el contenedor de Flight Service. OpenSky queda fuera del despliegue
  de Docker Compose, como permite el enunciado.
- Pruebas: cliente contra un servidor HTTP falso (12), regla (5), dominio (6), manejo de 429 y de
  vuelos que cambian durante la consulta (4) e integración con PostgreSQL real y OpenSky simulado (17).
  Verificación en vivo contra OpenSky real en [evidencias/opensky-en-vivo.md](../evidencias/opensky-en-vivo.md).
