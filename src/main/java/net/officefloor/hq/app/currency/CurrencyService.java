package net.officefloor.hq.app.currency;

import java.util.List;
import net.officefloor.hq.app.Audit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrencyService {

    private final CurrencyRepository currencies;
    private final Audit audit;

    public CurrencyService(CurrencyRepository currencies, Audit audit) {
        this.currencies = currencies;
        this.audit = audit;
    }

    /** The known currencies, the original ones first. */
    @Transactional(readOnly = true)
    public List<CurrencyResponse> list() {
        return currencies.findAll().stream()
                .sorted((a, b) -> Currency.ORDER.compare(a.getCode(), b.getCode()))
                .map(CurrencyResponse::from)
                .toList();
    }

    /** Whether the code names a known currency. */
    @Transactional(readOnly = true)
    public boolean exists(String code) {
        return code != null && currencies.existsById(code);
    }

    /** Changes the step a currency's amounts are rounded to, recording the change in the audit log. */
    @Transactional
    public CurrencyResponse changeRounding(String code, CurrencyRoundingRequest request) {
        Currency currency = currencies.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown currency"));
        if (currency.getRoundingStep().compareTo(request.roundingStep()) != 0) {
            currency.setRoundingStep(request.roundingStep());
            currencies.flush();
            audit.record("CURRENCY_ROUNDING_CHANGED code=" + code + " step=" + currency.getRoundingStep().toPlainString());
        }
        return CurrencyResponse.from(currency);
    }
}
