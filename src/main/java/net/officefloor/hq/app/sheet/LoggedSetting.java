package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line of a sheet's setting log: a value recorded for a setting from a from-date, and the
 * sheet's today when it was recorded (none: from the beginning of time).
 */
@Embeddable
public class LoggedSetting {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal value;

    @Column(name = "made_on")
    private LocalDate madeOn;

    protected LoggedSetting() {
    }

    public LoggedSetting(String key, LocalDate fromDate, BigDecimal value, LocalDate madeOn) {
        this.key = key;
        this.fromDate = fromDate;
        this.value = value;
        this.madeOn = madeOn;
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

    /** Whether it was recorded by the end of a day: the sheet's today then on or before it. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
