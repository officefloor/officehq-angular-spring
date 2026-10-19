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

    protected SettingFrom() {}

    public SettingFrom(String key, LocalDate from, BigDecimal value) {
        this.key = key;
        this.from = from;
        this.value = value;
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
