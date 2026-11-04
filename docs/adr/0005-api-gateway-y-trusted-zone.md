# ADR 0005: API Gateway, Security Filter y trusted zone

Estado: aprobado. Fecha: 2026-11-03. Complementa el ADR 0002.

## Contexto
El enunciado exige un API Gateway como punto de entrada, que enrute a los microservicios, tenga
un Security Filter acorde al esquema de autenticación y evite que los consumidores conozcan la
topología interna. Hasta ahora el cliente llamaba directo a Flight (8081) y a Reservation (8082).

## Decisión
- **Spring Cloud Gateway (WebFlux)** en `api-gateway/`, puerto 8080. Es el **único servicio de
  aplicación que publica un puerto** en Docker Compose; los microservicios solo usan `expose` dentro
  de la red de Compose.
- **Rutas**:

  | Entrada | Destino |
  |---|---|
  | `/api/v1/flights/**` | `flight-service:8081` |
  | `/api/v1/passengers/**`, `/api/v1/reservations/**` | `reservation-service:8082` |
  | `/api-docs/flights`, `/api-docs/reservations` | `/v3/api-docs/public` de cada servicio |
  | `/swagger-ui.html` | Swagger único del gateway con los dos documentos |
  | `/internal/**` y cualquier otra ruta | **denegada** (401 sin token, 403 con token) |

- **Security Filter** (`SecurityConfig` del gateway): es *resource server* igual que los servicios.
  Valida la firma con el JWKS de Keycloak, el *issuer* y el vencimiento, y autoriza por rol y ruta
  con la misma matriz que los servicios: GET de vuelos USER/ADMIN, escritura de vuelos ADMIN,
  pasajeros y reservas USER/ADMIN. El rol `SERVICE` no entra a nada por el gateway. Los roles se
  leen con el `KeycloakRealmRoleConverter` del componente común (ADR 0004).
- **Token relay**: el gateway reenvía el encabezado `Authorization` tal cual. No lo cambia ni agrega
  encabezados de identidad.
- **Correlation id**: un `WebFilter` previo a la seguridad fija `X-Correlation-Id` (respeta el del
  cliente o genera uno), lo reenvía al servicio, lo devuelve y deja una línea de log por solicitud.
- **Errores propios del gateway** con el formato común (`ProblemResponses`): 401 `UNAUTHENTICATED`,
  403 `ACCESS_DENIED`, 503 `UPSTREAM_UNAVAILABLE` (servicio caído), 504 `UPSTREAM_TIMEOUT` (no
  respondió en `GATEWAY_RESPONSE_TIMEOUT`, 15 s). Los errores de los servicios se reenvían sin cambios.
- **Documentación**: cada servicio divide su OpenAPI en grupos. El gateway publica solo el grupo
  `public` (`/api/**`); el de Flight `internal` se ve únicamente entrando directo al servicio. El
  servidor de cada documento es relativo (`/`), así "Try it out" funciona desde el gateway.
- **Versión**: el gateway usa Spring Boot 4.0.9 con Spring Cloud 2025.1.4, la última versión estable.
  La que soporta Boot 4.1 (2026.0) estaba en RC al implementarlo. Cada servicio se despliega por
  separado, así que puede tener su propia versión de Boot.

### Trusted zone
- **Tráfico de entrada (cliente → sistema)**: solo por el gateway. Primera barrera: token válido y
  rol adecuado para la ruta.
- **Tráfico interno (Reservation → Flight)**: directo por la red de Compose, sin pasar por el gateway,
  y autenticado con el token `client_credentials` del rol `SERVICE` (ADR 0002). Que el tráfico venga
  de adentro de la red no alcanza: Flight sigue exigiendo el rol.
- **Segunda barrera en los servicios**: Flight y Reservation **siguen validando el JWT** y sus roles,
  y Reservation decide el acceso a cada pasajero y reserva por el `sub` (RN-09). Esto último el gateway
  no lo puede hacer, porque depende de los datos.
- **Keycloak** queda fuera del gateway: es el IdP y el cliente obtiene el token directo de él (`:8180`).

## Alternativas descartadas
- **Que los servicios confíen en encabezados puestos por el gateway** (`X-User-Id`, `X-Roles`) sin
  validar el token: cualquier contenedor de la red podría falsificarlos, y se perdería la segunda
  barrera. Validar un JWT localmente es barato (la clave pública queda en caché).
- **Que el gateway solo enrute y toda la seguridad quede en los servicios**: no cumple el Security
  Filter exigido, y las solicitudes sin token llegarían igual hasta los servicios.
- **Enrutar también el tráfico interno por el gateway**: suma un salto y un punto de falla al proceso
  de reserva, y obligaría a publicar `/internal/**`.
- **Spring Cloud Gateway sobre WebMVC**: también es válido. Se eligió WebFlux porque es la variante
  más difundida y aísla mejor al gateway de los servicios lentos (no bloquea un hilo por solicitud).
- **Usar la versión RC de Spring Cloud para alinear Boot 4.1**: no se usa una versión no estable en
  la pieza por la que entra todo el tráfico.

## Consecuencias
- Los clientes (Postman, scripts de humo, Swagger) usan una sola URL: `http://localhost:8080`.
- La API interna no se puede probar desde afuera. Para el diagnóstico existe
  `docker-compose.debug.yml`, que publica 8081 y 8082 solo en `127.0.0.1` y no forma parte del
  despliegue. Lo usa `scripts/smoke-flights.ps1`.
- La autorización por rol está en dos lugares (gateway y servicio). Agregar un endpoint implica
  revisar las dos matrices, y las pruebas de cada lado lo cubren.
- Si un servicio está caído, el gateway responde 503 `UPSTREAM_UNAVAILABLE`. Si Flight cae durante
  una reserva, la respuesta sigue siendo la de Reservation (503 `FLIGHT_SERVICE_UNAVAILABLE`, RN-10).
