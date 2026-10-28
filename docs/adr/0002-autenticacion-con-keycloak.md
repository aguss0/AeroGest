# ADR 0002: Autenticación y autorización con Keycloak

Estado: aprobado. Fecha: 2026-10-28. Reemplaza la decisión 2 del ADR 0001.

## Contexto
La decisión original hacía que Reservation Service guardara usuarios y emitiera JWT firmados con un secreto compartido. La cátedra recomendó usar Keycloak.

## Decisión
- Keycloak (contenedor `quay.io/keycloak/keycloak:26.8.1`) es el proveedor de identidad. Un realm `aerogest` con los roles `ADMIN`, `USER` y `SERVICE`, importado desde `keycloak/aerogest-realm.json`.
- Flight Service y Reservation Service son *resource servers*: validan la firma con la clave pública de Keycloak (`jwk-set-uri`) y el *issuer*. No comparten ningún secreto.
- Los roles se leen del claim `realm_access.roles` y se autorizan por endpoint.
- La propiedad de una reserva se determina por el claim `sub`. Reservation Service guarda ese valor, sin tabla de usuarios.
- Reservation Service llama a `/internal/**` de Flight Service con un token de *client credentials* (rol `SERVICE`), en lugar de una API key.
- Keycloak es infraestructura, no un microservicio de negocio propio: el sistema mantiene dos microservicios (pendiente de confirmar con la cátedra).

## Consecuencias
- Desaparecen `users`, `/auth/login`, `/auth/register`, `JWT_SECRET` e `INTERNAL_API_KEY`.
- El cliente `aerogest-api` habilita *password grant* solo para demostración y Postman.
- El *issuer* se fija con `KC_HOSTNAME=http://localhost:8180` para que los tokens sean válidos tanto desde el host como entre contenedores.
- Las pruebas automatizadas usan tokens simulados (`spring-security-test`); Keycloak se usa para la demo y la colección Postman.
- Keycloak en modo `start-dev` no persiste datos: el realm se reimporta en cada arranque limpio.
