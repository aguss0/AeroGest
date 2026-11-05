# Matriz de trazabilidad

Vincula cada requisito con su implementación y con la evidencia que lo verifica. Abreviaturas:
**FS** = flight-service, **RS** = reservation-service, **PM** = colección Postman (carpeta),
**SR** = `scripts/smoke-reservations.ps1`, **SF** = `scripts/smoke-flights.ps1`.
Los nombres de las pruebas son los métodos JUnit; la salida de cada corrida está en [evidencias/](evidencias/).

## 1. Reglas de negocio

| Regla | Implementación | Pruebas automáticas | Contra el sistema real |
|---|---|---|---|
| RN-01 Código único | FS `FlightService.create`, restricción `uq_flights_code` | `FlightServiceTest.rechazaCodigoDuplicadoSinGuardar_RN01`, `…DetectadoPorLaBase_altasSimultaneas_RN01`, `FlightApiIntegrationTest.rechazaUnCodigoDuplicado_RN01` | PM 01, SF |
| RN-02 Horarios | FS `Flight.create/update`, `ck_flights_times` | `FlightTest.rechazaLlegadaAnteriorOIgualALaSalida_RN02`, `FlightApiIntegrationTest.rechazaHorariosInvalidos_RN02` | PM 01, SF |
| RN-03 Datos obligatorios y capacidad | FS DTOs con Bean Validation, `Flight`, `ck_flights_capacity` | `FlightTest.rechazaCapacidadCeroONegativa_RN03`, `FlightApiIntegrationTest.rechazaCapacidadInvalida_RN03` | PM 01, SF |
| RN-04 Vuelo no reservable | FS `Flight.isReservable` y `UPDATE` atómico condicionado | `FlightTest.soloEsReservableSiEstaScheduledYNoSalio_RN04`, `SeatAllocationIntegrationTest.unVueloCerradoNoAdmiteAsignaciones_RN04`, RS `ReservationApiIntegrationTest.vueloNoReservable_409_RN04` | PM 05, SR, SF |
| RN-05 Capacidad | FS `FlightRepository.allocateSeat` (`seats_taken < capacity`), `ck_flights_seats` | `SeatAllocationIntegrationTest.laUltimaPlazaSoloSeAsignaUnaVez_RN05`, `…veinteSolicitudesSimultaneasPorLaUltimaPlazaConfirmanSoloUna`, `…treintaSolicitudesSimultaneasConCapacidadCinco…`, RS `ReservationApiIntegrationTest.capacidadAgotada_409YReservaRechazada_RN05` | PM 06, SR (10 reservas simultáneas por la última plaza) |
| RN-06 Duplicados | RS `ReservationService.registerPending` e índice único parcial | `ReservationServiceTest.noPermiteUnaSegundaReservaActiva…_RN06`, `…elIndiceUnicoDeLaBaseFrenaDuplicadosSimultaneos_RN06`, `ReservationApiIntegrationTest.reservaActivaDuplicada_409_RN06`, `ReservationConcurrencyIntegrationTest.diezReservasSimultaneas…_RN06` | PM 03, SR |
| RN-07 Cancelación | RS `Reservation.cancel`, `ReservationService.cancel`; FS liberación idempotente | `ReservationTest.noSePuedeCancelarDosVeces_RN07`, `SeatAllocationIntegrationTest.liberarDevuelveLaPlazaUnaSolaVez_RN07`, `…diezLiberacionesSimultaneas…_RN07`, RS `ReservationApiIntegrationTest.cancelacionCorrectaLiberaLaPlaza`, `…cancelacionRepetida_409`, `ReservationConcurrencyIntegrationTest.diezCancelacionesSimultaneas…_RN07` | PM 04, SR |
| RN-08 Estados | FS `FlightStatus`, RS `ReservationStatus` | `FlightStatusTest` (4), `FlightTest.rechazaCambioDeEstadoInvalido_RN08`, `ReservationStatusTest` (5), `ReservationTest.unaReservaCanceladaNoSeReactiva_RN08`, `FlightApiIntegrationTest.rechazaUnCambioDeEstadoInvalidoYNoModificaLosDatos` | PM 01, SF |
| RN-09 Seguridad | FS y RS `SecurityConfig`, `KeycloakRealmRoleConverter`; RS `CurrentUser.canAccess` | `FlightApiIntegrationTest.sinCredencialesDevuelve401`, `…unUsuarioSinPermisoNoPuedeCrearVuelos_403`, `…losEndpointsInternosSoloAdmitenElRolService`, RS `ReservationApiIntegrationTest.unUsuarioNoPuedeVerNiCancelarUnaReservaAjena_403`, `…unUsuarioNoPuedeReservarConUnPasajeroAjeno_403`, `…elRolServiceNoTieneAccesoALaApiPublica_403`, `PassengerServiceTest.unUsuarioNoPuedeVerNiModificarPasajerosAjenos` | PM 01 a 04, SR, SF |
| RN-10 Errores de integración | RS `HttpFlightGateway`, `FlightServiceException`, compensación en `ReservationService`, `PendingReservationReconciler` | `HttpFlightGatewayTest` (25: caída, timeout, 5xx, cuerpo inválido, 401/403), `ReservationServiceTest.anteUnFalloDeIntegracion…_RN10`, `…siTampocoSePuedeLiberar…_RN10`, `ReservationApiIntegrationTest.flightFueraDeServicio_503…`, `…timeoutDeFlight_504_RN10`, `…respuestaInvalidaDeFlight_502_RN10` | PM 07 y 08 (manuales), SR `-IncludeFailures` |
| RN-11 Origen ≠ destino | FS `Flight`, `ck_flights_route` | `FlightTest.rechazaOrigenIgualADestino_RN11`, `FlightApiIntegrationTest.rechazaOrigenIgualADestino_RN11` | PM 01 |
| RN-12 Salida futura | FS `Flight.create` | `FlightTest.rechazaSalidaEnElPasado_RN12`, `FlightApiIntegrationTest.rechazaUnaSalidaEnElPasado_RN12` | — |
| RN-13 Capacidad ≥ ocupadas | FS `Flight.update` | `FlightTest.noPermiteReducirLaCapacidadPorDebajoDeLasPlazasOcupadas_RN13` | — |
| RN-14 Documento único | RS `DocumentType.normalize`, `PassengerService.register`, `uq_passengers_document` | `PassengerTest.normalizaElDocumento…_RN14`, `PassengerServiceTest.rechazaUnDocumentoYaRegistrado_RN14`, `…traduceLaRestriccionUnica…_RN14`, `ReservationApiIntegrationTest.registraUnPasajeroYRechazaElDocumentoDuplicado_RN14` | PM 02, SR |
| RN-15 Idempotencia | FS PK `seat_allocations.reservation_id`, `SeatInventoryService` | `SeatInventoryServiceTest.unReintentoDeLaMismaReservaNoOcupaOtraPlaza_RN15`, `SeatAllocationIntegrationTest.repetirLaAsignacionNoOcupaOtraPlaza_RN15`, `…diezSolicitudesSimultaneasDeLaMismaReserva…_RN15` | SF |
| RN-16 Salida real (OpenSky) | FS `DepartureCheckResult.evaluate`, `DepartureVerificationService`, `DepartureVerificationJob`, `Flight.assignAircraft/registerDetectedDeparture`, `HttpOpenSkyClient`, migración `V2` (ADR 0006) | `DepartureCheckResultTest` (5), `FlightTest.elDespegueDetectadoCierraElVueloAunqueLaSalidaProgramadaSeaFutura_RN16`, `…callsignEIcao24VanJuntos_RN16`, `DepartureVerificationIntegrationTest.siLaAeronaveEstaEnElAireConSuCallsignElVueloSeCierraYNoAceptaReservas_RN16`, `…elJobConsultaUnaSolaVezLosVuelosDeLaVentanaYCierraLosQueDespegaron`, `…siOpenSkyFallaElJobNoCambiaNingunVuelo`, `DepartureVerificationServiceTest.con429SeSalteanLasCorridasHastaElTiempoIndicado`, `HttpOpenSkyClientTest` (12) | PM 09; [evidencias/opensky-en-vivo.md](evidencias/opensky-en-vivo.md) (OpenSky real) |

