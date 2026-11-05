# Evidencias

Salidas reales de las pruebas, para consultar sin levantar el sistema. Se regeneran con los comandos
indicados; la fecha de cada corrida está en la primera línea de cada archivo.

| Archivo | Qué muestra | Cómo se regenera |
|---|---|---|
| [arranque-limpio.txt](arranque-limpio.txt) | Sistema completo desde cero (volúmenes borrados, imágenes reconstruidas): tiempos hasta estar listo, `docker compose ps`, que 8081/8082 no se publican y la verificación del login OIDC de Swagger (authorization code + PKCE) | `docker compose down -v`; `docker compose up -d --build` |
| [tests-common.txt](tests-common.txt) | Las 11 pruebas JUnit de `aerogest-common` | `cd common; .\mvnw.cmd install` |
| [tests-flight-service.txt](tests-flight-service.txt) | Las 113 pruebas JUnit de Flight Service (incluye OpenSky con servidor falso y doble) | `cd flight-service; .\mvnw.cmd test` |
| [tests-reservation-service.txt](tests-reservation-service.txt) | Las 113 pruebas JUnit de Reservation Service | `cd reservation-service; .\mvnw.cmd test` |
| [tests-api-gateway.txt](tests-api-gateway.txt) | Las 11 pruebas JUnit del API Gateway (rutas, Security Filter, 503/504) | `cd api-gateway; .\mvnw.cmd test` |
| [smoke-reservations.txt](smoke-reservations.txt) | Por el gateway: proceso completo, la última plaza con 10 reservas simultáneas y los escenarios de falla (503 y 504) | `.\scripts\smoke-reservations.ps1 -IncludeFailures` |
| [smoke-flights.txt](smoke-flights.txt) | Flight Service directo (con `docker-compose.debug.yml`): vuelos, seguridad y la API interna de plazas | `.\scripts\smoke-flights.ps1` |
| [postman.txt](postman.txt) | Colección Postman con newman, todo por el gateway: carpetas 00 a 06, 07 con Flight apagado, 08 con Flight congelado y 09 contra OpenSky real | `.\scripts\run-postman.ps1`; para 07 a 09, `-Folders "<nombre>"` |
| [opensky-en-vivo.md](opensky-en-vivo.md) | RN-16 contra OpenSky real: un vuelo con una aeronave en el aire se cierra y rechaza reservas; logs del endpoint y del job | Carpeta 09 de Postman o los pasos del archivo |
| [consistencia-entre-bases.md](consistencia-entre-bases.md) | Cruce de las dos bases: ninguna reserva confirmada sin plaza y ninguna plaza retenida por una reserva no confirmada (corrida anterior al gateway; las bases y la regla no cambiaron) | Consultas SQL incluidas en el archivo |

Los smoke tests y la colección necesitan el sistema levantado (`docker compose up -d`)
y las variables cargadas (`. .\scripts\load-env.ps1`).
