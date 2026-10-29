package ar.edu.utn.frc.aerogest.flight.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Página de resultados con un formato JSON estable (no depende de la clase interna de Spring Data). */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