## 2. Escenarios de prueba del proceso central

Lista de escenarios que se fijó en la Fase 1 (enunciado anterior). Siguen vigentes como cobertura del
proceso de reserva; los requisitos del enunciado 2026 están en la sección 4.

| Escenario | Prueba automática | Evidencia funcional |
|---|---|---|
| Vuelo: alta correcta | `FlightApiIntegrationTest.elAdminCreaUnVueloYQuedaPersistido` | PM 01 |
| Vuelo: código duplicado | `FlightApiIntegrationTest.rechazaUnCodigoDuplicado_RN01` | PM 01 |
| Vuelo: horarios inválidos | `FlightApiIntegrationTest.rechazaHorariosInvalidos_RN02` | PM 01 |
| Vuelo: capacidad inválida | `FlightApiIntegrationTest.rechazaCapacidadInvalida_RN03` | PM 01 |
| Vuelo: consulta de inexistente | `FlightApiIntegrationTest.consultarUnVueloInexistenteDevuelve404` | PM 01 |
| Vuelo: cambio de estado válido e inválido | `…permiteUnCambioDeEstadoValido`, `…rechazaUnCambioDeEstadoInvalidoYNoModificaLosDatos` | PM 01, PM 05 |
| Reserva: creación correcta | `ReservationApiIntegrationTest.creaUnaReservaConfirmada` | PM 03, SR |
| Reserva: vuelo inexistente | `ReservationApiIntegrationTest.vueloInexistente_404` | PM 03, SR |
| Reserva: vuelo cancelado o no reservable | `ReservationApiIntegrationTest.vueloNoReservable_409_RN04` | PM 05, SR |
| Reserva: capacidad agotada | `ReservationApiIntegrationTest.capacidadAgotada_409YReservaRechazada_RN05` | PM 06, SR |
| Reserva: activa duplicada | `ReservationApiIntegrationTest.reservaActivaDuplicada_409_RN06` | PM 03, SR |
| Reserva: cancelación correcta | `ReservationApiIntegrationTest.cancelacionCorrectaLiberaLaPlaza` | PM 04, SR |
| Reserva: cancelación repetida | `ReservationApiIntegrationTest.cancelacionRepetida_409` | PM 04, SR |
| Reserva: liberación de disponibilidad | `SeatAllocationIntegrationTest.liberarDevuelveLaPlazaUnaSolaVez_RN07` | PM 04 (`availableSeats` 1 → 2), SR |
| Reserva: concurrencia sobre la última plaza | `SeatAllocationIntegrationTest.veinteSolicitudesSimultaneasPorLaUltimaPlazaConfirmanSoloUna` | SR: 10 reservas simultáneas → 1 confirmada, 9 rechazadas, 0 plazas |
| Seguridad: sin credenciales | `FlightApiIntegrationTest.sinCredencialesDevuelve401`, `ReservationApiIntegrationTest.sinCredencialesDevuelve401` | PM 01, PM 03 |
| Seguridad: permisos insuficientes | `FlightApiIntegrationTest.unUsuarioSinPermisoNoPuedeCrearVuelos_403`, `ReservationApiIntegrationTest.elRolServiceNoTieneAccesoALaApiPublica_403` | PM 01, PM 03 |
| Seguridad: reserva ajena | `ReservationApiIntegrationTest.unUsuarioNoPuedeVerNiCancelarUnaReservaAjena_403` | PM 03, PM 04, SR |
| Integración: Flight fuera de servicio | `ReservationApiIntegrationTest.flightFueraDeServicio_503YNuncaConfirmada_RN10`, `HttpFlightGatewayTest.conexionRechazadaCuandoFlightEstaCaido` | PM 07, SR `-IncludeFailures` (`docker stop`) |
| Integración: timeout o respuesta inválida | `HttpFlightGatewayTest.flightQueTardaDemasiadoProvocaTimeout`, `…cuerpoQueNoEsJsonEsRespuestaInvalida`, `ReservationApiIntegrationTest.timeoutDeFlight_504_RN10`, `…respuestaInvalidaDeFlight_502_RN10` | PM 08, SR `-IncludeFailures` (`docker pause`) |

