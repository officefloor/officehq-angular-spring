package net.officefloor.hq.app.settings;

import java.math.BigDecimal;

public record SettingsResponse(BigDecimal defaultTaxPct, String homeCurrency) {

    public static SettingsResponse from(Settings settings) {
        return new SettingsResponse(settings.getDefaultTaxPct(), settings.getHomeCurrency());
    }
}
