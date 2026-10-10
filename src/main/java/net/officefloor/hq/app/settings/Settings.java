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

    /** When revenue counts: when an invoice is sent ("sent") or once it is paid ("paid"). */
    @Column(name = "revenue_recognition_basis", nullable = false, length = 10)
    private String revenueRecognitionBasis = RecognitionBasis.SENT;

    protected Settings() {
    }

    public BigDecimal getDefaultTaxPct() {
        return defaultTaxPct;
    }

    public String getHomeCurrency() {
        return homeCurrency;
    }

    public String getRevenueRecognitionBasis() {
        return revenueRecognitionBasis;
    }

    public void setRevenueRecognitionBasis(String revenueRecognitionBasis) {
        this.revenueRecognitionBasis = revenueRecognitionBasis;
    }

    public void setDefaultTaxPct(BigDecimal defaultTaxPct) {
        this.defaultTaxPct = defaultTaxPct.setScale(2, RoundingMode.HALF_UP);
    }
}
