# Guion de la presentación oral — AeroGest

> **Reemplazado** por [presentacion-3-integrantes.md](presentacion-3-integrantes.md) (3 integrantes,
> diagrama de flujo, referencias al código). Este queda como versión de 4 bloques.

Texto para decir en voz alta, diapositiva por diapositiva. Complementa a
[guion-defensa.md](guion-defensa.md), que tiene los pasos técnicos de la demo, el plan B y las
preguntas probables con sus respuestas.

- **Duración:** unos 20 minutos (12 de exposición + 8 de demo), más preguntas.
- **Hablantes:** `[I1]` a `[I4]` siguen el reparto de bloques de `guion-defensa.md`. Si son menos,
  juntá bloques; la rúbrica (PRE-01) pide que **cualquiera** pueda responder sobre cualquier parte.
- **Formato:** cada diapositiva tiene *En pantalla* (qué poner en la lámina), *Decir* (el texto, en
  tono natural: no hace falta leerlo literal) y, a veces, *Remate* (la frase que conviene que quede).

---

## Bloque 1 — Problema y contexto `[I1]` (≈ 4 min)

### Diapositiva 1 · Portada (20 s)
**En pantalla:** "AeroGest — Backend de vuelos y reservas". Backend de Aplicaciones 2026, UTN FRC.
Nombres de los integrantes.

**Decir:**
> Buenas. Somos [nombres] y les vamos a presentar AeroGest, el backend de una aerolínea para
> administrar vuelos y reservas de pasajeros. Primero les contamos el problema, después cómo lo
> resolvimos, y al final lo mostramos funcionando.

### Diapositiva 2 · El problema (1 min 30 s)
**En pantalla:** "El desafío no es el CRUD: es **no confirmar reservas inválidas**". Tres íconos:
última plaza disputada · reserva duplicada · servicio caído a mitad de camino.

**Decir:**
> A primera vista, reservar un vuelo parece un alta en una tabla. El problema real aparece cuando
> las plazas y las reservas viven en dos servicios distintos, cada uno con su propia base.
> Nos propusimos que el sistema **nunca confirme una reserva inválida**, y eso tiene tres amenazas
> concretas:
> primero, dos personas que piden la última plaza en el mismo instante;
> segundo, el mismo pasajero reservando dos veces el mismo vuelo;
> y tercero, que el servicio de vuelos se caiga o no conteste justo en medio de una reserva.
> Todo lo que van a ver está pensado alrededor de esas tres situaciones.

**Remate:** "Si no podemos validar, no confirmamos."

### Diapositiva 3 · Actores y alcance (1 min)
**En pantalla:** tabla de actores (ADMIN, USER, Reservation Service con rol SERVICE, anónimo).
Dentro / fuera del alcance.

**Decir:**
> Tenemos tres tipos de usuario, todos administrados en Keycloak. El **administrador** crea vuelos,
> cambia su estado y puede ver y cancelar cualquier reserva. El **usuario** consulta vuelos, registra
> sus pasajeros y reserva o cancela **solo lo suyo**. Y hay un tercer actor que no es una persona:
> el propio Reservation Service, con rol SERVICE, que solo puede usar la API interna de vuelos.
> Dejamos afuera pagos, check-in y asientos numerados: manejamos cantidad de plazas, no asientos.
> Fue una decisión para concentrarnos en la consistencia, que es lo difícil.

### Diapositiva 4 · Diagrama de contexto C1 (1 min)
**En pantalla:** `docs/diagramas/C4_Contexto_AeroGest.png`.

**Decir:**
> Este es el C1 del modelo C4. En el centro, AeroGest como caja negra. Lo usan administradores y
> usuarios. Depende de dos sistemas externos: **Keycloak**, que es el proveedor de identidad y
> emite los tokens, y **OpenSky Network**, una API pública de tráfico aéreo real, que usamos para
> saber si un avión ya despegó. Más adelante les explicamos por qué esa información no la podemos
> inventar nosotros.

---

## Bloque 2 — Arquitectura `[I1]` (≈ 3 min)

### Diapositiva 5 · Diagrama de contenedores C2 (2 min)
**En pantalla:** `docs/diagramas/C4_Contenedores_AeroGest.png`.

