package ar.edu.utn.frc.aerogest.reservation.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tipo de documento del pasajero. Cada tipo define su formato válido. Normalizar el número
 * (sin puntos, espacios ni guiones, en mayúsculas) es lo que hace efectiva la unicidad de RN-14:
 * "12.345.678" y "12345678" son el mismo documento.
 */
public enum DocumentType {
    DNI("\\d{7,8}"),
    PASSPORT("[A-Z0-9]{6,15}");

    private final Pattern pattern;

    DocumentType(String regex) {
        this.pattern = Pattern.compile(regex);
    }

    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    public boolean isValid(String normalizedNumber) {
        return pattern.matcher(normalizedNumber).matches();
    }
}
