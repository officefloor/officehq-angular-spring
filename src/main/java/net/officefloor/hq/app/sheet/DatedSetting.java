package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One value recorded for one of a sheet's settings that carry a figure: in force from its from-date
 * (none: from the beginning of time) until the key's next. A null value is none (no limit, no cap).
 */
@Embeddable
public class DatedSetting {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal value;

    protected DatedSetting() {
    }

    public DatedSetting(String key, LocalDate fromDate, BigDecimal value) {
        this.key = key;
        this.fromDate = fromDate;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public BigDecimal getValue() {
        return value;
    }
}
