# ADR 0004: Componente común reutilizable (`aerogest-common`)

Estado: aprobado. Fecha: 2026-11-03.

## Contexto
El enunciado pide al menos un componente Java reutilizable, empaquetado como JAR en un artefacto
Maven independiente, instalado como mínimo en el repositorio local (`.m2`) y usado como dependencia
por los dos microservicios. No alcanza con copiar código entre proyectos.

Flight Service y Reservation Service tenían código duplicado: `KeycloakRealmRoleConverter` y
`PageResponse` eran idénticos, `CorrelationIdFilter` casi idéntico, y los dos armaban a mano el
mismo formato de error (RFC 7807 + `code` + `correlationId`) en el manejador de errores y en los
401/403 de `SecurityConfig`.

## Decisión
- Artefacto `ar.edu.utn.frc.aerogest:aerogest-common:1.0.0` en `common/`. Es una biblioteca (un JAR
  común, no un JAR ejecutable de Spring Boot). Se instala con `mvn install` y los servicios lo
  declaran en su `pom.xml`.
- **Qué contiene**, porque es una convención de todo el sistema y no una regla de un servicio:

  | Paquete | Clase | Para qué |
  |---|---|---|
  | `common.security` | `KeycloakRealmRoleConverter` | `realm_access.roles` → `ROLE_xxx`. Lo usan los dos servicios **y el gateway** |
  | `common.web` | `CorrelationId` | Nombre del encabezado `X-Correlation-Id`, clave del MDC y cuándo generar uno nuevo |
  | `common.web` | `CorrelationIdFilter` | Filtro servlet que pone el id en el log y en la respuesta |
  | `common.web` | `PageResponse<T>` | Formato JSON estable de las páginas |
  | `common.web` | `ServletProblemWriter` | Escribe los 401/403 de Spring Security con el formato común |
  | `common.error` | `ProblemResponses`, `FieldIssue` | El formato único de error, para controladores, seguridad y gateway |
  | `common.autoconfigure` | `AerogestCommonAutoConfiguration` | Registra `CorrelationIdFilter` solo en aplicaciones servlet |

- **Qué queda en cada servicio, a propósito:**
  - `ErrorCode` y el mapeo de código a status HTTP: son el contrato propio de cada API.
  - `BusinessRuleException`, porque depende de ese `ErrorCode`.
  - `SecurityConfig`: la matriz de roles por endpoint es de cada servicio.
  - `@RestControllerAdvice`: cada servicio decide qué excepciones traduce y cómo.
  - El dominio, los DTOs y el cliente HTTP de Flight.

  Si estuvieran en el JAR, un cambio de negocio en un servicio obligaría a publicar una versión
  nueva del componente y a actualizar a todos sus consumidores.
- **Auto-configuración de Spring Boot** (`META-INF/spring/...AutoConfiguration.imports`): con solo
  agregar la dependencia, los servicios tienen el filtro de correlation id. La condición
  `@ConditionalOnWebApplication(SERVLET)` evita que se active en el gateway, que es reactivo, y
  `@ConditionalOnMissingBean` permite reemplazarlo.
- **Dependencias `provided`**: las clases de Spring las aporta cada consumidor con su versión. El
  componente se compila contra la versión de Spring Boot más baja que lo usa (4.0.x, la del gateway)
  y no usa nada específico de 4.1, así funciona en los tres.
- **Docker**: el contexto de construcción de las imágenes es la raíz del repositorio. Cada
  `Dockerfile` primero hace `mvn install` de `common/` en el `.m2` de la imagen y después compila
  el servicio: es el mismo mecanismo que en desarrollo y `docker compose up --build` sigue siendo
  reproducible desde cero.

## Alternativas descartadas
- **Copiar el código** en cada servicio: es justo lo que el enunciado excluye, y las copias divergen.
- **Un `@RestControllerAdvice` base abstracto en el JAR**: ahorraría unas 40 líneas, pero mete
  comportamiento web implícito en la dependencia y acopla la forma en que cada servicio traduce
  sus excepciones.
- **Pom agregador multi-módulo**: compila todo junto y oculta que el componente es un artefacto
  independiente que se instala y se consume por versión.
- **Copiar el JAR del `.m2` del host al construir la imagen**: la imagen dependería del estado de
  la máquina de quien la construye.

## Consecuencias
- Para correr un servicio con Maven hay que instalar antes el componente:
  `.\scripts\build-common.ps1` (o `cd common; .\mvnw.cmd install`).
- Un cambio en el componente obliga a reinstalarlo. Si rompe compatibilidad, se sube la versión
  y cada servicio la adopta cuando quiere.
- Las pruebas del componente (11) viven en `common/`. `KeycloakRealmRoleConverterTest` se movió
  ahí desde Flight Service.
