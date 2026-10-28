# ADR 0001: Decisiones de diseño de la Fase 1

Estado: aprobado (la decisión 2 fue reemplazada por el ADR 0002). Fecha: 2026-10-27.

## Contexto
AeroGest necesita consistencia de plazas entre dos servicios con bases de datos separadas, bajo concurrencia y fallos de red.

## Decisiones
1. **Contador de plazas en Flight Service.** La asignación es una sentencia `UPDATE ... WHERE seats_taken < capacity` atómica. Reservation Service orquesta el flujo (reserva en `PENDING` → asignación → `CONFIRMED` o `REJECTED`).
2. **Autenticación.** ~~Reservation Service emite JWT propios~~ **Reemplazada por el ADR 0002 (Keycloak).**
3. **Pasajeros.** Un USER registra varios pasajeros; el documento (tipo + número) es único globalmente (RN-14).
4. **Sin asientos individuales.** Solo se gestiona la cantidad de plazas.
5. **Vuelo cancelado.** Sus reservas no se cancelan automáticamente en el MVP (limitación documentada).
6. **Reconciliación.** Un job programado resuelve reservas `PENDING` que quedaron sin resolver por fallos de integración.
7. **Código de vuelo único global** (RN-01).

## Consecuencias
- La integración es necesaria: sin Flight Service no se puede confirmar (RN-10).
- Asignar y liberar son idempotentes por `reservationId` (RN-15), lo que permite reintentos seguros.

## Tecnología
Java 21, Spring Boot 4.1.x, Maven, PostgreSQL 16 (una instancia por servicio), Flyway, springdoc-openapi 3.x, JUnit, Mockito, Testcontainers y Docker Compose.