**Decir:**
> Abrimos la caja. Hay un **API Gateway** en el puerto 8080, que es el único punto de entrada: es lo
> único que publica un puerto. El cliente no sabe dónde están los servicios.
> Detrás hay dos microservicios en Spring Boot con Java 21.
> **Flight Service** es dueño de los vuelos, sus estados y, muy importante, del **contador de plazas**:
> es la fuente de verdad de la capacidad.
> **Reservation Service** es dueño de los pasajeros y las reservas, y es el que **orquesta** el
> proceso de reserva.
> Cada uno tiene **su propia base PostgreSQL**. Reservation nunca lee la base de Flight: si necesita
> algo, se lo pide por HTTP. Así cada servicio puede cambiar su modelo sin romper al otro.
> Los tres componentes Java usan un JAR común, `aerogest-common`, que les cuento en un minuto.
> Y todo esto levanta con un solo `docker compose up`.

**Remate:** "Dividimos por responsabilidad de negocio: quién es dueño de la capacidad y quién es
dueño de la reserva. No son dos CRUD separados."

### Diapositiva 6 · Componente común (1 min)
**En pantalla:** qué hay en `aerogest-common` / qué quedó afuera.

**Decir:**
> El enunciado pide un JAR Maven reutilizable. Lo instalamos en el repositorio local `.m2` y lo
> consumen como dependencia el gateway y los dos servicios.
> Adentro pusimos solo lo que es **convención de todo el sistema**: cómo se leen los roles del token
> de Keycloak, el *correlation id* para seguir una petición entre servicios, el formato estándar de
> error y la paginación.
> Y dejamos **afuera** a propósito los códigos de error y las reglas de cada servicio: si estuvieran
> en el JAR, cualquier cambio de negocio obligaría a publicar una versión nueva para todos.

---

## Bloque 3 — Proceso de reserva `[I2]` (≈ 5 min)

### Diapositiva 7 · El flujo de la reserva (2 min)
**En pantalla:** diagrama de secuencia (`docs/arquitectura.md` §4) o `04-proceso-reserva`.

**Decir:**
> Este es el proceso central. El usuario pide una reserva al gateway, que valida el token y la
> manda a Reservation.
> **Uno:** Reservation guarda la reserva como **PENDING**, en una transacción corta, con un id propio.
> **Dos:** fuera de esa transacción, llama a la API interna de Flight para **ocupar una plaza**,
> mandando el id de la reserva. Para esa llamada usa **su propio token** de servicio.
> **Tres:** según lo que conteste Flight, la reserva pasa a **CONFIRMED** o a **REJECTED**.
> ¿Por qué la llamada HTTP queda afuera de la transacción? Porque si Flight tarda, no queremos tener
> una conexión de base bloqueada esperando a la red.

### Diapositiva 8 · Concurrencia: la última plaza (1 min 30 s)
**En pantalla:** `UPDATE flights SET seats_taken = seats_taken + 1 WHERE id = ? AND seats_taken < capacity`

**Decir:**
> ¿Cómo evitamos vender la misma plaza dos veces? No consultamos primero "¿hay lugar?" y después
> descontamos, porque dos consultas simultáneas verían el mismo lugar libre.
> Lo hacemos con **un solo UPDATE condicionado**. PostgreSQL bloquea la fila: el primero pasa, y el
> segundo, al reevaluar la condición, ya no actualiza nada y recibe "sin disponibilidad".
> Lo probamos con 20 pedidos simultáneos por la última plaza: se confirma exactamente uno.
> Para los duplicados usamos lo mismo: un **índice único parcial** en la base, que impide dos
> reservas activas del mismo pasajero en el mismo vuelo aunque lleguen al mismo tiempo.

**Remate:** "La garantía no está en un `if`, está en la base de datos."

### Diapositiva 9 · ¿Y si Flight no responde? (1 min 30 s)
**En pantalla:** tabla de casos: caído → 503 · lento → 504 · respuesta rara → 502 · incierto →
liberar + REJECTED · job cada 1 min.

