package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** The app-wide settings, stored as a single row with id {@link #ID}. */
@Entity
@Table(name = "app_settings")
public class Settings {

    /** The id of the one settings row. */
    public static final long ID = 1L;

    @Id
    private Long id;

    /** The sales tax percentage a new invoice starts with. */
    @Column(name = "default_tax_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal defaultTaxPct = BigDecimal.ZERO.setScale(2);

    /** The currency foreign invoices are converted into for the business's own totals. */
    @Column(name = "home_currency", nullable = false, length = 3)
    private String homeCurrency = "USD";

    /** The currency the dashboard's totals are shown in; null shows them in the home currency. */
    @Column(name = "dashboard_base_currency", length = 3)
    private String dashboardBaseCurrency;

    /** When revenue counts: when an invoice is sent ("sent") or once it is paid ("paid"). */
    @Column(name = "revenue_recognition_basis", nullable = false, length = 10)
    private String revenueRecognitionBasis = RecognitionBasis.SENT;

    /** What the business aims to bill over the year, in the home currency; null when no target is set. */
    @Column(name = "billing_target", precision = 15, scale = 2)
    private BigDecimal billingTarget;

    /** The revenue, in the home currency, from which a client is in the "medium" revenue band. */
    @Column(name = "revenue_band_medium_from", nullable = false, precision = 15, scale = 2)
    private BigDecimal revenueBandMediumFrom = new BigDecimal("1000.00");

    /** The revenue, in the home currency, from which a client is in the "high" revenue band. */
    @Column(name = "revenue_band_high_from", nullable = false, precision = 15, scale = 2)
    private BigDecimal revenueBandHighFrom = new BigDecimal("5000.00");

    protected Settings() {
    }

    public BigDecimal getDefaultTaxPct() {
        return defaultTaxPct;
    }

    public String getHomeCurrency() {
        return homeCurrency;
    }

    /** The currency the dashboard's totals are shown in: the chosen one, or else the home currency. */
    public String getDashboardBaseCurrency() {
        return dashboardBaseCurrency == null ? homeCurrency : dashboardBaseCurrency;
    }

    public void setDashboardBaseCurrency(String dashboardBaseCurrency) {
        this.dashboardBaseCurrency = dashboardBaseCurrency;
    }

    public String getRevenueRecognitionBasis() {
        return revenueRecognitionBasis;
    }

    public void setRevenueRecognitionBasis(String revenueRecognitionBasis) {
        this.revenueRecognitionBasis = revenueRecognitionBasis;
    }

    public BigDecimal getBillingTarget() {
        return billingTarget;
    }

    public void setBillingTarget(BigDecimal billingTarget) {
        this.billingTarget = billingTarget == null ? null : billingTarget.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getRevenueBandMediumFrom() {
        return revenueBandMediumFrom;
    }

    public BigDecimal getRevenueBandHighFrom() {
        return revenueBandHighFrom;
    }

    public void setDefaultTaxPct(BigDecimal defaultTaxPct) {
        this.defaultTaxPct = defaultTaxPct.setScale(2, RoundingMode.HALF_UP);
    }
}
