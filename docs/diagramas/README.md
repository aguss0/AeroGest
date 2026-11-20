# Diagramas de la propuesta (fuentes editables)

Copias de los bloques de [propuesta-tpi-2026-v2.md](../propuesta-tpi-2026-v2.md), una por archivo,
para generar la "representación gráfica" que piden las secciones 04, 05, 06 y 08 del formulario.
**La fuente de verdad es la propuesta**: si cambia un diagrama allá, hay que copiarlo de nuevo acá.

| Archivo | Sección del formulario | Formato |
|---|---|---|
| `04-proceso-reserva.mmd` | 04 — proceso principal (actividades) | Mermaid |
| `04-estados-reserva.mmd` | 04 — estados de la reserva | Mermaid |
| `05-c4-contexto.puml` | 05 — C4 nivel 1 | PlantUML (C4-PlantUML) |
| `06-c4-contenedores.puml` | 06 — C4 nivel 2 | PlantUML (C4-PlantUML) |
| `08-der-flights-db.mmd` | 08 — DER de `flights_db` | Mermaid |
| `08-der-reservations-db.mmd` | 08 — DER de `reservations_db` | Mermaid |
| `10-flujo-presentacion.mmd` (+ `.png`) | Presentación oral: curso normal y alternativos | Mermaid |
| `11-secuencia-reserva.mmd` (+ `.png`) | Presentación oral: secuencia de la reserva | Mermaid |
| `12-flujo-presentacion-compacto.mmd` (+ `.png`) | Presentación oral: flujo para la diapositiva | Mermaid |
| `13-flujo-presentacion-sin-carriles.mmd` (+ `.png`) | Presentación oral: el mismo flujo, sin los carriles por alumno | Mermaid |

## Cómo generar las imágenes

- **En línea:** pegar el contenido en https://www.plantuml.com/plantuml (`.puml`) o en
  https://mermaid.live (`.mmd`) y descargar PNG/SVG.
- **Con Docker, sin subir nada** (desde esta carpeta):

  ```powershell
  docker run --rm -v "${PWD}:/data" -w /data plantuml/plantuml:1.2025.4 -tpng -charset UTF-8 05-c4-contexto.puml 06-c4-contenedores.puml
  docker run --rm -u root -v "${PWD}:/data" minlag/mermaid-cli:11.4.2 -i /data/04-proceso-reserva.mmd -o /data/04-proceso-reserva.png -b white -s 2
  ```

  (repetir el segundo comando para cada `.mmd`).

En PlantUML, `**` dentro de un texto se interpreta como negrita: por eso las rutas del C2 se escriben
`/api/v1/flights/*` y no `/**`.