**Decir:**
> Esta es la parte que más nos costó. Si Flight está caído, respondemos 503 y la reserva queda
> rechazada. Si tarda más de lo permitido, 504.
> El caso fino es el **incierto**: quizás Flight sí ocupó la plaza pero la respuesta se perdió.
> Ahí Reservation le pide que **libere** esa plaza, usando el mismo id de reserva, y recién después
> la rechaza. Como asignar y liberar son **idempotentes** por id de reserva, repetirlos no hace daño.
> Si ni siquiera se puede liberar, la reserva queda PENDING y un **job que corre cada minuto** la
> resuelve cuando Flight vuelve.
> No usamos transacciones distribuidas ni un broker de mensajes: con idempotencia, compensación y
> el job resolvimos lo mismo con menos piezas.

**Remate:** "Ante la duda, nunca confirmamos."

---

## Bloque 2 (cont.) — Seguridad e integración externa `[I1]` / `[I2]` (≈ 3 min)

### Diapositiva 10 · Seguridad y trusted zone (1 min 30 s)
**En pantalla:** login OIDC → JWT → gateway (Security Filter) → servicio (valida otra vez + dueño
por `sub`). `/internal/**` bloqueado en el gateway.

**Decir:**
> Usamos **Keycloak** con OpenID Connect y tokens JWT. En Swagger el login es el flujo real de
> Keycloak, *authorization code* con PKCE.
> El **Security Filter del gateway** es la primera barrera: rechaza sin token con 401 y sin rol con
> 403, y nunca publica la API interna, ni siquiera al rol SERVICE.
> Pero los servicios **vuelven a validar el token**. ¿Por qué dos veces? Porque el gateway no sabe
> de quién es cada reserva: eso lo decide Reservation comparando el `sub` del token con el dueño.
> Y si los servicios confiaran ciegamente en lo que les pasa el gateway, cualquiera dentro de la red
> podría falsificarlo. Validar un JWT es barato, la clave pública queda en caché.
> Entre servicios, Reservation pide su propio token con *client credentials*.
> Los secretos están en un `.env`, fuera del código.

### Diapositiva 11 · OpenSky: el dato que no podemos inventar (1 min 30 s)
**En pantalla:** "¿Despegó de verdad?" icao24 + callsign + `on_ground=false` → vuelo **CLOSED**.

**Decir:**
> El enunciado pide una API pública con uso **genuino** de negocio. Nuestra regla es: **si el avión
> ya despegó, el vuelo deja de vender plazas.**
> El horario dice lo que la aerolínea *planeó*; que el avión esté en el aire es un hecho del mundo
> real, y no es algo que se pueda guardar como configuración.
> Flight consulta OpenSky cada 5 minutos, en una sola llamada con todos los aviones de los vuelos
> que salen cerca de ahora. Si la aeronave aparece en el aire **con el callsign de nuestro vuelo**,
> lo cerramos. Pedimos los dos datos porque el mismo avión hace varios tramos por día.
> ¿Y si OpenSky falla, tarda o devuelve algo raro? **No cambiamos nada**: sigue rigiendo la regla
> por horario. Y las reservas nunca dependen de OpenSky: el job corre aparte.
> Usamos acceso anónimo: 400 créditos por día y el job gasta 288.

---

## Bloque 4 — Demostración en vivo `[I3]` (≈ 8 min)

Pasos técnicos detallados, comandos y plan B: [guion-defensa.md](guion-defensa.md) §4.

### Diapositiva 12 · "Ahora lo vemos andar"
**Decir mientras se hace cada paso:**

1. **Tokens (Postman, carpeta 00).**
   > Pedimos tokens reales a Keycloak para el admin, dos usuarios y el servicio.
2. **Reserva confirmada (carpeta 03).**
   > user1 reserva a Ana: 201, **CONFIRMED**. Fíjense el encabezado `X-Correlation-Id`.
3. **La plaza bajó.**
   > Consultamos el vuelo: las plazas disponibles pasaron de 2 a 1. La integración es real, no un mock.
4. **Rechazos de negocio y de seguridad.**
   > La misma reserva otra vez: 409, reserva duplicada. Y user2 intentando ver la reserva de user1:
   > 403, porque no es suya.
5. **Cancelar dos veces (carpeta 04).**
   > Primera cancelación, 200 y la plaza vuelve. Segunda: 409, y la plaza **no** se devuelve dos veces.
