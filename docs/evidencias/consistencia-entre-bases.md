# Consistencia entre las dos bases

Verificación hecha el 2026-11-01 sobre el sistema en Docker, después de correr varias veces
`smoke-flights.ps1` y `smoke-reservations.ps1 -IncludeFailures` (incluye Flight apagado y congelado).

## Invariante de Flight Service: plazas ocupadas = asignaciones activas

```sql
select count(*) as vuelos,
       count(*) filter (where f.seats_taken <> (select count(*) from seat_allocations s
                                                where s.flight_id = f.id and s.status = 'ALLOCATED')) as inconsistentes,
       sum(f.seats_taken) as plazas_ocupadas,
       (select count(*) from seat_allocations where status = 'ALLOCATED') as asignaciones_activas
from flights f;
```

```
 vuelos | inconsistentes | plazas_ocupadas | asignaciones_activas
--------+----------------+-----------------+----------------------
     15 |              0 |              12 |                   12
```

## Cruce de reservas con asignaciones (id por id)

| Comprobación | Resultado |
|---|---|
| Reservas `CONFIRMED` sin plaza asignada en Flight | **0** |
| Asignaciones activas de reservas `CONFIRMED` | 6 |
| Asignaciones activas sin reserva en Reservation Service | 6: las deja `smoke-flights.ps1`, que prueba la API interna con ids de reserva inventados (una por corrida) |
| Reservas `PENDING` que retienen una plaza | 0 (las 2 `PENDING` son de los escenarios de falla y su plaza ya se liberó) |

## Caso real de compensación con resultado incierto

En el escenario `docker pause` (Flight congelado), la asignación y la liberación de la reserva
`e2eee72d-c148-4fb8-a8cf-2cfec5e89e0d` quedaron encoladas y llegaron juntas al reanudar Flight.
Chocaron en la clave primaria de `seat_allocations` (el log de Flight muestra `duplicate key value
violates unique constraint "seat_allocations_pkey"`) y Flight reintentó. Ganó la liberación:

```
reservation_id                        | status   | allocated_at               | released_at
e2eee72d-c148-4fb8-a8cf-2cfec5e89e0d  | RELEASED | 2026-11-01 21:36:55.564292 | 2026-11-01 21:36:55.564292
```

`allocated_at = released_at` indica la marca que deja una liberación sin asignación previa, y la
asignación tardía se rechazó: el vuelo no quedó con una plaza ocupada por una reserva no confirmada
(ADR 0003, decisión 4). El mismo `correlationId` (`d010c62a-4720-434c-a1e6-d6128f2ae75f`) aparece en
los logs de los dos servicios para esa solicitud.
