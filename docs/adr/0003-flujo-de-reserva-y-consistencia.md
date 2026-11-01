# ADR 0003: Flujo de reserva, compensación y reconciliación

Estado: aprobado. Fecha: 2026-10-29. Precisa la decisión 1 y la 6 del ADR 0001.

## Contexto
Para confirmar una reserva, Reservation Service necesita que Flight Service ocupe una plaza. Son dos
bases distintas: no hay una transacción que abarque a las dos. Además, la llamada puede fallar de
dos formas muy distintas:

- **Rechazo definitivo:** Flight responde que no hay plazas, que el vuelo no admite reservas o que no existe.
- **Resultado incierto:** Flight no responde, tarda demasiado o responde algo inválido. La plaza
  *pudo* haber quedado ocupada (por ejemplo, Flight la asignó pero la respuesta se perdió).

RN-10 exige que en ningún caso se confirme una reserva sin validar.

## Decisión
1. **La reserva nace `PENDING` antes de llamar a Flight**, en una transacción corta que también
   verifica el pasajero (RN-09) y los duplicados (RN-06). Su id (UUID) lo genera la aplicación y se
   envía a Flight como **clave de idempotencia** (RN-15).
2. **La llamada a Flight queda fuera de toda transacción.** Una segunda transacción corta deja la
   reserva en `CONFIRMED` (plaza asignada) o `REJECTED` (rechazo definitivo).
3. **Ante un resultado incierto se compensa:** se pide a Flight liberar la plaza de esa reserva
   (idempotente) y, si responde, se marca `REJECTED`. Si Flight tampoco responde, la reserva queda
   `PENDING`. Al cliente se le responde 503, 504 o 502 según el caso.
4. **Liberar una reserva que nunca ocupó plaza deja una ficha `RELEASED`** en Flight. Si la asignación
   original llegara tarde, Flight la rechaza (`ALLOCATION_CONFLICT`), así la compensación es segura
   aunque no se sepa qué pasó con el primer pedido.
5. **Un job de reconciliación** corre cada minuto y resuelve las reservas `PENDING` de más de
   5 minutos con la misma compensación. Si Flight sigue caído, queda para la próxima ejecución.
6. **Rechazo por falta de plaza:** se responde 409 con el `code` de Flight (`NO_AVAILABILITY`,
   `FLIGHT_NOT_RESERVABLE`) o 404 (`FLIGHT_NOT_FOUND`), y la reserva queda guardada como `REJECTED`
   para dejar rastro del intento.
7. **Cancelación:** solo de reservas `CONFIRMED`. Primero se libera la plaza en Flight y recién después
   se marca `CANCELLED`. Si Flight no responde, la reserva sigue `CONFIRMED` y se puede reintentar.
8. **Modificaciones simultáneas** de una reserva se resuelven con bloqueo optimista (`@Version`):
   gana una y la otra recibe 409.

## Alternativas descartadas
- **Consultar disponibilidad antes de reservar:** no evita la carrera por la última plaza (dos
  consultas simultáneas ven la misma plaza libre). La garantía real es el `UPDATE` atómico.
- **Mantener la transacción abierta durante la llamada HTTP:** retiene una conexión y bloqueos
  mientras se espera a otro servicio, y aun así no da atomicidad entre las dos bases.
- **Saga con mensajería (colas):** resolvería lo mismo de forma asincrónica, pero agrega un broker
  y complejidad que el MVP no necesita. La idempotencia por `reservationId` cumple ese papel.
- **Responder 201 con la reserva `REJECTED`:** el enunciado pide rechazar la operación con un
  error comprensible. Un 409 con `code` es más claro para el cliente.

## Consecuencias
- Una reserva confirmada siempre tiene su plaza ocupada en Flight. Una plaza ocupada sin reserva
  confirmada solo puede existir de forma transitoria (hasta la compensación o el job).
- Todas las fallas posibles quedan cubiertas por pruebas automáticas (`ReservationServiceTest`,
  `ReservationApiIntegrationTest`, `HttpFlightGatewayTest`). Contra los servicios reales,
  `scripts/smoke-reservations.ps1 -IncludeFailures` prueba Flight apagado (503) y congelado (504).

## Limitaciones conocidas
- **Carrera teórica con el job:** si una creación tardara más de 5 minutos entre guardar la reserva
  y confirmarla, el job podría liberar su plaza al mismo tiempo. Con los timeouts configurados
  (2 s de conexión y 3 s de lectura) una creación termina en segundos, así que en la práctica no
  ocurre. Una solución completa sería que la confirmación verificara la asignación en Flight.
- **No se valida el horario al cancelar:** el enunciado no pide impedir cancelaciones después de la
  salida del vuelo, así que no se implementó.
- **Cancelar un vuelo no cancela sus reservas** (ADR 0001, decisión 5). Las reservas siguen
  `CONFIRMED` y el administrador debe gestionarlas.
- **Reservas `PENDING` visibles para el usuario:** si Flight no respondió, el usuario puede ver su
  reserva `PENDING` hasta 6 minutos, hasta que el job la rechaza.
