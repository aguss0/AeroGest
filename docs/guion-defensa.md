# Guion de defensa oral

Duración estimada: 20 minutos de presentación y demostración, más preguntas.

## Antes de la defensa (15 minutos antes)
1. Abrir Docker Desktop y ejecutar `docker compose --profile apps up -d`. Keycloak tarda hasta 3 minutos.
2. Verificar que responden `http://localhost:8180/realms/aerogest`, `http://localhost:8081/actuator/health`
   y `http://localhost:8082/actuator/health`.
3. En una terminal: `. .\scripts\load-env.ps1`.
4. Dejar abiertos: este guion, `docs/arquitectura.md` (diagramas), Postman con la colección importada
   y el entorno completo, y una terminal con `docker compose logs -f reservation-service flight-service`.
5. Ensayo rápido: `.\scripts\smoke-reservations.ps1` debe terminar con 0 fallidas.

## 1. El problema (2 min)
- AeroGest administra vuelos y reservas. El desafío no es el CRUD: es **no confirmar reservas inválidas**
  cuando las plazas y las reservas viven en dos servicios con bases separadas.
- Tres amenazas: dos personas por la última plaza al mismo tiempo, el mismo pasajero reservando dos
  veces, y Flight Service que se cae o no responde a mitad de una reserva.
- Fuera de alcance: pagos, check-in, asientos individuales (solo cantidad de plazas).

## 2. Arquitectura (3 min) — `docs/arquitectura.md` §3
- Dos microservicios, cada uno dueño de su base. Reservation nunca lee la base de Flight.
- Flight es la fuente de verdad de las plazas; Reservation orquesta la reserva.
- Keycloak emite los tokens; los servicios solo validan la firma. Tres roles: ADMIN, USER, SERVICE.
- Reservation llama a la API interna de Flight con su propio token de servicio (client credentials).

## 3. Proceso de reserva (4 min) — diagrama de secuencia §4
Puntos a remarcar:
1. La reserva nace `PENDING` con un id propio, que viaja a Flight como clave de idempotencia.
2. Flight ocupa la plaza con **un solo `UPDATE` condicionado** (`seats_taken < capacity`).
   PostgreSQL serializa la fila: el segundo pedido ve la plaza ya ocupada y no actualiza nada.
   Una consulta previa de disponibilidad no alcanzaría: dos consultas simultáneas ven el mismo lugar libre.
3. La llamada HTTP queda fuera de las transacciones.
4. Si el resultado es incierto, se libera la plaza y se rechaza; si ni eso se puede, queda `PENDING`
   y un job la resuelve. **Nunca se confirma sin validar (RN-10).**

## 4. Demostración (8 min)

| Paso | Qué mostrar | Qué se ve |
|---|---|---|
| 1 | Postman, carpeta 00 | Tokens de admin, user1, user2 y servicio |
| 2 | Carpeta 03: `user1 reserva a Ana` | 201 `CONFIRMED` y el `X-Correlation-Id` en la respuesta |
| 3 | `Flight descontó la plaza` | `availableSeats` bajó de 2 a 1: la integración es real |
| 4 | Swagger o Postman: el encabezado `X-Correlation-Id` de la respuesta | Cada solicitud tiene un id de trazabilidad (se usa en el paso 9) |
| 5 | `Misma reserva otra vez` y `user2 no ve la reserva de user1` | 409 `DUPLICATE_ACTIVE_RESERVATION`, 403 `ACCESS_DENIED` |
| 6 | Carpeta 04: cancelar dos veces | 200 y luego 409; la plaza vuelve a 2 una sola vez |
| 7 | Terminal: `.\scripts\smoke-reservations.ps1` (sección "Última plaza") | 10 reservas simultáneas: 1 confirmada, 9 `NO_AVAILABILITY`, 0 plazas |
| 8 | `docker stop aerogest-flight-service` y carpeta 07 | 503 `FLIGHT_SERVICE_UNAVAILABLE`; la reserva no queda `CONFIRMED` |
| 9 | `docker start …`, luego `docker pause …` y carpeta 08 | 504 `FLIGHT_SERVICE_TIMEOUT` en unos 3 segundos. Al final `docker unpause …`: en los logs aparece el mismo `correlationId` en los dos servicios |
| 10 | Swagger en `:8082/swagger-ui.html` | Contratos documentados y botón *Authorize* |

