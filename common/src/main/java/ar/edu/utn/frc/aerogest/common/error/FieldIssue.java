package ar.edu.utn.frc.aerogest.common.error;

/** Un campo inválido de la solicitud (lista "errors" de una respuesta VALIDATION_ERROR). */
public record FieldIssue(String field, String message) {
}
