package net.officefloor.hq.app.taxadjustment;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxAdjustmentRepository extends JpaRepository<TaxAdjustment, Long> {

    /** The adjustments, earliest date first. */
    List<TaxAdjustment> findAllByOrderByAdjustmentDateAscIdAsc();

    /** The adjustments dated on or between the given dates. */
    List<TaxAdjustment> findByAdjustmentDateBetween(LocalDate from, LocalDate to);
}