Las pruebas de integración verifican la respuesta HTTP **y** el efecto en la base (consultas JDBC
directas): por ejemplo, que tras un 503 no quede ninguna reserva `CONFIRMED`.

## 3. Criterios de evaluación

Criterios de `Rubrica_General_TPI_2026_v05` (propuesta 0.11, ponderaciones pendientes de aprobación
docente). Esta tabla no afirma que un criterio esté cumplido: indica qué evidencia lo respalda y
cómo se verifica en la defensa.

| Criterio | Evidencia | Cómo se verifica |
|---|---|---|
| ARQ-01 Arquitectura y diseño de microservicios | Gateway + dos servicios con base propia; Reservation no accede a la base de Flight; capas controller/service/domain/repository; C4 C1 y C2; ADRs 0001 a 0006 | `docs/arquitectura.md` §3; propuesta v2 §05 y §06; `docker-compose.yml`; mostrar que `reservation-service` no tiene configuración de `flights_db` |
| SEG-01 Seguridad y control de acceso | Keycloak (OIDC: authorization code + PKCE en Swagger, client credentials entre servicios); Security Filter en el gateway; resource servers; propiedad por `sub`; 401/403 con formato uniforme; secretos en `.env` | PM 00 a 04; pruebas `…401`, `…403`, `…Ajena_403`, `ApiGatewayIntegrationTest`; `.env.example` sin secretos reales; matriz de permisos en `arquitectura.md` §2 |
| TEC-01 Calidad técnica | DTOs, validación, manejo central de errores (RFC 7807 + `code`), transacciones cortas, reglas en el dominio; 248 pruebas | `.\mvnw.cmd test` en cada proyecto; [evidencias/](evidencias/) |
| INF-01 Persistencia, reutilización y despliegue | Migraciones Flyway versionadas; restricciones que garantizan reglas; componente común; Dockerfiles multi-etapa; Compose con el sistema completo | `docker compose up -d --build` desde cero; `\dt` en cada base |
| NEG-01 Proceso de negocio central | Reserva `PENDING` → plaza en Flight → `CONFIRMED`/`REJECTED`; cancelación que libera la plaza | Diagrama de secuencia; PM 03 y 04; SR |
| NEG-02 Reglas de negocio | RN-01 a RN-16 implementadas y probadas | Sección 1 de esta matriz |
| INT-01 Integración entre servicios y con sistemas externos | API interna con token de servicio, idempotencia, timeouts, clasificación de errores, compensación, reconciliación, correlation id; OpenSky con tolerancia a fallas | `HttpFlightGatewayTest`, `HttpOpenSkyClientTest`; SR `-IncludeFailures`; PM 09; logs de los dos servicios con el mismo `correlationId` |
| ALC-01 Cumplimiento del alcance | Requisitos del enunciado (sección 4) y compromiso de la propuesta v2 (sección 5); pendientes y cambios registrados (sección 6) | Secciones 4 a 6 de esta matriz |
| DES-01 Complejidad y desafío | Consistencia entre dos bases sin transacción distribuida; concurrencia por la última plaza; resultados inciertos | ADR 0003; pruebas de concurrencia; demostración con `docker pause` |
| PRE-01 Presentación y defensa | Guion con tiempos, reparto entre integrantes, plan B y preguntas probables | [guion-defensa.md](guion-defensa.md) |
| API-01 Colección de llamadas y pruebas | Colección organizada por carpetas, parametrizada por entorno, con tests en cada solicitud y datos únicos por corrida; todo por el gateway | `.\scripts\run-postman.ps1`; [evidencias/postman.txt](evidencias/postman.txt) |
| DOC-01 Evolución y proceso de trabajo | Historial de commits por fase y por incremento; ADRs con fecha; cambios de la propuesta v1 → v2 | `git log --oneline`; propuesta v2, tabla de cambios |

