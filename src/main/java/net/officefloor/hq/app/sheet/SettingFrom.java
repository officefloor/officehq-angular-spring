package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

/** A value a dated setting of a sheet has from a from-date (or from the beginning of time when it has none);
 * none (null) where the setting allows it. */
@Embeddable
public class SettingFrom {

    /** Values by from-date, the one with no from-date first. */
    public static final Comparator<SettingFrom> BY_FROM =
            Comparator.comparing(SettingFrom::getFrom, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "setting_from")
    private LocalDate from;

    @Column(name = "setting_value", precision = 12, scale = 2)
    private BigDecimal value;

    /** The sheet's today when the value was recorded; none when not kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected SettingFrom() {}

    public SettingFrom(String key, LocalDate from, BigDecimal value, LocalDate madeOn) {
        this.key = key;
        this.from = from;
        this.value = value;
        this.madeOn = madeOn;
    }

    /** Whether the value was recorded while the sheet's today was on or before a date (one not kept was). */
    public boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFrom() {
        return from;
    }

    public BigDecimal getValue() {
        return value;
    }
}
