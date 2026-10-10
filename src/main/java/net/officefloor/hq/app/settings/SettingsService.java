package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.util.Locale;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.currency.CurrencyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SettingsService {

    private final SettingsRepository settings;
    private final CurrencyRepository currencies;
    private final Audit audit;

    public SettingsService(SettingsRepository settings, CurrencyRepository currencies, Audit audit) {
        this.settings = settings;
        this.currencies = currencies;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        return SettingsResponse.from(find());
    }

    @Transactional
    public SettingsResponse update(SettingsRequest request) {
        Settings current = find();
        current.setDefaultTaxPct(request.defaultTaxPct());
        if (request.revenueRecognitionBasis() != null) {
            current.setRevenueRecognitionBasis(request.revenueRecognitionBasis());
        }
        settings.flush();
        return SettingsResponse.from(current);
    }

    /** Sets the yearly billings target, or clears it when none is given. */
    @Transactional
    public SettingsResponse updateBillingTarget(BillingTargetRequest request) {
        Settings current = find();
        current.setBillingTarget(request.billingTarget());
        settings.flush();
        return SettingsResponse.from(current);
    }

    /** Chooses the currency the dashboard's totals are shown in, recording it in the audit log. */
    @Transactional
    public SettingsResponse updateDashboardBaseCurrency(BaseCurrencyRequest request) {
        String currency = request.baseCurrency().trim().toUpperCase(Locale.ROOT);
        if (!currencies.existsById(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown currency");
        }
        Settings current = find();
        current.setDashboardBaseCurrency(currency);
        settings.flush();
        audit.record("DASHBOARD_BASE_CURRENCY_SET currency=" + currency);
        return SettingsResponse.from(current);
    }

    /** The currency the dashboard's totals are shown in: the chosen one, or else the home currency. */
    @Transactional(readOnly = true)
    public String dashboardBaseCurrency() {
        return find().getDashboardBaseCurrency();
    }

    /** What the business aims to bill over the year, in the home currency; null when no target is set. */
    @Transactional(readOnly = true)
    public BigDecimal billingTarget() {
        return find().getBillingTarget();
    }

    /** The revenue, in the home currency, from which a client is in the "medium" revenue band. */
    @Transactional(readOnly = true)
    public BigDecimal revenueBandMediumFrom() {
        return find().getRevenueBandMediumFrom();
    }

    /** The revenue, in the home currency, from which a client is in the "high" revenue band. */
    @Transactional(readOnly = true)
    public BigDecimal revenueBandHighFrom() {
        return find().getRevenueBandHighFrom();
    }

    /** The sales tax percentage a new invoice starts with when none is given for it. */
    @Transactional(readOnly = true)
    public BigDecimal defaultTaxPct() {
        return find().getDefaultTaxPct();
    }

    /** The currency foreign invoices are converted into for the business's own totals. */
    @Transactional(readOnly = true)
    public String homeCurrency() {
        return find().getHomeCurrency();
    }

    /** When revenue counts: {@link RecognitionBasis#SENT} or {@link RecognitionBasis#PAID}. */
    @Transactional(readOnly = true)
    public String revenueRecognitionBasis() {
        return find().getRevenueRecognitionBasis();
    }

    private Settings find() {
        return settings.findById(Settings.ID)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing"));
    }
}
