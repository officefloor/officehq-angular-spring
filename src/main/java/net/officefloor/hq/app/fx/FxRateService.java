package net.officefloor.hq.app.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.currency.CurrencyService;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps the history of exchange rates, and converts foreign amounts into the home currency at the rate in
 * effect on a given date.
 */
@Service
public class FxRateService {

    private final FxRateRepository rates;
    private final CurrencyService currencies;
    private final SettingsService settings;
    private final Audit audit;

    public FxRateService(FxRateRepository rates, CurrencyService currencies, SettingsService settings, Audit audit) {
        this.rates = rates;
        this.currencies = currencies;
        this.settings = settings;
        this.audit = audit;
    }

    /** The recorded rates, by currency with the most recent first. */
    @Transactional(readOnly = true)
    public List<FxRateResponse> list() {
        return rates.findAllByOrderByCurrencyAscRateDateDescIdDesc().stream().map(FxRateResponse::from).toList();
    }

    /**
     * Records the currency's rate from the date, recording it in the audit log. A currency has at most one
     * rate a day, and the home currency needs none.
     */
    @Transactional
    public FxRateResponse record(FxRateRequest request) {
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        if (!currencies.exists(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown currency");
        }
        if (currency.equals(settings.homeCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The home currency needs no exchange rate");
        }
        if (rates.existsByCurrencyAndRateDate(currency, request.date())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A rate for this currency on this date already exists");
        }
        FxRate saved = rates.saveAndFlush(new FxRate(currency, request.date(), request.rate()));
        audit.record("FX_RATE_RECORDED id=" + saved.getId() + " currency=" + currency + " date=" + saved.getRateDate()
                + " rate=" + saved.getRate().stripTrailingZeros().toPlainString());
        return FxRateResponse.from(saved);
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