6. **La última plaza (smoke en terminal).**
   > Diez reservas simultáneas por una sola plaza: una confirmada, nueve sin disponibilidad, cero
   > plazas. Sin sobreventa.
7. **Flight caído (`docker stop`, carpeta 07).**
   > Apagamos Flight y reservamos: 503, y la reserva **no** queda confirmada.
8. **Flight congelado (`docker pause`, carpeta 08).**
   > Ahora Flight está vivo pero no contesta: a los 3 segundos, 504. En los logs se ve el mismo
   > correlation id pasando por el gateway y los dos servicios.
9. **Swagger unificado** (`localhost:8080/swagger-ui.html`).
   > Los dos servicios documentados detrás del gateway, con login de Keycloak. La API interna no aparece.
10. **OpenSky en vivo (carpeta 09, necesita Internet).**
    > Elegimos un avión que está volando ahora, creamos un vuelo con su callsign, hacemos el chequeo
    > y el vuelo pasa a CLOSED: intentar reservar da 409, vuelo no reservable.

**Si algo falla en vivo:**
> Tenemos las salidas guardadas de esta misma corrida en `docs/evidencias/`.

---

## Bloque 5 — Calidad, despliegue y cierre `[I4]` (≈ 2 min)

### Diapositiva 13 · Calidad y pruebas (1 min)
**En pantalla:** 248 pruebas automáticas (common 11 · Flight 113 · Reservation 113 · gateway 11).
Postman: 9 carpetas. Matriz de trazabilidad.

**Decir:**
> Tenemos 248 pruebas automáticas. No son solo de métodos sueltos: hay pruebas de API completas
> contra un PostgreSQL real levantado con Testcontainers, que verifican la respuesta HTTP **y** cómo
> quedó la base. Hay pruebas de concurrencia, de seguridad y de fallos de integración: caída,
> timeout y respuesta inválida. OpenSky se prueba con un servidor falso, así que ninguna prueba
> depende de Internet.
> Además está la colección Postman, que se corre por consola con un script, y una **matriz de
> trazabilidad** que une cada regla de negocio con su código y su prueba.
> Todo el sistema arranca desde cero con `docker compose up --build`: dos bases, Keycloak, los dos
> servicios y el gateway, y el realm de Keycloak se importa solo.

### Diapositiva 14 · Decisiones y limitaciones (40 s)
**En pantalla:** 3 decisiones clave + 3 limitaciones aceptadas.

**Decir:**
> Las decisiones que más pesan: el contador de plazas en Flight con UPDATE atómico, la reserva en
> dos pasos con compensación en vez de transacción distribuida, y validar el token en el gateway y
> en cada servicio. Cada una está escrita como ADR, con las alternativas que descartamos.
> También somos honestos con lo que no hace: cancelar un vuelo no cancela sus reservas
> automáticamente, no impedimos cancelar después de la salida, y con OpenSky solo cerramos ventas,
> no seguimos el aterrizaje.

### Diapositiva 15 · Cierre (20 s)
**Decir:**
> En resumen: dos microservicios con responsabilidades claras, que colaboran en un proceso real,
> sin sobreventa aunque haya concurrencia y sin confirmar nada que no se pudo validar.
> Muchas gracias. ¿Preguntas?

---

## Ayuda memoria para las preguntas

Las respuestas completas están en [guion-defensa.md](guion-defensa.md) § Preguntas probables. Las
cinco más probables, en una línea:

| Pregunta | Idea clave |
|---|---|
| ¿Por qué el contador está en Flight? | La capacidad es del vuelo; contarla desde Reservation cruzaría dos bases sin atomicidad. |
| ¿Por qué no 2PC o una saga con colas? | 2PC acopla y no aplica a REST; un broker es una pieza más. Idempotencia + compensación + job alcanzan. |
| ¿Por qué validar el JWT dos veces? | El gateway no sabe de quién es cada recurso, y confiar en headers internos sería falsificable. |
| ¿Por qué Reservation llama a Flight directo y no por el gateway? | El gateway es para tráfico externo; la API interna no se publica. La llamada igual va autenticada (rol SERVICE). |
| ¿Qué pasa si OpenSky no responde? | No cambia ningún vuelo; rige la regla por horario; la reserva nunca consulta OpenSky. |