Plan B si algo falla en vivo: mostrar [evidencias/](evidencias/) con las salidas guardadas.

## 5. Calidad y pruebas (2 min)
- 185 pruebas automáticas: dominio, servicios con dobles, API completa contra PostgreSQL real
  (Testcontainers) y concurrencia. Verifican la respuesta HTTP y el efecto en la base.
- Matriz de trazabilidad: cada regla con su implementación y sus pruebas (`docs/trazabilidad.md`).
- Historial por incrementos: `git log --oneline`.

## 6. Limitaciones y trabajo futuro (1 min) — ADR 0003
- Cancelar un vuelo no cancela sus reservas automáticamente.
- No se impide cancelar después de la salida (no lo pide el enunciado).
- Carrera teórica entre una creación de más de 5 minutos y el job; los timeouts la hacen improbable.
- Pendiente con la cátedra: Keycloak como infraestructura y no como tercer microservicio.

## Preguntas probables

**¿Por qué el contador de plazas está en Flight y no en Reservation?**
Porque la capacidad es un dato del vuelo. Si Reservation contara las reservas confirmadas, la
validación cruzaría dos bases y no habría forma atómica de hacerla. En Flight es un `UPDATE` de una fila.

**¿Qué pasa si dos usuarios reservan la última plaza al mismo tiempo?**
Los dos `UPDATE` compiten por la misma fila. PostgreSQL los serializa: el primero pasa
`seats_taken` de 0 a 1 y el segundo, al reevaluar la condición, no actualiza ninguna fila y recibe
`NO_AVAILABILITY`. Probado con 20 solicitudes simultáneas en JUnit y con 10 en el smoke.

**¿Y si Flight asignó la plaza pero la respuesta se perdió?**
Reservation no sabe qué pasó, así que pide liberar esa reserva (por su id). Liberar es idempotente:
si había plaza la devuelve, y si no había deja una marca para rechazar una asignación tardía.
Recién entonces rechaza la reserva. Si Flight sigue caído, el job lo reintenta cada minuto.

**¿Por qué no usaron una transacción distribuida o una saga con colas?**
Una transacción distribuida (2PC) acopla los dos servicios y no está soportada por los clientes REST.
Una saga con mensajería agrega un broker que el MVP no necesita: la idempotencia por `reservationId`
más la compensación y el job resuelven lo mismo con menos piezas.

**¿Cómo evitan que un pasajero reserve dos veces el mismo vuelo?**
Con un índice único parcial en la base: `(passenger_id, flight_id) WHERE status IN ('PENDING','CONFIRMED')`.
La verificación previa da un mensaje claro, pero la garantía es el índice: en la prueba de 10 reservas
simultáneas, las 9 duplicadas las frenó la base, no la verificación previa.

**¿Cómo se autentica Reservation ante Flight?**
Con su propio token de Keycloak (client credentials, rol SERVICE). Lo cachea y lo renueva antes de
que venza. Flight solo acepta ese rol en `/internal/**`, y ese rol no puede usar la API pública.

**¿Cómo saben de quién es una reserva?**
Por el claim `sub` del token, que se guarda al crearla. No hay tabla de usuarios en Reservation.
ADMIN ve todo; un USER que pide una reserva ajena recibe 403.

**¿Cómo diagnostican un error entre servicios?**
Cada solicitud tiene un `X-Correlation-Id` que va en los logs de los dos servicios, se reenvía a
Flight y vuelve en el cuerpo de los errores. Ejemplo real en
[evidencias/consistencia-entre-bases.md](evidencias/consistencia-entre-bases.md).

**¿Qué pasa si la asignación y la liberación de la misma reserva llegan a Flight al mismo tiempo?**
Chocan en la clave primaria de `seat_allocations` (una fila por reserva) y Flight reintenta la que
perdió, que ya ve el resultado de la otra. Pasó de verdad en el escenario `docker pause` y el
resultado quedó consistente: está documentado en las evidencias.

**¿Por qué Keycloak y no JWT propios?**
Lo recomendó la cátedra y evita guardar contraseñas y firmar tokens en nuestro código. Los servicios
no comparten ningún secreto: validan con la clave pública de Keycloak (ADR 0002).
