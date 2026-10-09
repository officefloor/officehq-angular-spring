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
        settings.flush();
        return SettingsResponse.from(current);
    }

    /** The sales tax percentage a new invoice starts with when none is given for it. */
    @Transactional(readOnly = true)
    public BigDecimal defaultTaxPct() {
        return find().getDefaultTaxPct();
    }

    private Settings find() {
        return settings.findById(Settings.ID)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing"));
    }
}
