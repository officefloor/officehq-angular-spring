package net.officefloor.hq.app.settings;

import java.math.BigDecimal;

public record SettingsResponse(BigDecimal defaultTaxPct, String homeCurrency, String revenueRecognitionBasis,
        BigDecimal billingTarget, String dashboardBaseCurrency) {

    public static SettingsResponse from(Settings settings) {
        return new SettingsResponse(settings.getDefaultTaxPct(), settings.getHomeCurrency(),
                settings.getRevenueRecognitionBasis(), settings.getBillingTarget(),
                settings.getDashboardBaseCurrency());
    }
}
