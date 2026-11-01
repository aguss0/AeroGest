# Evidencias

Salidas reales de las pruebas, para consultar sin levantar el sistema. Se regeneran con los comandos
indicados; la fecha de cada corrida está en la primera línea de cada archivo.

| Archivo | Qué muestra | Cómo se regenera |
|---|---|---|
| [tests-flight-service.txt](tests-flight-service.txt) | Resumen de las 72 pruebas JUnit de Flight Service | `cd flight-service; .\mvnw.cmd test` |
| [tests-reservation-service.txt](tests-reservation-service.txt) | Resumen de las 113 pruebas JUnit de Reservation Service | `cd reservation-service; .\mvnw.cmd test` |
| [smoke-flights.txt](smoke-flights.txt) | Flight Service real: vuelos, seguridad, asignación y liberación de plazas | `.\scripts\smoke-flights.ps1` |
| [smoke-reservations.txt](smoke-reservations.txt) | Proceso completo con los dos servicios reales, la última plaza con 10 reservas simultáneas y los escenarios de falla (503 y 504) | `.\scripts\smoke-reservations.ps1 -IncludeFailures` |
| [consistencia-entre-bases.md](consistencia-entre-bases.md) | Cruce de las dos bases: ninguna reserva confirmada sin plaza y ninguna plaza retenida por una reserva no confirmada | Consultas SQL incluidas en el archivo |
| [postman.txt](postman.txt) | Ejecución de la colección Postman con newman: carpetas 00 a 06 (88 aserciones), 07 con Flight apagado y 08 con Flight congelado | `.\scripts\run-postman.ps1`; para 07 y 08, `-Folders "<nombre>"` con Flight detenido o pausado |

Los smoke tests y la colección necesitan el sistema levantado (`docker compose --profile apps up -d`)
y las variables cargadas (`. .\scripts\load-env.ps1`).