## 4. Requisitos de arquitectura del enunciado 2026

Una fila por requisito del enunciado preliminar del 2026-10-05.

| Requisito | Implementación | Pruebas automáticas | Contra el sistema real |
|---|---|---|---|
| Al menos dos microservicios funcionales Java/Spring Boot con responsabilidad propia | `flight-service` (vuelos, estados, inventario de plazas) y `reservation-service` (pasajeros, reservas, orquestación); límites justificados en ADR 0001 | 113 + 113 pruebas, cada servicio con las suyas | `docker compose up -d --build` |
| Colaboración real en un proceso de negocio | La reserva: Reservation pide la plaza a Flight y confirma o rechaza; cancelación y reconciliación liberan la plaza (ADR 0003) | `ReservationApiIntegrationTest`, `ReservationConcurrencyIntegrationTest`, `HttpFlightGatewayTest` | PM 03 a 08; SR |
| Qué hacer cuando otro componente no responde | Timeouts, clasificación de errores, compensación, `PENDING` + reconciliación (Reservation → Flight); 503/504 del gateway; OpenSky sin respuesta no cambia nada | `…flightFueraDeServicio_503…`, `…timeoutDeFlight_504_RN10`, `…servicioCaido_503ConElFormatoComun`, `…siOpenSkyFallaElJobNoCambiaNingunVuelo` | PM 07 y 08; SR `-IncludeFailures` |
| API Gateway como punto de entrada, sin exponer la topología | `api-gateway/` (rutas en `application.yml`); en Compose solo el gateway publica puerto (ADR 0005) | `ApiGatewayIntegrationTest.unUsuarioConsultaVuelosYLaSolicitudLlegaAFlightConElCorrelationId`, `…laDocumentacionEsPublicaYSoloExponeElGrupoPublicoDelServicio` | PM 00 a 08 y SR, todos por `:8080` |
| Security Filter en el gateway | `SecurityConfig` del gateway (JWT de Keycloak, rol por ruta, `/internal/**` denegado) | `…sinTokenDevuelve401ConElFormatoComunYNoLlegaAlServicio`, `…soloElAdminCreaVuelos`, `…laApiInternaNoSePublicaNiSiquieraParaElRolService`, `…elRolServiceNoTieneAccesoALaApiPublica`, `…unaRutaNoPublicadaSeDeniega` | PM 01 (API interna → 403), SR (sección Seguridad) |
| Gateway ante un servicio caído o lento | `GatewayErrorHandler`: 503 `UPSTREAM_UNAVAILABLE`, 504 `UPSTREAM_TIMEOUT` | `…servicioCaido_503ConElFormatoComun`, `…servicioQueNoRespondeATiempo_504` | `docker stop aerogest-reservation-service` y cualquier llamada a `/api/v1/reservations` |
| Trusted zone | Doble validación del JWT (gateway y servicios), `sub` en Reservation, token `SERVICE` en la llamada interna (ADR 0002 y 0005) | Las pruebas de seguridad de la sección 1 (RN-09) siguen vigentes en cada servicio | SR |
| API pública externa con uso genuino de negocio | OpenSky Network: FS `HttpOpenSkyClient`, `DepartureVerificationService`, `DepartureVerificationJob`, `POST /api/v1/flights/{id}/departure-check`; regla RN-16 (ADR 0006) | `HttpOpenSkyClientTest` (formato, 429, timeout, caída, JSON inválido), `DepartureVerificationIntegrationTest` (17), `DepartureVerificationServiceTest` (4) | PM 09; [evidencias/opensky-en-vivo.md](evidencias/opensky-en-vivo.md) |
| Keycloak como IdP: OIDC, JWT, Spring Security, roles, autenticación y autorización | Realm `aerogest` (`keycloak/aerogest-realm.json`) con roles ADMIN, USER, SERVICE; Swagger con authorization code + PKCE; client credentials entre servicios; gateway y servicios como resource servers (ADR 0002 y 0005) | `KeycloakRealmRoleConverterTest` (3); pruebas de seguridad de la sección 1 (RN-09) y del gateway | PM 00 (tokens reales de Keycloak); Swagger *Authorize → keycloak* |
| Integración de la seguridad con el API Gateway | Security Filter del gateway con el mismo conversor de roles del JAR común; token relay | Ver "Security Filter en el gateway" | PM 01 a 04 |
| Componente común en JAR Maven usado por los dos servicios | `common/` → `aerogest-common:1.0.0` en `.m2`; dependencia en los `pom.xml` de los dos servicios y del gateway (ADR 0004) | `common/`: `KeycloakRealmRoleConverterTest` (3), `CorrelationIdFilterTest` (2), `ProblemResponsesTest` (3), `AerogestCommonAutoConfigurationTest` (3) | `docker compose build`: cada imagen instala el JAR antes de compilar el servicio |
| Persistencia en un DBMS relacional, con estrategia justificada | Una base PostgreSQL 16 por servicio, esquema con Flyway (`V1`, `V2` en Flight), restricciones que garantizan reglas (`arquitectura.md` §6) | Pruebas de integración contra PostgreSQL real (Testcontainers) en los dos servicios | `\dt` en cada base; [evidencias/consistencia-entre-bases.md](evidencias/consistencia-entre-bases.md) |
| APIs REST: métodos y códigos HTTP coherentes, JSON, validación, errores | Controladores + DTOs con Bean Validation; RFC 7807 con `code` y `correlationId` (formato común del JAR) | `FlightApiIntegrationTest` (17), `ReservationApiIntegrationTest`, `ProblemResponsesTest` | PM 01 a 09 (cada solicitud verifica status y `code`) |
| Documentación OpenAPI / Swagger de la API implementada | springdoc en cada servicio (grupo `public`); Swagger único en el gateway (`/swagger-ui.html`) | `…laDocumentacionEsPublicaYSoloExponeElGrupoPublicoDelServicio` | `http://localhost:8080/swagger-ui.html` |
| Contenerización y Docker Compose del sistema completo | `docker-compose.yml`: dos PostgreSQL, Keycloak, Flight, Reservation y gateway; Dockerfiles multi-etapa; `.env.example` | — | Arranque desde cero (`down -v` + `up --build`): [evidencias/arranque-limpio.txt](evidencias/arranque-limpio.txt) |
| Diagramas C4 C1 y C2 que representen lo implementado | Propuesta v2 §05 y §06 (C4-PlantUML); fuentes en [diagramas/](diagramas/) | — | Comparar el C2 con `docker compose ps` |

