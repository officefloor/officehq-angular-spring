package net.officefloor.hq.app.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converts foreign amounts into the home currency at the exchange rate in effect on a given date. */
@Service
public class FxRateService {

    private final FxRateRepository rates;

    public FxRateService(FxRateRepository rates) {
        this.rates = rates;
    }

    /**
     * The amount in the home currency, converted at the currency's rate on the date (its latest rate dated
     * on or before it); empty when the currency has no rate by then.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> toHome(String currency, LocalDate date, BigDecimal amount) {
        return rates.findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(currency, date)
                .map(r -> amount.multiply(r.getRate()).setScale(2, RoundingMode.HALF_UP));
    }
}
