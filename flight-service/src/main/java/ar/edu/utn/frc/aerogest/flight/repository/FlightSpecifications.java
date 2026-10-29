package ar.edu.utn.frc.aerogest.flight.repository;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.dto.FlightSearchCriteria;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Arma la consulta de búsqueda de vuelos con los filtros opcionales. */
public final class FlightSpecifications {

    private FlightSpecifications() {
    }

    public static Specification<Flight> from(FlightSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (criteria.origin() != null) {
                predicates.add(cb.equal(root.get("origin"), criteria.origin()));
            }
            if (criteria.destination() != null) {
                predicates.add(cb.equal(root.get("destination"), criteria.destination()));
            }
            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }
            if (criteria.departureFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("departureAt"), criteria.departureFrom()));
            }
            if (criteria.departureTo() != null) {
                predicates.add(cb.lessThan(root.get("departureAt"), criteria.departureTo()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