## 5. Compromiso: criterios de verificación de la propuesta v2

| Criterio | Funcionalidades / reglas | Evidencia automatizada | Evidencia contra el sistema real |
|---|---|---|---|
| CV-1 Reserva confirmada, plaza descontada, mismo correlation id en los tres componentes | F-07, F-10, F-14, RN-04, RN-05 | `ReservationApiIntegrationTest.creaUnaReservaConfirmada`, `ApiGatewayIntegrationTest.unUsuarioConsultaVuelosYLaSolicitudLlegaAFlightConElCorrelationId` | PM 03; logs (guion, paso 9) |
| CV-2 10 reservas simultáneas por la última plaza | F-07, F-10, RN-05 | `SeatAllocationIntegrationTest.veinteSolicitudesSimultaneas…`, `ReservationConcurrencyIntegrationTest` | SR |
| CV-3 Cancelar dos veces | F-09, RN-07 | `…cancelacionCorrectaLiberaLaPlaza`, `…cancelacionRepetida_409` | PM 04 |
| CV-4 Duplicada y vuelo cancelado | F-03, F-07, RN-04, RN-06, RN-08 | `…reservaActivaDuplicada_409_RN06`, `…vueloNoReservable_409_RN04` | PM 03 y 05 |
| CV-5 Accesos denegados (ajeno, SERVICE, API interna, sin token) | F-13, F-14, RN-09 | Sección 1 (RN-09) y sección 4 (Security Filter) | PM 00 a 04 |
| CV-6 Flight caído o congelado | F-07, F-12, RN-10 | Sección 1 (RN-10) | PM 07 y 08; SR `-IncludeFailures` |
| CV-7 Despegue real detectado con OpenSky | F-15, RN-16 | `DepartureVerificationIntegrationTest.siLaAeronaveEstaEnElAireConSuCallsign…_RN16` | PM 09; [evidencias/opensky-en-vivo.md](evidencias/opensky-en-vivo.md) |
| CV-8 OpenSky inaccesible o con respuesta inválida | F-15, RN-16, RN-04 | `…siOpenSkyNoRespondeATiempoDevuelve504YElVueloNoCambia`, `…siOpenSkyRespondeAlgoIninteligibleDevuelve502…`, `HttpOpenSkyClientTest` | — (se prueba con dobles) |

## 6. Pendientes y cambios respecto del compromiso

| Tema | Estado |
|---|---|
| Cambios de la propuesta v1 a la v2 (gateway, JAR común, OpenSky, C4) | Registrados en la tabla de cambios de la propuesta v2 |
| Imágenes de los diagramas (secciones 04, 05, 06 y 08 del formulario) | Pendiente: fuentes listas en [diagramas/](diagramas/) |
| Datos del equipo (Sección 01 de la propuesta) | Pendiente: los completa el equipo |
| Limitaciones aceptadas | Cancelar un vuelo no cancela sus reservas; no se impide cancelar después de la salida; el aterrizaje no se sigue en OpenSky (ADR 0003 y 0006) |
