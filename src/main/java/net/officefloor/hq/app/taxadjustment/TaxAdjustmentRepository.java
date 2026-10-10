package net.officefloor.hq.app.taxadjustment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxAdjustmentRepository extends JpaRepository<TaxAdjustment, Long> {

    /** The adjustments, earliest date first. */
    List<TaxAdjustment> findAllByOrderByAdjustmentDateAscIdAsc();
}
