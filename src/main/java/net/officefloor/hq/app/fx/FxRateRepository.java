package net.officefloor.hq.app.fx;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRate, Long> {

    /** The currency's rate in effect on the date: its latest rate dated on or before it. */
    Optional<FxRate> findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String currency, LocalDate date);
}
