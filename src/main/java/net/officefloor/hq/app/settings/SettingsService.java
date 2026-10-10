package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

    private final SettingsRepository settings;

    public SettingsService(SettingsRepository settings) {
        this.settings = settings;
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
