package net.officefloor.hq.app.fx;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRate, Long> {

    /** The currency's rate in effect on the date: its latest rate dated on or before it. */
    Optional<FxRate> findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String currency, LocalDate date);

    /** Whether the currency already has a rate dated on the day. */
    boolean existsByCurrencyAndRateDate(String currency, LocalDate rateDate);

    /** The rate history: by currency, the most recent rate first. */
    List<FxRate> findAllByOrderByCurrencyAscRateDateDescIdDesc();
}
